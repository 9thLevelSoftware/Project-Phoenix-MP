Based strictly on a code review of `MainViewModel.kt`, `KableBleRepository.kt`, `RepCounterFromMachine.kt`, and `BleConstants.kt`, here is a comprehensive, non-consolidated list of every flow involving BLE protocols within the application.

---

### 1. Device Connection and Initialization Flow

**Description:** The sequence executed when `ensureConnection` is called to establish a link, negotiate protocol parameters, and set up the initial monitoring state.

* **Step 1: Pre-Connection Cleanup (Parity with parent)**
* `KableBleRepository` calls `cleanupExistingConnection()` to release any dangling GATT connections.
* **Code:** Cancels polling jobs, disconnects existing peripheral, releases resources.
* **Purpose:** Prevents Android 16/Pixel 7 connection issues from stale GATT state.


* **Step 2: Scan and Connect**
* `MainViewModel` calls `bleRepository.scanAndConnect()`.
* `KableBleRepository` filters advertisements for names starting with "Vee_" or "VIT" or service UUID `0000fef3`.
* Upon finding a device, `peripheral.connect()` is called with a retry mechanism (3 attempts).


* **Step 3: MTU Negotiation**
* Immediately after connection, `onDeviceReady()` is launched.
* **Code:** `p.requestMtuIfSupported(247)`
* **Reasoning:** The app requests an MTU of 247 bytes to accommodate the 96-byte `PROGRAM` command frames used later.


* **Step 4: Service Discovery**
* The code explicitly validates the presence of the Nordic UART Service (NUS) TX (`6e400002`) and RX/Monitor characteristics.


* **Step 5: Enable Notifications**
* `startObservingNotifications()` is called.
* **REPS Characteristic (`8308f2a6...`):** Observed for rep completion events.
* **HEURISTIC Characteristic (`c7b73007...`):** Observed for concentric/eccentric force data (used in Echo mode).
* **VERSION Characteristic:** Observed for firmware version data.


* **Step 6: Start Heartbeat**
* `startHeartbeat()` launches a coroutine loop running every 2000ms.
* **Protocol:** It attempts to `read` the Monitor characteristic. If that fails, it falls back to writing a 4-byte No-Op (`0x00, 0x00, 0x00, 0x00`) to the TX characteristic.


* **Step 7: Start Diagnostic Polling**
* `startDiagnosticPolling()` begins a 500ms loop reading the `DIAGNOSTIC` characteristic (`5fa538ec...`).
* This reads fault codes and temperature data.



### 2. Just Lift: Preparation (Handle Detection) Flow

**Description:** The flow entered when the user navigates to the `JustLiftScreen` but has not yet started a workout. This sets up the "Auto-Start" machine state.

* **Step 1: State Reset**
* `MainViewModel` calls `prepareForJustLift()`.
* **Code:** `_workoutState.value = WorkoutState.Idle`


* **Step 2: Enable Handle Detection**
* `MainViewModel` calls `bleRepository.enableHandleDetection()`.
* `KableBleRepository` invokes `startMonitorPolling(p, forAutoStart = true)`.


* **Step 3: State Machine Initialization**
* Inside `KableBleRepository`, `_handleState` is set to `HandleState.WaitingForRest`.
* **Logic:** The code monitors the `Monitor` characteristic (position data). It enforces that cables must be at "Rest" (< 5.0mm) before transitioning to "Released" (Armed).


* **Step 4: Continuous Position Tracking**
* `MainViewModel` collects `bleRepository.metricsFlow`.
* **Code:** `repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)`
* **Purpose:** Because no workout is active, the app manually builds min/max position ranges to be used later for Auto-Stop detection.



### 3. Just Lift: Auto-Start Trigger Flow

**Description:** The transition from Idle to Active Workout triggered by user movement in Just Lift mode.

* **Step 1: Movement Detection**
* `KableBleRepository.analyzeHandleState` processes polling data.
* If Position > 8.0mm AND Velocity > 50.0mm/s (sustained for 200ms hysteresis), state transitions to `HandleState.Grabbed`.


* **Step 2: Trigger Countdown**
* `MainViewModel` observes `handleState`. When `Grabbed` is detected:
* **Code:** `startAutoStartTimer()` begins a 5-second UI countdown.


* **Step 3: Auto-Launch**
* If countdown completes without handles being released, `startWorkout(skipCountdown = true, isJustLiftMode = true)` is called.



### 4. Workout Start Flow (Standard & Just Lift)

**Description:** The specific sequence of BLE commands sent to engage the motors and begin a session.

* **Step 1: Init Command**
* `MainViewModel.startWorkout` calls `bleRepository.sendWorkoutCommand`.
* **Command:** `0x0A` (RESET/INIT).
* **Purpose:** Ensures the machine is in a clean state.


* **Step 2: Configuration Command**
* **Param Generation:** `BlePacketFactory` creates the payload.
* *Program Mode:* 96-byte packet containing mode profile, weight, progression.
* *Echo Mode:* 32-byte packet containing level, warmup reps, eccentric load.


* **Command:** `0x04` + Payload.
* **Code:** `bleRepository.sendWorkoutCommand(command)`
* **Result:** Parameters are set, but motors are *not* yet engaged.


* **Step 3: Start Command**
* **Command:** `0x03` (START).
* **Code:** `bleRepository.sendWorkoutCommand(BlePacketFactory.createStartCommand())`
* **Result:** Motors engage.


* **Step 4: Active Polling**
* `MainViewModel` calls `bleRepository.startActiveWorkoutPolling()`.
* **Repository Logic:** Calls `startMonitorPolling(p, forAutoStart = false)`. This disables the "Handle Detection" state machine and sets the internal state to `Active`.



### 5. Active Workout Monitoring Flow

**Description:** The continuous loop running during a workout to track metrics and safety.

* **Step 1: Monitor Polling**
* `KableBleRepository` performs `p.read(monitorCharacteristic)` in a loop (throttled only by BLE response time).
* **Data Parsing:** It parses 28 bytes (Position A/B, Load A/B, Velocity A/B).
* **Velocity Smoothing:** It applies an Exponential Moving Average (Alpha 0.3) to velocity data to handle jitter.
* **Emission:** Emits to `metricsFlow`.


* **Step 2: Rep Counting (Two Paths)**
* *Notification Path:* The machine sends notifications on `REPS_UUID`. `RepCounterFromMachine.processModern` handles these to increment warmup/working reps.
* *Polling Path (Just Lift):* Since Just Lift often suppresses rep events, `MainViewModel` relies on `repCounter.updatePositionRangesContinuously` fed by the monitor polling flow.


* **Step 3: Status Flag Check**
* Every monitor packet is checked for status flags.
* **Safety:** Checks for `ROM_OUTSIDE_HIGH` or `ROM_OUTSIDE_LOW`.
* **Deload:** Checks for `DELOAD_OCCURRED` and emits `deloadOccurredEvents` if detected.



### 6. Auto-Stop Flow (Stall & Position Detection)

**Description:** The logic used to automatically end a set when the user struggles or releases the cables.

* **Step 1: Data Ingestion**
* `MainViewModel.handleMonitorMetric` receives the latest metric.


* **Step 2: Velocity-Based Stall Detection (Primary)**
* **Code:** `maxVelocity < STALL_VELOCITY_LOW` (2.5 mm/s).
* **Hysteresis:** If velocity stays below 2.5 mm/s for 5.0 seconds (`STALL_DURATION_SECONDS`), an auto-stop is requested.


* **Step 3: Position-Based Detection (Secondary)**
* **Logic:** Checks `repCounter.isInDangerZone` (bottom 5% of ROM) AND if cables are near minimum length/at rest.
* **Timer:** Triggers if conditions persist for 2.5 seconds (`AUTO_STOP_DURATION_SECONDS`).


* **Step 4: Trigger**
* Calls `handleSetCompletion()`.



### 7. Workout Stop Flow

**Description:** The sequence to disengage motors and save data.

* **Step 1: Stop Command**
* `MainViewModel` calls `bleRepository.sendWorkoutCommand`.
* **Command:** `0x03` (STOP) with empty payload (`0x00...`).


* **Step 2: Stop Polling (General)**
* `bleRepository.stopWorkout()` is called.
* This calls `stopPolling()` which cancels the Monitor and Heartbeat jobs.


* **Step 3: Immediate Restart (Just Lift Specific)**
* **Condition:** If `params.isJustLift` is true.
* **Code:** `bleRepository.restartMonitorPolling()`.
* **Reasoning:** As noted in the comments: *"The machine needs active polling to process the stop command and reset quickly. Without this, the machine stays in fault state (red lights)."*


* **Step 4: Save & Summary**
* The session is saved to the DB, and `_workoutState` updates to `SetSummary`.



### 8. Live Weight Adjustment Flow

**Description:** Changing the weight while the motors are engaged.

* **Step 1: Update Parameters**
* User modifies weight in UI. `_workoutParameters` is updated in `MainViewModel`.


* **Step 2: Send Command**
* `MainViewModel` calls `sendWeightUpdateToMachine`.
* **Logic:** Re-creates the `0x04` (Configuration) command with the *new* weight bytes but the *same* mode/reps.
* **Code:** `bleRepository.sendWorkoutCommand(command)`.
* **Note:** This does *not* require sending init or start commands again; the machine updates dynamically.



### 9. Color Scheme Update Flow

**Description:** Changing the LED colors on the machine.

* **Step 1: Trigger**
* `MainViewModel.setColorScheme(index)` is called.


* **Step 2: Command Construction**
* **Code:** `byteArrayOf(0x10, schemeIndex.toByte(), 0x00, 0x00)`.


* **Step 3: Send**
* `bleRepository.sendWorkoutCommand` writes to NUS TX.



### 10. Deload Recovery Flow

**Description:** Handling a safety deload event (where the machine cuts weight due to instability).

* **Step 1: Detection**
* `KableBleRepository` detects `isDeloadOccurred()` flag in a monitor packet.


* **Step 2: Debounce & Emit**
* Emits to `deloadOccurredEvents` flow (debounced to 2000ms).


* **Step 3: (Implicit)**
* While not explicitly shown in `MainViewModel` provided code, the flow implies the UI would react to this repository event, likely by resetting the workout state or pausing.



### 11. Echo Mode Force Feedback Flow

**Description:** Providing real-time "Heuristic" data (force output) specifically for Echo mode visualization.

* **Step 1: Observation**
* `KableBleRepository` observes `HEURISTIC_UUID` (`c7b73007...`).


* **Step 2: Parsing**
* `parseHeuristicData` decodes 48 bytes (Little Endian).
* Extracts `concentric.kgMax` and `eccentric.kgMax`.


* **Step 3: Update State**
* `MainViewModel` collects `bleRepository.heuristicData`.
* **Code:** `_currentHeuristicKgMax.value = currentMax`.
* This drives the "CircularForceGauge" or similar UI elements live during the rep.
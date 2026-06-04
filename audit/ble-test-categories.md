# BLE Test Categories

**Updated:** 2026-06-04
**Baseline:** `b16731ef`
**Purpose:** Distinguish fast unit coverage from host integration and hardware-required BLE validation.

## Unit Tests

Run with `:shared:testAndroidHostTest` unless a narrower Gradle filter is used.

- `BleOperationQueueTest`: mutex serialization and lock state.
- `DiscoModeTest`: disco job lifecycle and terminal shutdown behavior.
- `HandleStateDetectorTest`: handle state machine transitions.
- `MonitorDataProcessorTest`: monitor packet processing and safety events.
- `ProtocolParserTest`: BLE packet parsing for monitor, reps, diagnostics, and telemetry.
- `DiagnosticFaultDecoderTest`: diagnostic fault decoding.
- `WorkoutCommandValidatorTest`: command boundary validation before BLE packet creation.
- `KableBleRepositoryTest`: repository shutdown and critical rep-event delivery counters without hardware.

## Host Integration Tests

These use repository/manager fakes or platform host test infrastructure, but do not require a physical Vitruvian machine.

- `KableBleConnectionManagerTest`: callback routing, diagnostic parsing, disconnect/cancel cleanup, and lifecycle shutdown.
- `MetricPollingEngineTest`: polling job lifecycle, partial stop/restart, and timeout-disconnect counters.
- `HardwareValidationTest`: static protocol/hardware assumptions that can run without a BLE peripheral.

## Hardware-Required Validation

These flows still require a real Vitruvian machine because Kable `Peripheral` and platform BLE stacks cannot be fully mocked in shared tests.

- Scan and connect to named Vitruvian devices across Android and iOS.
- Subscribe to REPS/VERSION/MODE characteristics and verify sustained notifications during an active workout.
- Confirm monitor, diagnostic, heuristic, and heartbeat polling rates on Trainer+ and V-Form hardware.
- Exercise unexpected disconnect and reconnect behavior during an active set.
- Validate Just Lift, Echo, standard program, color scheme, and stop/reset commands against real firmware.

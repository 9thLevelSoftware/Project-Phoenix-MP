# Codebase Concerns

**Analysis Date:** 2026-02-13

## Tech Debt

**iOS SQLDelight Migration System:**
- Issue: iOS migrations crash app via terminateWithUnhandledException, bypassing all try-catch blocks
- Files: `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt`
- Impact: Schema changes break iOS installs. Requires complete 4-layer defense system: no-op schema, health checks on every launch, backup exclusion, manual SQL
- Fix approach: Currently mitigated with 996-line defensive implementation. Long-term: SQLDelight iOS migration infrastructure needs replacement or upstream fix

**Large Component Files:**
- Issue: Several UI components exceed 1000+ lines despite v0.4.1 decomposition work
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt` (1,704L), `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt` (1,495L), `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/InsightCards.kt` (1,492L)
- Impact: Difficult to navigate and modify. Increased recomposition risk
- Fix approach: Further decomposition into smaller composables or extract view logic to ViewModels

**KableBleRepository Complexity:**
- Issue: Single 2,886-line file handles all BLE operations including scanning, connection, polling, packet parsing, and state management
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`
- Impact: High cognitive load for modifications. BLE protocol changes require navigating massive file. Testing requires extensive mocking
- Fix approach: Extract packet parsing to dedicated parser, separate connection management from data collection, split polling logic

**RevenueCat Integration Disabled:**
- Issue: Subscription system code exists but is commented out pending API key configuration
- Files: `androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt` (lines 34, 46), `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt` (lines 467, 493, 519, 549), `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AccountScreen.kt` (lines 62, 134)
- Impact: Feature incomplete, commented code creates confusion about what's active
- Fix approach: Either complete RevenueCat integration or remove unused code. If keeping for future use, gate behind feature flag instead of TODO comments

**Portal/Cloud Sync Feature Gate:**
- Issue: Cloud sync UI exists but feature is not ready for public release
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt` (line 982)
- Impact: Code exists but is disabled. User confusion if accidentally enabled
- Fix approach: Complete Portal integration or remove UI elements. Current TODO comment is acceptable as feature gate

## Known Bugs

**Routine Flow Back Button Behavior:**
- Symptoms: Inconsistent back button handling between routine flow and non-routine workout modes
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt` (line 118)
- Trigger: Back button pressed during active workout
- Workaround: Different code paths for routine vs single exercise workouts
- Fix: Unified navigation logic needed

**First Rep Not Registering (Issue #210):**
- Symptoms: V-Form users report first warmup rep doesn't register in rep counter
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachine.kt` (lines 53-56, extensive test coverage lines 289-470)
- Trigger: Counter initialization at 0 instead of null causes delta calculation issues
- Workaround: Fixed in RepCounterFromMachine by initializing lastTopCounter/lastCompleteCounter to 0
- Status: Addressed but extensively tested due to previous regression

**Bodyweight Exercise BLE Command Issue (Issue #222):**
- Symptoms: Bodyweight exercises incorrectly send BLE stop commands to machine
- Files: Referenced in `shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/e2e/WorkoutFlowE2ETest.kt` (lines 279, 295, 303), `shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModelTest.kt` (line 356)
- Trigger: Set completion for bodyweight exercises
- Workaround: INIT command removed, now only CONFIG (0x04) + START (0x03). Cable exercises force warmupReps=3 regardless of input
- Status: Mitigated via protocol changes

**Navigation Bar Overlap:**
- Symptoms: Workout content goes behind soft navigation buttons on Android
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt` (line 264)
- Trigger: Certain Android devices with gesture navigation
- Workaround: `.navigationBarsPadding()` modifier applied
- Status: UI workaround in place

## Security Considerations

**Portal Token Storage:**
- Risk: User authentication tokens stored in platform preferences
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt` (lines 80-81), `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt` (lines 13-16)
- Current mitigation: Uses platform secure storage (EncryptedSettings on Android, Keychain on iOS via Russhwolf MultiplatformSettings)
- Recommendations: Verify token encryption is enabled. Add token refresh mechanism. Implement token expiry checks

**iOS Security-Scoped File Access:**
- Risk: Improper handling of security-scoped URLs could leak file access
- Files: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/FilePicker.ios.kt` (lines 42-43, 50, 143, 197)
- Current mitigation: Calls `startAccessingSecurityScopedResource()` and `stopAccessingSecurityScopedResource()`, copies files to app sandbox
- Recommendations: Verify all code paths properly release security-scoped access

**No Authentication on Local BLE Communication:**
- Risk: Any app on device could potentially connect to Vitruvian machine via BLE
- Files: BLE protocol implementation in `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`
- Current mitigation: None - BLE connections are unauthenticated (matches official app behavior)
- Recommendations: Acceptable for this use case (personal workout equipment). BLE pairing provides device-level security

## Performance Bottlenecks

**BLE Monitor Polling Rate:**
- Problem: Monitor characteristic polled at ~20Hz during workouts, critical path for real-time metrics
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt` (polling loop mentions "~20Hz polling rate without lag")
- Cause: Synchronous polling loop with sequential read operations
- Improvement path: Already optimized. Comment mentions "Critical for maintaining ~20Hz polling rate without lag". EMA velocity smoothing prevents cold start lag (Task 10 implementation)

**Metric Collection During Workouts:**
- Problem: `handleMonitorMetric()` called 10-20Hz, processes rep counting, auto-stop, position tracking, metrics collection
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt` (2,174 lines, hot path processing)
- Cause: All metric processing happens synchronously in single hot path
- Improvement path: Per PITFALLS.md analysis, keep as orchestration point but ensure synchronous sub-manager delegation. Avoid splitting across coroutines (latency)

**Database Queries on UI Thread:**
- Problem: Some repository operations may block during workout history fetching
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt` (781 lines)
- Cause: SQLDelight queries executed synchronously
- Improvement path: Most queries already wrapped in `withContext(Dispatchers.IO)`. Verify history screen pagination

## Fragile Areas

**Routine Flow State Machine:**
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt` (1,091 lines)
- Why fragile: Manages exercise index, set index, skip/complete tracking, superset navigation. Interleaved state makes changes risky
- Safe modification: All state transitions go through RoutineFlowManager. Never modify `_currentExerciseIndex`, `_currentSetIndex` directly. Write characterization tests first
- Test coverage: 22 tests in DWSMRoutineFlowTest (per v0.4.1 audit)

**RepCounter State Management:**
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachine.kt` (819 lines)
- Why fragile: Tracks warmup/working reps, pending rep state, phase tracking, position ranges. Issue #210 required extensive fixes. Changes can break rep counting
- Safe modification: Use RepCounterFromMachineTest (684 lines of tests). Never modify counter logic without full test run. Position calibration affects ROM visualization
- Test coverage: Comprehensive tests covering legacy/modern packets, warmup/working transitions, AMRAP mode, stopAtTop variations

**BLE Connection State Machine:**
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt` (connection state management), `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt`
- Why fragile: Auto-reconnect logic, explicit disconnect flag, connection retry count, MTU negotiation, characteristic discovery. Race conditions possible
- Safe modification: Test with actual hardware. Connection state transitions must be atomic. Verify auto-reconnect doesn't trigger on explicit disconnect
- Test coverage: Limited - mostly E2E tests with FakeBleRepository

**iOS Database Initialization:**
- Files: `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt` (996 lines, 4-layer defense)
- Why fragile: Any schema change requires manual SQL in `ensureSchemaComplete()`. iCloud backup exclusion must persist. Health checks run every launch
- Safe modification: NEVER use SQLDelight migrations on iOS. Always add columns with `IF NOT EXISTS`. Test on iOS device after any schema change. Monitor NSLog output
- Test coverage: Runtime verification only (no unit tests for platform-specific code)

## Scaling Limits

**Exercise Library:**
- Current capacity: 200+ exercises in JSON import
- Limit: Linear scan of exercise list on every lookup. No indexing
- Scaling path: Current size acceptable. If expanding to 1000+, add database indexing on exercise name/muscle group

**Workout History:**
- Current capacity: Unlimited history storage
- Limit: History screen loads all sessions into memory. Large datasets (1000+ workouts) will cause UI lag
- Scaling path: Implement pagination in history queries. Add time-based filtering (last 30/60/90 days)

**Connection Logs:**
- Current capacity: MAX_LOG_ENTRIES = 1000 (per ConnectionLogRepository)
- Limit: Hard-coded 1000 log limit, older entries discarded
- Scaling path: Already handled via circular buffer pattern

**Training Cycle Complexity:**
- Current capacity: Multi-week programs with per-day routines
- Limit: No limit on cycle length. Very long cycles (52+ weeks) may cause UI performance issues in cycle editor
- Scaling path: Add virtualization to day strip if needed

## Dependencies at Risk

**Kable BLE Library:**
- Risk: Active development but KMP BLE space is immature
- Impact: Core functionality depends on Kable. Breaking changes would require significant rework
- Migration plan: Fork Kable if development stalls. Nordic BLE library available as Android fallback (parent repo used it)

**SQLDelight:**
- Risk: Version 2.0.2 is stable but iOS migration issues are unfixed upstream
- Impact: Schema evolution on iOS requires manual workarounds (see DriverFactory.ios.kt)
- Migration plan: Current 4-layer defense mitigates. Consider Room KMP when stable

**Kotlin 2.0.21:**
- Risk: Recent major version, potential stability issues
- Impact: Compiler bugs could block builds
- Migration plan: Pin to working version. KMP ecosystem moving fast, staying current is necessary

## Missing Critical Features

**Export Analytics Data:**
- Problem: Export FAB exists but functionality not implemented (platform-specific context needed)
- Blocks: Users can't export workout data for external analysis
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt` (line 418)
- Priority: Medium - backup/restore exists as alternative

**Subscription System:**
- Problem: RevenueCat integration incomplete, subscription features commented out
- Blocks: Monetization, premium features
- Files: Multiple (see Tech Debt section)
- Priority: Low - app is community rescue project, free tier is complete

**Offline First Architecture:**
- Problem: Portal sync exists but is feature-gated
- Blocks: Multi-device sync, cloud backup
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`, sync module
- Priority: Low - local-first is intentional design

## Test Coverage Gaps

**BLE Connection Edge Cases:**
- What's not tested: Auto-reconnect after iOS backgrounding, MTU negotiation failures, characteristic discovery timeouts
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`
- Risk: Connection issues only discovered on real hardware
- Priority: High - BLE is core functionality

**iOS-Specific Code:**
- What's not tested: DriverFactory.ios.kt 4-layer defense, iOS file picker security scoping, haptic feedback
- Files: `shared/src/iosMain/kotlin/**/*.kt`
- Risk: Platform-specific crashes not caught in CI
- Priority: Medium - requires iOS test infrastructure

**UI Recomposition Performance:**
- What's not tested: Recomposition counts during active workout, memory pressure tests
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt`, WorkoutHud.kt
- Risk: Battery drain, dropped frames during workouts
- Priority: Medium - performance regressions hard to catch without device testing

**Large Routine Navigation:**
- What's not tested: Routines with 50+ exercises, deeply nested supersets, extreme set counts
- Files: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt`
- Risk: UI overflow, state machine edge cases
- Priority: Low - typical routines are 5-15 exercises

**Migration from v0.3.x to v0.4.x:**
- What's not tested: Existing user data migration after manager decomposition
- Files: DI module restructuring in v0.4.1
- Risk: App crash on upgrade for existing users
- Priority: High - requires manual testing before release

---

*Concerns audit: 2026-02-13*

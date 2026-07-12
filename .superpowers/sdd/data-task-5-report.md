# Task 5 Report: Active-Profile Training Settings

## Scope and baseline

- Base commit: `1a84814990db2129ccd587955eafab7f1d929af1`
- Branch: `codex/add-profile-tab`
- Scope: Task 5 only. No Task 6 work or tracked design/plan documents were changed.

## Implemented

- Reworked `SettingsManager` into a global-plus-active-profile compatibility facade. Profile-owned setters capture the originating Ready profile, serialize section changes, reject stale/switching writes, preserve consent/VBT cascades and clamps, and never write legacy Settings.
- Seeded the compatibility stream and every derived `StateFlow` synchronously from an already-Ready profile, avoiding transient legacy/default values for immediate consumers.
- Narrowed the `PreferencesManager` abstraction to global writes and deprecated migration-only quick-start reads. Concrete legacy profile writers remain internal solely for migration-source tests; the production-call audit found no callers.
- Added repository-level atomic `mutateCore`, used by both SettingsManager and health import, so a long health read changes only body weight against the latest core section without reverting concurrent unit/increment updates.
- Added `RequiredMigrationGate`; required-migration awaiters now terminate on Ready or throw `RequiredMigrationFailedException` on Failed. Health maps migration failure and profile-context failures to `HealthBodyWeightSyncResult.Failed`.
- Replaced the production equipment-rack binding with a lifecycle-owned active-profile repository and retained the Settings implementation only as an explicit legacy implementation.
- Routed SafeWord effective-state gating, quick-start documents and auto-saves, DWSM VBT reads, MainViewModel LED unlocks, BLE color application, and workout start gating through the active Ready profile.
- Preserved complete single-exercise documents, rack IDs, per-set arrays, and Just Lift conversion fidelity; the existing Just Lift runtime `Int` boundary intentionally rounds the persisted float.
- Made `JustLiftScreen` reload defaults on Ready profile-ID changes and suppress parameter writes while Switching or while the current profile's defaults are loading.
- Updated Koin bindings and shared Android-host/DWSM fixtures to use one Ready `FakeUserProfileRepository` per fixture.
- Replaced the adults-only dialog's former callback sequence with the serialized composite operation and removed the now-dead callback/comments.

## TDD evidence

- Initial consumer RED: 57 tests executed, 6 expected failures covering legacy-setting isolation, A/B rack switching, health active-core writes, SafeWord effective gating, BLE profile color, and the Ready start gate.
- Additional RED regressions:
  - health profile transition surfaced an uncaught `ProfileContextUnavailableException`;
  - immediate `SettingsManager.userPreferences` exposed the legacy initial snapshot;
  - final review set executed 21 tests with 4 intended failures: stale health core overwrite, failed migration gate handling, migration await timeout, and one-shot Just Lift defaults loading.
- The same final review set passed 21/21 after the fixes.

## Final verification

All commands used Android Studio's JBR and `-Pskip.supabase.check=true`.

- Required focused consumer suite: **128 tests, 0 failures, 0 errors**.
- Required DWSM/regression suite: **173 tests, 0 failures, 0 errors**.
- `:shared:compileAndroidMain` plus `KoinModuleVerifyTest`: **build passed; 1 test, 0 failures, 0 errors**.
- Unfiltered `:shared:testAndroidHostTest`: **2516 tests, 0 failures, 0 errors**.
- `:shared:compileKotlinIosArm64`: **build passed**.
- Independent full-diff review: **no remaining Critical or Important findings**.
- Static boundary audit: no common-main profile-owned calls through `PreferencesManager`, no common-main legacy quick-start calls, and production DI binds `ProfileEquipmentRackRepository` only.
- `git diff --check`: passed (Git reports only the repository's Windows line-ending notices).

## Notes

- Existing project compiler/deprecation warnings remain; verification introduced no build or test failures.
- `openspec/AGENTS.md` referenced by the root instructions is absent in this checkout; the complete Task 5 brief was used as the authoritative implementation contract.

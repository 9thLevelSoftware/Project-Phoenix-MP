# Phoenix persisted-file upgrade verification

This runbook verifies PR 709's physical filename migration without treating a synthetic fixture as production evidence. The automated harness is destructive only to one explicitly selected disposable emulator and one exact package name; it never clears data during an upgrade.

The harness default `-ExpectedSchemaVersion` is 56, the current database `user_version` (highest migration file + 1). Pass that parameter only to override it.

## Automated v0.9.6 debug-lineage matrix

Build the legacy APK from the immutable public release source and the candidate from the current checkout. Both debug APKs use the same application ID suffix and local debug certificate, so `adb install -r` exercises Android's real retained-sandbox upgrade path.

```powershell
git worktree add --detach .worktrees/v096-upgrade-fixture v0.9.6
Push-Location .worktrees/v096-upgrade-fixture
.\gradlew ':androidApp:assembleDebug' '-Pskip.supabase.check=true'
Pop-Location

.\gradlew ':androidApp:assembleDebug' '-Pskip.supabase.check=true'

$legacyApk = '.worktrees/v096-upgrade-fixture/androidApp/build/outputs/apk/debug/androidApp-debug.apk'
$candidateApk = 'androidApp/build/outputs/apk/debug/androidApp-debug.apk'

foreach ($scenario in 'Fresh', 'Upgrade', 'InterruptedStaging', 'CorruptSource', 'DualDatabase', 'LowStorage') {
    .\scripts\qa\Invoke-PhoenixPersistedFileUpgrade.ps1 `
        -Scenario $scenario `
        -LegacyApk $legacyApk `
        -CandidateApk $candidateApk `
        -Serial emulator-5554 `
        -EvidenceDirectory (Join-Path $env:TEMP "phoenix-file-migration-$scenario")
}
```

The upgrade scenarios launch the v0.9.6 source build, stop it, and insert only the minimal exercise/routine prerequisites that its debug QA receiver needs. That stopped-process fixture is integrity-checked before it is returned to the app. The released app's receiver then creates representative profiles and workouts through its repository APIs. Because this release build uses rollback journaling, the harness finally places a synchronous committed marker in a real SQLite WAL while the database is offline, snapshots the main/WAL pair before the writer closes, and restores that pair to the stopped sandbox. The successful path proves the WAL payload—not just its filename—survives; it also compares profile/workout/routine counts and schema version, checks the plaintext sentinel, requires no legacy files after the first launch, requires the neutral recovery on that launch, and requires cleanup after the second launch. The encrypted preference migration is covered by the real Android instrumentation test because release/debug fixtures must not inject ciphertext bytes.

The negative cases preserve and display evidence:

- `InterruptedStaging` resumes with an incomplete staging file present.
- `CorruptSource` blocks with a stable database diagnostic and leaves the source intact.
- `DualDatabase` renders `DB_DUAL_DATABASES`, does not offer automatic recovery, and verifies both candidates remain byte-identical.
- `LowStorage` fills only a named file on the disposable emulator data partition, verifies recovery-copy failure preserves the canonical source, removes that exact filler, and activates the rendered Retry action in the same process before checking the two-launch recovery policy.

## Production-signed Android gate

The GitHub v0.9.6 asset is production-signed, non-debuggable, and uses `com.devil.phoenixproject`. A genuine release upgrade therefore requires a candidate APK with a higher version code and the same production signing certificate. Do not re-sign either APK, substitute the debug result, use `pm clear`, or copy private app files through a weaker mechanism.

On a disposable physical device or release-test emulator with representative data already created through the released app UI:

1. Record the installed package version and signing-certificate digest.
2. Force-stop with active WAL state, install the production-signed candidate with `adb install -r`, and launch.
3. Verify profiles, workouts, routines, plaintext settings, and encrypted authentication state through app behavior.
4. Verify the sandbox filenames through an authorized release-test diagnostic or device container capture.
5. Launch a second time and verify neutral recovery artifacts are gone.

This gate cannot be completed from a local debug key. It remains explicit until CI produces the signed candidate and a controlled device is available.

A true skipped-version run must use the oldest still-supported production build (not v0.9.6) and the same production-signed candidate. The compatibility state-machine tests exercise discovery of the legacy filename indefinitely, but they do not replace this device evidence.

## Low-storage and interruption gate

The deterministic staging scenario covers restart after an interrupted copy. `LowStorage` performs a real allocation on a disposable AVD, records free bytes, forces the recovery-copy write to fail, verifies the legacy source remains canonical, removes only `/data/local/tmp/phoenix-persisted-file-low-storage.bin`, and uses the app's Retry button. It refuses to run on physical hardware. Do not report a permission-denied simulation as low-storage evidence.

## Physical iOS gate

Upgrade the last TestFlight or release installation on a physical iOS device with the candidate while retaining the same bundle ID and signing identity. Verify representative counts, schema version, backup exclusion on target/staging/recovery paths, first-launch recovery retention, and cleanup after the second launch. Record that device result against PR 709.

The project target `iosSimulatorArm64` exists, and CI runs `:shared:iosSimulatorArm64Test` on pushes to `main`. A simulator test is not this retained-install upgrade. Compiling or linking the Kotlin/Native framework is not a substitute for it either.

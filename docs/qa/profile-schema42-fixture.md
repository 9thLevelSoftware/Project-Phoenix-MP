# Profile schema-42 Android fixture

This recipe materializes the pre-profile database and legacy preference input used to prove the schema-42 to current-schema upgrade. The fixture source is commit `ac84d9bb8e156002833ad526bf324a8f12710da0`; do not substitute a moving branch or shortened SHA.

All commands below are PowerShell commands run from the repository root. They use only installed Android SDK components and Gradle's existing offline cache. They must not contact Supabase or any other network service.

## Create the schema-42 build

Create the isolated worktree and make only this version edit in `shared/build.gradle.kts`:

```powershell
git check-ignore -q .worktrees/profile-schema42
git worktree add .worktrees/profile-schema42 -b codex/schema-42-fixture ac84d9bb8e156002833ad526bf324a8f12710da0
Set-Location .worktrees/profile-schema42
```

```diff
-            // Version 41 = initial schema (1) + 40 migrations (1.sqm through 40.sqm).
-            version = 41
+            // Version 42 = initial schema (1) + 41 migrations (1.sqm through 41.sqm).
+            version = 42
```

The fixture branch must not contain `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/42.sqm`.

```powershell
$env:JAVA_HOME = 'C:\Users\dasbl\AppData\Local\Programs\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\dasbl\AppData\Local\Android\Sdk'
if (Test-Path shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/42.sqm) { throw '42.sqm must be absent' }
.\gradlew.bat '-Pskip.supabase.check=true' --offline :shared:generateCommonMainVitruvianDatabaseInterface :shared:verifyCommonMainVitruvianDatabaseMigration :shared:validateSchemaManifest :androidApp:assembleDebug --rerun-tasks --console=plain
```

## Create the disposable API-36 AVD

Use the installed Google Play x86_64 image and keep the AVD disposable. If this AVD name already exists, delete it first rather than reusing unknown state.

```powershell
$sdk = 'C:\Users\dasbl\AppData\Local\Android\Sdk'
$avd = 'phoenix-schema42-api36'
$package = 'com.devil.phoenixproject.debug'
& "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd --name $avd --package 'system-images;android-36;google_apis_playstore;x86_64' --device 'pixel_6' --force
& "$sdk\emulator\emulator.exe" -avd $avd -wipe-data -no-snapshot-load -no-boot-anim -dns-server 127.0.0.1
```

After Android boots, explicitly disable radios and install the fixture APK:

```powershell
$adb = "$sdk\platform-tools\adb.exe"
& $adb wait-for-device
& $adb shell svc wifi disable
& $adb shell svc data disable
$apk = (Resolve-Path 'androidApp/build/outputs/apk/debug/androidApp-debug.apk')
& $adb install $apk
& $adb shell monkey -p $package -c android.intent.category.LAUNCHER 1
& $adb shell am force-stop $package
```

## Inject the tracked legacy preferences and save the snapshot

The app must launch once before injection so that its schema-42 database and private data directory exist. Inject while it is stopped, then save the named snapshot.

```powershell
$xml = (Resolve-Path '..\profile-readiness\docs\qa\fixtures\profile-schema42\vitruvian_preferences.xml')
& $adb push $xml /data/local/tmp/vitruvian_preferences.xml
& $adb shell run-as $package mkdir -p shared_prefs
& $adb shell run-as $package cp /data/local/tmp/vitruvian_preferences.xml shared_prefs/vitruvian_preferences.xml
& $adb shell run-as $package chmod 600 shared_prefs/vitruvian_preferences.xml
& $adb shell run-as $package cat shared_prefs/vitruvian_preferences.xml
& $adb emu avd snapshot save phoenix-schema42-v1
& $adb emu avd snapshot list
```

Never uninstall the app and never run `pm clear` between fixture creation and the upgrade. Either action destroys the state this fixture is designed to exercise.

## Record checksums and immutable evidence

Store all generated evidence outside Git's tracked fixture inputs:

```powershell
$evidence = 'C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.phoenix-review\profile-readiness\schema42'
New-Item -ItemType Directory -Force $evidence | Out-Null
git rev-parse HEAD | Set-Content "$evidence\fixture-commit.txt"
Get-FileHash $apk -Algorithm SHA256 | Format-List | Out-File "$evidence\apk-sha256.txt"
Get-FileHash $xml -Algorithm SHA256 | Format-List | Out-File "$evidence\xml-sha256.txt"
& "$sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk | Out-File "$evidence\apk-signer.txt"
& $adb version | Out-File "$evidence\adb-version.txt"
& "$sdk\emulator\emulator.exe" -version 2>&1 | Out-File "$evidence\emulator-version.txt"
& $adb shell getprop ro.build.version.sdk | Out-File "$evidence\api.txt"
& $adb shell getprop ro.product.cpu.abi | Out-File "$evidence\abi.txt"
& $adb emu avd name | Out-File "$evidence\avd-name.txt"
Get-ChildItem "$env:USERPROFILE\.android\avd\$avd.avd\snapshots\phoenix-schema42-v1" -File -Recurse |
    Get-FileHash -Algorithm SHA256 |
    Sort-Object Path |
    Format-Table -AutoSize |
    Out-File "$evidence\snapshot-sha256.txt"
```

Shut down the fixture emulator after the snapshot is saved. Restore immutably by launching that snapshot with snapshot writes disabled:

```powershell
& $adb emu kill
& "$sdk\emulator\emulator.exe" -avd $avd -snapshot phoenix-schema42-v1 -no-snapshot-save -no-boot-anim -dns-server 127.0.0.1
& $adb wait-for-device
```

## Upgrade in place and inspect SQL

Install the current debug APK with `-r` so Android retains the schema-42 database and injected SharedPreferences. Again, do not uninstall and do not use `pm clear`.

```powershell
$upgradeApk = (Resolve-Path '..\profile-readiness\androidApp\build\outputs\apk\debug\androidApp-debug.apk')
& $adb install -r $upgradeApk
& $adb shell monkey -p $package -c android.intent.category.LAUNCHER 1
$migrationDeadline = [DateTime]::UtcNow.AddSeconds(60)
$migrationReady = $false
do {
    $migrationLine = & $adb shell run-as $package grep -F profile_preferences_legacy_migration_complete_v1 shared_prefs/vitruvian_preferences.xml 2>$null
    $migrationReady = $LASTEXITCODE -eq 0 -and $migrationLine -match 'value="true"'
    if (-not $migrationReady) { Start-Sleep -Seconds 1 }
} while (-not $migrationReady -and [DateTime]::UtcNow -lt $migrationDeadline)
if (-not $migrationReady) { throw 'Timed out after 60 seconds waiting for required profile preference migration' }
& $adb shell am force-stop $package
& $adb shell run-as $package sqlite3 databases/vitruvian.db 'PRAGMA user_version;'
& $adb shell run-as $package sqlite3 databases/vitruvian.db 'SELECT profile_id, legacy_migration_version, body_weight_kg, weight_unit, weight_increment, led_color_scheme_id, equipment_rack_json, workout_preferences_json, vbt_preferences_json FROM UserProfilePreferences ORDER BY profile_id;'
& $adb shell run-as $package cat shared_prefs/vitruvian_preferences.xml
```

The post-upgrade database must be on the current schema, each existing profile row must have `legacy_migration_version = 1`, and the sentinel values from the tracked XML must appear in the corresponding profile preference sections.

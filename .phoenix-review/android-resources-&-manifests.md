# Android Resources & Manifests Review

Task: `t_2d2b6fc7`
Scope: Android manifest, resources, backup rules, ProGuard config, and lint settings.

## Summary

Findings: 8

Severity breakdown:
- Critical: 0
- High: 1
- Medium: 4
- Low: 3

Verification performed:
- Read all 8 assigned files completely.
- Cross-checked manifest declarations against relevant Android code paths for BLE permissions, Health Connect routing, OAuth redirects, FileProvider use, backup/prefs/database names, and camera/microphone usage.
- Parsed all assigned XML files with Python `xml.etree.ElementTree`: all XML files are well-formed.
- Attempted `./gradlew :androidApp:lintDebug -Pskip.supabase.check=true --no-daemon`, but it could not run because no Java Runtime is installed on this machine.

## androidApp/src/main/AndroidManifest.xml

### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: 17-19
- Description: The manifest comment says location is required for BLE scanning on older Android, and the runtime BLE permission handler only requests `ACCESS_FINE_LOCATION` below Android 12. However, both `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` are declared without `android:maxSdkVersion="30"`, so Android 12+ builds still declare location access even though the app path no longer requests it for BLE. This can create unnecessary Play Console/data-safety disclosure burden and user trust friction.
- Suggested fix direction: If location is only for pre-Android-12 BLE scanning, add `android:maxSdkVersion="30"` to both location permissions. If a separate Android 12+ feature needs location, document that feature and ensure runtime request/disclosure paths exist.

### Finding 2
- Category: stub
- Severity: medium
- Line numbers: 41-55
- Description: `CAMERA` and optional camera features are declared for a "Form Check" feature, but repository search found no Android camera runtime permission request or CameraX/MediaPipe camera pipeline using `Manifest.permission.CAMERA`. Declaring a sensitive permission before the feature is implemented can trigger Play/privacy review questions and asks users for a capability the current app does not appear to use.
- Suggested fix direction: Remove the camera permission/features until Form Check ships, or complete the feature with runtime permission UX, privacy-policy coverage, and tests proving the permission is requested only when needed.

### Finding 3
- Category: failure-point
- Severity: high
- Line numbers: 106-120
- Description: Health Connect rationale/onboarding intent filters are attached to `MainActivity`, but `MainActivity` does not branch on `ACTION_SHOW_PERMISSIONS_RATIONALE`, `VIEW_PERMISSION_USAGE`, or `ACTION_SHOW_ONBOARDING`; it simply enters the normal app content behind `RequireBlePermissions`. A Health Connect/Play policy launch can therefore show the BLE permission gate or generic app shell instead of the required Health Connect rationale/onboarding/privacy surface.
- Suggested fix direction: Add explicit intent handling for these Health Connect actions before the BLE gate, or route them to a dedicated exported rationale/onboarding activity that displays the required Health Connect disclosure and privacy policy. Add a manifest/host test that launches each action and verifies the expected screen.

### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: 137-144
- Description: The OAuth callback intent filter hard-codes the custom scheme `com.devil.phoenixproject`. The Android debug build uses `applicationIdSuffix = ".debug"` in `androidApp/build.gradle.kts`, so debug and release installs still register the same OAuth scheme/host. If both variants are installed, Android can route the callback to the wrong app or show a resolver, breaking sign-in/debugging.
- Suggested fix direction: Use a manifest placeholder for the callback scheme and set variant-specific values, e.g. release `com.devil.phoenixproject` and debug `com.devil.phoenixproject.debug`, keeping Supabase redirect allow-lists and `PortalAuthRepository.OAUTH_CALLBACK_SCHEME` in sync per build variant.

## androidApp/src/main/res/values/strings.xml

No findings.

## androidApp/src/main/res/values/themes.xml

No findings.

## androidApp/src/main/res/xml/backup_rules.xml

### Finding 5
- Category: failure-point
- Severity: low
- Line numbers: 2-10
- Description: The full-backup rules are documented as excluding database and sensitive preference files, but `AndroidManifest.xml` sets `android:allowBackup="false"`. On API 23-30 this makes the `android:fullBackupContent` rule effectively inert, which is easy for future reviewers to misread as an active backup allow/exclude policy.
- Suggested fix direction: If Android Auto Backup is intentionally disabled, update the comments to say these rules are defensive/documentational only or remove the unused resource. If partial backup is intended, set `allowBackup="true"` only after auditing all included data domains.

## androidApp/src/main/res/xml/data_extraction_rules.xml

### Finding 6
- Category: failure-point
- Severity: medium
- Line numbers: 5-20
- Description: The API 31+ extraction rules exclude only the database and two shared-preference files, while the manifest simultaneously sets `allowBackup="false"`. Android 12+ data extraction rules govern cloud backup and device-transfer behavior, and OEM behavior around disabling device-to-device transfer can vary. As written, the policy is ambiguous: future internal files outside the excluded database/sharedpref names can become transferable even though the manifest appears to opt out of backup.
- Suggested fix direction: Decide explicitly between full opt-out and selective transfer. For full opt-out, add broad excludes for the relevant domains under both `<cloud-backup>` and `<device-transfer>`. For selective transfer, document the intended transferable domains and add regression tests/checks for newly added sensitive files.

## androidApp/src/main/res/xml/file_paths.xml

No findings.

## androidApp/proguard-rules.pro

### Finding 7
- Category: failure-point
- Severity: low
- Line numbers: 72-79
- Description: The release R8 configuration keeps all `androidx.compose.**` classes and Compose runtime fields. Compose libraries normally ship consumer rules, so this broad keep can significantly reduce shrinking/obfuscation effectiveness and increase release APK/AAB size without addressing a specific reflection boundary.
- Suggested fix direction: Remove the broad Compose keep rules unless a reproducible minified-release crash requires them. Prefer narrow keep rules tied to the failing class/member and verify with a minified release build.

## androidApp/lint.xml

### Finding 8
- Category: failure-point
- Severity: low
- Line numbers: 21-22
- Description: Play policy lint checks for Health Connect and Foreground Services are globally downgraded to informational. The comments say declarations are maintained separately, but this makes local/CI lint less likely to catch future manifest-policy drift when permissions or service types change.
- Suggested fix direction: Keep these checks warning/error in release CI, or add a separate policy verification document/test with owners and update cadence. If the downgrade stays, link the exact Play Console declarations or policy review artifact in the comment so future changes can be audited.

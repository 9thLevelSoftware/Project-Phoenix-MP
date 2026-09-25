# Project Phoenix - iOS App

iOS application for controlling compatible smart fitness machines via BLE.

## Prerequisites

- macOS with Xcode 26.x (CI uses Xcode 26.2; Compose Multiplatform 1.11 links against the iOS 26 SDK)
- JDK 17
- iOS device with Bluetooth LE support (iOS 15.0+)

## Building the Shared Framework

Before opening in Xcode, build the shared Kotlin Multiplatform framework and its
Compose resources. These are the same three Gradle tasks the release workflows
run (`.github/workflows/ios-testflight.yml`), using the debug framework that the
Xcode project links:

```bash
# From the project root directory
./gradlew :shared:linkDebugFrameworkIosArm64 \
  :shared:generateComposeResClass \
  :shared:iosArm64ProcessResources \
  -Pskip.supabase.check=true
```

The Xcode project picks up:
- the framework from `shared/build/bin/iosArm64/debugFramework/shared.framework`
- the Compose resources from `shared/build/processedResources/iosArm64/main/composeResources` (copied by a build phase)

(CI runs `:shared:linkReleaseFrameworkIosArm64` instead of the debug task for release builds.)

Then open the project:

```bash
open iosApp/PhoenixApp/PhoenixApp.xcodeproj
```

## Xcode Project Setup

### Supabase Configuration

The Xcode project references the tracked
`PhoenixApp/Config/SupabaseBase.xcconfig`, which optionally includes the
local-only `PhoenixApp/Config/Supabase.xcconfig`. The local file is
intentionally ignored by git because it contains environment values. Create it
from the tracked template before opening the project:

```bash
cp PhoenixApp/Config/Supabase.xcconfig.example PhoenixApp/Config/Supabase.xcconfig
```

Fill in local development values in `Supabase.xcconfig`. GitHub Actions writes
that ignored file from encrypted repository secrets during iOS build workflows, so the
real file must not be committed. If a real anon key was ever committed, rotate
it in Supabase and update the GitHub secrets.

### Project Files

Use the checked-in `PhoenixApp/PhoenixApp.xcodeproj`. The `PhoenixApp/PhoenixApp/` directory contains the Swift source files:

- `PhoenixApp.swift` - App entry point with Koin initialization
- `ContentView.swift` - SwiftUI wrapper for Compose Multiplatform UI
- `Info.plist` - App configuration with BLE permissions

## Key Features

### Bluetooth Permissions

The `Info.plist` includes the required BLE permission string:
- `NSBluetoothAlwaysUsageDescription` - Required for BLE scanning/connection

### Bluetooth Integration

BLE is shared code: `KableBleRepository.kt` in `shared/src/commonMain` uses the
Kable multiplatform library, which runs on CoreBluetooth on iOS. It scans for
Phoenix devices (names starting with "Vee_" or "VIT"), connects, parses real-time
workout metrics and handles rep notifications. iOS-specific code lives in
`shared/src/iosMain/`.

## Testing on Device

1. Connect your iOS device
2. Select your device as the run destination
3. Build and run from Xcode
4. Grant Bluetooth permission when prompted
5. The app will scan for Phoenix devices

## Troubleshooting

### Framework Not Found

If you get "No such module 'shared'" error:
1. Rebuild the framework with the Gradle tasks in [Building the Shared Framework](#building-the-shared-framework)
2. Clean Xcode build folder: Product → Clean Build Folder
3. Verify framework path in Build Settings → Framework Search Paths

### Bluetooth Not Working

- Ensure "bluetooth-le" capability is in UIRequiredDeviceCapabilities
- Test on a real device (Simulator doesn't support BLE)
- Check that Bluetooth is enabled on the device

### Koin Initialization and Migrations

The app initializes Koin and runs migrations in `PhoenixAppEntry.init()`:
```swift
try KoinInitIosKt.doInitKoin()   // declared in shared/iosMain/.../KoinInitIos.kt (@Throws)
KoinInitKt.runMigrations()        // declared in shared/commonMain/.../KoinInit.kt
```

This must be called before any Compose UI is rendered. Migrations run automatically on app startup, matching Android behavior.

### BLE Permission Handling

The app includes a BLE permission UI component (`RequireBlePermissions`) that:
- Shows guidance screens before BLE is first used
- Provides instructions for enabling permissions in Settings if denied
- Matches Android's permission handling UX

On iOS, CoreBluetooth automatically requests permission when BLE scanning starts, but the UI provides guidance beforehand.

### Background Execution

The app supports background BLE execution via `UIBackgroundModes` with `bluetooth-central` in `Info.plist`. This allows BLE connections to persist when the app is backgrounded, similar to Android's foreground service.

## Assets

Icons, the launch image, and the launch background are already in the Xcode asset catalog. CI archives that catalog as-is. Details are in [SETUP_ASSETS.md](SETUP_ASSETS.md).

### App icon

`Assets.xcassets/AppIcon.appiconset` is a single universal 1024×1024 PNG (`AppIcon1024.png`). The same file is also at `iosApp/AppIcon1024.png`. `ios-testflight.yml` validates both before signing:

```bash
python3 scripts/validate_ios_app_icons.py --source iosApp/AppIcon1024.png
python3 scripts/test_ios_app_icons.py
```

### Launch screen

`Info.plist` `UILaunchScreen` names two catalog entries that are checked in:

- `LaunchIcon.imageset` — 200pt mark (1x/2x/3x) downsampled from `AppIcon1024.png`
- `LaunchScreenBackground.colorset` — light `#F8FAFC`, dark `#0F172A` (same window background as Android)

### Sounds

`iosApp/convert_sounds.sh` converts `shared/src/androidMain/res/raw/*.ogg` to `PhoenixApp/PhoenixApp/Sounds/*.caf`. Run it on macOS only when those OGG sources change (`brew install ffmpeg`). The Xcode project uses a synchronized group, so new `.caf` files in that folder are bundled without adding them by hand.

### TestFlight Deployment

TestFlight builds are produced by the manually triggered GitHub Actions
workflows (`iOS TestFlight`, or `Release All Platforms` for a full release).
See [GITHUB_ACTIONS_SETUP.md](GITHUB_ACTIONS_SETUP.md).

## Development Notes

- The Compose Multiplatform UI is shared between Android and iOS
- Platform-specific code is in `shared/src/iosMain/`
- All business logic is shared via the `shared` module
- The SwiftUI wrapper is minimal - just hosts the Compose view
- Sound playback uses AVAudioPlayer in `HapticFeedbackEffect.ios.kt`
- Haptic feedback uses UIImpactFeedbackGenerator and UINotificationFeedbackGenerator

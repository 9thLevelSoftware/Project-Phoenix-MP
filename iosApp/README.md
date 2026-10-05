# Project Phoenix - iOS App

iOS application for controlling compatible smart fitness machines via BLE.

## Prerequisites

- macOS with Xcode 26.x (CI uses Xcode 26.2; Compose Multiplatform 1.11 links against the iOS 26 SDK)
- JDK 17
- iOS device with Bluetooth LE support (iOS 15.0+)

## Building the Shared Framework

Before opening in Xcode, build the shared Kotlin Multiplatform framework and
install it on the path the Xcode project links. Debug and Release both use
`shared/build/bin/iosArm64/xcodeFramework/shared.framework`. Gradle still writes
the configuration-specific bundle under `debugFramework` or `releaseFramework`;
`iosApp/install-xcode-framework.sh` copies the one you just built.

```bash
# From the project root directory
./gradlew :shared:linkDebugFrameworkIosArm64 \
  :shared:generateComposeResClass \
  :shared:iosArm64ProcessResources \
  -Pskip.supabase.check=true
iosApp/install-xcode-framework.sh debug
```

The Xcode project picks up:
- the framework from `shared/build/bin/iosArm64/xcodeFramework/shared.framework`
- the Compose resources from `shared/build/processedResources/iosArm64/main/composeResources` (copied by a build phase; device SDK only)

Release workflows (`.github/workflows/ios-testflight.yml`, `ios-release-ipa.yml`, `ios-testflight-internal.yml`) run `:shared:linkReleaseFrameworkIosArm64` and then `iosApp/install-xcode-framework.sh release`.

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
Kable multiplatform library, which runs on CoreBluetooth on iOS. Scanning and
connection live in `KableBleConnectionManager.kt`. The app parses real-time
workout metrics and handles rep notifications. iOS-specific code lives in
`shared/src/iosMain/`.

The interactive scan (`startScanning()`) keeps an advertisement when:

- Its name starts with `Vee_`, `VIT`, or `Phoenix` (case-insensitive). A named
  advertisement that matches none of those prefixes is ignored, even if it also
  carries a service UUID.
- It has no name, and it advertises the Nordic UART service
  (`6e400001-b5a3-f393-e0a9-e50e24dcca9e`), a service UUID whose string starts
  with `0000fef3`, or non-empty service data for FEF3
  (`0000fef3-0000-1000-8000-00805f9b34fb`).

`scanAndConnect()` does not use that filter. It connects only to the first
advertisement whose name starts with `Vee_` or `VIT` (case-insensitive). The
`Phoenix` prefix and the UUID/FEF3 fallback do not apply there. Once a `Vee_`
or `VIT` device is in the interactive scan list, advertisements that match only
by a `Phoenix` name or by UUID/FEF3 are left out of that list.

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

`PhoenixAppEntry.init()` in `PhoenixApp/PhoenixApp/PhoenixApp.swift` starts Koin before the SwiftUI scene is shown:

```swift
try KoinInitIosKt.doInitKoin()
```

`doInitKoin()` is declared in `shared/src/iosMain/kotlin/com/devil/phoenixproject/di/KoinInitIos.kt` with `@Throws(Throwable::class)`, so the Swift `try` receives initialization failures. It delegates to `doInitKoinInternal()` in `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt`, which calls `initKoin()`.

Required startup work runs later, inside the Compose host. `ContentView` (`PhoenixApp/PhoenixApp/ContentView.swift`) creates the UI with `MainViewControllerKt.MainViewController()`. `MainViewController()` in `shared/src/iosMain/kotlin/com/devil/phoenixproject/MainViewController.kt` builds a `ComposeUIViewController` whose content is `IosAppHost()`.

`IosAppHost` (`shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt`) then:

1. Draws `StartupPendingSurface()` (the splash) while startup is still unresolved.
2. On `Dispatchers.Default`, `prepareAppHostDependencies` resolves `PersistedFileStartupPrerequisite` and `MigrationManager`, then calls `runRequiredMigrations()` and `awaitRequiredMigrations()`.
3. On failure, shows `PersistedFileStartupFailureScreen`, which can retry.
4. On success, shows `RequireBlePermissions` around `IosAppContent`.

Android follows the same gate in `AndroidAppHost`: `runRequiredMigrations()` and `awaitRequiredMigrations()` finish before the feature UI.

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

`Assets.xcassets/AppIcon.appiconset` is a single universal 1024×1024 PNG (`AppIcon1024.png`). The same file is also at `iosApp/AppIcon1024.png`. PR CI (`.github/workflows/ci-tests.yml`) and the release workflows (`ios-testflight.yml`, `ios-testflight-internal.yml`, `ios-release-ipa.yml`) validate both:

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

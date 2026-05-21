# iOS App Assets Setup Guide

This guide covers the manual setup steps required to complete iOS app asset configuration. These steps must be performed in Xcode.

## App Icons

### Source Image
The app icon source image is located at:
- `androidApp/src/main/res/drawable-xxxhdpi/vitphoe_logo_foreground.png`

### Setup Steps

1. **Open Xcode Project**
   - Open your Xcode project for VitruvianPhoenix

2. **Create AppIcon Asset**
   - In Xcode, select your project in the navigator
   - Select the target → General tab
   - Scroll to "App Icons and Launch Screen"
   - Click "Use Asset Catalog" if not already using one
   - Open `Assets.xcassets` in the navigator
   - Right-click and select "New Image Set" → Name it `AppIcon`

3. **Add Icon Sizes**
   - Select the `AppIcon` image set
   - Drag the source image (`vitphoe_logo_foreground.png`) into the appropriate slots:
     - **iPhone**: 20pt, 29pt, 40pt, 60pt (2x and 3x for each)
     - **iPad**: 20pt, 29pt, 40pt, 76pt, 83.5pt (1x, 2x for each)
     - **App Store**: 1024pt (1x)
   - Or use an app icon generator tool to create all sizes from the source

4. **Configure in Project Settings**
   - Select target → General → App Icons and Launch Screen
   - Set "App Icons Source" to `AppIcon`

## Launch Screen Assets

### Launch Icon

1. **Create LaunchIcon Image Set**
   - In `Assets.xcassets`, create a new Image Set named `LaunchIcon`
   - Add the logo image (same source as app icon)
   - Configure for 1x, 2x, 3x scales

### Launch Screen Background Color

1. **Create Color Asset**
   - In `Assets.xcassets`, create a new Color Set named `LaunchScreenBackground`
   - Set the color to match the app theme background:
     - Light mode: `#F8FAFC` (SurfaceContainerLight)
     - Dark mode: `#0F172A` (SurfaceContainerDark)
   - Or use the color from `androidApp/src/main/res/drawable/ic_launcher_background.xml` (#0F172A)

2. **Verify Info.plist**
   - Ensure `Info.plist` references these assets:
     ```xml
     <key>UILaunchScreen</key>
     <dict>
         <key>UIColorName</key>
         <string>LaunchScreenBackground</string>
         <key>UIImageName</key>
         <string>LaunchIcon</string>
     </dict>
     ```

## Sound Files

iOS sound playback is implemented in `HapticFeedbackEffect.ios.kt` using AVAudioPlayer. The code automatically looks for sound files in the app bundle.

### Source Files
Android sound files are located in the shared Kotlin Multiplatform library resources:
- `shared/src/androidMain/res/raw/*.ogg`

This directory contains 60+ sound files including core workout tones, rep-count announcements (`rep_01` through `rep_25`), and various badge/PR celebration tracks.

### Automated Conversion (Recommended)

To dynamically scan and convert all raw sound files from the shared KMP resources to iOS Core Audio Format (.caf) and place them directly in the synced Xcode target folder:

Run the conversion script on macOS:
```bash
cd iosApp
chmod +x convert_sounds.sh
./convert_sounds.sh
```

This script works dynamically and utilizes either `afconvert` (native macOS tool) + `ffmpeg` (to generate highly optimized ADPCM `ima4` compressed `.caf` audio files) or falls back to `ffmpeg` alone.

### Manual Conversion Steps

1. **Convert OGG to iOS Format**
   - iOS supports `.caf` (Core Audio Format), `.m4a`, `.wav`, or `.mp3`
   - Use ffmpeg (recommended):
     ```bash
     ffmpeg -i beep.ogg beep.caf
     ```
   - Or use `afconvert` with intermediate WAV:
     ```bash
     ffmpeg -i beep.ogg beep.wav
     afconvert beep.wav beep.caf -d ima4 -f caff
     ```

2. **Verification in Xcode Project**
   - The converted files are placed directly in the `VitruvianPhoenix/VitruvianPhoenix/Sounds/` folder on disk.
   - Because the Xcode project uses file-system synchronized groups, these files are **automatically** picked up and bundled with the iOS target. No manual dragging or target membership configuration is needed.

3. **Verify Sound Loading**
   - The app will log sound loading status at startup
   - Check console for: "Loaded sound: beep.caf" messages
   - Missing sounds will show: "Sound file not found: beep"

## Quick Setup Checklist

- [ ] Create `AppIcon.appiconset` with all required sizes
- [ ] Configure app icon in target settings
- [ ] Create `LaunchIcon.imageset` with logo
- [ ] Create `LaunchScreenBackground.colorset` with theme colors (#0F172A dark / #F8FAFC light)
- [ ] Verify `Info.plist` references launch assets correctly
- [ ] Run `./convert_sounds.sh` to dynamically convert and synchronize all sound files
- [ ] Build shared framework: `./gradlew :shared:assembleXCFramework`
- [ ] Verify shared.xcframework is linked in Xcode
- [ ] Test app icons display correctly on device
- [ ] Test launch screen displays correctly
- [ ] Test sound playback and haptic feedback during workout

## Notes

- App icons and launch screen assets are required for App Store submission
- Sound files are optional but enhance user experience (haptic feedback works without them)
- All asset setup must be done in Xcode - these cannot be automated via Gradle
- The `Info.plist` already references `LaunchIcon` and `LaunchScreenBackground` - you just need to create the assets
- Sound playback is implemented in `shared/src/iosMain/.../HapticFeedbackEffect.ios.kt` using AVAudioPlayer
- The app supports `.caf`, `.m4a`, `.wav`, and `.mp3` formats (tries them in that order)


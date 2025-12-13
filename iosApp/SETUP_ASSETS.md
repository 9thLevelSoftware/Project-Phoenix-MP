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

### Source Files
Android sound files are located at:
- `androidApp/src/main/res/raw/beep.ogg`
- `androidApp/src/main/res/raw/beepboop.ogg`
- `androidApp/src/main/res/raw/boopbeepbeep.ogg`
- `androidApp/src/main/res/raw/chirpchirp.ogg`
- `androidApp/src/main/res/raw/restover.ogg`

### Conversion Steps

1. **Convert OGG to iOS Format**
   - iOS supports `.caf` (Core Audio Format) or `.m4a` formats
   - Use `afconvert` command-line tool (macOS):
     ```bash
     afconvert beep.ogg beep.caf -d ima4 -f caff
     ```
   - Or use a tool like Audacity to export as `.m4a` or `.caf`

2. **Add to Xcode Project**
   - Create a `Sounds` folder in your Xcode project
   - Drag converted sound files into the folder
   - Ensure "Copy items if needed" is checked
   - Add to target membership

3. **Note on Sound Playback**
   - Currently, sound playback uses Android's SoundPool
   - iOS implementation may need expect/actual pattern for AVFoundation
   - See `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.ios.kt` for iOS haptic implementation

## Quick Setup Checklist

- [ ] Create `AppIcon.appiconset` with all required sizes
- [ ] Configure app icon in target settings
- [ ] Create `LaunchIcon.imageset` with logo
- [ ] Create `LaunchScreenBackground.colorset` with theme colors
- [ ] Verify `Info.plist` references launch assets correctly
- [ ] Convert OGG sound files to CAF/M4A format
- [ ] Add sound files to Xcode project bundle
- [ ] Test app icons display correctly on device
- [ ] Test launch screen displays correctly
- [ ] Test sound playback (if iOS implementation added)

## Notes

- App icons and launch screen assets are required for App Store submission
- Sound files are optional but enhance user experience
- All asset setup must be done in Xcode - these cannot be automated via code
- The `Info.plist` already references `LaunchIcon` and `LaunchScreenBackground` - you just need to create the assets


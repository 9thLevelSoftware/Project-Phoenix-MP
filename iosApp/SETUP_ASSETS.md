# iOS App Assets

Icons and the launch screen are committed in `PhoenixApp/PhoenixApp/Assets.xcassets`. The iOS workflows archive those files directly. Local setup is the Gradle framework build in [README.md](README.md), plus `convert_sounds.sh` when the Android OGG sources change.

## App icon

`AppIcon.appiconset` contains one universal iOS icon:

- `AppIcon1024.png` (1024×1024, opaque RGB PNG)
- The same bytes are at `iosApp/AppIcon1024.png`, which TestFlight passes to the validator as `--source`

`ios-testflight.yml` checks them before signing:

```bash
python3 scripts/validate_ios_app_icons.py --source iosApp/AppIcon1024.png
python3 scripts/test_ios_app_icons.py
```

`Contents.json` lists that one universal image with `"size" : "1024x1024"`. `scripts/validate_ios_app_icons.py` checks each declared `size` against the PNG pixel dimensions, and this file is 1024×1024.

## Launch screen

`Info.plist` already points `UILaunchScreen` at the catalog:

```xml
<key>UILaunchScreen</key>
<dict>
    <key>UIColorName</key>
    <string>LaunchScreenBackground</string>
    <key>UIImageName</key>
    <string>LaunchIcon</string>
</dict>
```

`LaunchIcon.imageset` is a 200pt centered mark derived from `AppIcon1024.png`:

| File | Pixels | Scale |
|------|--------|-------|
| `LaunchIcon.png` | 200×200 | 1x |
| `LaunchIcon@2x.png` | 400×400 | 2x |
| `LaunchIcon@3x.png` | 600×600 | 3x |

`LaunchScreenBackground.colorset` matches the app window background:

- Any appearance (light): `#F8FAFC`
- Dark: `#0F172A`

Those are the same values as `phoenix_window_background` (`values/colors.xml` and `values-night/colors.xml`).

## Shared framework

The Xcode project links the debug framework. From the repo root, with Supabase checks skipped for a local build that does not need credentials:

```bash
./gradlew :shared:linkDebugFrameworkIosArm64 \
  :shared:generateComposeResClass \
  :shared:iosArm64ProcessResources \
  -Pskip.supabase.check=true
```

Release workflows (`ios-testflight.yml`, `ios-testflight-internal.yml`, `ios-release-ipa.yml`) use the release framework in one Gradle invocation:

```bash
./gradlew :shared:linkReleaseFrameworkIosArm64 \
  :shared:generateComposeResClass \
  :shared:iosArm64ProcessResources \
  --no-parallel -Pskip.supabase.check=true
```

Outputs the Xcode project expects:

- `shared/build/bin/iosArm64/debugFramework/shared.framework` (local debug builds)
- `shared/build/processedResources/iosArm64/main/composeResources` (copied into the app bundle by an Xcode build phase)

## Sound files

iOS playback is in `shared/src/iosMain/.../HapticFeedbackEffect.ios.kt` (AVAudioPlayer). It loads `.caf` files from the app bundle, trying `.caf`, then `.m4a`, `.wav`, and `.mp3`.

Sources live in `shared/src/androidMain/res/raw/*.ogg` (workout tones, `rep_01`–`rep_25`, badge and PR tracks). Convert them when those OGG files change:

```bash
cd iosApp
chmod +x convert_sounds.sh
./convert_sounds.sh
```

The script prefers `afconvert` (Xcode command-line tools) plus `ffmpeg`, and falls back to `ffmpeg` alone (`brew install ffmpeg`). It writes `PhoenixApp/PhoenixApp/Sounds/*.caf`. That folder sits in the synchronized `PhoenixApp` group, so the files are bundled without a manual target-membership change.

Startup logs `Loaded sound: beep.caf` for files it finds, and `Sound file not found: beep` when one is missing. Haptics still run if a sound file is absent.

## What is already in git

- [x] `AppIcon.appiconset` — one universal 1024×1024 icon
- [x] `LaunchIcon.imageset` — 1x/2x/3x mark from that icon
- [x] `LaunchScreenBackground.colorset` — `#F8FAFC` light / `#0F172A` dark
- [x] `Info.plist` `UILaunchScreen` references both launch assets
- [ ] `./convert_sounds.sh` — only after `shared/src/androidMain/res/raw/*.ogg` changes
- [ ] Debug framework build — the Gradle command above, before opening the project in Xcode

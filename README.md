# Vitruvian Project Phoenix - Multiplatform Control App

[![Latest Release](https://img.shields.io/github/v/release/DasBluEyedDevil/Project-Phoenix-2.0?include_prereleases)](https://github.com/DasBluEyedDevil/Project-Phoenix-2.0/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg)](https://kotlinlang.org)

A Kotlin Multiplatform application for controlling Vitruvian Trainer workout machines via Bluetooth Low Energy (BLE). This is a multiplatform version of the original [VitruvianProjectPhoenix](https://github.com/DasBluEyedDevil/VitruvianProjectPhoenix) Android app.

## Support the Project

If you find this app useful and want to support its continued development:

**[Buy Me a Coffee](https://buymeacoffee.com/vitruvianredux)**

Your support helps keep the machines running and the code flowing!

---

## Project Overview

This app enables local control of Vitruvian Trainer machines after the company's bankruptcy. It's a community rescue project providing native applications for multiple platforms to keep these machines functional and prevent them from becoming e-waste.

## Installation

The app is now available on both app stores!

| Platform | App Store | Alternative |
|----------|-----------|-------------|
| **Android** | [Google Play Store](https://play.google.com/store/apps/details?id=com.devil.phoenixproject) | [APK Download](../../releases) |
| **iOS** | [App Store](https://apps.apple.com/app/vitruvian-phoenix/id6740537270) | [TestFlight Beta](iOS_INSTALL.md#option-2-testflight-beta-testing) |

For detailed installation instructions, see:
- [Android Install Guide](ANDROID_INSTALL.md)
- [iOS Install Guide](iOS_INSTALL.md)

## Supported Platforms

| Platform | Status | Notes |
|----------|--------|-------|
| **Android** | Released | Google Play Store + APK sideloading |
| **iOS** | Released | App Store + TestFlight beta |

## Features

### Core Workout Features
- **BLE Connectivity**: Reliable connection to Vitruvian Trainer devices
- **All Workout Modes**: Old School, Pump, TUT, TUT Beast, Eccentric-Only, Echo
- **Real-time Monitoring**: Live load, position, velocity, and power metrics
- **Rep Counting**: Accurate rep detection with visual feedback
- **Auto-Stop**: Automatic workout termination based on rep targets
- **Screen Wake Lock**: Screen stays on during active workouts

### Set Summary & History
- **Detailed Set Summary**: View comprehensive stats after each set (reps, volume, forces, calories, RPE)
- **Expandable History Cards**: Tap any workout in history to view the full set summary
- **Echo Mode Phase Breakdown**: See warmup, working, and burnout phase metrics
- **RPE Tracking**: Rate perceived exertion saved with each set

### Exercise Library & Routines
- **200+ Exercises**: Pre-loaded exercises categorized by muscle group
- **Custom Routines**: Build and save your own workout routines
- **Superset Support**: Create and manage supersets with visual tree connectors
- **Drag-and-Drop**: Easily reorder exercises and create supersets by dragging

### Training Cycles
- **Multi-Week Programs**: Create structured training programs with customizable day counts
- **Two-Panel Editor**: Visual cycle editor with drag-and-drop routine assignment
- **Per-Day Modifiers**: Configure intensity, volume, and deload settings for each day
- **Cycle Review**: Collapsible timeline view of your entire training cycle
- **Progress Tracking**: Automatic day completion and cycle progression
- **Day Strip Navigation**: Quick navigation between cycle days

### Analytics & Insights
- **Personal Records**: Automatic PR detection and historical tracking
- **Workout History**: Complete history with metrics stored locally
- **Muscle Balance Radar**: Visual balance across muscle groups
- **Consistency Gauge**: Monthly workout consistency tracking
- **Volume vs Intensity**: Session comparison charts
- **Total Volume History**: Track volume lifted over time
- **Mode Distribution**: Workout mode usage breakdown

### Data Management
- **Local Storage**: All data stored securely on your device
- **Backup & Restore**: Export/import your workout history
- **No Account Required**: Works completely offline

## Technology Stack

| Category | Technology |
|----------|------------|
| **Language** | Kotlin 2.0+ |
| **UI** | Compose Multiplatform |
| **Architecture** | MVVM + Clean Architecture |
| **DI** | Koin (Multiplatform) |
| **BLE** | Platform-specific implementations |
| **Database** | SQLDelight (Multiplatform) |
| **Preferences** | Multiplatform Settings |
| **Async** | Coroutines + Flow |

## Project Structure

```
Project-Phoenix-MP/
├── shared/                    # Shared Kotlin Multiplatform code
│   └── src/
│       ├── commonMain/        # Common business logic
│       ├── androidMain/       # Android-specific implementations
│       └── iosMain/           # iOS-specific implementations
├── androidApp/                # Android application
├── iosApp/                    # iOS application (Xcode project)
├── gradle/                    # Gradle wrapper and version catalog
├── build.gradle.kts           # Root build configuration
└── settings.gradle.kts        # Project settings
```

## Building from Source

### Prerequisites
- JDK 17+
- Android Studio Hedgehog or newer (for Android development)
- Xcode 15+ (for iOS development, macOS only)
- Kotlin 2.0+

### Building the Android App
```bash
./gradlew :androidApp:assembleDebug
```

### Building for iOS
```bash
# Build the shared framework first
./gradlew :shared:assembleXCFramework

# Then open in Xcode and build
open iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj
```

## Hardware Compatibility

### Vitruvian V-Form Trainer (Euclid / VIT-200)
- **Status:** Fully Supported
- **Device Name:** `Vee_*`
- **Max Resistance:** 200 kg (440 lbs)

### Vitruvian Trainer+
- **Status:** Fully Supported
- **Max Resistance:** 220 kg (485 lbs)

## What's New in v0.2.1

### Set Summary History
- View detailed workout metrics from history by tapping workout entries
- Expandable cards with chevron animation
- Full SetSummaryCard display including forces, volume, and calories
- RPE displayed when recorded
- Graceful handling of pre-v0.2.1 workouts

### Training Cycles Redesign
- New day count picker for cycle creation
- Two-panel cycle editor with drag-and-drop routine assignment
- Per-day modifier configuration (intensity, volume, deload)
- Cycle review screen with collapsible timeline
- Day strip for quick navigation
- Automatic day completion tracking

### Superset Containers
- First-class superset support with dedicated database table
- Visual tree connectors showing superset groupings
- Create supersets via drag-and-drop or context menu
- Enhanced superset management dialogs

### Quality of Life
- Screen stays awake during active workouts
- Improved audio management for workout sounds

## Contributing

This is an open-source community project. Contributions welcome!

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Test with real hardware if possible
5. Submit a pull request

## License

MIT License - See [LICENSE](LICENSE) file for details

## Acknowledgments

- Original [VitruvianProjectPhoenix](https://github.com/DasBluEyedDevil/VitruvianProjectPhoenix) Android app
- Original web app developers for reverse-engineering the BLE protocol
- Vitruvian machine owners community for testing and feedback
- JetBrains for Kotlin Multiplatform
- All contributors and supporters

## Support

- **Issues**: [GitHub Issues](https://github.com/DasBluEyedDevil/Project-Phoenix-2.0/issues)
- **Discussions**: [GitHub Discussions](https://github.com/DasBluEyedDevil/Project-Phoenix-2.0/discussions)
- **Support Development**: [Buy Me a Coffee](https://buymeacoffee.com/vitruvianredux)

---

*This app is a community rescue project to keep Vitruvian Trainer machines functional after the company's bankruptcy. It is not affiliated with or endorsed by Vitruvian.*

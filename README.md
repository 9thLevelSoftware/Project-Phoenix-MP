# Project Phoenix - Vitruvian Trainer Control App

[![Latest Release](https://img.shields.io/github/v/release/DasBluEyedDevil/Project-Phoenix-MP?include_prereleases)](https://github.com/DasBluEyedDevil/Project-Phoenix-MP/releases)
[![License](https://img.shields.io/badge/license-Proprietary-red.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS-green.svg)](https://github.com/DasBluEyedDevil/Project-Phoenix-MP/releases)

**Keep your Vitruvian Trainer alive.** This community-developed app restores full functionality to Vitruvian V-Form and Trainer+ machines after the company's closure. Don't let your investment become e-waste.

---

## Support the Project

If Project Phoenix has helped keep your machine running, please consider supporting continued development:

**[☕ Support on Ko-fi](https://ko-fi.com/vitruvianredux)**

Your support helps cover development costs and keeps this community rescue project going!

---

## Installation

| Platform | Install | Guide |
|----------|---------|-------|
| **Android** | [Join Beta](https://dasblueyeddevil.github.io/Project-Phoenix-MP/#beta-signup) | [Android Guide](ANDROID_INSTALL.md) |
| **iOS** | [TestFlight](https://testflight.apple.com/join/TFw1m89R) | [iOS Guide](iOS_INSTALL.md) |

---

## Features

### All Workout Modes
| Mode | Description |
|------|-------------|
| **Just Lift** | Quick start - grab handles and go, no setup required |
| **Old School** | Classic resistance training |
| **Pump** | High rep, muscle pump focus |
| **TUT** | Time Under Tension - controlled tempo |
| **TUT Beast** | Intensified TUT training |
| **Eccentric-Only** | Negative-focused reps |
| **Echo** | Progressive loading with warmup, working, and burnout phases |

### Real-Time Workout Tracking
- Live load, velocity, position, and power metrics
- Animated rep counter with visual phase feedback
- Auto-stop when rep targets reached
- Detailed set summaries with concentric/eccentric force breakdowns
- RPE (Rate of Perceived Exertion) logging
- Screen stays on during workouts

### Exercise Library & Routines
- **200+ exercises** organized by muscle group
- Build custom routines with **superset support**
- Drag-and-drop exercise ordering
- Visual tree connectors for supersets
- Per-exercise weight, reps, and mode configuration

### Training Cycles
- Create **multi-week training programs**
- Two-panel editor with drag-and-drop routine assignment
- Per-day intensity, volume, and deload modifiers
- Automatic progression tracking
- Day strip navigation for quick access

### Analytics & Progress
- **Automatic personal record detection**
- Complete workout history with expandable stats
- Muscle balance radar chart
- Workout consistency tracking
- Volume vs intensity comparisons
- Mode distribution breakdown

### Privacy Focused
- All data stored locally on your device
- No account required
- Works completely offline
- Backup & restore your data anytime

---

## Supported Hardware

| Machine | Device Name | Max Resistance | Status |
|---------|-------------|----------------|--------|
| **Vitruvian V-Form Trainer** (VIT-200) | `Vee_*` | 200 kg (440 lbs) | ✅ Fully Supported |
| **Vitruvian Trainer+** | `VIT*` | 220 kg (485 lbs) | ✅ Fully Supported |

---

## What's New in v0.3.2

### Bug Fixes
- **Zero Rest Time Fix**: Routines with 0 rest between sets no longer soft-lock
- **Navigation Fix**: Exiting routine workouts now navigates correctly (no blank screens)
- **Superset Fix**: Supersets now loop correctly through all sets
- **Single-Cable Fix**: Single-cable exercises work properly
- **Summary Settings**: "Off" setting now correctly skips summary screen

### Improvements
- Unified autoplay behavior through Set Summary setting
- Animated rep counter with phase visualization
- Video playback during workouts
- Live stats dashboard with real-time metrics

See [Release Notes](https://github.com/DasBluEyedDevil/Project-Phoenix-MP/releases/tag/v0.3.2) for full details.

---

## Building from Source

### Prerequisites
- JDK 17+
- Android Studio Hedgehog or newer
- Xcode 15+ (for iOS, macOS only)
- Kotlin 2.0+

### Android
```bash
./gradlew :androidApp:assembleDebug
```

### iOS
```bash
./gradlew :shared:assembleXCFramework
open iosApp/VitruvianPhoenix/VitruvianPhoenix.xcodeproj
```

---

## Technology Stack

| Category | Technology |
|----------|------------|
| **Language** | Kotlin 2.0+ |
| **UI** | Compose Multiplatform |
| **Architecture** | MVVM + Clean Architecture |
| **DI** | Koin (Multiplatform) |
| **BLE** | Platform-specific (Nordic on Android, CoreBluetooth on iOS) |
| **Database** | SQLDelight (Multiplatform) |
| **Async** | Coroutines + Flow |

---

## Project Structure

```
Project-Phoenix-MP/
├── shared/                    # Kotlin Multiplatform shared code
│   └── src/
│       ├── commonMain/        # Cross-platform business logic
│       ├── androidMain/       # Android BLE & platform implementations
│       └── iosMain/           # iOS BLE & platform implementations
├── androidApp/                # Android application
├── iosApp/                    # iOS application (Xcode project)
└── gradle/                    # Gradle wrapper and version catalog
```

---

## Contributing

This is a proprietary project. Contributions are not currently accepted.

Please contact the project maintainers if you have suggestions or would like to collaborate.

---

## Support & Community

- **Issues**: [GitHub Issues](https://github.com/DasBluEyedDevil/Project-Phoenix-MP/issues)
- **Discussions**: [GitHub Discussions](https://github.com/DasBluEyedDevil/Project-Phoenix-MP/discussions)
- **Support Development**: [Ko-fi](https://ko-fi.com/vitruvianredux)

---

## License

Proprietary License - All Rights Reserved. See [LICENSE](LICENSE) file for details.

---

## Acknowledgments

- Original [VitruvianProjectPhoenix](https://github.com/DasBluEyedDevil/VitruvianProjectPhoenix) Android app
- Web app developers for reverse-engineering the BLE protocol
- Vitruvian machine owners community for testing and feedback
- JetBrains for Kotlin Multiplatform
- All contributors and supporters

---

*Project Phoenix is a community rescue project to keep Vitruvian Trainer machines functional. It is not affiliated with or endorsed by Vitruvian.*

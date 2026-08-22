# Project Phoenix — Phoenix Trainer Control App

[![Latest Release](https://img.shields.io/github/v/release/9thLevelSoftware/Project-Phoenix-MP)](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/latest)
[![License](https://img.shields.io/badge/license-Proprietary-red.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS-green.svg)](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases)
[![DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/9thLevelSoftware/Project-Phoenix-MP)

**Keep your Phoenix Trainer alive.** This community-developed app restores full functionality to Phoenix V-Form and Trainer+ machines after the company's closure. Don't let your investment become e-waste.

---

## Support the Project

If Project Phoenix has helped keep your machine running, please consider supporting continued development:

**[☕ Support on Ko-fi](https://ko-fi.com/phoenixredux)**

Your support helps cover development, testing, and platform costs and keeps this community rescue project going.

---

## Installation

| Platform | Install | Guide |
|----------|---------|-------|
| **Android** | [Play Store](https://play.google.com/store/apps/details?id=com.devil.phoenixproject) / [Join Beta](ANDROID_INSTALL.md#join-the-beta) | [Android Guide](ANDROID_INSTALL.md) |
| **iOS** | [TestFlight](https://testflight.apple.com/join/TFw1m89R) | [iOS Guide](iOS_INSTALL.md) |

Current release: **[v0.9.6](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/tag/v0.9.6)** — profile-centered training controls, Exercise Insights, and workout reliability improvements. See the [release notes](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/tag/v0.9.6) for full details.

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
- **Automatic personal record detection** with velocity-estimated 1RM, percent-of-1RM scaling, badges, and backfill
- Complete workout history with expandable stats; exercise-history quick view *(merged after v0.9.6; release pending)*
- Exercise picker filter for previously completed movements *(merged after v0.9.6; release pending)*
- Muscle balance radar chart, workout consistency tracking, volume/intensity comparisons, and mode distribution

### Profiles & Personalized Training
- Dedicated **Profile** tab for switching, creating, and managing lifters
- Per-profile body weight, Equipment Rack, workout behavior, audio, LED, VBT, and safety preferences
- **Exercise Insights** for profile-specific PRs, estimated 1RM, recent sessions, volume trends, and full history

### Smarter Workout Setup
- Equipment Rack support for external load, counterweight, and display-only accessories
- Next-set recommendations, session body-weight prompts, and temporary Active Recovery / Heavy Deload routine starts
- TUT and TUT Beast in Just Lift; set-type labels during workouts
- Routine-set repetition *(merged after v0.9.6; release pending)*

### Privacy Focused
- All data stored locally on your device
- No account required
- Works completely offline
- Backup & restore your data anytime

---

## Supported Hardware

| Machine | Device Name | Max Resistance | Status |
|---------|-------------|----------------|--------|
| **Phoenix V-Form Trainer** (VIT-200) | `Vee_*` | 200 kg (440 lbs) | ✅ Fully Supported |
| **Phoenix Trainer+** | `VIT*` | 220 kg (485 lbs) | ✅ Fully Supported |

---

## What's New

For the latest features, fixes, and upgrade notes see the
[GitHub Releases page](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases).
Recent highlights:

- **[v0.9.6](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/tag/v0.9.6)** — Profile tab, profile-scoped preferences, Exercise Insights, clearer set types, Health Connect mapping, and workout-flow reliability fixes.
- **[v0.9.5](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/tag/v0.9.5)** — UI/BLE compatibility and training-cycle reliability.
- **[v0.9.4](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/tag/v0.9.4)** — Optional age-gated VBT verbal feedback.
- **[v0.9.3](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/tag/v0.9.3)** — Velocity-based 1RM, body-weight prompts, Equipment Rack improvements, and TUT/TUT Beast in Just Lift.
- **[v0.9.2](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases/tag/v0.9.2)** — Equipment Rack, health sync, next-set recommendations, and routine modifiers.
- Earlier releases are listed on the [Releases](https://github.com/9thLevelSoftware/Project-Phoenix-MP/releases) page.

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
open iosApp/PhoenixApp/PhoenixApp.xcodeproj
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

The source is released for personal use under the project's [Proprietary License](LICENSE),
but the maintainers welcome bug reports, feature requests, and pull requests via
[GitHub Issues](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues) and
[Pull Requests](https://github.com/9thLevelSoftware/Project-Phoenix-MP/pulls).
Please open an issue before starting large changes so the approach can be coordinated.

---

## Support & Community

- **Issues**: [GitHub Issues](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues)
- **Discussions**: [GitHub Discussions](https://github.com/9thLevelSoftware/Project-Phoenix-MP/discussions)
- **Project Portal**: [phoenix-portal.com](https://phoenix-portal.com)
- **Support Development**: [Ko-fi](https://ko-fi.com/phoenixredux)

---

## License

Proprietary License - All Rights Reserved. See [LICENSE](LICENSE) file for details.

---

## Acknowledgments

- Original [ProjectPhoenix](https://github.com/9thLevelSoftware/Project-Phoenix-MP) Android app
- Community protocol documentation for the machine BLE interface
- Phoenix machine owners community for testing and feedback
- JetBrains for Kotlin Multiplatform
- All contributors and supporters

---

*Project Phoenix is a community rescue project to keep Phoenix Trainer machines functional. It is not affiliated with or endorsed by Phoenix.*

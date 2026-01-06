# Project Phoenix 2.0 - Project Context

## Purpose
**Project Phoenix 2.0** is a community-driven "rescue project" designed to control Vitruvian Trainer workout machines (V-Form Trainer and Trainer+) via Bluetooth Low Energy (BLE). The primary goal is to ensure these machines remain fully functional independent of the official cloud infrastructure, preserving user hardware investment.

## Tech Stack
- **Language:** Kotlin 2.3.0
- **UI Framework:** Compose Multiplatform 1.10.0 (Android, iOS)
- **Architecture:** Clean Architecture + MVVM (Model-View-ViewModel)
- **Dependency Injection:** Koin 4.1.1
- **Database:** SQLDelight 2.2.1
- **Asynchronous Processing:** Kotlin Coroutines 1.10.2 + Flow
- **BLE:** Kable 0.40.2 (Kotlin Multiplatform BLE)
- **Navigation:** Jetbrains Navigation Compose 2.9.x
- **Logging:** Kermit 2.0.8
- **Image Loading:** Coil 3.3.0
- **Serialization:** Kotlinx Serialization 1.9.0
- **Date/Time:** Kotlinx Datetime 0.7.1

## Project Conventions

### Code Style
- **Kotlin:** Follows standard Kotlin coding conventions.
- **UI:** Exclusively use **Compose Multiplatform**. Avoid platform-specific UI frameworks (e.g., `android.view.*`, `java.awt.*`, `UIKit`) in shared code.
- **Resources:** Use Moko Resources or Compose Multiplatform Resources for strings and images.
- **Logging:** Use `Kermit` for all logging to ensure visibility across platforms. Avoid `android.util.Log` or `Timber`.
- **Date/Time:** Use `kotlinx-datetime` for all date and time operations. Avoid `java.util.Date` or `java.time.*`.

### Architecture Patterns
- **Clean Architecture:** Divided into `Domain`, `Data`, and `Presentation` layers.
    - **Domain:** Pure Kotlin, no framework dependencies. Contains Entities, Use Cases, and Repository Interfaces.
    - **Data:** Implements Repository Interfaces. Handles Data Sources (SQLDelight, BLE, Network).
    - **Presentation:** ViewModels (Shared) and UI (Compose).
- **MVVM:** ViewModels allow the UI to be reactive and platform-agnostic.

### Testing Strategy
- **Unit Tests:** `JUnit 4` and `kotlin-test` for logic verification.
- **Mocking:** `Mockk` for mocking dependencies.
- **Coroutines:** `kotlinx-coroutines-test` and `Turbine` for testing flows and suspending functions.

### Git Workflow
- Standard feature-branch workflow.
- Commit messages should be clear and descriptive.

## Domain Context
- **Devices:** Supports **V-Form Trainer** and **Trainer+**.
- **BLE Protocol:** strict adherence to the **Legacy "V1" Protocol** (Command 4F/50) to avoid cloud dependency.
    - **Service UUID:** `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Nordic UART)
    - **TX Characteristic:** `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
    - **RX Characteristic:** `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
- **Device Naming:** `Vee_*` (V-Form) or `VIT*` (Trainer+).
- **Safety:** Auto-stop logic and safety limits are critical.

## Important Constraints
- **Cloud Independence:** The app must function 100% offline. No dependency on Vitruvian's official servers for authentication or workout data.
- **Platform Specifics:** 
    - **iOS:** Uses `CoreBluetooth` via Kable.
    - **Android:** Uses `Nordic Android BLE Library` via Kable.
- **Safety Critical:** BLE communication reliability is paramount for user safety during heavy lifts.

## External Dependencies
- **No Cloud Backend:** This project replaces the backend with local processing and storage.
- **Firmware:** Interacts directly with the machine's existing firmware.
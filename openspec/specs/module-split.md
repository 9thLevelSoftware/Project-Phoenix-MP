# Canonical Spec: Module Split

## Purpose

Define stable module boundaries so shared business logic, platform adapters, and UI delivery concerns evolve independently.

## Target module responsibilities

### `shared/src/commonMain`

- Domain models and use-cases
- Cross-platform business rules
- Repository abstractions and pure interfaces
- Framework-agnostic orchestration logic

### `shared/src/androidMain`

- Android-specific BLE implementations
- Android persistence/OS integrations
- Platform adapter bindings to common interfaces

### `shared/src/iosMain`

- CoreBluetooth and iOS platform integrations
- iOS-specific adapter implementations

### `androidApp/`

- Android app shell, navigation, and platform UI integration
- Composition root and app-level dependency wiring

### `iosApp/`

- iOS app shell and integration with shared modules
- Composition root and app-level configuration

## Boundary rules

1. Common module must not import platform-specific APIs.
2. App shells must depend on abstractions, not concrete platform internals from the opposite platform.
3. New cross-cutting concerns must define interfaces in common and implementations in platform modules.
4. Shared contracts should be additive/versioned when possible to minimize breakage.

## Ownership model

- Shared contracts: architecture maintainers
- Platform adapters: corresponding platform maintainers
- App wiring: app maintainers per platform

## Acceptance criteria for split-related changes

- Dependency direction remains inward toward abstractions.
- Module responsibilities remain aligned with this spec.
- Required tests updated at affected boundaries.

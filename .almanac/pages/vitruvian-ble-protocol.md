---
title: Vitruvian BLE Protocol
summary: BLE control is centered on a Kable-based connection manager, parser utilities that normalize multiple firmware packet formats, and shared command semantics built around per-cable loads.
topics: [systems, ble, stack]
sources:
  - id: ble-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt
    note: Defines BLE lifecycle ownership, scan filtering, callbacks, and connection state transitions.
  - id: protocol-parser
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/ProtocolParser.kt
    note: Defines packet parsing rules for monitor, rep, and diagnostic payloads.
  - id: constants
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt
    note: Carries protocol packet sizes and mode identifiers used by the app.
  - id: ble-tests
    type: file
    path: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManagerTest.kt
    note: Documents the callback-routing contract that is testable without real hardware.
status: active
verified: 2026-06-25
---
`KableBleConnectionManager` owns the live `Peripheral` reference exclusively. The class comment says it was extracted from `KableBleRepository` so connection lifecycle code, notification subscriptions, readiness checks, and command sending all live in one place while repository-facing flows remain in the façade layer [@ble-manager]. [[workouts]] is the right cluster hub when the symptom might still be in routine flow, persistence, or platform behavior rather than in BLE itself.

Scan filtering is conservative and hardware-specific. The manager accepts advertisement names starting with `Vee_`, `VIT`, or `Vitruvian`, which matches the supported hardware naming patterns documented in the README and keeps the scan UI from filling with unrelated devices [@ble-manager].

The parser layer treats byte handling as a correctness boundary. `ProtocolParser.kt` calls out signed Kotlin bytes as a recurring hazard and masks every byte with `and 0xFF` before assembling integers, so changes to parser code need the same discipline to avoid sign-extension bugs [@protocol-parser].

Rep packets come in at least two firmware shapes. `parseRepPacket()` accepts legacy payloads anywhere from `6..23` effective bytes and modern payloads at `24+` bytes, because V-Form firmware can emit shorter rep packets that earlier Phoenix MP code rejected [@protocol-parser]. That compatibility branch is one of the repo's hardware-preservation contracts.

Monitor packets are treated as little-endian telemetry frames with two positions, two velocities, two loads, and optional status or extra bytes. The parser reconstructs positions in millimeters and loads in kilograms directly from raw packet data [@protocol-parser].

The same parser layer also owns machine diagnostics payload decoding. `parseDiagnosticPacket()` accepts zero snapshots, decodes four fault words plus optional temperatures, crash data, and warnings, and feeds that payload into the shared diagnostics workflow described in [[machine-diagnostics]] [@protocol-parser].

Command semantics in shared code still distinguish mode families. `ProtocolConstants` carries separate regular, echo, activation, and stop packet sizes plus mode IDs such as `0` for Old School and `10` for Echo [@constants]. These values are load-bearing for both BLE writes and workout orchestration in [[workout-engine]].

The common BLE tests intentionally cover only what is stable without real hardware. `KableBleConnectionManagerTest` verifies opcode routing, disconnect cleanup, and empty-packet handling, while the test header explicitly says connection, scanning, and auto-reconnect behavior still need manual BLE validation [@ble-tests].

Read [[project-phoenix]] before changing scan filters or packet compatibility rules because those branches preserve support for older Vitruvian hardware and firmware instead of optimizing for a narrower current-device contract. Read [[machine-diagnostics]] when the BLE symptom is specifically about fault decoding, diagnostic snapshots, or the troubleshooting export built on top of this parser. Read [[platform-hosts]] when Android and iOS disagree about BLE permissions, reconnects, or background workout continuity around the same shared protocol code.

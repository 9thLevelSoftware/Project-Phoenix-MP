---
title: Machine Diagnostics
summary: Machine diagnostics is a developer-tools workflow that reads the trainer's diagnostic BLE characteristic, decodes fault and crash payloads into a live shared UI state, and exports a redacted troubleshooting snapshot.
topics: [systems, workouts, ble, frontend]
sources:
  - id: settings-tab
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
    note: Shows that the diagnostics entry point lives under Developer Tools in Settings and is described as a redacted snapshot rather than a backup.
  - id: nav-graph
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt
    note: Shows that diagnostics is a dedicated shared route.
  - id: diagnostics-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DiagnosticsScreen.kt
    note: Defines the UI states, copy action, and section layout for faults, temperatures, crash data, and warnings.
  - id: diagnostics-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/DiagnosticsViewModel.kt
    note: Defines the live diagnostics state, export text, and redaction wording.
  - id: ble-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BleRepository.kt
    note: Defines diagnostics as a first-class BLE repository stream.
  - id: kable-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt
    note: Shows how parsed diagnostics are timestamped, published, and logged when fault words change.
  - id: protocol-parser
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/ProtocolParser.kt
    note: Defines the binary layout for the diagnostic characteristic, including optional temperatures, crash blocks, and warnings.
  - id: fault-decoder
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/DiagnosticFaultDecoder.kt
    note: Defines category-specific decoding for Vee, Other, Motor A, and Motor B fault words.
  - id: protocol-models
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/ProtocolModels.kt
    note: Defines the shared diagnostic packet and crash payload models.
  - id: schema-file
    type: file
    path: shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
    note: Defines the reserved DiagnosticsHistory table and diagnostic snapshot queries in the local schema.
  - id: diagnostics-tests
    type: file
    path: shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/DiagnosticsViewModelTest.kt
    note: Verifies decoded fault labels, connected-state projection, and the redacted export contract.
status: active
verified: 2026-06-25
---
Machine diagnostics is a shared troubleshooting workflow for a connected trainer, not a general app log viewer. The entry point lives under Developer Tools in Settings, the shared nav graph routes it to a dedicated diagnostics screen, and the settings copy describes the result as a redacted machine snapshot rather than a backup [@settings-tab] [@nav-graph].

The screen is driven by live BLE state. `BleRepository` exposes `diagnostics` as a `StateFlow<DiagnosticPacket?>`, `DiagnosticsViewModel` combines that stream with connection state, and `DiagnosticsScreen` renders one of three states: disconnected with no snapshot, connected but waiting for the characteristic to answer, or a populated packet with sections for uptime, faults, temperatures, crash data, and warnings [@ble-repo] [@diagnostics-vm] [@diagnostics-screen].

The binary contract is broader than just four fault words. `parseDiagnosticPacket()` accepts an empty payload as a valid zero snapshot, rejects non-empty payloads shorter than `18` bytes, then decodes uptime seconds, four unsigned `16-bit` fault words, six required temperatures, two optional extra temperatures, an optional `52`-byte crash block, and an optional `32-bit` warnings field [@protocol-parser] [@protocol-models]. [[vitruvian-ble-protocol]] is the neighboring page for the broader scan, rep, monitor, and command surface around this diagnostic characteristic.

Fault decoding is intentionally category-specific. `DiagnosticFaultDecoder` always projects the packet into four display slots named `Vee`, `Other`, `Motor A`, and `Motor B`, then maps bitmasks to labels such as `TI restarted`, `Over voltage`, `Encoder`, or `Motor overtemp` instead of exposing only raw integers [@fault-decoder]. The view-model tests pin that labeling contract and verify that the export text includes both the human label and the raw hex code [@diagnostics-tests].

The export contract is deliberately narrow. `buildDiagnosticsExportText()` prepends app version, export timestamp, `REDACTED_DIAGNOSTICS` classification, and a privacy line that excludes workout history, profiles, auth or session tokens, Supabase config, and keystore data before listing the current packet contents [@diagnostics-vm]. The copy button stays disabled until a packet exists, so the feature exports the latest machine snapshot rather than an empty template [@diagnostics-screen].

Live publication and logging happen below the UI. `KableBleRepository.publishDiagnostics()` stamps packets with `receivedAtMillis` when needed, publishes them to the shared diagnostics flow, and emits a diagnostic log entry only when the fault-word set changes so repeated identical snapshots do not spam the log stream [@kable-repo].

The current workflow is live-only even though the schema already reserves a history table. `VitruvianDatabase.sq` still defines `DiagnosticsHistory` plus recent and fault-only queries, but the present code path does not write packets into that table or read it back into `DiagnosticsViewModel`; the user-facing diagnostics screen is driven by the current BLE packet and the export text derived from it [@schema-file] [@kable-repo] [@diagnostics-vm].

Read [[workouts]] first when the bug source is still unclear, [[vitruvian-ble-protocol]] when the question is whether the packet itself is being parsed or delivered correctly, [[local-data-model]] when the task is about turning the reserved diagnostics schema into a real persistence feature, and [[platform-hosts]] when Android and iOS disagree about the conditions under which a connected machine reaches this screen.

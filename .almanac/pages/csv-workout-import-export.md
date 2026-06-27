---
title: CSV Workout Import Export
summary: Phoenix uses the Integrations screen for local CSV interchange, exporting local workouts in Strong format and importing Strong or Hevy files into profile-scoped external activities.
topics: [integrations, flows, data, workouts]
sources:
  - id: integrations-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/IntegrationsScreen.kt
    note: Defines the Strong or CSV card, local file picker or saver flow, and import preview dialog.
  - id: integrations-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt
    note: Defines export, preview-import, and confirm-import behavior plus profile and paid-user inputs.
  - id: csv-exporter
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/CsvExporter.kt
    note: Defines Strong-format export, per-cable-to-total weight conversion, and routine grouping.
  - id: csv-importer
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/CsvImporter.kt
    note: Defines Strong or Hevy format detection, workout grouping, deterministic external IDs, and import preview shape.
  - id: external-activity
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ExternalActivity.kt
    note: Defines imported activity provider keys, total-weight convention, profile scope, and sync eligibility flag.
  - id: external-activities-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExternalActivitiesScreen.kt
    note: Shows that imported CSV workouts land in the external-activities view beside provider-fed activities.
  - id: csv-exporter-test
    type: file
    path: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/CsvExporterTest.kt
    note: Pins Strong export weight conversion, routine grouping, and output-shape contracts.
  - id: csv-importer-test
    type: file
    path: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/CsvImporterTest.kt
    note: Pins Strong or Hevy detection, one-activity-per-workout grouping, deterministic IDs, and timezone fallback behavior.
status: active
verified: 2026-06-24
---
Phoenix has a third integration path that does not talk to a platform health store or the portal at all. The shared [[integrations]] screen includes a Strong or CSV card that exports local workout history to a file and imports third-party workout files back into Phoenix as profile-scoped `ExternalActivity` rows [@integrations-screen] [@integrations-vm] [@external-activity].

## Boundary

This flow is local file interchange, not provider connection state. The Integrations screen shows the CSV card without any connection toggle, `exportCsv()` reads local `WorkoutSession` rows for the active profile, and `confirmImport()` writes previewed `ExternalActivity` rows directly into the local external-activity repository [@integrations-screen] [@integrations-vm].

Imported CSV workouts still join the same external-activity surface as provider-fed data. `ExternalActivitiesScreen` tells users to connect Hevy, connect Liftosaur, or import a CSV from the Integrations screen, and it renders the resulting rows side by side with other `IntegrationProvider` values [@external-activities-screen] [@external-activity].

## Export contract

Export is one-way and Strong-shaped. `CsvExporter.generateStrongCsv()` always writes Strong headers, and the screen's CSV card lets the user choose `kg` or `lbs` before launching a local file save flow for `phoenix_workouts.csv` [@csv-exporter] [@integrations-screen].

The exporter translates Phoenix workout semantics into Strong's flatter file contract. It groups all sessions that share a `routineSessionId` under one workout name, numbers repeated exercises inside each workout group, and falls back to standalone exercise names when there is no routine session [@csv-exporter]. `CsvExporterTest` pins those grouping rules so routine exports stay one workout per routine session instead of one row-group per saved set [@csv-exporter-test].

Weight conversion is the main semantic trap in this path. Phoenix stores `WorkoutSession.weightPerCableKg` per cable, but `CsvExporter` multiplies by `2` before optional pound conversion so the CSV shows the total weight convention expected by Strong and by Phoenix's external-activity layer [@csv-exporter] [@csv-exporter-test].

## Import contract

Import accepts two third-party shapes. `CsvImporter.detectFormat()` recognizes Strong files from `Workout Name`-style headers and Hevy files from `title` or `exercise_title`-style headers, then parses the file into a preview instead of writing directly to the database [@csv-importer] [@csv-importer-test].

The preview is workout-level, not row-level. Both parsers collapse one-row-per-set CSVs into one `ExternalActivity` per workout key, compute workout count, date range, total duration, and row-level parse errors, and the screen shows that summary in an import-preview dialog before `confirmImport()` persists anything [@csv-importer] [@integrations-screen] [@integrations-vm].

The importers also normalize source differences into Phoenix's external-activity model. Strong rows group by workout name plus date, Hevy rows group by title plus start time, both generate deterministic external IDs, and the Hevy parser falls back to local datetime parsing when timezone-free exports omit an offset [@csv-importer] [@csv-importer-test].

## Relationship to profiles and sync

Imported CSV workouts are profile-scoped local data first. `previewCsvImport()` passes the active profile ID into `CsvImporter.parse()`, and `ExternalActivity` keeps that `profileId` alongside provider and timestamp fields [@integrations-vm] [@csv-importer] [@external-activity]. Read [[profiles]] when imported workouts seem to disappear after a profile switch.

The import path is still aware of premium policy, but only to mark later sync eligibility. `IntegrationsViewModel` derives `isPaidUser` from either the active profile's local subscription state or the current portal user's premium flag, then passes that combined result into `CsvImporter.parse()` [@integrations-vm]. `CsvImporter` uses it only to set `ExternalActivity.needsSync` for paid users; the file import itself succeeds locally regardless of entitlement, while any later remote movement belongs to [[external-provider-sync]] and [[sync]] rather than to this page [@csv-importer] [@external-activity].

Read [[health-platform-integration]] when the import or export question is really about Health Connect or HealthKit. Read [[external-provider-sync]] when the issue is provider connection, cursor sync, or disconnect cleanup rather than local files. Read [[local-data-model]] when imported activities look persisted incorrectly, and read [[premium-entitlements]] when questions about `needsSync` or paid-user behavior are the real issue.

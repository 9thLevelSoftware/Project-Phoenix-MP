# Utilities Part 1 Review

Scope: backup system and CSV import/export data integrity/error handling.

Files reviewed:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupDestination.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupJsonNavigator.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupJsonWriter.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupStreamSource.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/CsvExporter.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/CsvImporter.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/CsvParser.kt`

Summary:
- Findings: 12
- Severity breakdown: critical 0, high 3, medium 7, low 2

---

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupDestination.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.kt`

No findings in the common interface. Platform implementations still need to enforce persisted permissions/bookmarks and sanitize destination filenames, but that is outside this assigned file.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupJsonNavigator.kt`

### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: 180-193, 204-217
- Description: `hasNextInArray()` and `hasNextInObject()` treat any non-closing, non-comma character as the next element/key. That makes malformed JSON such as `[1 2]` or `{"a":1 "b":2}` advance as if the required comma existed. A corrupted backup can therefore be partially accepted or fail later with misleading per-entity errors rather than being rejected at the structural boundary.
- Suggested fix direction: Track whether the parser is reading the first element/key vs a subsequent one, and require a comma before every subsequent element/key. Return false only for the matching closing token; otherwise throw a parse error.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupJsonWriter.kt`

No findings in the common expect declaration.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.kt`

No findings in the common expect declaration.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt`

No findings.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupStreamSource.kt`

No findings in the common interface.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt`

### Finding 2
- Category: bug
- Severity: high
- Line numbers: 1218-1252, 1729-1733, 1926-1928
- Description: The streaming importer has no `routineGroups` case, so v4 `routineGroups` data is treated as an unknown field and skipped. The streaming exporter writes `routineGroups` at the end of the backup, after `routines`; during streaming import, routines are inserted earlier with `groupId` values that can reference groups that have not been imported. With foreign keys enabled this can abort a large restore; without FK enforcement it silently drops group organization.
- Suggested fix direction: Add explicit streaming handling for `routineGroups` and import groups before routines that reference them. If streaming must stay single-pass, defer routine rows with non-null `groupId` until groups are read, or change export order so `routineGroups` is emitted before `routines`.

### Finding 3
- Category: bug
- Severity: medium
- Line numbers: 1355-1380
- Description: The streaming personal-record importer drops `PersonalRecordBackup.cableCount` by passing `cable_count = null` to `queries.upsertPR`. The non-streaming importer preserves this field at lines 631-643, so large backups imported through the streaming path lose cable-count metadata while small backups do not.
- Suggested fix direction: Pass `pr.cableCount?.toLong()` in the streaming path to match `importFromJson()`.

### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: 1031-1043, 1783-1785
- Description: Streaming import applies `equipmentRackItems` to the settings-backed repository immediately while the rest of the file is still being parsed. If a later field fails and `importFromStream()` returns `Result.failure`, rack settings may already have been modified, leaving a partial restore despite the failed result. The non-streaming path intentionally imports equipment rack items only after the database transaction succeeds (lines 874-876).
- Suggested fix direction: Buffer rack items during streaming and call `importEquipmentRackItems()` only after the full stream has parsed and all database imports have succeeded, or explicitly report partial side effects in the result.

### Finding 5
- Category: failure-point
- Severity: medium
- Line numbers: 1138-1215
- Description: Metric batching counts only metrics that are actually inserted (`batchCount++` is inside `metric.sessionId in importedSessionIds`). If a large backup contains mostly metrics for skipped/pre-existing sessions, the second batching loop can consume the entire remaining `metricSamples` array in one transaction because `batchCount` never reaches `IMPORT_BATCH_SIZE`. This defeats the OOM/lock-avoidance goal of streaming import for exactly the large-file path.
- Suggested fix direction: Limit each transaction by rows processed/seen, not rows inserted, or maintain separate `rowsInBatch` and `insertedInBatch` counters.

### Finding 6
- Category: failure-point
- Severity: medium
- Line numbers: 976-980, 1025-1737
- Description: The streaming importer is order-dependent even though JSON object member order is not semantically significant. Child arrays such as `metricSamples`, `routineExercises`, `cycleDays`, `plannedSets`, and `completedSets` are imported only if their parent IDs have already been seen in earlier fields. A valid backup with fields reordered can silently skip children or log warnings while returning success.
- Suggested fix direction: Make streaming import robust to object order by doing multiple passes over a seekable source, buffering small parent/child ID sets and deferred child rows, or enforcing/exporting a documented field order and rejecting out-of-order backups instead of silently skipping data.

### Finding 7
- Category: error
- Severity: low
- Line numbers: 503-515, 807-820, 859-871, 1621-1644, 1706-1727
- Description: Several import counters treat successful execution of `INSERT OR IGNORE` queries as an import even when SQLite ignored the row. `tryImport()` returns `Unit` on both inserted and ignored rows, so duplicate routine groups, earned badges, and session notes can be counted as imported rather than skipped. This produces misleading restore summaries and hides duplicate/conflict conditions from the UI.
- Suggested fix direction: Check affected-row counts where available, query for existing IDs before insert, or use insert/upsert APIs that can distinguish inserted vs ignored rows before updating `Imported`/`Skipped` counters.

### Finding 8
- Category: failure-point
- Severity: low
- Line numbers: 2568-2576, 2635-2644
- Description: Auto-backup filenames concatenate raw `sessionId` and `routineSessionId` into filesystem paths. IDs are expected to be generated internally, but restored or externally-sourced IDs can contain path separators or other invalid filename characters. That can make backup creation fail or write outside the intended filename shape.
- Suggested fix direction: Sanitize IDs before using them in filenames (allow a conservative `[A-Za-z0-9._-]` set, replace everything else) while preserving the original IDs inside the JSON payload.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/CsvExporter.kt`

No findings in the common interface itself. The data-loss issues are in the shared parser/import contract below; platform exporter implementations should be updated consistently with those fixes.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/CsvImporter.kt`

### Finding 9
- Category: bug
- Severity: high
- Line numbers: 41-45
- Description: The documented workout-history CSV contract has no session ID or time-of-day column, yet duplicate handling relies on a parsed session ID or matching timestamp+exercise. `CsvParser` generates a fresh ID for every row and, without a time column, maps every same-date row to midnight. Multiple workouts for the same exercise on the same day are therefore indistinguishable to importers and can be skipped as duplicates.
- Suggested fix direction: Extend the CSV schema to include either a stable session ID or a date-time/timestamp column. Importers should use that value for duplicate detection and preserve enough precision to distinguish same-day workouts.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/CsvParser.kt`

### Finding 10
- Category: bug
- Severity: high
- Line numbers: 218-222
- Description: `parseWeight()` strips unit suffixes but never converts pounds to kilograms. CSV exporters format weights through the selected display unit, so a user exporting in pounds can produce values such as `176.4 lbs`; import will store `176.4` as kilograms instead of converting back to about `80.0 kg`. This corrupts imported workout weights and progressions.
- Suggested fix direction: Detect `lb`, `lbs`, or the active/exported weight unit and convert pound values back to kilograms before constructing `WorkoutSession`. Prefer including a machine-readable unit column or always exporting numeric storage values in kg.

### Finding 11
- Category: bug
- Severity: medium
- Line numbers: 140-154, 160-174
- Description: The parser treats the CSV `Duration (s)` column as a raw `Long` and writes it directly into `WorkoutSession.duration`, whose domain model stores milliseconds. If CSV producers follow the documented seconds header, imported durations will be 1000x too small; if platform exporters write milliseconds, the header is misleading for users and external CSV tools.
- Suggested fix direction: Define a single unit for CSV duration. Either convert seconds to milliseconds during import and milliseconds to seconds during export, or rename the column to `Duration (ms)` and document that value as milliseconds.

### Finding 12
- Category: failure-point
- Severity: medium
- Line numbers: 140-145, 160-162
- Description: When no `time` column is present, the parser maps every row for a date to midnight in the current system timezone. Combined with generated IDs, this loses time-of-day information and causes duplicate detection by timestamp+exercise to collapse distinct same-day workouts. The use of `TimeZone.currentSystemDefault()` also means the same CSV can import to different epoch timestamps on devices in different timezones.
- Suggested fix direction: Require/export a timestamp or local date-time column with explicit timezone semantics. If legacy date-only files must remain supported, avoid using date-only timestamps as a uniqueness key and warn that time precision is unavailable.

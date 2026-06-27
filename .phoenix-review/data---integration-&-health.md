# Data - Integration & Health Review

Reviewed task scope:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/ActivityBridge.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthBridge.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthPermissionScopes.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/IntegrationManager.kt`

Note: the first three assigned paths do not exist in the repository at the requested locations. Exact filename and symbol searches for `ActivityBridge`, `HealthBridge`, and `HealthPermissionScopes` returned no production source files. `HealthIntegration.kt` exists in the same package, but it is not one of the assigned file paths.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/ActivityBridge.kt`

### Finding 1
- Category: error
- Severity: medium
- Line numbers: N/A (file not found)
- Description: The assigned file is missing from `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/`. Exact filename and content searches did not find an `ActivityBridge` implementation anywhere under the repository. This means the activity bridge scope could not be reviewed and may indicate a stale task manifest, a deleted abstraction, or a missing source file.
- Suggested fix direction: Confirm whether this file was renamed or intentionally removed. If renamed, update the review/task manifest and any architecture documentation to point to the current source file. If the bridge is still required, restore or implement it and ensure it is covered by compilation/tests.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthBridge.kt`

### Finding 2
- Category: error
- Severity: medium
- Line numbers: N/A (file not found)
- Description: The assigned file is missing from `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/`. Exact filename and content searches did not find a `HealthBridge` implementation anywhere under the repository. The health bridge scope therefore could not be reviewed from the requested file.
- Suggested fix direction: Confirm whether this functionality moved into `HealthIntegration.kt` or platform `HealthIntegration` actual implementations. Update the task manifest if renamed, or restore the missing bridge file if callers/architecture still expect it.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthPermissionScopes.kt`

### Finding 3
- Category: error
- Severity: medium
- Line numbers: N/A (file not found)
- Description: The assigned file is missing from `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/`. Exact filename search found no production `HealthPermissionScopes.kt`; only `HealthPermissionScopesTest.kt` references permission-scope behavior indirectly through `HealthIntegration.hasBodyWeightReadPermission`. This makes the permission-scope implementation unreviewable at the requested path.
- Suggested fix direction: Confirm whether permission scopes were folded into platform-specific health integration files. If so, update the review manifest and tests naming to match the current implementation. If a shared permission-scope source file is intended, add it back and wire platform code through it.

## `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/IntegrationManager.kt`

### Finding 4
- Category: bug
- Severity: high
- Line numbers: 182-194, 226-232
- Description: Upgrade-required portal errors are treated as successful connections. The code only fails when `response.status == "error" && !response.requiresUpgrade`; when the portal returns `status = "error"` with `requiresUpgrade = true`, execution falls through to `persistResponse`, merges an entitlement state, and then unconditionally writes `ConnectionStatus.CONNECTED`. This can show a provider as connected even though the sync did not actually succeed and the user needs an upgrade.
- Suggested fix direction: Handle `requiresUpgrade` responses explicitly before persistence. Return a partial/entitlement result without marking the integration as connected, or add a dedicated blocked/upgrade-required status. Avoid running normal persistence for `status == "error"` responses.

### Finding 5
- Category: failure-point
- Severity: high
- Line numbers: 194, 240-286
- Description: Local persistence failures are not caught or reflected in integration status. `syncProviderInternal` handles API failures with `getOrElse`, but `persistResponse` can throw from any repository call after earlier repositories have already written data. Such an exception escapes the `Result<IntegrationSyncResult>` flow, can leave a partially persisted sync, and does not update `ConnectionStatus.ERROR` with a useful message.
- Suggested fix direction: Wrap `persistResponse` in `runCatching`/`try-catch`, update integration status to `ERROR` on failure, and return `Result.failure`. Where possible, move multi-repository writes into a shared transaction or make each entity group idempotent with a rollback/retry strategy so partial local syncs are not reported as successful.

### Finding 6
- Category: bug
- Severity: high
- Line numbers: 253-267
- Description: The multi-entity sync only applies remote deletions to activities. `deletedExternalIds` is processed by `activityRepository.markDeletedByExternalIds`, but there is no equivalent handling for deleted routines, routine folders, exercise templates, body measurements, programs, or program stats. For a multi-entity integration response, deleted non-activity records will remain locally visible as stale data.
- Suggested fix direction: Include entity type information in deletion payloads or add per-entity deletion fields, then route deletions to the appropriate repositories. If the portal currently only sends activity deletions, rename the field to make that contract explicit and avoid silently ignoring future entity deletions.

### Finding 7
- Category: failure-point
- Severity: medium
- Line numbers: 94-116
- Description: `disconnectProvider` deletes all local provider data and marks the provider disconnected even when the portal disconnect call fails. The failure is logged as non-fatal, but the app now reports a disconnected state while the remote portal/third-party authorization may still be active. This creates a user-visible privacy/security mismatch and can make later reconnection/debugging confusing.
- Suggested fix direction: Distinguish local cleanup from remote disconnect. Surface a warning/result state when portal revocation fails, retry the remote disconnect, or only mark full `DISCONNECTED` after the portal confirms revocation. If local-first disconnect is intentional, persist a warning that remote cleanup is pending.

### Finding 8
- Category: failure-point
- Severity: medium
- Line numbers: 321, 426, 504-513
- Description: Invalid required timestamps are silently replaced with the current time. `startedAt` for activities and `measuredAt` for body measurements call `parseEpochMillisOrNow`, so malformed portal data imports as if it occurred at sync time. This can corrupt ordering, analytics, deduplication behavior, and body-weight history without surfacing an entity-level warning.
- Suggested fix direction: Treat invalid required timestamps as an entity import error: skip the bad entity, add an `IntegrationSyncWarning`, and keep the provider sync partial. Only use `currentTimeMillis()` as a fallback for optional/generated timestamps where that behavior is explicitly acceptable.

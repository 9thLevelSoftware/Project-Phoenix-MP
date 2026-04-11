# Plan 27-01 Summary: Pull DTOs, API, Adapter, Merge

**Status:** Complete
**Date:** 2026-03-02

## What was done

1. **Pull DTOs added to PortalSyncDtos.kt** — PortalSyncPullResponse (syncTime: Long), PullRoutineDto with nested PullRoutineExerciseDto, PullBadgeDto, PullRpgAttributesDto, PullGamificationStatsDto, PullWorkoutSessionDto (for deserialization only). All camelCase, NO @SerialName annotations.

2. **pullPortalPayload() added to PortalApiClient** — POST to /functions/v1/mobile-sync-pull with Bearer token + apikey header. Request body: `{deviceId, lastSync}`.

3. **PortalPullAdapter created** — Converts portal pull DTOs to legacy merge DTOs: toRoutineSyncDto(), toBadgeSyncDto(), toGamificationStatsSyncDto(). Also includes portalModeToMobileMode() (SCREAMING_SNAKE → mobile format), parseEccentricLoad(), parseEchoLevel().

4. **mergePortalRoutines() added** — SyncRepository interface + SqlDelightSyncRepository implementation. Checks local updatedAt vs lastSync for PULL-03 local preference. Upserts routine metadata, deletes+re-inserts exercises with full 30-column insertRoutineExercise mapping (portal fields → mobile schema).

## Key adaptations from plan

- Plan assumed simplified insertRoutineExercise with portal column names. Actual mobile schema uses 30 different columns (exerciseName, setReps as comma-separated, mode as "OldSchool" not "OLD_SCHOOL", etc.). Implemented full mapping.
- upsertRoutine takes (id, name, description, createdAt, lastUsed, useCount), not updatedAt. Adapted.
- deleteRoutineExercises (not deleteRoutineExercisesByRoutineId). Adapted.

## Files modified
- `shared/.../data/sync/PortalSyncDtos.kt` — pull DTOs added
- `shared/.../data/sync/PortalApiClient.kt` — pullPortalPayload() added
- `shared/.../data/sync/PortalPullAdapter.kt` — NEW file
- `shared/.../data/repository/SyncRepository.kt` — mergePortalRoutines() interface
- `shared/.../data/repository/SqlDelightSyncRepository.kt` — mergePortalRoutines() implementation

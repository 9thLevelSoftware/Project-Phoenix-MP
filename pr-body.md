## Summary

Fixes #525 by changing routine auto-backups from per-set JSON exports to one consolidated routine JSON export.

- adds `DataBackupManager.exportRoutine(routineSessionId)` to aggregate all `WorkoutSession` rows, completed sets, and metrics that share a routine session id
- skips the per-set `exportSession` path for routine sets while preserving single-exercise / Just Lift auto-backup behavior
- fires routine auto-backup before `routineSessionId` is cleared across the known routine-completion paths (manual next from summary, autoplay/rest advance, skip-final-exercise, interrupted routine completion)
- broadens Android/iOS retention/stat filters so both `phoenix-workout-*.json` and `phoenix-routine-*.json` are counted/pruned
- updates Settings copy from “Auto-Backup Workouts” to “Auto-Backup Routines”
- adds regression coverage for multi-session routine export and missing routine-session failure

## Verification

- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:compileKotlinMetadata :shared:compileAndroidMain -Pskip.supabase.check=true --no-daemon` — PASS
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:compileKotlinMetadata :shared:compileAndroidHostTest :shared:testAndroidHostTest --tests '*DataBackupManagerRoutineNameTest*' -Pskip.supabase.check=true --no-daemon` — FAILS in existing commonTest compilation before the targeted test can run; errors are unrelated pre-existing unresolved references in tests such as `PortalAuthRepositoryOAuthCallbackTest`, `RepMetricRepositoryTest`, and `ConflictResolutionTest`.
- `git diff --check` — PASS

## Notes

The first local Gradle attempt without environment overrides failed because this shell had no Java runtime selected and the Android SDK path was not set. Successful compile uses the installed Homebrew JDK 17 and Android SDK path.

User retains final merge approval; this automation will not merge.

# Profile Preferences Data Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every person-specific training preference profile-scoped, migration-safe, locally authoritative, and ready for the separate sync and Profile UI plans.

**Architecture:** SQLDelight schema 43 stores a one-to-one preference aggregate with five independently versioned sections, while profile-prefixed multiplatform Settings stores safety and consent values that must never sync or enter backups. `UserProfileRepository` remains the public active-profile facade and delegates typed persistence to focused preference and safety stores; an awaited startup gate copies the legacy global snapshot before any profile-sensitive consumer can run. Existing managers keep a compatibility `UserPreferences` view while their setters and runtime reads are moved to the active profile.

**Tech Stack:** Kotlin Multiplatform, Kotlin coroutines and `StateFlow`, kotlinx.serialization JSON, SQLDelight/SQLite migrations, multiplatform-settings, Koin, Compose Multiplatform, kotlin.test, Android host tests.

## Global Constraints

- This plan must land before `2026-07-11-profile-preferences-sync-backend.md` and `2026-07-11-profile-tab-ui-navigation.md`.
- Schema version ends at **43** everywhere: canonical schema, `42.sqm`, Gradle SQLDelight version, fallback statements, reconciliation manifest, generated schema, and parity tests.
- Existing profiles receive one normalized snapshot of legacy values; profiles created later receive product defaults and `legacyMigrationVersion = 1`.
- `vbtEnabled` defaults to `true` for both migrated and new profiles and gates live VBT evaluation/feedback only.
- `discoModeUnlocked` is profile-scoped; transient `discoModeActive` remains BLE runtime state and is never persisted, synchronized, or backed up.
- Each local section edit changes only that section's timestamp, generation, and dirty flag; it does not change the server revision.
- Raw malformed JSON remains stored and diagnostically invalid until an explicit user edit/reset; typed fallback values must never overwrite it automatically.
- `safeWord`, calibration, and adult consent/prompt state use profile-prefixed local keys and never appear in syncable models or backup JSON.
- Legacy global keys remain readable for one release and are never written by a profile-scoped setter.
- Profile deletion remains forbidden for `default`, merges existing profile-owned data deterministically, and journals cross-store cleanup before SQL commit.
- Profile create/activate and switch operations journal the prior active ID (plus a newly created ID when applicable) in the same SQL transaction as activation; normal success clears the journal, while failure/startup reconciliation restores one database-consistent Ready context and removes any failed-create rows.
- `PendingProfileContextRecovery` and `PendingProfileLocalCleanup` are device-local operational journals; neither is synchronized nor included in backup v5.
- Backup schema version 5 excludes local timestamps, generations, dirty flags, server revisions, voice phrase, calibration, and consent.
- Commands assume `$env:JAVA_HOME='C:\Users\dasbl\AppData\Local\Programs\Android Studio\jbr'`; in PowerShell the Gradle property must be quoted as `'-Pskip.supabase.check=true'`.
- Do not apply or deploy backend changes in this plan.

---

## File Structure

### New files

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ProfilePreferences.kt` — typed core, rack, workout, LED, VBT, safety, metadata, validity, and active-context models.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesCodec.kt` — the only raw JSON encode/decode boundary for profile preference documents.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesValidator.kt` — deterministic field and nested-document validation.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfileWorkoutDefaultsMapper.kt` — lossless legacy quick-start/default document mappings shared by migration and runtime consumers.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/LegacyProfilePreferencesReader.kt` — one-shot legacy snapshot read and field-by-field normalization.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfileLocalSafetyStore.kt` — profile-prefixed Settings persistence and cleanup.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProfilePreferencesRepository.kt` — focused SQLDelight preference store used by the public profile facade.
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/42.sqm` — schema 42 to 43 migration and default-row seeding.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesCodecTest.kt` — document defaults, round trips, validation, and invalid-section isolation.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/LegacyProfilePreferencesReaderTest.kt` — corrupt legacy normalization coverage.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightProfilePreferencesRepositoryTest.kt` — section updates, generations, invalid raw retention, and profile isolation.
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/ProfilePreferencesMigrationTest.kt` — required migration, retries, reconciliation, and boot-gate behavior.
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/VbtEnabledRuntimeTest.kt` — live VBT gating without losing history or subordinate configuration.

### Existing files modified by this plan

- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- `shared/build.gradle.kts`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaParityTest.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaManifestTest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightUserProfileRepositoryTest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/MigrationManagerTest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt`
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/AndroidAppHost.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManagerTest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepositoryTest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManager.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManagerTest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngineIntegrationTest.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinatorEventTest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt`
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/BackupSerializationTest.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/BackupJsonNavigatorTest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt`
- platform backup bindings and `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt` located by `rg -n "DataBackupManager|verifyAll" shared/src` before editing.

### Stable interfaces produced for later plans

```kotlin
interface ProfilePreferencesRepository {
    fun observe(profileId: String): Flow<UserProfilePreferences>
    suspend fun get(profileId: String): UserProfilePreferences
    suspend fun seedMissingProfiles()
    suspend fun insertDefaults(profileId: String)
    suspend fun updateCore(profileId: String, value: CoreProfilePreferences, now: Long)
    suspend fun updateRack(profileId: String, value: RackPreferences, now: Long)
    suspend fun updateWorkout(profileId: String, value: WorkoutPreferences, now: Long)
    suspend fun updateLed(profileId: String, value: LedPreferences, now: Long)
    suspend fun updateVbt(profileId: String, value: VbtPreferences, now: Long)
    suspend fun resetInvalidSection(profileId: String, section: ProfilePreferenceSectionName, now: Long)
    suspend fun delete(profileId: String)
}

interface ProfileLocalSafetyStore {
    fun read(profileId: String): ProfileLocalSafetyPreferences
    fun write(profileId: String, value: ProfileLocalSafetyPreferences)
    suspend fun copyLegacyToProfiles(profileIds: List<String>, value: ProfileLocalSafetyPreferences)
    fun delete(profileId: String)
}

sealed interface ActiveProfileContext {
    data class Switching(val targetProfileId: String?) : ActiveProfileContext
    data class Ready(
        val profile: UserProfile,
        val preferences: UserProfilePreferences,
        val localSafety: ProfileLocalSafetyPreferences,
    ) : ActiveProfileContext
}

class ProfileContextUnavailableException : IllegalStateException("Active profile context is switching")

class ProfileContextRecoveryException(cause: Throwable) :
    IllegalStateException("Could not reconcile the active profile context", cause)

class StaleProfileContextException(expected: String, actual: String) :
    IllegalStateException("Profile changed from $expected to $actual before the update completed")
```

### Task 1: Define the typed preference contract and strict codec

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ProfilePreferences.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesCodec.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesValidator.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfileWorkoutDefaultsMapper.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesCodecTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ScalingBasis.kt`

**Interfaces:**
- Consumes: existing `RackItem`, `WeightUnit`, `RepCountTiming`, `ScalingBasis`, and `VulgarTier` domain types.
- Produces: `CoreProfilePreferences`, `RackPreferences`, `WorkoutPreferences`, `LedPreferences`, `VbtPreferences`, `ProfileLocalSafetyPreferences`, `UserProfilePreferences`, `ProfilePreferenceSection<T>`, and `ProfilePreferencesCodec`.

- [ ] **Step 1: Write failing contract and invalid-section tests**

```kotlin
class ProfilePreferencesCodecTest {
    @Test
    fun defaultsMatchVersionOneContract() {
        assertEquals(WeightUnit.LB, CoreProfilePreferences().weightUnit)
        assertEquals(-1f, CoreProfilePreferences().weightIncrement)
        assertEquals(10, WorkoutPreferences().summaryCountdownSeconds)
        assertEquals(5, WorkoutPreferences().autoStartCountdownSeconds)
        assertTrue(VbtPreferences().enabled)
        assertEquals(20, VbtPreferences().velocityLossThresholdPercent)
        assertEquals(1, RackPreferences().version)
    }

    @Test
    fun malformedWorkoutDoesNotInvalidateRack() {
        val rack = ProfilePreferencesCodec.decodeRack(
            """{"version":1,"items":[]}""",
        )
        val workout = ProfilePreferencesCodec.decodeWorkout("{not-json")

        assertEquals(ProfilePreferenceValidity.Valid, rack.validity)
        assertIs<ProfilePreferenceValidity.Invalid>(workout.validity)
        assertEquals(WorkoutPreferences(), workout.value)
        assertEquals("{not-json", workout.raw)
    }

}
```

- [ ] **Step 2: Run the focused test and confirm the contract is absent**

Run:

```powershell
$env:JAVA_HOME='C:\Users\dasbl\AppData\Local\Programs\Android Studio\jbr'
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfilePreferencesCodecTest*" --console=plain
```

Expected: FAIL during test compilation because `CoreProfilePreferences` and `ProfilePreferencesCodec` do not exist.

- [ ] **Step 3: Add the complete typed model surface**

```kotlin
@Serializable
data class CoreProfilePreferences(
    val bodyWeightKg: Float = 0f,
    val weightUnit: WeightUnit = WeightUnit.LB,
    val weightIncrement: Float = -1f,
)

@Serializable
data class RackPreferences(
    val version: Int = 1,
    val items: List<RackItem> = emptyList(),
)

@Serializable
data class JustLiftDefaultsDocument(
    val workoutModeId: Int = 0,
    val weightPerCableKg: Float = 20f,
    val weightChangePerRep: Float = 0f,
    val eccentricLoadPercentage: Int = 100,
    val echoLevelValue: Int = 1,
    val stallDetectionEnabled: Boolean = true,
    val repCountTimingName: String = "TOP",
    val restSeconds: Int = 60,
)

@Serializable
data class SingleExerciseDefaultsDocument(
    val exerciseId: String,
    val setReps: List<Int?>,
    val weightPerCableKg: Float,
    val setWeightsPerCableKg: List<Float>,
    val progressionKg: Float,
    val setRestSeconds: List<Int>,
    val workoutModeId: Int,
    val eccentricLoadPercentage: Int,
    val echoLevelValue: Int,
    val duration: Int,
    val isAMRAP: Boolean,
    val perSetRestTime: Boolean,
    val defaultRackItemIds: List<String> = emptyList(),
)

@Serializable
data class WorkoutPreferences(
    val version: Int = 1,
    val stopAtTop: Boolean = false,
    val beepsEnabled: Boolean = true,
    val stallDetectionEnabled: Boolean = true,
    val audioRepCountEnabled: Boolean = false,
    val repCountTiming: RepCountTiming = RepCountTiming.TOP,
    val summaryCountdownSeconds: Int = 10,
    val autoStartCountdownSeconds: Int = 5,
    val gamificationEnabled: Boolean = true,
    val autoStartRoutine: Boolean = false,
    val countdownBeepsEnabled: Boolean = true,
    val repSoundEnabled: Boolean = true,
    val motionStartEnabled: Boolean = false,
    val weightSuggestionsEnabled: Boolean = true,
    val defaultRoutineExerciseUsePercentOfPR: Boolean = false,
    val defaultRoutineExerciseWeightPercentOfPR: Int = 80,
    val voiceStopEnabled: Boolean = false,
    val justLiftDefaults: JustLiftDefaultsDocument = JustLiftDefaultsDocument(),
    val singleExerciseDefaults: Map<String, SingleExerciseDefaultsDocument> = emptyMap(),
)

@Serializable
data class LedPreferences(
    val version: Int = 1,
    val colorScheme: Int = 0,
    val discoModeUnlocked: Boolean = false,
)

@Serializable
data class VbtPreferences(
    val version: Int = 1,
    val enabled: Boolean = true,
    val velocityLossThresholdPercent: Int = 20,
    val autoEndOnVelocityLoss: Boolean = false,
    val defaultScalingBasis: ScalingBasis = ScalingBasis.MAX_WEIGHT_PR,
    val verbalEncouragementEnabled: Boolean = false,
    val vulgarModeEnabled: Boolean = false,
    val vulgarTier: VulgarTier = VulgarTier.STRONG,
    val dominatrixModeUnlocked: Boolean = false,
    val dominatrixModeActive: Boolean = false,
)

data class ProfileLocalSafetyPreferences(
    val safeWord: String? = null,
    val safeWordCalibrated: Boolean = false,
    val adultsOnlyConfirmed: Boolean = false,
    val adultsOnlyPrompted: Boolean = false,
)

enum class ProfilePreferenceSectionName { CORE, RACK, WORKOUT, LED, VBT }

sealed interface ProfilePreferenceValidity {
    data object Valid : ProfilePreferenceValidity
    data class Invalid(val reason: String) : ProfilePreferenceValidity
}

data class ProfileSectionMetadata(
    val updatedAt: Long,
    val localGeneration: Long,
    val serverRevision: Long,
    val dirty: Boolean,
)

data class ProfilePreferenceSection<T>(
    val value: T,
    val raw: String? = null,
    val validity: ProfilePreferenceValidity,
    val metadata: ProfileSectionMetadata,
)

data class UserProfilePreferences(
    val profileId: String,
    val schemaVersion: Int,
    val legacyMigrationVersion: Int,
    val core: ProfilePreferenceSection<CoreProfilePreferences>,
    val rack: ProfilePreferenceSection<RackPreferences>,
    val workout: ProfilePreferenceSection<WorkoutPreferences>,
    val led: ProfilePreferenceSection<LedPreferences>,
    val vbt: ProfilePreferenceSection<VbtPreferences>,
)
```

Also add `val vbtEnabled: Boolean = true` to the compatibility `UserPreferences` model. Keep `enableVideoPlayback`, backup, language, BLE compatibility, and `velocityOneRepMaxBackfillDone` there because they remain global.

Annotate `WeightUnit`, `RepCountTiming`, `VulgarTier`, and `ScalingBasis` with `@Serializable`; their existing enum names are the persisted uppercase values. Do not annotate unrelated domain types.

Create the shared mappings needed by the startup reader before any consumer routing task runs:

```kotlin
internal fun SingleExerciseDefaults.toDocument() = SingleExerciseDefaultsDocument(
    exerciseId = exerciseId,
    setReps = setReps,
    weightPerCableKg = weightPerCableKg,
    setWeightsPerCableKg = setWeightsPerCableKg,
    progressionKg = progressionKg,
    setRestSeconds = setRestSeconds,
    workoutModeId = workoutModeId,
    eccentricLoadPercentage = eccentricLoadPercentage,
    echoLevelValue = echoLevelValue,
    duration = duration,
    isAMRAP = isAMRAP,
    perSetRestTime = perSetRestTime,
    defaultRackItemIds = defaultRackItemIds,
)

internal fun SingleExerciseDefaultsDocument.toLegacySingleExerciseDefaults() = SingleExerciseDefaults(
    exerciseId = exerciseId,
    setReps = setReps,
    weightPerCableKg = weightPerCableKg,
    setWeightsPerCableKg = setWeightsPerCableKg,
    progressionKg = progressionKg,
    setRestSeconds = setRestSeconds,
    workoutModeId = workoutModeId,
    eccentricLoadPercentage = eccentricLoadPercentage,
    echoLevelValue = echoLevelValue,
    duration = duration,
    isAMRAP = isAMRAP,
    perSetRestTime = perSetRestTime,
    defaultRackItemIds = defaultRackItemIds,
)

internal fun com.devil.phoenixproject.data.preferences.JustLiftDefaults.toDocument() =
    JustLiftDefaultsDocument(
        workoutModeId = workoutModeId,
        weightPerCableKg = weightPerCableKg,
        weightChangePerRep = weightChangePerRep,
        eccentricLoadPercentage = eccentricLoadPercentage,
        echoLevelValue = echoLevelValue,
        stallDetectionEnabled = stallDetectionEnabled,
        repCountTimingName = repCountTimingName,
        restSeconds = restSeconds,
    )
```

- [ ] **Step 4: Implement exact validation and non-destructive decoding**

```kotlin
object ProfilePreferencesValidator {
    fun core(value: CoreProfilePreferences): List<String> = buildList {
        if (!value.bodyWeightKg.isFinite() || (value.bodyWeightKg != 0f && value.bodyWeightKg !in 20f..300f)) add("bodyWeightKg")
        if (!value.weightIncrement.isFinite() || (value.weightIncrement != -1f && value.weightIncrement <= 0f)) add("weightIncrement")
    }

    fun rack(value: RackPreferences): List<String> = buildList {
        if (value.version != 1) add("version")
        if (value.items.map { it.id }.distinct().size != value.items.size) add("duplicateRackItemId")
        value.items.forEach { item ->
            if (item.id.isBlank()) add("rackItem.id")
            if (item.name.isBlank()) add("rackItem.name")
            if (!item.weightKg.isFinite() || item.weightKg < 0f) add("rackItem.weightKg")
        }
    }

    fun workout(value: WorkoutPreferences): List<String> = buildList {
        if (value.version != 1) add("version")
        if (value.summaryCountdownSeconds !in setOf(-1, 0, 5, 10, 15, 20, 25, 30)) add("summaryCountdownSeconds")
        if (value.autoStartCountdownSeconds !in 2..10) add("autoStartCountdownSeconds")
        if (value.defaultRoutineExerciseWeightPercentOfPR !in 50..120) add("defaultRoutineExerciseWeightPercentOfPR")
        if (value.justLiftDefaults.restSeconds != 0 && value.justLiftDefaults.restSeconds !in 5..300) add("justLiftDefaults.restSeconds")
        if (!value.justLiftDefaults.weightPerCableKg.isFinite() || value.justLiftDefaults.weightPerCableKg < 0f) add("justLiftDefaults.weightPerCableKg")
        if (!value.justLiftDefaults.weightChangePerRep.isFinite()) add("justLiftDefaults.weightChangePerRep")
        if (value.justLiftDefaults.workoutModeId !in setOf(0, 2, 3, 4, 6, 10)) add("justLiftDefaults.workoutModeId")
        if (value.justLiftDefaults.eccentricLoadPercentage !in 0..150) add("justLiftDefaults.eccentricLoadPercentage")
        if (value.justLiftDefaults.echoLevelValue !in 0..3) add("justLiftDefaults.echoLevelValue")
        if (value.justLiftDefaults.repCountTimingName !in RepCountTiming.entries.map { it.name }) add("justLiftDefaults.repCountTimingName")
        value.singleExerciseDefaults.forEach { (key, defaults) ->
            if (key != defaults.exerciseId || key.isBlank()) add("singleExerciseDefaults.exerciseId")
            if (!defaults.weightPerCableKg.isFinite() || defaults.weightPerCableKg < 0f) add("singleExerciseDefaults.weightPerCableKg")
            if (!defaults.progressionKg.isFinite()) add("singleExerciseDefaults.progressionKg")
            if (defaults.setWeightsPerCableKg.any { !it.isFinite() || it < 0f }) add("singleExerciseDefaults.setWeightsPerCableKg")
            if (defaults.setRestSeconds.any { it != 0 && it !in 5..300 }) add("singleExerciseDefaults.setRestSeconds")
            if (defaults.setReps.any { it != null && it < 0 }) add("singleExerciseDefaults.setReps")
            if (defaults.workoutModeId !in setOf(0, 2, 3, 4, 6, 10)) add("singleExerciseDefaults.workoutModeId")
            if (defaults.eccentricLoadPercentage !in 0..150) add("singleExerciseDefaults.eccentricLoadPercentage")
            if (defaults.echoLevelValue !in 0..3) add("singleExerciseDefaults.echoLevelValue")
            if (defaults.duration < 0) add("singleExerciseDefaults.duration")
            if (defaults.defaultRackItemIds.any { it.isBlank() } || defaults.defaultRackItemIds.distinct().size != defaults.defaultRackItemIds.size) add("singleExerciseDefaults.defaultRackItemIds")
        }
    }

    fun led(value: LedPreferences): List<String> = buildList {
        if (value.version != 1) add("version")
        if (value.colorScheme < 0) add("colorScheme")
    }

    fun vbt(value: VbtPreferences): List<String> = buildList {
        if (value.version != 1) add("version")
        if (value.velocityLossThresholdPercent !in 10..50) add("velocityLossThresholdPercent")
    }
}

object ProfilePreferencesCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Serializable
    private data class LedPreferencesDocument(
        val version: Int = 1,
        val discoModeUnlocked: Boolean = false,
    )

    @Serializable
    private data class VbtPreferencesDocument(
        val version: Int = 1,
        val velocityLossThresholdPercent: Int = 20,
        val autoEndOnVelocityLoss: Boolean = false,
        val defaultScalingBasis: ScalingBasis = ScalingBasis.MAX_WEIGHT_PR,
        val verbalEncouragementEnabled: Boolean = false,
        val vulgarModeEnabled: Boolean = false,
        val vulgarTier: VulgarTier = VulgarTier.STRONG,
        val dominatrixModeUnlocked: Boolean = false,
        val dominatrixModeActive: Boolean = false,
    )

    fun encodeRack(value: RackPreferences): String = json.encodeToString(value)
    fun encodeWorkout(value: WorkoutPreferences): String = json.encodeToString(value)
    fun encodeLed(value: LedPreferences): String = json.encodeToString(
        LedPreferencesDocument(value.version, value.discoModeUnlocked),
    )
    fun encodeVbt(value: VbtPreferences): String = json.encodeToString(
        VbtPreferencesDocument(
            value.version,
            value.velocityLossThresholdPercent,
            value.autoEndOnVelocityLoss,
            value.defaultScalingBasis,
            value.verbalEncouragementEnabled,
            value.vulgarModeEnabled,
            value.vulgarTier,
            value.dominatrixModeUnlocked,
            value.dominatrixModeActive,
        ),
    )

    fun decodeRack(raw: String) = decode(raw, RackPreferences(), ProfilePreferencesValidator::rack)
    fun decodeWorkout(raw: String) = decode(raw, WorkoutPreferences(), ProfilePreferencesValidator::workout)
    fun decodeLed(raw: String, colorScheme: Int): DecodedProfileDocument<LedPreferences> =
        decode(raw, LedPreferencesDocument()) { value -> if (value.version == 1) emptyList() else listOf("version") }
            .let { decoded ->
                decoded.mapValue { value -> LedPreferences(value.version, colorScheme, value.discoModeUnlocked) }
            }
    fun decodeVbt(raw: String, enabled: Boolean): DecodedProfileDocument<VbtPreferences> =
        decode(raw, VbtPreferencesDocument()) { value ->
            ProfilePreferencesValidator.vbt(
                VbtPreferences(
                    version = value.version,
                    enabled = enabled,
                    velocityLossThresholdPercent = value.velocityLossThresholdPercent,
                    autoEndOnVelocityLoss = value.autoEndOnVelocityLoss,
                    defaultScalingBasis = value.defaultScalingBasis,
                    verbalEncouragementEnabled = value.verbalEncouragementEnabled,
                    vulgarModeEnabled = value.vulgarModeEnabled,
                    vulgarTier = value.vulgarTier,
                    dominatrixModeUnlocked = value.dominatrixModeUnlocked,
                    dominatrixModeActive = value.dominatrixModeActive,
                ),
            )
        }.let { decoded ->
            decoded.mapValue { value ->
                VbtPreferences(
                    value.version, enabled, value.velocityLossThresholdPercent,
                    value.autoEndOnVelocityLoss, value.defaultScalingBasis,
                    value.verbalEncouragementEnabled, value.vulgarModeEnabled,
                    value.vulgarTier, value.dominatrixModeUnlocked, value.dominatrixModeActive,
                )
            }
        }

    private inline fun <reified T> decode(
        raw: String,
        fallback: T,
        validate: (T) -> List<String>,
    ): DecodedProfileDocument<T> = runCatching { json.decodeFromString<T>(raw) }
        .fold(
            onSuccess = { value ->
                val errors = validate(value)
                DecodedProfileDocument(
                    value = if (errors.isEmpty()) value else fallback,
                    raw = raw,
                    validity = if (errors.isEmpty()) ProfilePreferenceValidity.Valid else ProfilePreferenceValidity.Invalid(errors.joinToString(",")),
                )
            },
            onFailure = { error ->
                DecodedProfileDocument(fallback, raw, ProfilePreferenceValidity.Invalid(error::class.simpleName ?: "decode"))
            },
        )
}

data class DecodedProfileDocument<T>(
    val value: T,
    val raw: String,
    val validity: ProfilePreferenceValidity,
) {
    fun <R> mapValue(transform: (T) -> R) = DecodedProfileDocument(transform(value), raw, validity)
}
```

The private document DTOs deliberately omit the typed row columns, while `encodeDefaults = true` guarantees every local JSON document emits `version`. Assert exact LED keys `version,discoModeUnlocked` and exact VBT keys excluding `enabled`.

- [ ] **Step 5: Add boundary-table tests and make the codec pass**

Add parameterized cases for body weight `0`, `20`, `300`, `19.99`, `300.01`, non-finite weights, timer bounds, percentage bounds, duplicate rack IDs, unsupported versions, unknown fields, and missing optional fields. Assert that exact encoded LED keys are `version,discoModeUnlocked` and exact encoded VBT keys exclude `enabled`.

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfilePreferencesCodecTest*" --tests "*UserPreferencesTest*" --console=plain
```

Expected: PASS with no failing tests.

- [ ] **Step 6: Commit the typed contract**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ProfilePreferences.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ScalingBasis.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesCodec.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesValidator.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfileWorkoutDefaultsMapper.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesCodecTest.kt
git commit -m "feat: define profile preference documents"
```

### Task 2: Upgrade SQLDelight to schema 43

**Files:**
- Create: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/42.sqm`
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- Modify: `shared/build.gradle.kts`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaParityTest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaManifestTest.kt`

**Interfaces:**
- Consumes: schema 42 and `UserProfile(id)`.
- Produces: schema 43, generated preference/cleanup query APIs, and reconciliation support.

- [ ] **Step 1: Write failing schema-version and migration-parity tests**

```kotlin
private const val EXPECTED_SCHEMA_VERSION = 43L

@Test
fun migration42To43AddsProfilePreferenceTablesAndSeedsProfiles() {
    createSchemaAtVersion(42)
    driver.execute(null, "INSERT INTO UserProfile(id,name,colorIndex,createdAt,isActive) VALUES('a','A',0,1,1)", 0)
    migrateToLatest()

    assertEquals(1L, scalarLong("SELECT count(*) FROM UserProfilePreferences WHERE profile_id='a'"))
    assertEquals(1L, scalarLong("SELECT vbt_enabled FROM UserProfilePreferences WHERE profile_id='a'"))
    assertEquals(0L, scalarLong("SELECT legacy_migration_version FROM UserProfilePreferences WHERE profile_id='a'"))
    assertTableExists("PendingProfileLocalCleanup")
    assertTableExists("PendingProfileContextRecovery")
}
```

Add a manifest test that drops `UserProfilePreferences`, runs reconciliation, and asserts all three new tables and all columns return.

- [ ] **Step 2: Run parity tests and verify the expected red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SchemaParityTest*" --tests "*SchemaManifestTest*" --console=plain
```

Expected: FAIL because schema 43 and migration `42.sqm` are absent.

- [ ] **Step 3: Add the canonical tables and migration with identical DDL**

Put this DDL in the canonical `.sq` file, `42.sqm`, and the `MigrationStatements` 42 fallback branch. Keep the canonical schema seed-free. Both executable migration paths—`42.sqm` and the resilient fallback branch—must append the same final seed statement so existing profiles are initialized regardless of which migration mechanism runs.

```sql
CREATE TABLE UserProfilePreferences (
    profile_id TEXT PRIMARY KEY NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    legacy_migration_version INTEGER NOT NULL DEFAULT 0,
    body_weight_kg REAL NOT NULL DEFAULT 0 CHECK(body_weight_kg = 0 OR body_weight_kg BETWEEN 20 AND 300),
    weight_unit TEXT NOT NULL DEFAULT 'LB' CHECK(weight_unit IN ('KG', 'LB')),
    weight_increment REAL NOT NULL DEFAULT -1 CHECK(weight_increment = -1 OR weight_increment > 0),
    core_updated_at INTEGER NOT NULL DEFAULT 0,
    core_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(core_local_generation >= 0),
    core_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(core_server_revision >= 0),
    core_dirty INTEGER NOT NULL DEFAULT 1 CHECK(core_dirty IN (0, 1)),
    equipment_rack_json TEXT NOT NULL DEFAULT '{"version":1,"items":[]}',
    rack_updated_at INTEGER NOT NULL DEFAULT 0,
    rack_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(rack_local_generation >= 0),
    rack_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(rack_server_revision >= 0),
    rack_dirty INTEGER NOT NULL DEFAULT 1 CHECK(rack_dirty IN (0, 1)),
    workout_preferences_json TEXT NOT NULL DEFAULT '{"version":1}',
    workout_updated_at INTEGER NOT NULL DEFAULT 0,
    workout_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(workout_local_generation >= 0),
    workout_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(workout_server_revision >= 0),
    workout_dirty INTEGER NOT NULL DEFAULT 1 CHECK(workout_dirty IN (0, 1)),
    led_color_scheme_id INTEGER NOT NULL DEFAULT 0 CHECK(led_color_scheme_id >= 0),
    led_preferences_json TEXT NOT NULL DEFAULT '{"version":1}',
    led_updated_at INTEGER NOT NULL DEFAULT 0,
    led_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(led_local_generation >= 0),
    led_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(led_server_revision >= 0),
    led_dirty INTEGER NOT NULL DEFAULT 1 CHECK(led_dirty IN (0, 1)),
    vbt_enabled INTEGER NOT NULL DEFAULT 1 CHECK(vbt_enabled IN (0, 1)),
    vbt_preferences_json TEXT NOT NULL DEFAULT '{"version":1}',
    vbt_updated_at INTEGER NOT NULL DEFAULT 0,
    vbt_local_generation INTEGER NOT NULL DEFAULT 0 CHECK(vbt_local_generation >= 0),
    vbt_server_revision INTEGER NOT NULL DEFAULT 0 CHECK(vbt_server_revision >= 0),
    vbt_dirty INTEGER NOT NULL DEFAULT 1 CHECK(vbt_dirty IN (0, 1)),
    FOREIGN KEY (profile_id) REFERENCES UserProfile(id) ON DELETE CASCADE
);

CREATE TABLE PendingProfileLocalCleanup (
    profile_id TEXT PRIMARY KEY NOT NULL,
    enqueued_at INTEGER NOT NULL
);

CREATE TABLE PendingProfileContextRecovery (
    recovery_key TEXT PRIMARY KEY NOT NULL
        DEFAULT 'active_profile_transition'
        CHECK(recovery_key = 'active_profile_transition'),
    prior_profile_id TEXT NOT NULL,
    created_profile_id TEXT,
    enqueued_at INTEGER NOT NULL
);
```

Append to both `42.sqm` and the `MigrationStatements` 42 fallback branch, but not the canonical `.sq` schema:

```sql
INSERT OR IGNORE INTO UserProfilePreferences(profile_id)
SELECT id FROM UserProfile;
```

- [ ] **Step 4: Add exact SQLDelight write and cleanup queries**

```sql
selectProfilePreferences:
SELECT * FROM UserProfilePreferences WHERE profile_id = ?;

selectAllProfilePreferences:
SELECT * FROM UserProfilePreferences ORDER BY profile_id;

insertDefaultProfilePreferences:
INSERT OR IGNORE INTO UserProfilePreferences(profile_id, legacy_migration_version)
VALUES (?, ?);

seedMissingProfilePreferences:
INSERT OR IGNORE INTO UserProfilePreferences(profile_id)
SELECT id FROM UserProfile;

updateCoreProfilePreferences:
UPDATE UserProfilePreferences
SET body_weight_kg = ?, weight_unit = ?, weight_increment = ?, core_updated_at = ?,
    core_local_generation = core_local_generation + 1, core_dirty = 1
WHERE profile_id = ?;

updateRackProfilePreferences:
UPDATE UserProfilePreferences
SET equipment_rack_json = ?, rack_updated_at = ?, rack_local_generation = rack_local_generation + 1, rack_dirty = 1
WHERE profile_id = ?;

updateWorkoutProfilePreferences:
UPDATE UserProfilePreferences
SET workout_preferences_json = ?, workout_updated_at = ?, workout_local_generation = workout_local_generation + 1, workout_dirty = 1
WHERE profile_id = ?;

updateLedProfilePreferences:
UPDATE UserProfilePreferences
SET led_color_scheme_id = ?, led_preferences_json = ?, led_updated_at = ?,
    led_local_generation = led_local_generation + 1, led_dirty = 1
WHERE profile_id = ?;

updateVbtProfilePreferences:
UPDATE UserProfilePreferences
SET vbt_enabled = ?, vbt_preferences_json = ?, vbt_updated_at = ?,
    vbt_local_generation = vbt_local_generation + 1, vbt_dirty = 1
WHERE profile_id = ?;

deleteProfilePreferences:
DELETE FROM UserProfilePreferences WHERE profile_id = ?;

enqueueProfileLocalCleanup:
INSERT OR REPLACE INTO PendingProfileLocalCleanup(profile_id, enqueued_at) VALUES (?, ?);

selectPendingProfileLocalCleanup:
SELECT * FROM PendingProfileLocalCleanup ORDER BY enqueued_at, profile_id;

dequeueProfileLocalCleanup:
DELETE FROM PendingProfileLocalCleanup WHERE profile_id = ?;

enqueueProfileContextRecovery:
INSERT OR REPLACE INTO PendingProfileContextRecovery(
    recovery_key, prior_profile_id, created_profile_id, enqueued_at
) VALUES ('active_profile_transition', ?, ?, ?);

selectPendingProfileContextRecovery:
SELECT * FROM PendingProfileContextRecovery
WHERE recovery_key = 'active_profile_transition';

clearPendingProfileContextRecovery:
DELETE FROM PendingProfileContextRecovery
WHERE recovery_key = 'active_profile_transition';
```

Add this guarded legacy-copy statement:

```sql
applyLegacyProfilePreferences:
UPDATE UserProfilePreferences
SET schema_version = 1,
    legacy_migration_version = 1,
    body_weight_kg = :body_weight_kg,
    weight_unit = :weight_unit,
    weight_increment = :weight_increment,
    core_updated_at = :migrated_at,
    core_local_generation = 1,
    core_server_revision = 0,
    core_dirty = 1,
    equipment_rack_json = :equipment_rack_json,
    rack_updated_at = :migrated_at,
    rack_local_generation = 1,
    rack_server_revision = 0,
    rack_dirty = 1,
    workout_preferences_json = :workout_preferences_json,
    workout_updated_at = :migrated_at,
    workout_local_generation = 1,
    workout_server_revision = 0,
    workout_dirty = 1,
    led_color_scheme_id = :led_color_scheme_id,
    led_preferences_json = :led_preferences_json,
    led_updated_at = :migrated_at,
    led_local_generation = 1,
    led_server_revision = 0,
    led_dirty = 1,
    vbt_enabled = :vbt_enabled,
    vbt_preferences_json = :vbt_preferences_json,
    vbt_updated_at = :migrated_at,
    vbt_local_generation = 1,
    vbt_server_revision = 0,
    vbt_dirty = 1
WHERE profile_id = :profile_id AND legacy_migration_version = 0;
```

- [ ] **Step 5: Reconcile every schema-version path**

Set SQLDelight `version = 43` in `shared/build.gradle.kts`; set the parity constant to `43`; add a `42 ->` branch in `MigrationStatements.kt` containing the same DDL and seed; and add all three table operations with every column to `SchemaManifest.kt`. Preserve the existing operation ordering: `UserProfile`, `UserProfilePreferences`, `PendingProfileContextRecovery`, `PendingProfileLocalCleanup`, then dependent tables.

- [ ] **Step 6: Generate interfaces and run all schema checks**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface :shared:verifyCommonMainVitruvianDatabaseMigration :shared:validateSchemaManifest --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SchemaParityTest*" --tests "*SchemaManifestTest*" --console=plain
```

Expected: both commands exit 0; migration parity reports schema 43 and no missing manifest operations.

- [ ] **Step 7: Commit schema 43**

```powershell
git add shared/build.gradle.kts shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/42.sqm shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaParityTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaManifestTest.kt
git commit -m "feat: add profile preference schema"
```

### Task 3: Implement the preference store, local safety store, and active-profile facade

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProfilePreferencesRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfileLocalSafetyStore.kt`
- Create: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightProfilePreferencesRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightUserProfileRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`

**Interfaces:**
- Consumes: generated schema-43 queries and Task 1's typed codec.
- Produces: `ProfilePreferencesRepository`, `ProfileLocalSafetyStore`, `UserProfileRepository.activeProfileContext`, typed update methods, atomic `createAndActivateProfile`, the idempotent pending-local-cleanup processor needed by Task 4, and compile-safe focused-store/repository DI bindings.

- [ ] **Step 1: Write failing section-isolation and switch-order tests**

```kotlin
@Test
fun editingCoreOnlyAdvancesCoreMetadata() = runTest {
    repository.insertDefaults("a")
    val before = repository.get("a")

    repository.updateCore("a", before.core.value.copy(bodyWeightKg = 80f), now = 200)
    val after = repository.get("a")

    assertEquals(before.core.metadata.localGeneration + 1, after.core.metadata.localGeneration)
    assertEquals(200, after.core.metadata.updatedAt)
    assertTrue(after.core.metadata.dirty)
    assertEquals(before.core.metadata.serverRevision, after.core.metadata.serverRevision)
    assertEquals(before.rack, after.rack)
    assertEquals(before.workout, after.workout)
    assertEquals(before.led, after.led)
    assertEquals(before.vbt, after.vbt)
}

@Test
fun switchingNeverEmitsMixedProfileContext() = runTest {
    facade.createAndActivateProfile("A", 1)
    val profileA = facade.activeProfile.value!!
    facade.createAndActivateProfile("B", 2)
    val profileB = facade.activeProfile.value!!
    val observed = mutableListOf<ActiveProfileContext>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) {
        facade.activeProfileContext.take(2).toList(observed)
    }

    facade.setActiveProfile(profileA.id)

    assertIs<ActiveProfileContext.Switching>(observed[0])
    val ready = assertIs<ActiveProfileContext.Ready>(observed[1])
    assertEquals(profileA.id, ready.profile.id)
    assertEquals(profileA.id, ready.preferences.profileId)
    assertNotEquals(profileB.id, ready.profile.id)
    job.cancel()
}

@Test
fun failedCreateAfterActivationRestoresPreviousDatabaseAndReadyContext() = runTest {
    val previous = assertIs<ActiveProfileContext.Ready>(facade.activeProfileContext.value)
    val profileIdsBefore = facade.allProfiles.value.map { it.id }.toSet()
    val preferenceIdsBefore = queries.selectAllProfilePreferences().executeAsList().map { it.profile_id }.toSet()
    preferenceStore.failNextGet = true

    assertFails { facade.createAndActivateProfile("Cannot publish", 2) }

    assertEquals(previous.profile.id, queries.getActiveProfile().executeAsOne().id)
    assertEquals(previous, facade.activeProfileContext.value)
    assertEquals(profileIdsBefore, facade.allProfiles.value.map { it.id }.toSet())
    assertEquals(
        preferenceIdsBefore,
        queries.selectAllProfilePreferences().executeAsList().map { it.profile_id }.toSet(),
    )
}

@Test
fun failedSwitchAfterDatabaseActivationRestoresPreviousDatabaseAndReadyContext() = runTest {
    val target = facade.createProfile("Target", 2)
    val previous = assertIs<ActiveProfileContext.Ready>(facade.activeProfileContext.value)
    preferenceStore.failNextGetFor = target.id

    assertFails { facade.setActiveProfile(target.id) }

    assertEquals(previous.profile.id, queries.getActiveProfile().executeAsOne().id)
    assertEquals(previous, facade.activeProfileContext.value)
}

@Test
fun rollbackFailureStaysJournaledUntilReconciliationRestoresDatabaseAndReady() = runTest {
    val target = facade.createProfile("Target", 2)
    val previous = assertIs<ActiveProfileContext.Ready>(facade.activeProfileContext.value)
    preferenceStore.failNextGetFor = target.id
    transitionFaults.failNextRecoveryTransaction = true

    assertFailsWith<ProfileContextRecoveryException> { facade.setActiveProfile(target.id) }
    assertNotNull(queries.selectPendingProfileContextRecovery().executeAsOneOrNull())

    facade.reconcileActiveProfileContext()

    assertNull(queries.selectPendingProfileContextRecovery().executeAsOneOrNull())
    assertEquals(previous.profile.id, queries.getActiveProfile().executeAsOne().id)
    assertEquals(previous.profile.id, assertIs<ActiveProfileContext.Ready>(facade.activeProfileContext.value).profile.id)
}

@Test
fun failedCreateCompensationRetriesFromJournalWithoutLeavingRows() = runTest {
    val profileIdsBefore = facade.allProfiles.value.map { it.id }.toSet()
    preferenceStore.failNextGet = true
    transitionFaults.failNextCreatedProfileDelete = true

    assertFailsWith<ProfileContextRecoveryException> {
        facade.createAndActivateProfile("Failed create", 2)
    }
    val pending = queries.selectPendingProfileContextRecovery().executeAsOne()
    val failedId = assertNotNull(pending.created_profile_id)

    facade.reconcileActiveProfileContext()

    assertNull(queries.selectPendingProfileContextRecovery().executeAsOneOrNull())
    assertNull(queries.getProfileById(failedId).executeAsOneOrNull())
    assertNull(queries.selectProfilePreferences(failedId).executeAsOneOrNull())
    assertEquals(profileIdsBefore, facade.allProfiles.value.map { it.id }.toSet())
}

@Test
fun pendingLocalCleanupDequeuesOnlyAfterEverySafetyKeyIsRemoved() = runTest {
    queries.enqueueProfileLocalCleanup("source", 100)
    safetyStore.failDeletes = true

    facade.retryPendingLocalCleanup()
    assertEquals(listOf("source"), queries.selectPendingProfileLocalCleanup().executeAsList().map { it.profile_id })

    safetyStore.failDeletes = false
    facade.retryPendingLocalCleanup()
    assertTrue(queries.selectPendingProfileLocalCleanup().executeAsList().isEmpty())
    assertNull(settings.getStringOrNull("profile_source_safe_word"))
}
```

Add tests proving malformed stored workout JSON returns typed defaults with `Invalid`, remains byte-for-byte unchanged after a rack edit, and becomes valid only through `resetInvalidSection(WORKOUT)` or a valid workout update. Implement `transitionFaults` with a test-only failing driver/query wrapper; do not put failure switches in production code.

- [ ] **Step 2: Run repository tests and verify they fail before implementation**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SqlDelightProfilePreferencesRepositoryTest*" --tests "*SqlDelightUserProfileRepositoryTest*" --console=plain
```

Expected: FAIL during compilation because the focused repository and active context are absent.

- [ ] **Step 3: Implement the focused SQLDelight store**

```kotlin
interface ProfilePreferencesRepository {
    fun observe(profileId: String): Flow<UserProfilePreferences>
    suspend fun get(profileId: String): UserProfilePreferences
    suspend fun seedMissingProfiles()
    suspend fun insertDefaults(profileId: String)
    suspend fun updateCore(profileId: String, value: CoreProfilePreferences, now: Long)
    suspend fun updateRack(profileId: String, value: RackPreferences, now: Long)
    suspend fun updateWorkout(profileId: String, value: WorkoutPreferences, now: Long)
    suspend fun updateLed(profileId: String, value: LedPreferences, now: Long)
    suspend fun updateVbt(profileId: String, value: VbtPreferences, now: Long)
    suspend fun resetInvalidSection(profileId: String, section: ProfilePreferenceSectionName, now: Long)
    suspend fun delete(profileId: String)
}

class SqlDelightProfilePreferencesRepository(
    private val database: VitruvianDatabase,
) : ProfilePreferencesRepository {
    private val queries = database.vitruvianDatabaseQueries

    override fun observe(profileId: String): Flow<UserProfilePreferences> =
        queries.selectProfilePreferences(profileId)
            .asFlow()
            .mapToOne(Dispatchers.IO)
            .map(::mapRow)

    override suspend fun get(profileId: String): UserProfilePreferences =
        mapRow(queries.selectProfilePreferences(profileId).executeAsOne())

    override suspend fun seedMissingProfiles() = queries.seedMissingProfilePreferences()

    override suspend fun insertDefaults(profileId: String) {
        queries.insertDefaultProfilePreferences(profileId, 1)
    }

    override suspend fun updateCore(profileId: String, value: CoreProfilePreferences, now: Long) {
        require(ProfilePreferencesValidator.core(value).isEmpty())
        queries.updateCoreProfilePreferences(
            value.bodyWeightKg.toDouble(), value.weightUnit.name, value.weightIncrement.toDouble(), now, profileId,
        )
    }

    override suspend fun updateRack(profileId: String, value: RackPreferences, now: Long) {
        require(ProfilePreferencesValidator.rack(value).isEmpty())
        queries.updateRackProfilePreferences(ProfilePreferencesCodec.encodeRack(value), now, profileId)
    }

    override suspend fun updateWorkout(profileId: String, value: WorkoutPreferences, now: Long) {
        require(ProfilePreferencesValidator.workout(value).isEmpty())
        queries.updateWorkoutProfilePreferences(ProfilePreferencesCodec.encodeWorkout(value), now, profileId)
    }

    override suspend fun updateLed(profileId: String, value: LedPreferences, now: Long) {
        require(ProfilePreferencesValidator.led(value).isEmpty())
        queries.updateLedProfilePreferences(value.colorScheme.toLong(), ProfilePreferencesCodec.encodeLed(value), now, profileId)
    }

    override suspend fun updateVbt(profileId: String, value: VbtPreferences, now: Long) {
        require(ProfilePreferencesValidator.vbt(value).isEmpty())
        queries.updateVbtProfilePreferences(if (value.enabled) 1 else 0, ProfilePreferencesCodec.encodeVbt(value), now, profileId)
    }

    override suspend fun delete(profileId: String) = queries.deleteProfilePreferences(profileId)
}
```

Construct each section without letting one decode affect another:

```kotlin
private fun mapRow(row: ProfilePreferencesRow): UserProfilePreferences {
    fun metadata(updatedAt: Long, generation: Long, revision: Long, dirty: Long) =
        ProfileSectionMetadata(updatedAt, generation, revision, dirty == 1L)

    val storedCore = CoreProfilePreferences(
        bodyWeightKg = row.body_weight_kg.toFloat(),
        weightUnit = WeightUnit.valueOf(row.weight_unit),
        weightIncrement = row.weight_increment.toFloat(),
    )
    val coreErrors = ProfilePreferencesValidator.core(storedCore)
    val rack = ProfilePreferencesCodec.decodeRack(row.equipment_rack_json)
    val workout = ProfilePreferencesCodec.decodeWorkout(row.workout_preferences_json)
    val led = ProfilePreferencesCodec.decodeLed(row.led_preferences_json, row.led_color_scheme_id.toInt())
    val vbt = ProfilePreferencesCodec.decodeVbt(row.vbt_preferences_json, row.vbt_enabled == 1L)

    return UserProfilePreferences(
        profileId = row.profile_id,
        schemaVersion = row.schema_version.toInt(),
        legacyMigrationVersion = row.legacy_migration_version.toInt(),
        core = ProfilePreferenceSection(
            value = if (coreErrors.isEmpty()) storedCore else CoreProfilePreferences(),
            validity = if (coreErrors.isEmpty()) ProfilePreferenceValidity.Valid else ProfilePreferenceValidity.Invalid(coreErrors.joinToString(",")),
            metadata = metadata(row.core_updated_at, row.core_local_generation, row.core_server_revision, row.core_dirty),
        ),
        rack = ProfilePreferenceSection(rack.value, rack.raw, rack.validity, metadata(row.rack_updated_at, row.rack_local_generation, row.rack_server_revision, row.rack_dirty)),
        workout = ProfilePreferenceSection(workout.value, workout.raw, workout.validity, metadata(row.workout_updated_at, row.workout_local_generation, row.workout_server_revision, row.workout_dirty)),
        led = ProfilePreferenceSection(led.value, led.raw, led.validity, metadata(row.led_updated_at, row.led_local_generation, row.led_server_revision, row.led_dirty)),
        vbt = ProfilePreferenceSection(vbt.value, vbt.raw, vbt.validity, metadata(row.vbt_updated_at, row.vbt_local_generation, row.vbt_server_revision, row.vbt_dirty)),
    )
}
```

Alias the generated row import as `ProfilePreferencesRow` to avoid colliding with the domain aggregate. `resetInvalidSection` writes the documented default through that section's normal update method, which increments its generation and preserves its server revision.

- [ ] **Step 4: Add profile-prefixed safety persistence**

```kotlin
class SettingsProfileLocalSafetyStore(
    private val settings: Settings,
) : ProfileLocalSafetyStore {
    private fun key(profileId: String, suffix: String) = "profile_${profileId}_$suffix"

    override fun read(profileId: String) = ProfileLocalSafetyPreferences(
        safeWord = settings.getStringOrNull(key(profileId, "safe_word")),
        safeWordCalibrated = settings.getBoolean(key(profileId, "safe_word_calibrated"), false),
        adultsOnlyConfirmed = settings.getBoolean(key(profileId, "adults_only_confirmed"), false),
        adultsOnlyPrompted = settings.getBoolean(key(profileId, "adults_only_prompted"), false),
    )

    override fun write(profileId: String, value: ProfileLocalSafetyPreferences) {
        val previous = read(profileId)
        try {
            writeKeys(profileId, value)
        } catch (error: Exception) {
            runCatching { writeKeys(profileId, previous) }
            throw error
        }
    }

    private fun writeKeys(profileId: String, value: ProfileLocalSafetyPreferences) {
        value.safeWord?.let { settings.putString(key(profileId, "safe_word"), it) }
            ?: settings.remove(key(profileId, "safe_word"))
        settings.putBoolean(key(profileId, "safe_word_calibrated"), value.safeWordCalibrated)
        settings.putBoolean(key(profileId, "adults_only_confirmed"), value.adultsOnlyConfirmed)
        settings.putBoolean(key(profileId, "adults_only_prompted"), value.adultsOnlyPrompted)
    }

    override suspend fun copyLegacyToProfiles(
        profileIds: List<String>,
        value: ProfileLocalSafetyPreferences,
    ) = profileIds.forEach { write(it, value) }

    override fun delete(profileId: String) {
        listOf("safe_word", "safe_word_calibrated", "adults_only_confirmed", "adults_only_prompted")
            .forEach { settings.remove(key(profileId, it)) }
    }
}
```

`copyLegacyToProfiles` is suspending because it belongs to the awaited migration pipeline and its fake uses a `suspend () -> Unit` timing hook. The production implementation writes the supplied immutable snapshot to every profile ID without changing dispatchers. The legacy reader is the only component that reads the four old keys. Do not log the phrase or any copied value.

- [ ] **Step 5: Expand the public profile facade and make creation atomic**

```kotlin
interface UserProfileRepository {
    val activeProfile: StateFlow<UserProfile?>
    val allProfiles: StateFlow<List<UserProfile>>
    val activeProfileContext: StateFlow<ActiveProfileContext>

    fun observePreferences(profileId: String): Flow<UserProfilePreferences>
    suspend fun createAndActivateProfile(name: String, colorIndex: Int): UserProfile
    suspend fun updateCore(profileId: String, value: CoreProfilePreferences)
    suspend fun updateRack(profileId: String, value: RackPreferences)
    suspend fun updateWorkout(profileId: String, value: WorkoutPreferences)
    suspend fun updateLed(profileId: String, value: LedPreferences)
    suspend fun updateVbt(profileId: String, value: VbtPreferences)
    suspend fun updateLocalSafety(profileId: String, value: ProfileLocalSafetyPreferences)
    suspend fun retryPendingLocalCleanup(profileId: String? = null)
    suspend fun recoverPendingProfileTransitionForStartup()
    suspend fun reconcileActiveProfileContext()
}
```

Keep existing identity/subscription methods. Construct `SqlDelightUserProfileRepository` with `ProfilePreferencesRepository`, `ProfileLocalSafetyStore`, and `GamificationRepository`. Initialize `activeProfileContext` as `Switching(activeProfile.value?.id)` and do not publish `Ready` from the constructor. The required migration first calls `recoverPendingProfileTransitionForStartup()`, which drains the journal and refreshes identity flows while deliberately retaining `Switching`; it calls `reconcileActiveProfileContext()` only after legacy values and local safety are copied. Keep `createProfile` as a backward-compatible non-activating operation, but make it insert the identity row and `insertDefaultProfilePreferences(id, 1)` in one SQL transaction. All new UI uses this activating operation:

```kotlin
private suspend fun <T> withProfileContextTransition(
    targetProfileId: String?,
    operation: suspend (previous: ActiveProfileContext.Ready) -> T,
): T {
    val previous = _activeProfileContext.value as? ActiveProfileContext.Ready
        ?: throw ProfileContextUnavailableException()
    _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)
    return try {
        operation(previous)
    } catch (failure: Throwable) {
        _activeProfileContext.value = ActiveProfileContext.Switching(previous.profile.id)
        runCatching {
            reconcileActiveProfileContextLocked(publishReady = true)
        }.getOrElse { recoveryFailure ->
            failure.addSuppressed(recoveryFailure)
            throw ProfileContextRecoveryException(failure)
        }
        throw failure
    }
}

override suspend fun createAndActivateProfile(name: String, colorIndex: Int): UserProfile =
    profileContextMutex.withLock {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Profile name must not be blank" }
        val id = generateUUID()
        val createdAt = currentTimeMillis()
        withProfileContextTransition(id) { previous ->
            database.transaction {
                queries.insertProfile(id, trimmedName, colorIndex.toLong(), createdAt, 0)
                queries.insertDefaultProfilePreferences(id, 1)
                queries.enqueueProfileContextRecovery(previous.profile.id, id, createdAt)
                queries.setActiveProfile(id)
            }
            refreshProfilesSync()
            publishReadyContext(id)
            val created = activeProfile.value ?: error("Activated profile missing: $id")
            queries.clearPendingProfileContextRecovery()
            created
        }
    }

override suspend fun setActiveProfile(id: String) {
    profileContextMutex.withLock {
        require(allProfiles.value.any { it.id == id }) { "Unknown profile: $id" }
        withProfileContextTransition(id) { previous ->
            database.transaction {
                queries.enqueueProfileContextRecovery(
                    prior_profile_id = previous.profile.id,
                    created_profile_id = null,
                    enqueued_at = currentTimeMillis(),
                )
                queries.setActiveProfile(id)
            }
            refreshProfilesSync()
            publishReadyContext(id)
            queries.clearPendingProfileContextRecovery()
        }
    }
}

override suspend fun updateProfile(id: String, name: String, colorIndex: Int) {
    profileContextMutex.withLock {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be blank" }
        queries.updateProfile(trimmed, colorIndex.toLong(), id)
        refreshProfilesSync()
        if (activeProfile.value?.id == id) publishReadyContext(id)
    }
}
```

Wrap `createAndActivateProfile`, `setActiveProfile`, reconciliation, deletion, and every public typed update in the same `profileContextMutex`. The transition journal is written in the same transaction as activation and cleared only after a complete Ready context has been published. Define `ProfileContextRecoveryException` for the exceptional case where journal recovery itself fails; route that exception to the app's blocking retry surface rather than returning to the switcher. Each update requires its `profileId` to equal the current `Ready.profile.id`; a stale screen write throws `StaleProfileContextException` rather than touching the newly active profile. It writes through `ProfilePreferencesRepository`, then republishes a complete `Ready` value for that same ID. If context is `Switching`, throw `ProfileContextUnavailableException` so workout-start callers remain blocked.

```kotlin
private suspend fun mutateActiveProfile(
    expectedProfileId: String,
    write: suspend () -> Unit,
) = profileContextMutex.withLock {
    val context = _activeProfileContext.value as? ActiveProfileContext.Ready
        ?: throw ProfileContextUnavailableException()
    if (context.profile.id != expectedProfileId) {
        throw StaleProfileContextException(expectedProfileId, context.profile.id)
    }
    write()
    publishReadyContext(expectedProfileId)
}

override suspend fun updateCore(profileId: String, value: CoreProfilePreferences) =
    mutateActiveProfile(profileId) { profilePreferencesRepository.updateCore(profileId, value, currentTimeMillis()) }

override suspend fun updateRack(profileId: String, value: RackPreferences) =
    mutateActiveProfile(profileId) { profilePreferencesRepository.updateRack(profileId, value, currentTimeMillis()) }

override suspend fun updateWorkout(profileId: String, value: WorkoutPreferences) =
    mutateActiveProfile(profileId) { profilePreferencesRepository.updateWorkout(profileId, value, currentTimeMillis()) }

override suspend fun updateLed(profileId: String, value: LedPreferences) =
    mutateActiveProfile(profileId) { profilePreferencesRepository.updateLed(profileId, value, currentTimeMillis()) }

override suspend fun updateVbt(profileId: String, value: VbtPreferences) =
    mutateActiveProfile(profileId) { profilePreferencesRepository.updateVbt(profileId, value, currentTimeMillis()) }

override suspend fun updateLocalSafety(profileId: String, value: ProfileLocalSafetyPreferences) =
    mutateActiveProfile(profileId) { profileLocalSafetyStore.write(profileId, value) }

private suspend fun reconcileActiveProfileContextLocked(publishReady: Boolean) {
    queries.selectPendingProfileContextRecovery().executeAsOneOrNull()?.let { pending ->
        database.transaction {
            val priorId = queries.getProfileById(pending.prior_profile_id)
                .executeAsOneOrNull()
                ?.id
                ?: "default"
            queries.setActiveProfile(priorId)
            pending.created_profile_id?.let { failedCreatedId ->
                queries.deleteProfilePreferences(failedCreatedId)
                queries.deleteProfile(failedCreatedId)
            }
            queries.clearPendingProfileContextRecovery()
        }
    }
    refreshProfilesSync()
    val actualActiveId = queries.getActiveProfile().executeAsOneOrNull()?.id
        ?: error("No active profile after context reconciliation")
    if (publishReady) {
        publishReadyContext(actualActiveId)
    } else {
        _activeProfileContext.value = ActiveProfileContext.Switching(actualActiveId)
    }
}

override suspend fun recoverPendingProfileTransitionForStartup() = profileContextMutex.withLock {
    _activeProfileContext.value = ActiveProfileContext.Switching(null)
    try {
        reconcileActiveProfileContextLocked(publishReady = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw ProfileContextRecoveryException(error)
    }
}

override suspend fun reconcileActiveProfileContext() = profileContextMutex.withLock {
    _activeProfileContext.value = ActiveProfileContext.Switching(
        queries.selectPendingProfileContextRecovery().executeAsOneOrNull()?.prior_profile_id,
    )
    try {
        reconcileActiveProfileContextLocked(publishReady = true)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw ProfileContextRecoveryException(error)
    }
}

private val profileCleanupMutex = Mutex()

override suspend fun retryPendingLocalCleanup(profileId: String?) = profileCleanupMutex.withLock {
    queries.selectPendingProfileLocalCleanup().executeAsList()
        .asSequence()
        .filter { profileId == null || it.profile_id == profileId }
        .forEach { pending ->
            try {
                profileLocalSafetyStore.delete(pending.profile_id)
                queries.dequeueProfileLocalCleanup(pending.profile_id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Logger.w(error) { "Profile local cleanup remains queued profile=${pending.profile_id}" }
            }
        }
}
```

- [ ] **Step 6: Update fakes and pass isolation tests**

Make `FakeUserProfileRepository` hold a `MutableStateFlow<ActiveProfileContext>` and implement the same whole-section updates, cleanup, startup recovery, and reconciliation hooks. Add tests for A/B values across every section, atomic create/default/activate, invalid update rejection, local safety isolation, `Switching -> Ready` order, failed create after database activation, and failed switch after database activation. Both ordinary failure tests must assert that SQLite's active ID and `activeProfileContext.Ready.profile.id` return to the same prior profile; the failed-create case must also prove that neither the identity nor its default-preference row remains. Then force rollback and failed-create deletion failures separately, prove the journal remains, retry `reconcileActiveProfileContext()`, and assert the journal drains only after SQLite, identity rows, preference rows, and Ready all agree. Separately assert that `recoverPendingProfileTransitionForStartup()` drains the journal but leaves the context in `Switching`, and that pending local cleanup remains queued on Settings failure and drains on retry.

- [ ] **Step 7: Update the focused DI bindings before compiling later tasks**

Replace the existing one-argument `SqlDelightUserProfileRepository(get())` binding in `DataModule.kt` now, in the same task that changes its constructor:

```kotlin
single<ProfilePreferencesRepository> { SqlDelightProfilePreferencesRepository(get()) }
single<ProfileLocalSafetyStore> { SettingsProfileLocalSafetyStore(get()) }
single<UserProfileRepository> {
    SqlDelightUserProfileRepository(
        database = get(),
        profilePreferencesRepository = get(),
        profileLocalSafetyStore = get(),
        gamificationRepository = get(),
    )
}
```

Do not register `LegacyProfilePreferencesReader`, the refactored `SettingsManager`, or backup dependencies yet; their implementations land in later tasks and Task 9 performs final graph verification.

- [ ] **Step 8: Run focused tests and compile the affected graph**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SqlDelightProfilePreferencesRepositoryTest*" --tests "*SqlDelightUserProfileRepositoryTest*" --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileAndroidMain --console=plain
```

Expected: PASS, including the malformed-raw retention test.

- [ ] **Step 9: Commit the repositories and compile-safe bindings**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProfilePreferencesRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfileLocalSafetyStore.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightProfilePreferencesRepositoryTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightUserProfileRepositoryTest.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt
git commit -m "feat: add active profile preference facade"
```

### Task 4: Copy legacy values behind an awaited startup gate

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/LegacyProfilePreferencesReader.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/LegacyProfilePreferencesReaderTest.kt`
- Create: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/ProfilePreferencesMigrationTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/MigrationManagerTest.kt`
- Modify: `androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt`
- Modify: `shared/src/androidMain/kotlin/com/devil/phoenixproject/AndroidAppHost.kt`
- Modify: `shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt`

**Interfaces:**
- Consumes: existing legacy `PreferencesManager`/Settings keys and the Task 3 stores.
- Produces: `RequiredMigrationState`, `MigrationManager.requiredMigrationState`, `runRequiredMigrations`, `retryRequiredMigrations`, and `awaitRequiredMigrations`.

- [ ] **Step 1: Write failing normalization, retry, and boot-gate tests**

```kotlin
@Test
fun corruptLegacyFieldsNormalizeWithoutFailingMigration() = runTest {
    settings.putString("weight_unit", "STONE")
    settings.putFloat("body_weight_kg", Float.NaN)
    settings.putString("equipment_rack_items_v1", "[{\"id\":\"x\"},{\"id\":\"x\"}]")
    settings.putString("single_exercise_defaults", "{broken")

    val snapshot = reader.readNormalized()

    assertEquals(WeightUnit.LB, snapshot.core.weightUnit)
    assertEquals(0f, snapshot.core.bodyWeightKg)
    assertEquals(emptyList(), snapshot.rack.items)
    assertEquals(emptyMap(), snapshot.workout.singleExerciseDefaults)
    assertTrue(snapshot.vbt.enabled)
}

@Test
fun partialFailureRetriesWithoutOverwritingCompletedRows() = runTest {
    createProfiles("a", "b")
    safetyStore.failProfileId = "b"
    migration.runRequiredMigrations()
    assertIs<RequiredMigrationState.Failed>(migration.requiredMigrationState.value)
    preferenceRepository.updateCore("a", CoreProfilePreferences(bodyWeightKg = 90f), 300)

    safetyStore.failProfileId = null
    migration.retryRequiredMigrations()

    assertEquals(90f, preferenceRepository.get("a").core.value.bodyWeightKg)
    assertEquals(1, preferenceRepository.get("a").legacyMigrationVersion)
    assertEquals(1, preferenceRepository.get("b").legacyMigrationVersion)
    assertEquals(RequiredMigrationState.Ready, migration.requiredMigrationState.value)
}

@Test
fun startupReconciliationDrainsInterruptedCreateBeforeLegacyCopyAndReady() = runTest {
    createProfiles("default", "failed-create")
    preferenceRepository.insertDefaults("failed-create")
    queries.enqueueProfileContextRecovery("default", "failed-create", 100)
    queries.setActiveProfile("failed-create")

    migration.runRequiredMigrations()

    assertNull(queries.selectPendingProfileContextRecovery().executeAsOneOrNull())
    assertNull(queries.getProfileById("failed-create").executeAsOneOrNull())
    assertNull(queries.selectProfilePreferences("failed-create").executeAsOneOrNull())
    assertEquals("default", queries.getActiveProfile().executeAsOne().id)
    assertEquals("default", assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value).profile.id)
    assertEquals(RequiredMigrationState.Ready, migration.requiredMigrationState.value)
}

@Test
fun activeContextStaysSwitchingUntilPreferenceAndLocalSafetyCopyFinish() = runTest {
    val copyStarted = CompletableDeferred<Unit>()
    val allowCopyToFinish = CompletableDeferred<Unit>()
    safetyStore.beforeFirstCopy = {
        copyStarted.complete(Unit)
        allowCopyToFinish.await()
    }

    val migrationJob = launch { migration.runRequiredMigrations() }
    copyStarted.await()

    assertIs<ActiveProfileContext.Switching>(profiles.activeProfileContext.value)
    assertEquals(RequiredMigrationState.Applying, migration.requiredMigrationState.value)

    allowCopyToFinish.complete(Unit)
    migrationJob.join()
    assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value)
}
```

Add a contract test around `startupSurface(eulaAccepted, splashCompleted, migrationState)`: `Applying` and `Failed` never return `MAIN`; `Failed` returns `MIGRATION_RETRY`; only `Ready` can return `MAIN`.

- [ ] **Step 2: Run migration tests and verify the red state**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*LegacyProfilePreferencesReaderTest*" --tests "*ProfilePreferencesMigrationTest*" --tests "*MigrationManagerTest*" --console=plain
```

Expected: FAIL because required migration state and normalized snapshot reader are absent.

- [ ] **Step 3: Implement a field-by-field legacy snapshot reader**

```kotlin
data class LegacyProfilePreferenceSnapshot(
    val core: CoreProfilePreferences,
    val rack: RackPreferences,
    val workout: WorkoutPreferences,
    val led: LedPreferences,
    val vbt: VbtPreferences,
    val localSafety: ProfileLocalSafetyPreferences,
)

interface LegacyProfilePreferencesReader {
    fun readNormalized(): LegacyProfilePreferenceSnapshot
}
```

Read the current legacy keys through the existing `PreferencesManager` snapshot plus its existing rack/Just Lift/single-exercise APIs. Normalize independently:

```kotlin
internal object LegacyProfilePreferenceKeys {
    const val EQUIPMENT_RACK = "equipment_rack_items_v1"
    const val JUST_LIFT = "just_lift_defaults"
    const val EXERCISE_PREFIX = "exercise_defaults_"
}

private fun normalizeBodyWeight(value: Float): Float =
    value.takeIf { it.isFinite() && (it == 0f || it in 20f..300f) } ?: 0f

private fun decodeLegacyRack(raw: String?): List<RackItem> = buildList {
    val ids = mutableSetOf<String>()
    val elements = runCatching { raw?.let { json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList()) }
        .getOrElse {
            Logger.w { "PROFILE_PREF_MIGRATION normalized legacy key equipment_rack_items_v1: malformed array" }
            JsonArray(emptyList())
        }
    elements.forEach { element ->
        runCatching { json.decodeFromJsonElement<RackItem>(element) }
            .getOrNull()
            ?.takeIf { item -> item.id.isNotBlank() && item.name.isNotBlank() && item.weightKg.isFinite() && item.weightKg >= 0f && ids.add(item.id) }
            ?.let(::add)
            ?: Logger.w { "PROFILE_PREF_MIGRATION normalized legacy key equipment_rack_items_v1: invalid item" }
    }
}

private fun decodeSingleExerciseDefaults(): Map<String, SingleExerciseDefaultsDocument> =
    settings.keys
        .asSequence()
        .filter { it.startsWith(LegacyProfilePreferenceKeys.EXERCISE_PREFIX) }
        .mapNotNull { key ->
            val decoded = runCatching {
                settings.getStringOrNull(key)?.let { json.decodeFromString<SingleExerciseDefaults>(it) }
            }.getOrNull()
            if (decoded == null || decoded.exerciseId.isBlank()) {
                Logger.w { "PROFILE_PREF_MIGRATION normalized legacy key $key: malformed defaults" }
                null
            } else {
                Triple(
                    decoded.exerciseId,
                    decoded.toDocument(),
                    key == LegacyProfilePreferenceKeys.EXERCISE_PREFIX + decoded.exerciseId,
                )
            }
        }
        .sortedByDescending { it.third }
        .distinctBy { it.first }
        .associate { it.first to it.second }
```

Share the three constants with `PreferencesManager` and the legacy rack implementation rather than leaving duplicate private literals. Decode Just Lift independently with `runCatching`, then apply the documented defaults for unknown enums, malformed JSON, non-finite/out-of-range numbers, and invalid nested entries. Preserve valid siblings. Log only the legacy key and reason, never a sensitive value. Force `vbt.enabled = true` regardless of subordinate legacy values.

- [ ] **Step 4: Add an independent required migration state machine**

```kotlin
sealed interface RequiredMigrationState {
    data object NotStarted : RequiredMigrationState
    data object Applying : RequiredMigrationState
    data object Ready : RequiredMigrationState
    data class Failed(val message: String) : RequiredMigrationState
}

private const val KEY_PROFILE_PREFERENCES_MIGRATION_COMPLETE =
    "profile_preferences_legacy_migration_complete_v1"

private val _requiredMigrationState = MutableStateFlow<RequiredMigrationState>(RequiredMigrationState.NotStarted)
val requiredMigrationState: StateFlow<RequiredMigrationState> = _requiredMigrationState.asStateFlow()

suspend fun runRequiredMigrations() = requiredMigrationMutex.withLock {
    if (_requiredMigrationState.value == RequiredMigrationState.Ready) return@withLock
    _requiredMigrationState.value = RequiredMigrationState.Applying
    runCatching { migrateProfilePreferences() }
        .onSuccess { _requiredMigrationState.value = RequiredMigrationState.Ready }
        .onFailure { error ->
            if (error is CancellationException) throw error
            _requiredMigrationState.value = RequiredMigrationState.Failed(error.message ?: "Profile preference migration failed")
        }
}

suspend fun retryRequiredMigrations() = runRequiredMigrations()

suspend fun awaitRequiredMigrations() {
    requiredMigrationState.first { it is RequiredMigrationState.Ready }
}
```

The migration body must execute in this order:

```kotlin
private suspend fun migrateProfilePreferences() {
    userProfileRepository.ensureDefaultProfile()
    profilePreferencesRepository.seedMissingProfiles()
    userProfileRepository.recoverPendingProfileTransitionForStartup()
    val profiles = userProfileRepository.allProfiles.value
    val snapshot = legacyProfilePreferencesReader.readNormalized()
    val migrationTime = currentTimeMillis()
    profiles.forEach { profile ->
        queries.applyLegacyProfilePreferences(
            profile_id = profile.id,
            body_weight_kg = snapshot.core.bodyWeightKg.toDouble(),
            weight_unit = snapshot.core.weightUnit.name,
            weight_increment = snapshot.core.weightIncrement.toDouble(),
            equipment_rack_json = ProfilePreferencesCodec.encodeRack(snapshot.rack),
            workout_preferences_json = ProfilePreferencesCodec.encodeWorkout(snapshot.workout),
            led_color_scheme_id = snapshot.led.colorScheme.toLong(),
            led_preferences_json = ProfilePreferencesCodec.encodeLed(snapshot.led),
            vbt_enabled = 1,
            vbt_preferences_json = ProfilePreferencesCodec.encodeVbt(snapshot.vbt.copy(enabled = true)),
            migrated_at = migrationTime,
        )
    }
    if (!settings.getBoolean(KEY_PROFILE_PREFERENCES_MIGRATION_COMPLETE, false)) {
        profileLocalSafetyStore.copyLegacyToProfiles(profiles.map { it.id }, snapshot.localSafety)
        settings.putBoolean(KEY_PROFILE_PREFERENCES_MIGRATION_COMPLETE, true)
    }
    userProfileRepository.retryPendingLocalCleanup()
    userProfileRepository.reconcileActiveProfileContext()
}
```

`applyLegacyProfilePreferences` must retain its SQL `WHERE legacy_migration_version = 0`; that is the retry guard. The startup-only recovery drains any transition journal left by a killed process before the profile list is snapshotted but deliberately leaves `activeProfileContext` in `Switching`; only the final reconciliation publishes migrated values as `Ready`. A recovery/reconciliation failure keeps the required migration in `Failed`, so Retry continues the same idempotent journal operation. Start ordinary non-critical repair passes only after required migration reaches `Ready`.

Make the existing platform startup entry point idempotently start the required stage first:

```kotlin
fun checkAndRunMigrations() {
    scope.launch {
        runRequiredMigrations()
        if (requiredMigrationState.value == RequiredMigrationState.Ready) {
            runNonCriticalRepairsNow()
        }
    }
}
```

Keep Android's `VitruvianApp.onCreate()` and iOS `KoinInit.runMigrations()` calling `checkAndRunMigrations`; update their comments so required preference migration is no longer described as best effort. `AppContent` observes the same state and its idempotent call guarantees the gate is started in previews or alternate hosts.

- [ ] **Step 5: Gate root navigation and expose Retry on both platforms**

Inject `MigrationManager` through `AndroidAppHost` and the retained `IosAppDependencies`, pass it into `AppContent`, and collect `requiredMigrationState` there. Start it once with `LaunchedEffect(migrationManager)`.

```kotlin
internal enum class StartupSurface { EULA, SPLASH, MIGRATION_RETRY, MAIN }

internal fun startupSurface(
    eulaAccepted: Boolean,
    splashCompleted: Boolean,
    migrationState: RequiredMigrationState,
): StartupSurface = when {
    !eulaAccepted -> StartupSurface.EULA
    migrationState is RequiredMigrationState.Failed -> StartupSurface.MIGRATION_RETRY
    migrationState != RequiredMigrationState.Ready || !splashCompleted -> StartupSurface.SPLASH
    else -> StartupSurface.MAIN
}

@Composable
private fun MigrationRetryScreen(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
        }
    }
}

val migrationState by migrationManager.requiredMigrationState.collectAsState()
LaunchedEffect(migrationManager) { migrationManager.runRequiredMigrations() }

when {
    !eulaAccepted -> EulaScreen(onAccept = eulaViewModel::acceptEula)
    migrationState is RequiredMigrationState.Failed -> MigrationRetryScreen(
        message = (migrationState as RequiredMigrationState.Failed).message,
        onRetry = { scope.launch { migrationManager.retryRequiredMigrations() } },
    )
    migrationState != RequiredMigrationState.Ready || showLaunchSplash -> SplashScreen(visible = true)
    else -> EnhancedMainScreen(
        viewModel = mainViewModel,
        exerciseRepository = exerciseRepository,
        themeMode = themeMode,
        onThemeModeChange = themeViewModel::setThemeMode,
        dynamicColorAvailable = dynamicColorAvailable,
        dynamicColorEnabled = dynamicColorEnabled,
        onDynamicColorEnabledChange = themeViewModel::setDynamicColorEnabled,
    )
}
```

Keep the existing 2.5-second splash minimum, but readiness and the timer are independent conditions. The Retry surface must not expose bottom navigation or workout actions.

- [ ] **Step 6: Pass migration and gate tests**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*LegacyProfilePreferencesReaderTest*" --tests "*ProfilePreferencesMigrationTest*" --tests "*MigrationManagerTest*" --console=plain
```

Expected: PASS for reconciliation seeding, all-profile copy, corrupt normalization, unchanged active profile, partial failure/retry, new-profile defaults, `vbtEnabled=true`, and navigation gating.

- [ ] **Step 7: Commit the required migration gate**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/LegacyProfilePreferencesReader.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/LegacyProfilePreferencesReaderTest.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/MigrationManagerTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/ProfilePreferencesMigrationTest.kt androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt shared/src/androidMain/kotlin/com/devil/phoenixproject/AndroidAppHost.kt shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt
git commit -m "feat: migrate legacy profile preferences at startup"
```

### Task 5: Route compatibility managers and direct consumers through the active profile

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManagerTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManager.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManagerTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`
- Modify affected tests and fakes found with `rg -n "PreferencesManager|SettingsManager|EquipmentRackRepository" shared/src/*Test`.

**Interfaces:**
- Consumes: Task 3's active `Ready` context and whole-section update methods.
- Produces: a compatibility `UserPreferences` stream with global and active-profile fields, with no profile setter writing legacy Settings.

- [ ] **Step 1: Write failing A/B consumer tests**

```kotlin
@Test
fun profileSetterWritesRepositoryAndLeavesLegacyStoreUntouched() = runTest {
    legacySettings.putFloat("body_weight_kg", 70f)
    settingsManager.setBodyWeightKg(82f)
    advanceUntilIdle()

    val ready = assertIs<ActiveProfileContext.Ready>(profileRepository.activeProfileContext.value)
    assertEquals(82f, ready.preferences.core.value.bodyWeightKg)
    assertEquals(70f, legacySettings.getFloat("body_weight_kg", 0f))
}

@Test
fun equipmentRackFollowsActiveProfile() = runTest {
    setActive("a")
    rackRepository.replaceItems(listOf(rackItem("a-item")))
    setActive("b")
    rackRepository.replaceItems(listOf(rackItem("b-item")))

    assertEquals(listOf("b-item"), rackRepository.getItems().map { it.id })
    setActive("a")
    assertEquals(listOf("a-item"), rackRepository.getItems().map { it.id })
}

@Test
fun healthImportWaitsForRequiredMigrationAndWritesActiveCore() = runTest {
    migrationState.value = RequiredMigrationState.Applying
    manager.importBodyWeightKg(81f)
    assertEquals(0f, activeCore().bodyWeightKg)
    migrationState.value = RequiredMigrationState.Ready
    advanceUntilIdle()
    assertEquals(81f, activeCore().bodyWeightKg)
}
```

Add tests that voice stop is effectively false when intent is true but the active profile lacks a calibrated phrase, adult-only modes are ineffective without local consent, Just Lift/default exercise settings change on profile switch, and BLE reconnect/profile switch receives the active LED scheme.

Add this start-gate regression:

```kotlin
@Test
fun workoutStartIsRejectedWhileProfileContextIsSwitching() = runTest {
    profileRepository.emitSwitchingForTest("profile-b")
    engine.startWorkout()
    advanceUntilIdle()

    assertIs<WorkoutState.Idle>(engine.workoutState.value)
    assertEquals(0, fakeBleRepository.startWorkoutCalls)
}
```

- [ ] **Step 2: Run the consumer tests and confirm legacy behavior fails isolation**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SettingsManagerTest*" --tests "*EquipmentRackRepositoryTest*" --tests "*HealthBodyWeightSyncManagerTest*" --console=plain
```

Expected: FAIL because current setters and rack/body-weight consumers use global `PreferencesManager`.

- [ ] **Step 3: Make `SettingsManager` a two-source compatibility facade**

```kotlin
class SettingsManager(
    private val globalPreferences: PreferencesManager,
    private val userProfileRepository: UserProfileRepository,
    private val bleRepository: BleRepository,
    private val scope: CoroutineScope,
) {
    val userPreferences: StateFlow<UserPreferences> = combine(
        globalPreferences.preferencesFlow,
        userProfileRepository.activeProfileContext.filterIsInstance<ActiveProfileContext.Ready>(),
    ) { global, ready ->
        global.copy(
            weightUnit = ready.preferences.core.value.weightUnit,
            weightIncrement = ready.preferences.core.value.weightIncrement,
            bodyWeightKg = ready.preferences.core.value.bodyWeightKg,
            stopAtTop = ready.preferences.workout.value.stopAtTop,
            beepsEnabled = ready.preferences.workout.value.beepsEnabled,
            stallDetectionEnabled = ready.preferences.workout.value.stallDetectionEnabled,
            audioRepCountEnabled = ready.preferences.workout.value.audioRepCountEnabled,
            repCountTiming = ready.preferences.workout.value.repCountTiming,
            summaryCountdownSeconds = ready.preferences.workout.value.summaryCountdownSeconds,
            autoStartCountdownSeconds = ready.preferences.workout.value.autoStartCountdownSeconds,
            gamificationEnabled = ready.preferences.workout.value.gamificationEnabled,
            autoStartRoutine = ready.preferences.workout.value.autoStartRoutine,
            countdownBeepsEnabled = ready.preferences.workout.value.countdownBeepsEnabled,
            repSoundEnabled = ready.preferences.workout.value.repSoundEnabled,
            motionStartEnabled = ready.preferences.workout.value.motionStartEnabled,
            weightSuggestionsEnabled = ready.preferences.workout.value.weightSuggestionsEnabled,
            defaultRoutineExerciseUsePercentOfPR = ready.preferences.workout.value.defaultRoutineExerciseUsePercentOfPR,
            defaultRoutineExerciseWeightPercentOfPR = ready.preferences.workout.value.defaultRoutineExerciseWeightPercentOfPR,
            voiceStopEnabled = ready.preferences.workout.value.voiceStopEnabled,
            safeWord = ready.localSafety.safeWord,
            safeWordCalibrated = ready.localSafety.safeWordCalibrated,
            colorScheme = ready.preferences.led.value.colorScheme,
            discoModeUnlocked = ready.preferences.led.value.discoModeUnlocked,
            vbtEnabled = ready.preferences.vbt.value.enabled,
            velocityLossThresholdPercent = ready.preferences.vbt.value.velocityLossThresholdPercent,
            autoEndOnVelocityLoss = ready.preferences.vbt.value.autoEndOnVelocityLoss,
            defaultScalingBasis = ready.preferences.vbt.value.defaultScalingBasis,
            verbalEncouragementEnabled = ready.preferences.vbt.value.verbalEncouragementEnabled,
            vulgarModeEnabled = ready.preferences.vbt.value.vulgarModeEnabled,
            vulgarTier = ready.preferences.vbt.value.vulgarTier,
            dominatrixModeUnlocked = ready.preferences.vbt.value.dominatrixModeUnlocked,
            dominatrixModeActive = ready.preferences.vbt.value.dominatrixModeActive,
            adultsOnlyConfirmed = ready.localSafety.adultsOnlyConfirmed,
            adultsOnlyPrompted = ready.localSafety.adultsOnlyPrompted,
        )
    }.stateIn(scope, SharingStarted.Eagerly, globalPreferences.preferencesFlow.value)
}
```

Implement each profile setter as a copy of the active typed section followed by the matching repository update. Keep theme/video/language/backup/BLE/backfill setters delegated to `globalPreferences`. Keep legacy profile getters only for migration; mark them `@Deprecated("Legacy migration read only")` where call-site churn permits.

```kotlin
private fun ready(): ActiveProfileContext.Ready =
    userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
        ?: throw ProfileContextUnavailableException()

private fun updateCore(transform: (CoreProfilePreferences) -> CoreProfilePreferences) = scope.launch {
    val context = ready()
    userProfileRepository.updateCore(context.profile.id, transform(context.preferences.core.value))
}

private fun updateWorkout(transform: (WorkoutPreferences) -> WorkoutPreferences) = scope.launch {
    val context = ready()
    userProfileRepository.updateWorkout(context.profile.id, transform(context.preferences.workout.value))
}

private fun updateLed(transform: (LedPreferences) -> LedPreferences) = scope.launch {
    val context = ready()
    userProfileRepository.updateLed(context.profile.id, transform(context.preferences.led.value))
}

private fun updateVbt(transform: (VbtPreferences) -> VbtPreferences) = scope.launch {
    val context = ready()
    userProfileRepository.updateVbt(context.profile.id, transform(context.preferences.vbt.value))
}

private fun updateSafety(transform: (ProfileLocalSafetyPreferences) -> ProfileLocalSafetyPreferences) = scope.launch {
    val context = ready()
    userProfileRepository.updateLocalSafety(context.profile.id, transform(context.localSafety))
}

fun setWeightUnit(value: WeightUnit) = updateCore { it.copy(weightUnit = value) }
fun setWeightIncrement(value: Float) = updateCore { it.copy(weightIncrement = value) }
fun setBodyWeightKg(value: Float) = updateCore { it.copy(bodyWeightKg = value) }
fun setStopAtTop(value: Boolean) = updateWorkout { it.copy(stopAtTop = value) }
fun setBeepsEnabled(value: Boolean) = updateWorkout { it.copy(beepsEnabled = value) }
fun setStallDetectionEnabled(value: Boolean) = updateWorkout { it.copy(stallDetectionEnabled = value) }
fun setAudioRepCountEnabled(value: Boolean) = updateWorkout { it.copy(audioRepCountEnabled = value) }
fun setRepCountTiming(value: RepCountTiming) = updateWorkout { it.copy(repCountTiming = value) }
fun setSummaryCountdownSeconds(value: Int) = updateWorkout { it.copy(summaryCountdownSeconds = value) }
fun setAutoStartCountdownSeconds(value: Int) = updateWorkout { it.copy(autoStartCountdownSeconds = value) }
fun setGamificationEnabled(value: Boolean) = updateWorkout { it.copy(gamificationEnabled = value) }
fun setAutoStartRoutine(value: Boolean) = updateWorkout { it.copy(autoStartRoutine = value) }
fun setCountdownBeepsEnabled(value: Boolean) = updateWorkout { it.copy(countdownBeepsEnabled = value) }
fun setRepSoundEnabled(value: Boolean) = updateWorkout { it.copy(repSoundEnabled = value) }
fun setMotionStartEnabled(value: Boolean) = updateWorkout { it.copy(motionStartEnabled = value) }
fun setWeightSuggestionsEnabled(value: Boolean) = updateWorkout { it.copy(weightSuggestionsEnabled = value) }
fun setDefaultRoutineExerciseUsePercentOfPR(value: Boolean) = updateWorkout { it.copy(defaultRoutineExerciseUsePercentOfPR = value) }
fun setDefaultRoutineExerciseWeightPercentOfPR(value: Int) = updateWorkout { it.copy(defaultRoutineExerciseWeightPercentOfPR = value) }
fun setVoiceStopEnabled(value: Boolean) = updateWorkout { it.copy(voiceStopEnabled = value) }
fun setColorScheme(value: Int) = updateLed { it.copy(colorScheme = value) }
fun setDiscoModeUnlocked(value: Boolean) = updateLed { it.copy(discoModeUnlocked = value) }
fun setVbtEnabled(value: Boolean) = updateVbt { it.copy(enabled = value) }
fun setVelocityLossThreshold(value: Int) = updateVbt { it.copy(velocityLossThresholdPercent = value) }
fun setAutoEndOnVelocityLoss(value: Boolean) = updateVbt { it.copy(autoEndOnVelocityLoss = value) }
fun setDefaultScalingBasis(value: ScalingBasis) = updateVbt { it.copy(defaultScalingBasis = value) }
fun setVerbalEncouragementEnabled(value: Boolean) = updateVbt { it.copy(verbalEncouragementEnabled = value) }
fun setVulgarModeEnabled(value: Boolean) = updateVbt { it.copy(vulgarModeEnabled = value) }
fun setVulgarTier(value: VulgarTier) = updateVbt { it.copy(vulgarTier = value) }
fun setDominatrixModeUnlocked(value: Boolean) = updateVbt { it.copy(dominatrixModeUnlocked = value) }
fun setDominatrixModeActive(value: Boolean) = updateVbt { it.copy(dominatrixModeActive = value) }
fun setSafeWord(value: String?) = updateSafety { it.copy(safeWord = value) }
fun setSafeWordCalibrated(value: Boolean) = updateSafety { it.copy(safeWordCalibrated = value) }
fun setAdultsOnlyConfirmed(value: Boolean) = updateSafety { it.copy(adultsOnlyConfirmed = value) }
fun setAdultsOnlyPrompted(value: Boolean) = updateSafety { it.copy(adultsOnlyPrompted = value) }
```

- [ ] **Step 4: Make rack, body-weight, quick-start, safety, and LED consumers active-profile aware**

Use these concrete boundaries:

```kotlin
class ProfileEquipmentRackRepository(
    private val profiles: UserProfileRepository,
) : EquipmentRackRepository {
    override val items: Flow<List<RackItem>> = profiles.activeProfileContext
        .map { (it as? ActiveProfileContext.Ready)?.preferences?.rack?.value?.items.orEmpty() }
        .distinctUntilChanged()

    override suspend fun replaceItems(items: List<RackItem>) {
        val ready = profiles.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()
        profiles.updateRack(ready.profile.id, ready.preferences.rack.value.copy(items = items))
    }
}

private suspend fun awaitReadyProfile(): ActiveProfileContext.Ready {
    migrationManager.awaitRequiredMigrations()
    return userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
        ?: throw ProfileContextUnavailableException()
}
```

Call `awaitReadyProfile()` at the start of the existing `syncLatestFromConnectedPlatform()` flow, use its profile ID instead of falling back to `"default"`, and replace the global write with:

```kotlin
userProfileRepository.updateCore(
    ready.profile.id,
    ready.preferences.core.value.copy(bodyWeightKg = sample.weightKg),
)
externalMeasurementRepository.upsertMeasurements(listOf(measurement))
```

Perform the core update before inserting `ExternalBodyMeasurement`, and convert `StaleProfileContextException` into `HealthBodyWeightSyncResult.Failed` so a concurrent switch never writes the new profile.

Change `SafeWordDetectionManager` to consume effective state derived from `ActiveProfileContext.Ready`: intent, nonblank phrase, calibration, and local adult consent all must agree before their respective runtime feature activates. Change `DefaultWorkoutSessionManager` and `ActiveSessionEngine` quick-start reads to `SettingsManager.userPreferences` or the ready workout section. Change MainViewModel's disco unlock write to `updateLed`. Change BLE color application to collect active LED changes while connected, not just the initial connect snapshot.

Insert this as the first executable block in the existing `ActiveSessionEngine.startWorkout` method, before logging or coordinator mutation:

```kotlin
if (userProfileRepository.activeProfileContext.value !is ActiveProfileContext.Ready) {
    Logger.w { "Workout start ignored while profile context is switching" }
    return
}
```

Map quick-start values explicitly at the ActiveSessionEngine boundary:

```kotlin
suspend fun getSingleExerciseDefaults(exerciseId: String): SingleExerciseDefaults? =
    ready().preferences.workout.value.singleExerciseDefaults[exerciseId]?.toLegacySingleExerciseDefaults()

fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults) = scope.launch {
    val context = ready()
    val current = context.preferences.workout.value
    userProfileRepository.updateWorkout(
        context.profile.id,
        current.copy(singleExerciseDefaults = current.singleExerciseDefaults + (defaults.exerciseId to defaults.toDocument())),
    )
}

suspend fun getJustLiftDefaults(): JustLiftDefaults =
    ready().preferences.workout.value.justLiftDefaults.toRuntimeJustLiftDefaults()

fun saveJustLiftDefaults(defaults: JustLiftDefaults) = scope.launch {
    val context = ready()
    val current = context.preferences.workout.value
    userProfileRepository.updateWorkout(context.profile.id, current.copy(justLiftDefaults = defaults.toDocument()))
}

private fun JustLiftDefaults.toDocument() = JustLiftDefaultsDocument(
    workoutModeId = workoutModeId,
    weightPerCableKg = weightPerCableKg,
    weightChangePerRep = weightChangePerRep.toFloat(),
    eccentricLoadPercentage = eccentricLoadPercentage,
    echoLevelValue = echoLevelValue,
    stallDetectionEnabled = stallDetectionEnabled,
    repCountTimingName = repCountTimingName,
    restSeconds = restSeconds,
)

private fun JustLiftDefaultsDocument.toRuntimeJustLiftDefaults() = JustLiftDefaults(
    workoutModeId = workoutModeId,
    weightPerCableKg = weightPerCableKg,
    weightChangePerRep = weightChangePerRep.roundToInt(),
    eccentricLoadPercentage = eccentricLoadPercentage,
    echoLevelValue = echoLevelValue,
    stallDetectionEnabled = stallDetectionEnabled,
    repCountTimingName = repCountTimingName,
    restSeconds = restSeconds,
)
```

Tests assert lossless round trips, including rack item IDs and per-set arrays. The Just Lift display-unit increment intentionally rounds at the existing runtime boundary, matching its current `Int` type.

- [ ] **Step 5: Update direct construction sites and pass focused tests**

Update MainViewModel's direct SettingsManager construction, DefaultWorkoutSessionManager tests/fakes, ActiveSessionEngine test fixtures, SafeWord fixtures, and BLE manager fixtures to provide `UserProfileRepository`. Remove profile-owned writes from `PreferencesManager`; retain the old keys and snapshot-reading APIs.

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SettingsManagerTest*" --tests "*SettingsPreferencesManagerTest*" --tests "*EquipmentRackRepositoryTest*" --tests "*HealthBodyWeightSyncManagerTest*" --tests "*SafeWord*" --tests "*BleConnectionManagerTest*" --console=plain
```

Expected: PASS; A/B consumers change immediately and legacy values remain unchanged after profile setters.

- [ ] **Step 6: Commit active-profile consumer routing**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/SettingsPreferencesManagerTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepositoryTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManagerTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManagerTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePreferencesManager.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMRoutineFlowTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMEquipmentRackTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngineIntegrationTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManagerTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManagerTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListenerIosAudioTapGuardTest.kt
git commit -m "refactor: route training settings through active profile"
```

### Task 6: Gate live VBT behavior with the profile-owned master toggle

**Files:**
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/VbtEnabledRuntimeTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinatorEventTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngineIntegrationTest.kt`

**Interfaces:**
- Consumes: `SettingsManager.userPreferences.value.vbtEnabled` and subordinate active-profile VBT values.
- Produces: `WorkoutCoordinator.updateVbtSettings(vbtEnabled, thresholdPercent, autoEnd)` and an observable runtime VBT-enabled state for the UI plan.

- [ ] **Step 1: Write failing disabled/re-enabled behavior tests**

```kotlin
@Test
fun disabledVbtKeepsMetricsButSuppressesLiveEnforcementAndFeedback() = runTest {
    engine.applyProfileVbt(
        VbtPreferences(
            enabled = false,
            velocityLossThresholdPercent = 20,
            autoEndOnVelocityLoss = true,
            verbalEncouragementEnabled = true,
        ),
    )
    engine.processWorkingRep(velocity = 1.0f)
    engine.processWorkingRep(velocity = 0.70f)

    assertEquals(listOf(1.0f, 0.70f), engine.recordedRepVelocities)
    assertFalse(engine.sessionEnded)
    assertTrue(engine.events.none { it is WorkoutEvent.VelocityLossThresholdReached })
    assertTrue(fakeAudio.feedbackRequests.isEmpty())
}

@Test
fun reEnablingRestoresUnchangedSubordinateConfiguration() = runTest {
    val configured = VbtPreferences(enabled = false, velocityLossThresholdPercent = 25, autoEndOnVelocityLoss = true)
    engine.applyProfileVbt(configured)
    engine.applyProfileVbt(configured.copy(enabled = true))

    assertEquals(25f, coordinator.configuredVelocityLossThresholdPercent)
    assertTrue(coordinator.autoEndOnVelocityLoss)
    assertTrue(coordinator.vbtEnabled)
}
```

- [ ] **Step 2: Run the focused VBT tests and verify disabled behavior fails**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*VbtEnabledRuntimeTest*" --tests "*WorkoutCoordinatorEventTest*" --tests "*ActiveSessionEngineIntegrationTest*" --console=plain
```

Expected: FAIL because the coordinator has no master toggle and still emits live velocity-loss events.

- [ ] **Step 3: Add the coordinator master without mutating subordinate values**

```kotlin
class WorkoutCoordinator(
    vbtEnabled: Boolean = true,
    velocityLossThresholdPercent: Float = 20f,
    autoEndOnVelocityLoss: Boolean = false,
) {
    private val _vbtEnabled = MutableStateFlow(vbtEnabled)
    internal val vbtEnabled: Boolean get() = _vbtEnabled.value
    internal var configuredVelocityLossThresholdPercent: Float = velocityLossThresholdPercent
        private set
    private val _autoEndOnVelocityLoss = MutableStateFlow(autoEndOnVelocityLoss)
    internal val autoEndOnVelocityLoss: Boolean get() = _autoEndOnVelocityLoss.value

    val biomechanicsEngine = BiomechanicsEngine(velocityLossThresholdPercent)

    internal fun updateVbtSettings(
        vbtEnabled: Boolean,
        thresholdPercent: Float,
        autoEnd: Boolean,
    ) {
        require(thresholdPercent in 10f..50f)
        _vbtEnabled.value = vbtEnabled
        configuredVelocityLossThresholdPercent = thresholdPercent
        biomechanicsEngine.updateVelocityLossThresholdPercent(thresholdPercent)
        _autoEndOnVelocityLoss.value = autoEnd
    }
}
```

At the existing velocity-loss decision point, return no live event when `vbtEnabled` is false. Do not clear baselines, stored rep velocities, assessment repositories, or history values.

- [ ] **Step 4: Gate engine evaluation, auto-end, indicators, and failure speech**

```kotlin
if (coordinator._repCount.value.isWarmupComplete && coordinator.vbtEnabled) {
    checkVelocityThreshold()
}

private suspend fun checkVelocityThreshold() {
    if (!coordinator.vbtEnabled) return
    val latestResult = coordinator.biomechanicsEngine.latestRepResult.value ?: return
    val velocity = latestResult.velocity
    if (velocity.shouldStopSet) {
        consecutiveThresholdReps++
        if (!velocityThresholdAlertEmitted) {
            velocityThresholdAlertEmitted = true
            coordinator._hapticEvents.emit(HapticEvent.VELOCITY_THRESHOLD_REACHED)
            val prefs = settingsManager.userPreferences.value
            if (prefs.beepsEnabled && prefs.verbalEncouragementEnabled) {
                val effectiveVulgar = prefs.vulgarModeEnabled && prefs.adultsOnlyConfirmed
                val effectiveDominatrix = effectiveVulgar &&
                    prefs.dominatrixModeUnlocked && prefs.dominatrixModeActive
                coordinator._hapticEvents.emit(
                    HapticEvent.VERBAL_ENCOURAGEMENT(
                        vulgarTier = prefs.vulgarTier,
                        dominatrixMode = effectiveDominatrix,
                        vulgarMode = effectiveVulgar,
                    ),
                )
            }
        }
        if (consecutiveThresholdReps >= 2 && coordinator.autoEndOnVelocityLoss) {
            handleSetCompletion()
        }
    } else {
        consecutiveThresholdReps = 0
    }
}
```

Continue calling the biomechanics/history recorder before `evaluateLiveVbt`. Feed `vbtEnabled`, threshold, and auto-end together from the active SettingsManager collector so a profile switch updates one coherent runtime snapshot. Expose `vbtEnabled` in the existing workout UI state for the next step.

- [ ] **Step 5: Thread VBT state through the active workout UI**

Thread the master through active workout UI and hide only live VBT interpretation:

```kotlin
// WorkoutUiState
val vbtEnabled: Boolean = true,

// ActiveWorkoutScreen -> WorkoutUiState
vbtEnabled = userPreferences.vbtEnabled,

// WorkoutTab -> WorkoutHud -> StatsPage
vbtEnabled = state.vbtEnabled,
```

Add `vbtEnabled: Boolean = true` to the inner `WorkoutTab`, `WorkoutHud`, and `StatsPage` signatures. Keep raw MCV/peak values visible, but remove zone color/label and velocity-loss threshold/reps-left feedback while disabled:

```kotlin
val zColor = if (vbtEnabled) {
    velocityZoneColor(latestBiomechanicsResult.velocity.zone)
} else {
    MaterialTheme.colorScheme.onSurface
}

if (vbtEnabled) {
    StatColumn(
        label = "Zone",
        value = velocityZoneLabel(latestBiomechanicsResult.velocity.zone),
        color = zColor,
    )
}

val vloss = latestBiomechanicsResult.velocity.velocityLossPercent
if (vbtEnabled && vloss != null) {
    VelocityLossIndicator(
        currentLossPercent = vloss,
        thresholdPercent = velocityLossThresholdPercent,
        shouldStopSet = latestBiomechanicsResult.velocity.shouldStopSet,
    )
    latestBiomechanicsResult.velocity.estimatedRepsRemaining?.let { repsRemaining ->
        StatColumn(
            label = "Est. Reps Left",
            value = "$repsRemaining",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

Add a source-contract assertion to `VbtEnabledRuntimeTest` that `WorkoutHud.kt` contains `if (vbtEnabled && vloss != null)` and that `BiomechanicsHistoryCard.kt` contains no `vbtEnabled` gate. This keeps historical velocity data visible.

- [ ] **Step 6: Pass VBT regression tests**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*VbtEnabledRuntimeTest*" --tests "*WorkoutCoordinatorEventTest*" --tests "*ActiveSessionEngineIntegrationTest*" --console=plain
```

Expected: PASS for disabled suppression, preserved metrics, unchanged subordinate values, and restored behavior after re-enable.

- [ ] **Step 7: Commit runtime VBT gating**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/VbtEnabledRuntimeTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinatorEventTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngineIntegrationTest.kt
git commit -m "feat: gate live VBT by active profile"
```

### Task 7: Harden profile deletion and journal local-key cleanup

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProfileDeletionMergePolicy.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/ProfileDeletionMergePolicyTest.kt`
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightUserProfileRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt`

**Interfaces:**
- Consumes: existing profile-inclusive PR/badge unique indexes and the Task 3 safety store.
- Consumes: Task 3's retryable `PendingProfileLocalCleanup` processor.
- Produces: deterministic PR/badge merge functions and deletion-side cleanup journaling.

- [ ] **Step 1: Write failing collision and cleanup-retry tests**

```kotlin
@Test
fun deleteMergesOverlappingPrAndBadgeKeysBeforeReassignment() = runTest {
    insertWeightPr(profile = "default", weight = 80.0, oneRepMax = 90.0, achievedAt = 10)
    insertWeightPr(profile = "source", weight = 85.0, oneRepMax = 92.0, achievedAt = 20)
    insertBadge(profile = "default", badgeId = "first_workout", earnedAt = 30, celebratedAt = null)
    insertBadge(profile = "source", badgeId = "first_workout", earnedAt = 20, celebratedAt = 40)

    assertTrue(repository.deleteProfile("source"))

    val pr = queries.selectPR("exercise", "OldSchool", "MAX_WEIGHT", "COMBINED", "default").executeAsOne()
    assertEquals(85.0, pr.weight)
    val badge = queries.selectEarnedBadgeById("first_workout", "default").executeAsOne()
    assertEquals(20, badge.earnedAt)
    assertEquals(40, badge.celebratedAt)
    assertEquals(0, queries.selectPendingProfileLocalCleanup().executeAsList().size)
}

@Test
fun failedSettingsCleanupStaysQueuedUntilStartupRetry() = runTest {
    safetyStore.failDeletes = true
    repository.deleteProfile("source")
    assertEquals(listOf("source"), pendingCleanupIds())
    assertNull(repository.allProfiles.value.find { it.id == "source" })

    safetyStore.failDeletes = false
    repository.retryPendingLocalCleanup()
    assertTrue(pendingCleanupIds().isEmpty())
    assertNull(settings.getStringOrNull("profile_source_safe_word"))
}

@Test
fun deletingInactiveProfilePreservesActiveDatabaseAndReadyContext() = runTest {
    val activeBefore = assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value)
    createInactiveProfile("source")

    assertTrue(repository.deleteProfile("source"))

    assertEquals(activeBefore.profile.id, queries.getActiveProfile().executeAsOne().id)
    assertEquals(activeBefore.profile.id, assertIs<ActiveProfileContext.Ready>(repository.activeProfileContext.value).profile.id)
}
```

- [ ] **Step 2: Run deletion tests and observe the current unique-index failure**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileDeletionMergePolicyTest*" --tests "*SqlDelightUserProfileRepositoryTest*" --console=plain
```

Expected: FAIL with a unique constraint violation from `reassignPRProfile` or `reassignBadgeProfile`, and no cleanup journal behavior.

- [ ] **Step 3: Add deterministic pure merge policies**

```kotlin
data class PersonalRecordMergeKey(
    val exerciseId: String,
    val workoutMode: String,
    val prType: String,
    val phase: String,
)

fun choosePersonalRecordWinner(a: PersonalRecord, b: PersonalRecord): PersonalRecord {
    val comparator = if (a.prType == "MAX_VOLUME") {
        compareBy<PersonalRecord>({ it.volume }, { it.weight }, { it.achievedAt })
    } else {
        compareBy<PersonalRecord>({ it.weight }, { it.oneRepMax }, { it.achievedAt })
    }
    return maxOf(a, b, comparator)
}

data class EarnedBadgeMerge(
    val earnedAt: Long,
    val celebratedAt: Long?,
)

fun mergeEarnedBadges(a: EarnedBadge, b: EarnedBadge) = EarnedBadgeMerge(
    earnedAt = minOf(a.earnedAt, b.earnedAt),
    celebratedAt = listOfNotNull(a.celebratedAt, b.celebratedAt).minOrNull(),
)
```

Test MAX_WEIGHT tie-breaks `weight -> oneRepMax -> achievedAt`, MAX_VOLUME tie-breaks `volume -> weight -> achievedAt`, earliest badge earned time, and preservation of either celebration.

- [ ] **Step 4: Add exact merge-support SQL queries**

```sql
deletePersonalRecordById:
DELETE FROM PersonalRecord WHERE id = ?;

updatePersonalRecordForProfileMerge:
UPDATE PersonalRecord
SET exerciseName = :exerciseName, weight = :weight, reps = :reps, oneRepMax = :oneRepMax,
    achievedAt = :achievedAt, volume = :volume, updatedAt = :updatedAt, serverId = :serverId,
    deletedAt = :deletedAt, cable_count = :cableCount, uuid = :uuid
WHERE id = :targetId;

deleteEarnedBadgeById:
DELETE FROM EarnedBadge WHERE id = ?;

updateEarnedBadgeForProfileMerge:
UPDATE EarnedBadge
SET earnedAt = :earnedAt, celebratedAt = :celebratedAt, updatedAt = :updatedAt,
    serverId = :serverId, deletedAt = :deletedAt
WHERE id = :targetId;
```

Use existing `selectAllRecords(profileId)` and `selectAllEarnedBadges(profileId)` to build source/target maps. Delete the duplicate source row before copying a source winner's UUID into the retained target row.

- [ ] **Step 5: Make deletion one SQL transaction plus post-commit cleanup**

Run deletion under `profileContextMutex`. Capture `priorReady` and `priorActiveProfileId` before changing anything. If the deleted profile is active, emit `ActiveProfileContext.Switching("default")` before the SQL transaction; otherwise keep the current Ready context mounted. On SQL failure, republish `priorReady` and rethrow. On commit, refresh identity flows and publish `Ready` for `postDeleteActiveProfileId`, which is Default only for an active-profile deletion and otherwise remains `priorActiveProfileId`, before best-effort local-key cleanup.

```kotlin
val priorReady = _activeProfileContext.value as? ActiveProfileContext.Ready
    ?: throw ProfileContextUnavailableException()
val priorActiveProfileId = priorReady.profile.id
val wasActive = priorActiveProfileId == id
val postDeleteActiveProfileId = if (wasActive) targetProfileId else priorActiveProfileId
if (wasActive) _activeProfileContext.value = ActiveProfileContext.Switching(targetProfileId)

database.transaction {
    mergePersonalRecordCollisions(sourceProfileId = id, targetProfileId = targetProfileId)
    mergeBadgeCollisions(sourceProfileId = id, targetProfileId = targetProfileId)
    queries.reassignRoutineProfile(targetProfileId, id)
    queries.reassignSessionProfile(targetProfileId, id)
    queries.reassignPRProfile(targetProfileId, id)
    queries.reassignTrainingCycleProfile(targetProfileId, id)
    queries.reassignBadgeProfile(targetProfileId, id)
    queries.reassignStreakProfile(targetProfileId, id)
    queries.reassignAssessmentResultProfile(targetProfileId, id)
    queries.reassignProgressionProfile(targetProfileId, id)
    queries.deleteGamificationStatsByProfile(id)
    queries.deleteGamificationStatsByProfile(targetProfileId)
    queries.deleteRpgAttributesByProfile(id)
    queries.deleteRpgAttributesByProfile(targetProfileId)
    queries.deleteProfilePreferences(id)
    queries.enqueueProfileLocalCleanup(id, currentTimeMillis())
    queries.deleteProfile(id)
    if (wasActive) queries.setActiveProfile("default")
}
refreshProfilesSync()
publishReadyContext(postDeleteActiveProfileId)
retryPendingLocalCleanup(id)
```

Call Task 3's `retryPendingLocalCleanup(id)` after commit; it catches non-cancellation failures and leaves the row queued, and Task 4 already calls its all-row form during required startup migration. Inject the existing `GamificationRepository`; do not construct `SqlDelightGamificationRepository` inside `deleteProfile`.

- [ ] **Step 6: Pass collision, default guard, and cleanup tests**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileDeletionMergePolicyTest*" --tests "*SqlDelightUserProfileRepositoryTest*" --tests "*ProfilePreferencesMigrationTest*" --console=plain
```

Expected: PASS; the default profile remains undeletable, overlapping rows merge without constraint errors, SQL deletion commits even when Settings cleanup fails, and startup retry drains the journal.

- [ ] **Step 7: Commit deletion hardening**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProfileDeletionMergePolicy.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/ProfileDeletionMergePolicyTest.kt shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightUserProfileRepositoryTest.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt
git commit -m "fix: merge profile data safely on deletion"
```

### Task 8: Add profile preferences to backup schema version 5

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt`
- Modify: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt`
- Modify: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt`
- Modify platform DI binding files returned by `rg -n "BaseDataBackupManager|DataBackupManager\(" shared/src/androidMain shared/src/iosMain`.
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/BackupSerializationTest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/BackupJsonNavigatorTest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/DataBackupManagerRoutineNameTest.kt`

**Interfaces:**
- Consumes: typed profile preferences and repository updates from Task 3.
- Produces: backup schema v5 with per-profile sections and deterministic v1-v4 rack compatibility.

- [ ] **Step 1: Write failing privacy, round-trip, and legacy-presence tests**

```kotlin
@Test
fun v5ExportsTypedProfileValuesWithoutLocalSafetyOrSyncMetadata() {
    val encoded = json.encodeToString(sampleV5Backup())
    assertContains(encoded, "\"profilePreferences\"")
    assertContains(encoded, "\"bodyWeightKg\":82.0")
    assertFalse(encoded.contains("safeWord"))
    assertFalse(encoded.contains("Calibrated"))
    assertFalse(encoded.contains("adultsOnly"))
    assertFalse(encoded.contains("localGeneration"))
    assertFalse(encoded.contains("serverRevision"))
    assertFalse(encoded.contains("dirty"))
    assertFalse(encoded.contains("equipmentRackItems"))
}

@Test
fun v4RackPresenceDistinguishesMissingEmptyAndNonEmpty() = runTest {
    importV4(dataWithoutRackField())
    assertEquals(listOf("existing"), rackIds("default"))
    importV4(dataWithRackField("[]"))
    assertEquals(emptyList(), rackIds("default"))
    importV4(dataWithRackField("[{\"id\":\"legacy\",\"name\":\"Vest\",\"weightKg\":5.0}]"))
    assertEquals(listOf("legacy"), rackIds("default"))
}

@Test
fun v5ExportOmitsInvalidSectionInsteadOfSerializingTypedFallback() = runTest {
    writeRawWorkoutJson(profileId = "default", raw = "{not-json")

    val entry = exportV5().data.profilePreferences.single { it.profileId == "default" }

    assertNull(entry.workout)
    assertNotNull(entry.core)
    assertNotNull(entry.rack)
    assertNotNull(entry.led)
    assertNotNull(entry.vbt)
}
```

Add buffered-versus-streaming equality, A/B profile round trip, invalid-section omission on export, malformed-one-section skip on import, v1-v3 no-rack no-op, and target-server-revision-retained/dirty-on-import cases.

- [ ] **Step 2: Run backup tests and verify v4 is still current**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*BackupSerializationTest*" --tests "*BackupJsonNavigatorTest*" --tests "*DataBackupManager*" --console=plain
```

Expected: FAIL because `CURRENT_BACKUP_VERSION` is 4 and no profile preference payload exists.

- [ ] **Step 3: Define the v5 wire model with independently decodable section objects**

```kotlin
const val CURRENT_BACKUP_VERSION: Int = 5

@Serializable
data class ProfilePreferencesBackup(
    val profileId: String,
    val core: JsonElement? = null,
    val rack: JsonElement? = null,
    val workout: JsonElement? = null,
    val led: JsonElement? = null,
    val vbt: JsonElement? = null,
)

@Serializable
data class BackupContent(
    val workoutSessions: List<WorkoutSessionBackup> = emptyList(),
    val metricSamples: List<MetricSampleBackup> = emptyList(),
    val routines: List<RoutineBackup> = emptyList(),
    val routineExercises: List<RoutineExerciseBackup> = emptyList(),
    val supersets: List<SupersetBackup> = emptyList(),
    val personalRecords: List<PersonalRecordBackup> = emptyList(),
    val trainingCycles: List<TrainingCycleBackup> = emptyList(),
    val cycleDays: List<CycleDayBackup> = emptyList(),
    val cycleProgress: List<CycleProgressBackup> = emptyList(),
    val cycleProgressions: List<CycleProgressionBackup> = emptyList(),
    val plannedSets: List<PlannedSetBackup> = emptyList(),
    val completedSets: List<CompletedSetBackup> = emptyList(),
    val progressionEvents: List<ProgressionEventBackup> = emptyList(),
    val earnedBadges: List<EarnedBadgeBackup> = emptyList(),
    val streakHistory: List<StreakHistoryBackup> = emptyList(),
    val gamificationStats: GamificationStatsBackup? = null,
    val userProfiles: List<UserProfileBackup> = emptyList(),
    val profilePreferences: List<ProfilePreferencesBackup> = emptyList(),
    @SerialName("equipmentRackItems")
    val legacyEquipmentRackItems: List<RackItem>? = null,
    val sessionNotes: List<SessionNotesBackup> = emptyList(),
    val routineGroups: List<RoutineGroupBackup> = emptyList(),
)
```

`JsonElement` keeps each preference section independently decodable even when a corrupt file supplies the wrong JSON kind. `decodeBackupSection` requires an object and returns null for an invalid kind, unsupported version, or failed typed validation. Update the privacy summary text to state that sync metadata, voice phrase, calibration, and consent are excluded.

- [ ] **Step 4: Export the same section values in buffered and streaming paths**

```kotlin
private inline fun <reified T> Json.encodeValidBackupSection(
    section: ProfilePreferenceSection<T>,
): JsonElement? = if (section.validity is ProfilePreferenceValidity.Valid) {
    encodeToJsonElement(section.value)
} else {
    null
}

private fun UserProfilePreferences.toBackup(json: Json) = ProfilePreferencesBackup(
    profileId = profileId,
    core = json.encodeValidBackupSection(core),
    rack = json.encodeValidBackupSection(rack),
    workout = json.encodeValidBackupSection(workout),
    led = json.encodeValidBackupSection(led),
    vbt = json.encodeValidBackupSection(vbt),
)
```

Inject `ProfilePreferencesRepository` into `BaseDataBackupManager`, load preferences for every exported `UserProfile`, and populate `profilePreferences`. Invalid/unsupported stored sections must serialize as absent/null rather than their typed fallback values; valid sibling sections still export. In the streaming writer, emit `profilePreferences` using the same serializer and omit `equipmentRackItems` for v5. Never read `ProfileLocalSafetyStore` during export.

Set `explicitNulls = false` on the backup `Json` instance while retaining `ignoreUnknownKeys = true` and `encodeDefaults = true`; this makes the nullable legacy rack field absent in a v5 buffered export. The streaming writer must omit that property explicitly.

- [ ] **Step 5: Import profiles first, then restore sections as local edits**

Move the existing user-profile import block to the start of the database import transaction. For every profile represented by the backup—newly inserted or already present—call `queries.insertDefaultProfilePreferences(profile.id, 1)` in that transaction so a preference row exists without inheriting the active profile. After that entity transaction succeeds, process each v5 `ProfilePreferencesBackup` only when its `profileId` now exists:

```kotlin
private inline fun <reified T> decodeBackupSection(
    profileId: String,
    section: String,
    element: JsonElement,
    validate: (T) -> List<String>,
    onInvalid: (profileId: String, section: String) -> Unit,
): T? = runCatching {
    val value = json.decodeFromJsonElement<T>(element.jsonObject)
    require(validate(value).isEmpty())
    value
}.onFailure {
    Logger.w { "Backup import skipped invalid profile preference section profile=$profileId section=$section" }
    onInvalid(profileId, section)
}.getOrNull()

private suspend fun importProfilePreferences(
    entry: ProfilePreferencesBackup,
    now: Long,
    onInvalid: (profileId: String, section: String) -> Unit,
) {
    entry.core?.let { decodeBackupSection(entry.profileId, "core", it, ProfilePreferencesValidator::core, onInvalid) }
        ?.let { profilePreferencesRepository.updateCore(entry.profileId, it, now) }
    entry.rack?.let { decodeBackupSection(entry.profileId, "rack", it, ProfilePreferencesValidator::rack, onInvalid) }
        ?.let { profilePreferencesRepository.updateRack(entry.profileId, it, now) }
    entry.workout?.let { decodeBackupSection(entry.profileId, "workout", it, ProfilePreferencesValidator::workout, onInvalid) }
        ?.let { profilePreferencesRepository.updateWorkout(entry.profileId, it, now) }
    entry.led?.let { decodeBackupSection(entry.profileId, "led", it, ProfilePreferencesValidator::led, onInvalid) }
        ?.let { profilePreferencesRepository.updateLed(entry.profileId, it, now) }
    entry.vbt?.let { decodeBackupSection(entry.profileId, "vbt", it, ProfilePreferencesValidator::vbt, onInvalid) }
        ?.let { profilePreferencesRepository.updateVbt(entry.profileId, it, now) }
}
```

Each normal update increments local generation, marks dirty, and retains the target row's server revision. Count a malformed section in `entitiesWithErrors`, log profile ID plus section name, and continue with other sections and workout entities.

For legacy imports, implement exactly:

```kotlin
val profileIdsAfterImport = queries.selectAllUserProfileIds().executeAsList().toSet()
val targetProfileIds = backup.data.userProfiles
    .map { it.id }
    .filter { it in profileIdsAfterImport }
    .distinct()

when {
    backup.version < 4 -> Unit
    backup.version == 4 && backup.data.legacyEquipmentRackItems == null -> Unit
    backup.version == 4 -> {
        val items = backup.data.legacyEquipmentRackItems.orEmpty()
        targetProfileIds.ifEmpty {
            listOf(queries.getActiveProfile().executeAsOneOrNull()?.id ?: "default")
        }
            .forEach { profileId ->
                val current = profilePreferencesRepository.get(profileId).rack.value
                val mergedItems = if (items.isEmpty()) {
                    emptyList()
                } else {
                    val importedById = items.filter { it.id.isNotBlank() }.distinctBy { it.id }.associateBy { it.id }
                    val existingIds = current.items.map { it.id }.toSet()
                    current.items.map { importedById[it.id] ?: it } + importedById.values.filterNot { it.id in existingIds }
                }
                profilePreferencesRepository.updateRack(profileId, current.copy(items = mergedItems), now)
            }
    }
    else -> Unit
}
```

The streaming parser must track whether the `equipmentRackItems` property token was present; absent and present-empty are distinct.

- [ ] **Step 6: Pass backup compatibility and parity tests**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*BackupSerializationTest*" --tests "*BackupJsonNavigatorTest*" --tests "*DataBackupManager*" --console=plain
```

Expected: PASS for identical buffered/streaming v5 values, local-only exclusions, A/B round trip, dirty/revision semantics, malformed-section isolation, and v1-v4 rack behavior.

- [ ] **Step 7: Commit backup v5**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/util/BackupSerializationTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/BackupJsonNavigatorTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/DataBackupManagerRoutineNameTest.kt shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt
git commit -m "feat: back up profile preferences in schema v5"
```

### Task 9: Wire dependency injection and verify the data foundation

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt`
- Modify any platform constructor binding identified in Tasks 4, 5, and 8.

**Interfaces:**
- Consumes: every implementation in this plan.
- Produces: a verified Android/iOS Koin graph and the stable foundation required by both later plans.

- [ ] **Step 1: Add a failing Koin graph test for every new boundary**

```kotlin
@Test
fun profilePreferenceFoundationResolves() {
    startTestKoin().use { koin ->
        assertIs<SqlDelightProfilePreferencesRepository>(koin.get<ProfilePreferencesRepository>())
        assertIs<SettingsProfileLocalSafetyStore>(koin.get<ProfileLocalSafetyStore>())
        assertNotNull(koin.get<LegacyProfilePreferencesReader>())
        assertNotNull(koin.get<UserProfileRepository>())
        assertNotNull(koin.get<MigrationManager>())
        assertNotNull(koin.get<SettingsManager>())
        assertNotNull(koin.get<DataBackupManager>())
    }
}
```

- [ ] **Step 2: Run Koin verification and confirm missing bindings**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*KoinModuleVerifyTest*" --console=plain
```

Expected: FAIL only for bindings introduced after Task 3, such as `LegacyProfilePreferencesReader`, refactored manager/health/safety consumers, or changed backup constructor dependencies. The focused preference stores and expanded `UserProfileRepository` binding already compile from Task 3.

- [ ] **Step 3: Complete the remaining graph bindings**

```kotlin
single<LegacyProfilePreferencesReader> { SettingsLegacyProfilePreferencesReader(get(), get()) }
single { SettingsManager(globalPreferences = get(), userProfileRepository = get(), bleRepository = get(), scope = get()) }
single<EquipmentRackRepository> { ProfileEquipmentRackRepository(get()) }
```

Retain and verify Task 3's focused-store and expanded `UserProfileRepository` registrations; do not add duplicate definitions. Update `MigrationManager`, health import, safe-word, backup, MainViewModel, and platform host bindings with their new arguments. Avoid a dependency cycle: `UserProfileRepository` may depend on focused stores and gamification, but neither focused store may depend on `UserProfileRepository`.

- [ ] **Step 4: Run focused and full shared verification**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface :shared:verifyCommonMainVitruvianDatabaseMigration :shared:validateSchemaManifest --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfilePreferences*" --tests "*SqlDelightUserProfileRepositoryTest*" --tests "*SettingsManagerTest*" --tests "*VbtEnabledRuntimeTest*" --tests "*Backup*" --tests "*KoinModuleVerifyTest*" --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --continue --console=plain
```

Expected: every command exits 0. The full shared suite reports no profile leakage or migration parity regression.

- [ ] **Step 5: Compile both platforms and assemble Android**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinIosArm64 :androidApp:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL with no missing platform constructor or serializer.

- [ ] **Step 6: Audit ownership and privacy mechanically**

Run:

```powershell
rg -n "set(BodyWeightKg|WeightUnit|WeightIncrement|ColorScheme|VelocityLossThreshold|AutoEndOnVelocityLoss|SafeWord|AdultsOnlyConfirmed)" shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt
rg -n "safeWord|safe_word|adultsOnly|localGeneration|serverRevision|dirty" shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt
git diff --check
```

Expected: the first command finds only deprecated legacy-migration read compatibility or interface declarations with no production profile write path; the second finds only explicit exclusion/privacy assertions and no exported fields; `git diff --check` prints nothing.

- [ ] **Step 7: Commit final wiring**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt
git commit -m "chore: wire profile preference foundation"
```

## Completion Gate

- Schema/migration/reconciliation all report version 43.
- Every existing profile has `legacy_migration_version = 1` after the awaited migration; a new profile starts with defaults and `vbtEnabled = true`.
- A/B tests prove every profile-owned consumer follows the active context without restart.
- A migration failure cannot expose root navigation; Retry succeeds without overwriting completed rows.
- Profile deletion handles overlapping PR/badge keys and eventually deletes local safety keys.
- Backup v5 round-trips every syncable section while excluding safety and sync metadata.
- The working tree contains only reviewed changes for this plan before either dependent plan begins.

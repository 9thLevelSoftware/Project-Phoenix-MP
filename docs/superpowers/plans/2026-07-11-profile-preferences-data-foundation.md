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
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/MigrationManagerTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt`
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
    settings.putString("exercise_defaults_broken", "{broken")

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

Register the reader and expanded migration constructor in the same task that introduces them so Android/iOS startup never resolves a partial graph:

```kotlin
single<LegacyProfilePreferencesReader> { SettingsLegacyProfilePreferencesReader(get(), get()) }
single {
    MigrationManager(
        database = get(),
        userProfileRepository = get(),
        gamificationRepository = get(),
        settings = get(),
        profilePreferencesRepository = get(),
        profileLocalSafetyStore = get(),
        legacyProfilePreferencesReader = get(),
    )
}
```

Replace the existing `MigrationManager` binding; do not add a duplicate. Task 9 verifies this binding with the complete final graph.

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
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/LegacyProfilePreferencesReader.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/preferences/LegacyProfilePreferencesReaderTest.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/MigrationManagerTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/ProfilePreferencesMigrationTest.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt shared/src/androidMain/kotlin/com/devil/phoenixproject/AndroidAppHost.kt shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt
git commit -m "feat: migrate legacy profile preferences at startup"
```

### Task 5: Route compatibility managers and direct consumers through the active profile

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/RequiredMigrationGate.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManagerTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManager.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManagerTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManager.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManagerTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModelTest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/e2e/WorkoutFlowE2ETest.kt`
- Modify affected tests and fakes found with `rg -n "PreferencesManager|SettingsManager|EquipmentRackRepository" shared/src/commonTest shared/src/androidHostTest`.

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
    rackRepository.saveItems(listOf(rackItem("a-item")))
    setActive("b")
    rackRepository.saveItems(listOf(rackItem("b-item")))

    assertEquals(listOf("b-item"), rackRepository.getItems().map { it.id })
    setActive("a")
    assertEquals(listOf("a-item"), rackRepository.getItems().map { it.id })
}

@Test
fun healthImportWaitsForRequiredMigrationAndWritesActiveCore() = runTest {
    val result = async { manager.syncLatestFromConnectedPlatform() }
    runCurrent()
    assertEquals(0f, activeCore().bodyWeightKg)
    migrationGate.state.value = RequiredMigrationState.Ready
    assertIs<HealthBodyWeightSyncResult.Synced>(result.await())
    assertEquals(81f, activeCore().bodyWeightKg)
}
```

Add tests that voice stop is effectively false when intent is true but the active profile lacks a calibrated phrase, Just Lift/default exercise settings change on profile switch, active-profile setter cascades preserve the existing adult-consent invariants, and BLE reconnect/profile switch receives the active LED scheme. Task 6 owns the final runtime assertion that persisted adult-mode intent remains ineffective without local confirmation.

Add this start-gate regression:

```kotlin
@Test
fun workoutStartIsRejectedWhileProfileContextIsSwitching() = runTest {
    profileRepository.recoverPendingProfileTransitionForStartup()
    engine.startWorkout()
    advanceUntilIdle()

    assertIs<WorkoutState.Idle>(engine.coordinator.workoutState.value)
    assertEquals(0, fakeBleRepository.workoutParameters.size)
}
```

- [ ] **Step 2: Run the consumer tests and confirm legacy behavior fails isolation**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SettingsManagerTest*" --tests "*EquipmentRackRepositoryTest*" --tests "*HealthBodyWeightSyncManagerTest*" --tests "*SafeWordDetectionManagerTest*" --tests "*BleConnectionManagerTest*" --tests "*ActiveSessionEngineIntegrationTest*" --console=plain
```

Expected: FAIL because current setters and rack/body-weight consumers use global `PreferencesManager`.

- [ ] **Step 3: Make `SettingsManager` a two-source compatibility facade**

```kotlin
class SettingsManager(
    private val globalPreferences: PreferencesManager,
    private val userProfileRepository: UserProfileRepository,
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

Implement each profile setter as a serialized mutation of the active typed section. Capture the originating profile ID before launching, re-read the latest section after taking its section mutex, and verify the same profile is still active before writing. This prevents both an A-originated action from landing in B and queued same-section setters from overwriting one another with an old whole-section snapshot. Keep theme/video/language/backup/BLE-compatibility/backfill setters delegated to `globalPreferences`. Keep legacy profile getters only for migration; mark them `@Deprecated("Legacy migration read only")` where call-site churn permits. Hardware LED application belongs to `BleConnectionManager`, so `SettingsManager` no longer takes `BleRepository`.

```kotlin
private val coreUpdates = Mutex()
private val workoutUpdates = Mutex()
private val ledUpdates = Mutex()
private val vbtAndSafetyUpdates = Mutex()

private fun ready(): ActiveProfileContext.Ready =
    userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
        ?: throw ProfileContextUnavailableException()

private fun readyFor(expectedId: String): ActiveProfileContext.Ready {
    val current = ready()
    if (current.profile.id != expectedId) {
        throw StaleProfileContextException(expectedId, current.profile.id)
    }
    return current
}

private fun <T> updateSection(
    mutex: Mutex,
    read: (ActiveProfileContext.Ready) -> T,
    write: suspend (String, T) -> Unit,
    transform: (ActiveProfileContext.Ready, T) -> T?,
) {
    val expectedId = (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)
        ?.profile?.id
        ?: run {
            Logger.w { "Profile preference update ignored while switching" }
            return
        }
    scope.launch {
        try {
            mutex.withLock {
                val current = readyFor(expectedId)
                val next = transform(current, read(current)) ?: return@withLock
                write(expectedId, next)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProfileContextUnavailableException) {
            Logger.w(error) { "Profile preference update skipped while switching" }
        } catch (error: StaleProfileContextException) {
            Logger.w(error) { "Profile preference update skipped after profile switch" }
        }
    }
}

private fun updateCore(transform: (CoreProfilePreferences) -> CoreProfilePreferences) =
    updateSection(coreUpdates, { it.preferences.core.value }, userProfileRepository::updateCore) { _, value -> transform(value) }

private fun updateWorkout(transform: (WorkoutPreferences) -> WorkoutPreferences) =
    updateSection(workoutUpdates, { it.preferences.workout.value }, userProfileRepository::updateWorkout) { _, value -> transform(value) }

private fun updateLed(transform: (LedPreferences) -> LedPreferences) =
    updateSection(ledUpdates, { it.preferences.led.value }, userProfileRepository::updateLed) { _, value -> transform(value) }

private fun updateVbt(transform: (ActiveProfileContext.Ready, VbtPreferences) -> VbtPreferences?) =
    updateSection(vbtAndSafetyUpdates, { it.preferences.vbt.value }, userProfileRepository::updateVbt, transform)

private fun updateSafety(transform: (ActiveProfileContext.Ready, ProfileLocalSafetyPreferences) -> ProfileLocalSafetyPreferences) =
    updateSection(vbtAndSafetyUpdates, { it.localSafety }, userProfileRepository::updateLocalSafety, transform)

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
fun setDefaultRoutineExerciseWeightPercentOfPR(value: Int) = updateWorkout { it.copy(defaultRoutineExerciseWeightPercentOfPR = value.coerceIn(50, 120)) }
fun setVoiceStopEnabled(value: Boolean) = updateWorkout { it.copy(voiceStopEnabled = value) }
fun setColorScheme(value: Int) = updateLed { it.copy(colorScheme = value) }
fun setDiscoModeUnlocked(value: Boolean) = updateLed { it.copy(discoModeUnlocked = value) }
fun setVbtEnabled(value: Boolean) = updateVbt { _, current -> current.copy(enabled = value) }
fun setVelocityLossThreshold(value: Int) = updateVbt { _, current -> current.copy(velocityLossThresholdPercent = value.coerceIn(10, 50)) }
fun setAutoEndOnVelocityLoss(value: Boolean) = updateVbt { _, current -> current.copy(autoEndOnVelocityLoss = value) }
fun setDefaultScalingBasis(value: ScalingBasis) = updateVbt { _, current -> current.copy(defaultScalingBasis = value) }
fun setVerbalEncouragementEnabled(value: Boolean) = updateVbt { _, current ->
    if (value) current.copy(verbalEncouragementEnabled = true)
    else current.copy(verbalEncouragementEnabled = false, vulgarModeEnabled = false, dominatrixModeActive = false)
}
fun setVulgarModeEnabled(value: Boolean) = updateVbt { context, current ->
    when {
        value && !context.localSafety.adultsOnlyPrompted -> null
        !value -> current.copy(vulgarModeEnabled = false, dominatrixModeActive = false)
        else -> current.copy(vulgarModeEnabled = true)
    }
}
fun setVulgarTier(value: VulgarTier) = updateVbt { _, current -> current.copy(vulgarTier = value) }
fun setDominatrixModeUnlocked(value: Boolean) = updateVbt { _, current -> current.copy(dominatrixModeUnlocked = value) }
fun setDominatrixModeActive(value: Boolean) = updateVbt { context, current ->
    if (value && (!current.dominatrixModeUnlocked || !current.vulgarModeEnabled || !context.localSafety.adultsOnlyConfirmed)) null
    else current.copy(dominatrixModeActive = value)
}
fun setSafeWord(value: String?) = updateSafety { _, current -> current.copy(safeWord = value) }
fun setSafeWordCalibrated(value: Boolean) = updateSafety { _, current -> current.copy(safeWordCalibrated = value) }
fun setAdultsOnlyConfirmed(value: Boolean) = updateSafety { _, current ->
    current.copy(adultsOnlyConfirmed = value, adultsOnlyPrompted = true)
}
fun isAdultsOnlyPrompted(): Boolean = ready().localSafety.adultsOnlyPrompted
fun setAdultsOnlyPrompted(value: Boolean) = updateSafety { _, current -> current.copy(adultsOnlyPrompted = value) }

fun confirmAdultsAndEnableVulgar() {
    val expectedId = (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)
        ?.profile?.id ?: return
    scope.launch {
        try {
            vbtAndSafetyUpdates.withLock {
                val before = readyFor(expectedId)
                userProfileRepository.updateLocalSafety(
                    expectedId,
                    before.localSafety.copy(adultsOnlyConfirmed = true, adultsOnlyPrompted = true),
                )
                val afterSafety = readyFor(expectedId)
                userProfileRepository.updateVbt(
                    expectedId,
                    afterSafety.preferences.vbt.value.copy(vulgarModeEnabled = true),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProfileContextUnavailableException) {
            Logger.w(error) { "Adult consent update stopped while switching" }
        } catch (error: StaleProfileContextException) {
            Logger.w(error) { "Adult consent update stopped after profile switch" }
        }
    }
}
```

Keep the existing clamp/cascade regression behavior in `SettingsManagerTest`: routine default percentage `50..120`; velocity threshold `10..50`; verbal-off clears vulgar and active Dominatrix; vulgar-off clears active Dominatrix; vulgar-on requires `adultsOnlyPrompted`; Dominatrix-on requires unlocked + vulgar intent + local confirmation; confirmation atomically implies prompted; prompted-only never changes confirmation. Disabling `vbtEnabled` preserves every subordinate VBT value. Expose `MainViewModel.confirmAdultsAndEnableVulgar`, add one `SettingsTab` callback for it, wire that callback in `NavGraph`, and replace the dialog's immediate confirmed → prompted → vulgar callback trio with the composite call. Add a queued/concurrent regression proving the composite never reads stale consent or enables profile B. The legacy `VerbalEncouragementPreferenceCascadeTest` remains unchanged as migration-source coverage; port the equivalent active-profile facade assertions into `SettingsManagerTest`.

- [ ] **Step 4: Make rack, body-weight, quick-start, safety, and LED consumers active-profile aware**

Use these concrete boundaries:

```kotlin
interface RequiredMigrationGate {
    val requiredMigrationState: StateFlow<RequiredMigrationState>
    suspend fun awaitRequiredMigrations()
}

class MigrationManager(/* existing required dependencies */) : RequiredMigrationGate {
    override val requiredMigrationState: StateFlow<RequiredMigrationState> = /* existing state */
    override suspend fun awaitRequiredMigrations() { /* existing implementation */ }
}

class ProfileEquipmentRackRepository(
    private val profiles: UserProfileRepository,
    private val scope: CoroutineScope,
) : EquipmentRackRepository {
    private val mutations = Mutex()

    private fun items(context: ActiveProfileContext): List<RackItem> =
        (context as? ActiveProfileContext.Ready)?.preferences?.rack?.value?.items.orEmpty()

    override val rackItems: StateFlow<List<RackItem>> = profiles.activeProfileContext
        .map(::items)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, items(profiles.activeProfileContext.value))

    private fun ready(): ActiveProfileContext.Ready =
        profiles.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: throw ProfileContextUnavailableException()

    private fun readyFor(expectedId: String): ActiveProfileContext.Ready {
        val current = ready()
        if (current.profile.id != expectedId) {
            throw StaleProfileContextException(expectedId, current.profile.id)
        }
        return current
    }

    private suspend fun mutate(
        expectedId: String,
        transform: (RackPreferences) -> RackPreferences,
    ) = mutations.withLock {
        val current = readyFor(expectedId)
        profiles.updateRack(expectedId, transform(current.preferences.rack.value))
    }

    override suspend fun getItems(): List<RackItem> = ready().preferences.rack.value.items

    override suspend fun saveItems(items: List<RackItem>) {
        val expectedId = ready().profile.id
        mutate(expectedId) { it.copy(items = items) }
    }

    override suspend fun upsert(item: RackItem) {
        val expectedId = ready().profile.id
        mutate(expectedId) { current ->
            val items = current.items.toMutableList()
            val index = items.indexOfFirst { it.id == item.id }
            if (index >= 0) items[index] = item else items += item
            current.copy(items = items)
        }
    }

    override suspend fun delete(id: String) {
        val expectedId = ready().profile.id
        mutate(expectedId) { current -> current.copy(items = current.items.filterNot { it.id == id }) }
    }

    override suspend fun resolveActiveItems(selection: ActiveRackSelection): List<RackItem> {
        val byId = getItems().filter { it.enabled }.associateBy { it.id }
        return selection.distinctItemIds.mapNotNull(byId::get)
    }

    fun close() = scope.cancel()
}

private suspend fun awaitReadyProfile(): ActiveProfileContext.Ready {
    requiredMigrationGate.awaitRequiredMigrations()
    return userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
        ?: throw ProfileContextUnavailableException()
}
```

Keep `SettingsEquipmentRackRepository` as the explicit legacy implementation, but replace its production `DataModule` binding with a lifecycle-cleaned singleton:

```kotlin
single<EquipmentRackRepository> {
    ProfileEquipmentRackRepository(
        profiles = get(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
} onClose { repository ->
    (repository as? ProfileEquipmentRackRepository)?.close()
}
```

Tests pass `backgroundScope` explicitly so no eager collector survives the test. Bind `single<RequiredMigrationGate> { get<MigrationManager>() }` in `DomainModule`. Replace `PreferencesManager` in `HealthBodyWeightSyncManager` with `RequiredMigrationGate`, and use named arguments in `SyncModule` so the changed same-arity constructor cannot silently swap dependencies.

Call `awaitReadyProfile()` at the start of the existing `syncLatestFromConnectedPlatform()` flow, use its profile ID instead of falling back to `"default"`, and replace the global write with:

```kotlin
userProfileRepository.updateCore(
    ready.profile.id,
    ready.preferences.core.value.copy(bodyWeightKg = sample.weightKg),
)
externalMeasurementRepository.upsertMeasurements(listOf(measurement))
```

Perform the core update before inserting `ExternalBodyMeasurement`, and convert `StaleProfileContextException` into `HealthBodyWeightSyncResult.Failed` so a concurrent switch never writes the new profile.

Change `SafeWordDetectionManager` to consume `ActiveProfileContext.Ready`: voice-stop intent, a nonblank local phrase, and local calibration must all agree before the listener starts. Because `SafeWordListener` is a final expect/actual class, keep the new common test at the factory boundary: a factory whose `create` body increments a counter then throws proves invalid effective state never invokes it and a fully valid active context does invoke it, without introducing a production listener abstraction solely for tests. Existing platform listener tests remain unchanged. Change `DefaultWorkoutSessionManager` and `ActiveSessionEngine` quick-start reads to `SettingsManager.userPreferences` or the ready workout section. Change MainViewModel's disco unlock write to `updateLed`.

Move hardware color application entirely into `BleConnectionManager`; `SettingsManager.setColorScheme` only persists the active LED section. Replace the connect-only snapshot with a combined collector so both reconnect and profile switch reapply the correct scheme:

```kotlin
scope.launch {
    try {
        combine(
            bleRepository.connectionState,
            settingsManager.userPreferences.map { it.colorScheme },
        ) { connection, color ->
            (connection is ConnectionState.Connected) to color
        }
            .distinctUntilChanged()
            .collect { (connected, color) ->
                bleRepository.setLastColorSchemeIndex(color)
                if (connected) {
                    bleRepository.setColorScheme(color).onFailure { error ->
                        Logger.e(error) { "Failed to apply active profile LED color" }
                    }
                }
            }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Logger.e(error) { "Error collecting active profile LED color" }
    }
}
```

Keeping disconnected `(false, color)` emissions ensures reconnecting with the same color still emits `(true, color)` and reapplies it.

Insert this as the first executable block in the existing `ActiveSessionEngine.startWorkout` method, before logging or coordinator mutation:

```kotlin
if (userProfileRepository.activeProfileContext.value !is ActiveProfileContext.Ready) {
    Logger.w { "Workout start ignored while profile context is switching" }
    return
}
```

Expose document-level quick-start accessors on `SettingsManager` so saves reuse its profile-ID capture and serialized workout mutation:

```kotlin
fun getSingleExerciseDefaultsDocument(exerciseId: String): SingleExerciseDefaultsDocument? =
    ready().preferences.workout.value.singleExerciseDefaults[exerciseId]

fun saveSingleExerciseDefaultsDocument(document: SingleExerciseDefaultsDocument) =
    updateWorkout { current ->
        current.copy(singleExerciseDefaults = current.singleExerciseDefaults + (document.exerciseId to document))
    }

fun getJustLiftDefaultsDocument(): JustLiftDefaultsDocument =
    ready().preferences.workout.value.justLiftDefaults

fun saveJustLiftDefaultsDocument(document: JustLiftDefaultsDocument) =
    updateWorkout { current -> current.copy(justLiftDefaults = document) }
```

Map runtime values explicitly at the `ActiveSessionEngine` boundary, reusing the existing complete single-exercise converters in `ProfileWorkoutDefaultsMapper.kt`:

```kotlin
suspend fun getSingleExerciseDefaults(exerciseId: String): SingleExerciseDefaults? =
    settingsManager.getSingleExerciseDefaultsDocument(exerciseId)?.toLegacySingleExerciseDefaults()

fun saveSingleExerciseDefaults(defaults: SingleExerciseDefaults) =
    settingsManager.saveSingleExerciseDefaultsDocument(defaults.toDocument())

suspend fun getJustLiftDefaults(): JustLiftDefaults =
    settingsManager.getJustLiftDefaultsDocument().toRuntimeJustLiftDefaults()

fun saveJustLiftDefaults(defaults: JustLiftDefaults) =
    settingsManager.saveJustLiftDefaultsDocument(defaults.toDocument())

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

Route `saveJustLiftDefaultsFromWorkout` and `saveSingleExerciseDefaultsFromWorkout` through the same document accessors; no active-profile quick-start path may call legacy `PreferencesManager`. Tests assert lossless round trips, including rack item IDs and per-set arrays. The Just Lift display-unit increment intentionally rounds at the existing runtime boundary, matching its current `Int` type.

- [ ] **Step 5: Update direct construction sites and pass focused tests**

Update MainViewModel's direct construction to `SettingsManager(preferencesManager, userProfileRepository, viewModelScope)`. Initialize a single shared `FakeUserProfileRepository().apply { setActiveProfileForTest() }` in the DWSM harness, MainViewModel tests, workout E2E tests, ActiveSessionEngine fixtures, SafeWord fixtures, and BLE fixtures; pass that same ready fake to every profile-aware collaborator in the fixture. Use `recoverPendingProfileTransitionForStartup()` when a test specifically needs `Switching`. Remove profile-owned writes from `PreferencesManager`; retain the old keys and snapshot-reading APIs for Task 4 migration. Keep `SettingsEquipmentRackRepository` available but unbound in production.

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SettingsManagerTest*" --tests "*SettingsPreferencesManagerTest*" --tests "*EquipmentRackRepositoryTest*" --tests "*HealthBodyWeightSyncManagerTest*" --tests "*SafeWordDetectionManagerTest*" --tests "*BleConnectionManagerTest*" --tests "*ActiveSessionEngineIntegrationTest*" --tests "*MainViewModelTest*" --tests "*WorkoutFlowE2ETest*" --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*DWSM*" --tests "*BodyweightRackLoadTest*" --tests "*CycleRoutineLoadFallbackTest*" --tests "*Issue593BodyweightRepEntryTest*" --tests "*WarmupProgressionTest*" --tests "*WarmupTransitionToneTest*" --tests "*WeightRecommendationIntegrationTest*" --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileAndroidMain :shared:testAndroidHostTest --tests "*KoinModuleVerifyTest*" --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --console=plain
```

Expected: PASS; A/B consumers change immediately, stale queued actions never mutate the newly active profile, same-section setters do not lose sibling changes, legacy values remain unchanged after profile setters, the production Koin graph resolves the new gate/rack/health boundaries, and the unfiltered host suite catches every shared fake/constructor call site.

- [ ] **Step 6: Commit active-profile consumer routing**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/RequiredMigrationGate.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt shared/src/commonTest shared/src/androidHostTest
git commit -m "refactor: route training settings through active profile"
```

### Task 6: Gate live VBT behavior with the profile-owned master toggle

**Files:**
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/VbtEnabledRuntimeTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt`
- Inspect only: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/BiomechanicsHistoryCard.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinatorEventTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngineIntegrationTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/VerbalEncouragementGateTest.kt`

**Interfaces:**
- Consumes: the complete VBT portion of `SettingsManager.userPreferences` for the active `Ready` profile.
- Produces: one immutable `VbtRuntimeSettings(enabled, velocityLossThresholdPercent, autoEndOnVelocityLoss)` snapshot in `WorkoutCoordinator`, updated as a unit and read once per completed rep.
- Produces: `WorkoutUiState.vbtEnabled`, threaded through the complete active-workout Compose call chain.
- Does not add a Koin binding or gate raw biomechanics capture, summaries, repository persistence, PR/progression inputs, or history.

- [ ] **Step 1: Characterize and expose the real engine seam without changing behavior**

The existing runtime path is `ActiveSessionEngine.processBiomechanicsForRep()` ->
`BiomechanicsEngine.processRep()` -> `checkVelocityThreshold()`. Do not invent
`applyProfileVbt`, `processWorkingRep`, `WorkoutEvent`, or a test-local decision tracker.

First run the existing VBT tests. Then make only a behavior-neutral extraction:

- keep `processBiomechanicsForRep()` as the unconditional capture path;
- expose the existing threshold decision as an `internal suspend` production method such as
  `evaluateLatestVbtResult()` so common tests can drive the real `BiomechanicsEngine` and real
  `HapticEvent` stream;
- have the production rep path call that same method after `BiomechanicsEngine.processRep()`; and
- keep the warm-up guard in the production decision path.

Run the existing VBT tests again and require the same baseline behavior before adding the master toggle.

- [ ] **Step 2: Write failing production-backed disabled/re-enabled tests**

Use `DWSMTestHarness`, its one `Ready` fake profile, the real coordinator
`BiomechanicsEngine`, real `WorkoutState`, real `HapticEvent` collection, and
`fakeBiomechanicsRepo`. Helpers may construct uniform `WorkoutMetric` samples, but must not
reimplement the VBT decision state machine.

Cover all of the following:

1. With `vbtEnabled=false`, threshold and auto-end still configured, processing baseline and
   threshold-crossing reps leaves `latestRepResult` and `getSetSummary().repResults` populated,
   emits neither `VELOCITY_THRESHOLD_REACHED` nor `VERBAL_ENCOURAGEMENT`, and does not auto-end
   the active set.
2. Completing that disabled-VBT set still writes its rep results to
   `FakeBiomechanicsRepository.savedBiomechanics`.
3. Re-enabling changes only the master: the configured threshold remains unchanged in
   `BiomechanicsEngine.currentVelocityLossThresholdPercent`, auto-end remains configured, and
   subsequent threshold reps restore the existing alert/auto-end behavior.
4. A `Ready` profile switch applies the new profile's enabled/threshold/auto-end triple as one
   `VbtRuntimeSettings` value; no mixed A/B snapshot is observable.
5. With persisted vulgar and dominatrix intent both true but active-profile local adult
   confirmation false, a real runtime threshold emits a `VERBAL_ENCOURAGEMENT` with
   `vulgarMode=false` and `dominatrixMode=false`. The VBT document must still retain both intent
   flags unchanged.

Replace or refactor the test-local mirror in `VerbalEncouragementGateTest` so policy assertions
call production policy code. Keep at least one `ActiveSessionEngine` assertion proving that the
policy is used by the runtime event path.

- [ ] **Step 3: Run the focused VBT tests and verify the master-toggle assertions fail**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*Vbt*" --tests "*VerbalEncouragementGateTest*" --tests "*WorkoutCoordinatorEventTest*" --tests "*ActiveSessionEngineIntegrationTest*" --console=plain
```

Expected: FAIL because the coordinator has no master toggle and still emits live velocity-loss events.

- [ ] **Step 4: Add one coherent coordinator runtime snapshot**

```kotlin
internal data class VbtRuntimeSettings(
    val enabled: Boolean = true,
    val velocityLossThresholdPercent: Float = 20f,
    val autoEndOnVelocityLoss: Boolean = false,
)

class WorkoutCoordinator(
    vbtEnabled: Boolean = true,
    velocityLossThresholdPercent: Float = 20f,
    autoEndOnVelocityLoss: Boolean = false,
) {
    private val _vbtRuntimeSettings = MutableStateFlow(
        VbtRuntimeSettings(vbtEnabled, velocityLossThresholdPercent, autoEndOnVelocityLoss),
    )
    internal val vbtRuntimeSettings: StateFlow<VbtRuntimeSettings> =
        _vbtRuntimeSettings.asStateFlow()

    val biomechanicsEngine = BiomechanicsEngine(velocityLossThresholdPercent)

    internal fun updateVbtSettings(
        vbtEnabled: Boolean,
        thresholdPercent: Float,
        autoEnd: Boolean,
    ) {
        require(thresholdPercent in 10f..50f)
        biomechanicsEngine.updateVelocityLossThresholdPercent(thresholdPercent)
        _vbtRuntimeSettings.value = VbtRuntimeSettings(vbtEnabled, thresholdPercent, autoEnd)
    }
}
```

Do not create separate enabled/threshold/auto-end `StateFlow`s and do not add a shadow
`configuredVelocityLossThresholdPercent`; the `BiomechanicsEngine` already exposes its current
threshold for internal verification. Existing convenience getters may derive from the one snapshot,
but every live decision must capture `vbtRuntimeSettings.value` once.

In `DefaultWorkoutSessionManager`, build the constructor snapshot and every update from
`settingsManager.userPreferences`, including `vbtEnabled`. Map all three values together and use
`distinctUntilChanged()` before calling `updateVbtSettings`. Task 5 already owns the SettingsManager
source; do not read `PreferencesManager` or add DI.

- [ ] **Step 5: Gate only live evaluation, auto-end, and feedback**

```kotlin
internal suspend fun evaluateLatestVbtResult() {
    if (!coordinator._repCount.value.isWarmupComplete) return
    val runtime = coordinator.vbtRuntimeSettings.value
    if (!runtime.enabled) return
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
        if (consecutiveThresholdReps >= 2 && runtime.autoEndOnVelocityLoss) {
            handleSetCompletion()
        }
    } else {
        consecutiveThresholdReps = 0
    }
}
```

`processBiomechanicsForRep()` must still call `BiomechanicsEngine.processRep()` while disabled and
must call the evaluator only after capture. Do not clear baselines or rep results when toggling. Do
not gate set-summary creation, `BiomechanicsRepository.saveRepBiomechanics`, raw MCV/peak flows,
PR/progression consumers, or historical views. The master suppresses only live interpretation,
threshold feedback, verbal routing, and auto-end.

- [ ] **Step 6: Thread VBT state through the complete active-workout UI**

Add `val vbtEnabled: Boolean = true` to `WorkoutUiState`. In `ActiveWorkoutScreen`, include
`userPreferences.vbtEnabled` both in the `remember(...)` key list and in the constructed
`WorkoutUiState`; omitting the key leaves stale UI state after a profile switch.

Thread `state.vbtEnabled` through both `WorkoutTab` overloads, then `WorkoutHud`, then private
`StatsPage`. Give inner composable parameters a default of `true` so previews remain source
compatible. Hide only live VBT interpretation:

```kotlin
// WorkoutUiState
val vbtEnabled: Boolean = true,

// ActiveWorkoutScreen -> WorkoutUiState
vbtEnabled = userPreferences.vbtEnabled,

// WorkoutTab -> WorkoutHud -> StatsPage
vbtEnabled = state.vbtEnabled,
```

Keep raw MCV/peak values visible, but remove zone color/label and velocity-loss threshold/reps-left feedback while disabled:

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

Keep the MCV and Peak columns visible with neutral color while disabled. Hide the Zone column,
`VelocityLossIndicator`, and estimated reps-left only. Do not conditionally remove the whole
biomechanics card or `latestBiomechanicsResult` from state.

Use `com.devil.phoenixproject.testutil.readProjectFile` for source-contract assertions covering:

- `ActiveWorkoutScreen` remember key and `WorkoutUiState` assignment;
- outer and inner `WorkoutTab` forwarding;
- `WorkoutHud` -> `StatsPage` forwarding;
- the guarded Zone, velocity-loss, and reps-left branches;
- raw MCV and Peak remaining outside the master gate; and
- `src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/BiomechanicsHistoryCard.kt`
  containing no `vbtEnabled` gate.

- [ ] **Step 7: Pass runtime, UI-contract, and native regression tests**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*Vbt*" --tests "*VerbalEncouragementGateTest*" --tests "*WorkoutCoordinatorEventTest*" --tests "*ActiveSessionEngineIntegrationTest*" --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinIosArm64 --console=plain
```

Expected: PASS for disabled suppression, real metrics/history persistence, coherent profile updates,
unchanged subordinate configuration, restored behavior after re-enable, locally neutralized adult
feedback, complete Compose threading, and iOS compilation. Run `git diff --check`. No Koin graph
change is expected in this task.

- [ ] **Step 8: Commit runtime VBT gating**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/VbtEnabledRuntimeTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/VerbalEncouragementGateTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinatorEventTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngineIntegrationTest.kt
git commit -m "feat: gate live VBT by active profile"
```

### Task 7: Harden profile deletion and journal local-key cleanup

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProfileDeletionMergePolicy.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProfileScopedDataMerger.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/ProfileDeletionMergePolicyTest.kt`
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightUserProfileRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/MigrationManagerTest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/ProfilePreferencesMigrationTest.kt`

**Interfaces:**
- Consumes: all direct profile-owned tables, existing profile-inclusive unique indexes, Task 3's local-safety store/journal, and `normalizeWorkoutModeKey`.
- Produces: neutral deterministic PR/badge/MVT merge models shared by deletion and `MigrationManager`, without generated/domain `PersonalRecord` type collisions.
- Produces: a schema-wide profile deletion transaction, retryable post-commit local-key cleanup, and exact active-context emission semantics.
- Adds SQLDelight queries only; no `.sqm` or schema-version bump is required.

- [ ] **Step 1: Write failing policy, schema-coverage, transaction, and restart tests**

Initialize every repository fixture to `ActiveProfileContext.Ready` before creating the source
profile. Use `createProfile`/`createAndActivateProfile`; do not insert an identity row behind a
still-`Switching` fake.

Pure policy tests must cover:

- every `MAX_WEIGHT` tie level: `weight -> oneRepMax -> achievedAt`;
- every `MAX_VOLUME` tie level: `volume -> weight -> achievedAt`;
- live rows beating tombstones and complete ties choosing the target;
- both source-winner and target-winner paths while retaining the target numeric ID;
- `uuid = winner.uuid ?: loser.uuid`, winner sync metadata, and nonblank exercise-name fallback;
- earliest badge earned time, earliest non-null celebration, and the badge metadata-donor rules;
- weighted MVT merging, including zero-count/newer-row and timestamp-tie behavior.

Add an Android-host schema-coverage test that introspects every SQLite table/column and requires
every direct `profile_id` or `profileId` table to appear in the deletion policy. This must catch at
least `ExerciseMvt`, `RoutineGroup`, `VelocityOneRepMaxEstimate`, all `External*` profile tables,
`IntegrationStatus`, `IntegrationSyncCursor`, `UserProfilePreferences`, and
`PendingProfileLocalCleanup`.

Repository tests must establish RED for:

- active deletion emitting exactly `Switching(default) -> Ready(default)`;
- inactive deletion targeting the current non-default Ready profile and emitting no `Switching`;
- an injected before-commit failure emitting `Switching(target) -> prior Ready` for active delete,
  while rolling back the source identity, active DB identity, preferences, every owned row, and the
  cleanup journal;
- every direct profile-scoped table following the explicit policy in Step 3;
- overlapping PR/badge/MVT/external natural keys merging without unique-index failures;
- source-only external rows and retained child graphs moving, while conflicting target rows remain
  byte-for-byte target-owned;
- Default remaining undeletable and target derived stats being recomputed.

Seed meaningful, distinct local safety values, for example source
`("source-secret", calibrated=true, confirmed=true, prompted=true)` and a different target value.

Add a true restart cleanup test:

1. Reach Ready, create the source, and fail source local-key deletion.
2. Delete the source and assert SQL deletion committed while the source secret and journal remain.
3. Construct a fresh `MigrationManager` over the same database/settings.
4. Clear the failure and call `runRequiredMigrations()`.
5. Assert all four source safety keys and the journal are gone, target safety is unchanged, and the
   required migration state is `Ready`.

- [ ] **Step 2: Run the focused tests and observe policy/coverage/transaction failures**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileDeletionMergePolicyTest*" --tests "*SqlDelightUserProfileRepositoryTest*" --tests "*MigrationManagerTest*" --tests "*ProfilePreferencesMigrationTest*" --console=plain
```

Expected: FAIL because current deletion omits tables, collides on unique indexes, loses sync/identity
metadata in migration merges, does not journal deletion cleanup transactionally, and does not provide
the required context/fault semantics.

- [ ] **Step 3: Define the complete deletion matrix and neutral merge policies**

For deleting `sourceProfileId`, define exactly:

```kotlin
val targetProfileId = if (priorReady.profile.id == sourceProfileId) {
    DEFAULT_PROFILE_ID
} else {
    priorReady.profile.id
}
```

Require the source to exist, reject Default, and require the target to exist.

| Tables | Deletion policy |
|---|---|
| `WorkoutSession`, `RoutineGroup`, `Routine`, `TrainingCycle`, `AssessmentResult`, `ProgressionEvent`, `StreakHistory`, `VelocityOneRepMaxEstimate` | Reassign source rows to target. |
| `PersonalRecord`, `EarnedBadge` | Resolve normalized-key collisions, retain target row IDs, then reassign remaining source rows. |
| `ExerciseMvt` | Merge same-exercise collisions, then reassign source-only rows. |
| `GamificationStats`, `RpgAttributes` | Delete source and target aggregates in-transaction; recompute target after commit. |
| `ExternalActivity`, `ExternalRoutine`, `ExternalRoutineFolder`, `ExternalProgram`, `ExternalExerciseTemplate`, `ExternalExerciseTemplateMapping`, `ExternalBodyMeasurement` | Target wins natural-key collisions byte-for-byte; remove the duplicate source row/children, then reassign source-only rows. |
| `IntegrationStatus`, `IntegrationSyncCursor` | Delete source operational state; never transplant connection state or cursors. |
| `UserProfilePreferences` | Delete source; leave target preferences unchanged. |
| `PendingProfileLocalCleanup` | Enqueue source as the first SQL mutation and retain until local Settings cleanup succeeds. |
| `UserProfile` | Delete source last. |
| Profile local safety | Delete source keys only, after SQL commit. |

For conflicting `ExternalRoutine` and `ExternalProgram` rows, delete source routine
sets/exercises and program stats before their source parent. Source-only parents retain their IDs and
children when reassigned.

Use neutral data classes such as `ProfileMergePersonalRecord`, `ProfileMergeEarnedBadge`, and
`ProfileMergeExerciseMvt`; do not reuse generated database rows, domain `PersonalRecord`, or
`MigrationManager`-private canonical types.

PR key and merge rules:

- group by `exerciseId`, `normalizeWorkoutModeKey(workoutMode)`, `prType`, and `phase`;
- collapse all raw workout-mode aliases and store the normalized mode on the retained row;
- live beats tombstone;
- `MAX_WEIGHT`: `weight -> oneRepMax -> achievedAt`;
- `MAX_VOLUME`: `volume -> weight -> achievedAt`;
- complete tie chooses target;
- retain the lowest target numeric ID, or lowest source ID when no target row exists;
- copy all winner business/sync fields, with blank winner exercise name falling back to the loser's
  nonblank name;
- set `uuid = winner.uuid ?: loser.uuid`; when source wins, delete the source duplicate before
  assigning its globally unique UUID to the retained target row.

Badge merge rules:

- retain the target numeric ID/profile;
- `earnedAt = minOf`, and `celebratedAt` is the earliest non-null celebration;
- metadata donor is live over tombstone, then earlier-earned, then target on a full tie;
- copy donor `updatedAt`, `serverId`, and `deletedAt`.

MVT merge rules:

- coerce counts nonnegative, sum them, and use a sample-count-weighted `personalMvtMs`;
- `updatedAt = maxOf`;
- if both counts are zero, use the newer row, target on a timestamp tie.

- [ ] **Step 4: Add the SQLDelight adapters for every policy row**

```sql
deletePersonalRecordById:
DELETE FROM PersonalRecord WHERE id = ?;

updatePersonalRecordForProfileMerge:
UPDATE PersonalRecord
SET exerciseName = :exerciseName, weight = :weight, reps = :reps, oneRepMax = :oneRepMax,
    achievedAt = :achievedAt, workoutMode = :workoutMode, prType = :prType,
    volume = :volume, phase = :phase, updatedAt = :updatedAt, serverId = :serverId,
    deletedAt = :deletedAt, cable_count = :cableCount, uuid = :uuid,
    profile_id = :targetProfileId
WHERE id = :targetId;

deleteEarnedBadgeById:
DELETE FROM EarnedBadge WHERE id = ?;

updateEarnedBadgeForProfileMerge:
UPDATE EarnedBadge
SET earnedAt = :earnedAt, celebratedAt = :celebratedAt, updatedAt = :updatedAt,
    serverId = :serverId, deletedAt = :deletedAt
WHERE id = :targetId;
```

Also add:

- `reassignRoutineGroupProfile` and `reassignVelocityOneRepMaxProfile`;
- `selectAllExerciseMvtByProfile`, `deleteExerciseMvt`, an exact merged-row update/upsert, and
  `reassignExerciseMvtProfile`;
- profile-wide deletes for `IntegrationStatus` and `IntegrationSyncCursor`;
- conflict-delete plus reassign queries for every external table in Step 3, including explicit
  source-child deletion for conflicting routines/programs; and
- any select-by-profile query required to adapt generated rows into the neutral merge models.

Retain existing `selectAllRecords`, `selectAllEarnedBadges`, and non-conflicting reassignment
queries. Generate the SQLDelight interfaces before Kotlin references new query names. The
`ProfileScopedDataMerger` must use these generated queries rather than delete/reinsert or raw-driver
type coupling.

- [ ] **Step 5: Share the merger and make deletion one journaled transaction**

Bind one `ProfileScopedDataMerger` in Koin and inject it into both
`SqlDelightUserProfileRepository` and `MigrationManager`. Refactor migration/orphan PR and badge
repair to use it; remove `CanonicalPersonalRecord`, `CanonicalEarnedBadge`, duplicate comparators,
and the delete/reinsert merge that loses numeric IDs and sync metadata. Preserve normalized
`OldSchool`/`Old School` collapse.

Under `profileContextMutex`:

1. Reject Default, require a `Ready` context, require source and target identities, and compute
   `targetProfileId` exactly as Step 3.
2. Emit `Switching(targetProfileId)` only when the source is active.
3. In one transaction:

   - enqueue `PendingProfileLocalCleanup(sourceProfileId)` as the first mutation;
   - invoke `ProfileScopedDataMerger` for PR/badge/MVT/external collisions;
   - reassign or delete every table in Step 3;
   - delete both source and target derived aggregates;
   - delete source preferences and then the source profile;
   - set active target only for active deletion; and
   - invoke a constructor-injected, default-no-op `beforeProfileDeletionCommit` fault hook.

4. On transaction failure, restore the exact prior `Ready` value and rethrow. SQL data, profile,
   active identity, preferences, and the newly enqueued journal must all roll back.
5. After commit, refresh flows and publish `Ready(targetProfileId)`. Preserve the existing
   reconciliation/recovery path if publication fails.
6. Only after Ready publication call `retryPendingLocalCleanup(sourceProfileId)`, which retains the
   journal on non-cancellation failure.
7. Best-effort recompute target gamification/RPG through the injected `GamificationRepository`.

The fault hook avoids brittle generated-query identifier matching and lets the test prove the
journal exists inside the transaction but disappears on rollback.

Required context sequences:

```text
active success: Switching(target) -> Ready(target)
inactive success: no Switching
active transaction failure: Switching(target) -> prior Ready
```

Update `FakeUserProfileRepository.deleteProfile` to match the target rule, active-only Switching,
preference removal, cleanup queue/retry, injected failure controls, and exact rollback/emission
semantics. Task 4's required startup migration remains the owner of draining leftover cleanup rows.

- [ ] **Step 6: Pass policy, schema, transaction, migration, DI, and native tests**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*ProfileDeletionMergePolicyTest*" --tests "*SqlDelightUserProfileRepositoryTest*" --tests "*MigrationManagerTest*" --tests "*ProfilePreferencesMigrationTest*" --tests "*KoinModuleVerifyTest*" --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinIosArm64 --console=plain
```

Expected: PASS for the complete schema policy, target/source/tie/UUID/sync/celebration merge cases,
exact context emissions, rollback/journal ordering, active and inactive targets, external child graphs,
fake parity, normalized orphan migration, true restart cleanup drain, Koin construction, and iOS
compilation. Run `git diff --check` and confirm no `.sqm` was added.

- [ ] **Step 7: Commit deletion hardening**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProfileDeletionMergePolicy.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProfileScopedDataMerger.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/ProfileDeletionMergePolicyTest.kt shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeUserProfileRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightUserProfileRepositoryTest.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/MigrationManagerTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/ProfilePreferencesMigrationTest.kt
git commit -m "fix: merge profile data safely on deletion"
```

### Task 8: Add profile preferences to backup schema version 5

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt`
- Modify: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt`
- Modify: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt`
- Modify: `shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt`
- Modify: `shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/BackupSerializationTest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/DataBackupManagerRoutineNameTest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/BackupJsonNavigatorTest.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt` only if constructor verification needs an updated extra type.

**Interfaces:**
- Consumes: `ProfilePreferencesRepository`, `UserProfileRepository.reconcileActiveProfileContext()`, typed section values and validators from Task 3, and the profile-aware runtime boundary from Task 5.
- Produces: backup schema v5, identical buffered/streaming preference behavior, deterministic v1-v4 rack compatibility, and a reconciled active profile after every successful restore.
- Privacy boundary: profile-local voice phrase, voice calibration, adult confirmation, and adult-prompt state never enter the backup model, exporter, importer, or backup-manager constructor.

- [ ] **Step 1: Write the complete failing compatibility, privacy, parity, and recovery matrix**

Add focused tests with two profiles (`profile-a` and `profile-b`) and distinct values for all five sections. Exercise both `importFromJson` and the protected streaming path exposed by `StreamingImportRoundTripTest`.

The required matrix is:

| Backup version | `profilePreferences` | `equipmentRackItems` | Expected restore |
|---|---|---|---|
| v1-v3 | Ignore even if supplied | Ignore even if supplied | Existing profile preferences and rack remain unchanged |
| v4 | Ignore | Missing property | No rack change |
| v4 | Ignore | Present empty array | Clear rack for every eligible represented profile, or the active fallback when none is eligible |
| v4 | Ignore | Present non-empty array | Merge by item ID for every eligible represented profile, or the active fallback when none is eligible |
| v5 | Restore valid sections independently | Ignore even if supplied | Restore only entries whose profile ID is represented by backup `userProfiles` and exists after identity import |
| v6+ | Restore the known v5 fields and ignore unknown fields | Ignore | Same known-field behavior as v5, with the existing forward-compatibility warning |

Add these assertions:

1. A buffered v5 export contains `profilePreferences`, preserves A/B values, and does not contain `equipmentRackItems`.
2. The same export contains no sync metadata field names or values: `localGeneration`, `serverRevision`, or `dirty`.
3. Seed a distinctive safe-word phrase plus calibrated/confirmed/prompted local state. Neither buffered nor streaming output may contain the phrase or any `safeWord`, calibration, or adult-consent field, and importing a backup must leave the target's existing local-safety values unchanged.
4. Corrupt one stored section while keeping its siblings valid. Export omits that section as null/absent rather than serializing its typed fallback; valid siblings still export.
5. Import a section with a wrong JSON kind or invalid typed value. Count exactly one `entitiesWithErrors`, log/skip that section, and restore its valid siblings and normal workout entities.
6. An entry for a profile that exists in the target database but is absent from backup `userProfiles` is ignored.
7. An entry named in backup preferences but whose backup identity did not become present in SQLite is ignored.
8. Import through normal `ProfilePreferencesRepository.update*` APIs: the target section's `serverRevision` is retained, local generation advances, and dirty becomes true.
9. v4 missing, empty, explicit-null, scalar, malformed-item-array, and valid non-empty rack inputs remain distinguishable in both import paths. Missing is a no-op, empty clears, explicit null/scalar/malformed typed arrays are counted and non-destructive, and a valid non-empty merge replaces matching IDs in existing order before appending new imported IDs in backup order.
10. With usable backup profile IDs, v4 applies to those profiles and never also mutates the active fallback. With no usable represented profile, it applies only to the current active existing profile.
11. v5 ignores a supplied legacy rack even when `profilePreferences` is empty.
12. A v6 payload restores known v5 sections while unknown root, data, entry, and section fields are ignored.
13. Import the same payload into identical databases through buffered and streaming paths, then compare typed values and section metadata.
14. Extend `StreamingImportRoundTripTest` so its export/import/re-export assertions cover both profile identities and all five preference sections, not only entity counts.
15. Add an adversarial streaming document whose `data` appears before root `version`, whose `profilePreferences` appears before `userProfiles`, and whose legacy rack appears before both. The final root version must control behavior.
16. A successful import observes restored preferences from inside a recording/delegating `UserProfileRepository.reconcileActiveProfileContext()` and records one reconciliation before `Result.success`.
17. Inject an unexpected preference-repository failure after the database transaction commits. The import returns failure, reconciliation is still attempted, the restore failure stays primary, and a reconciliation failure is attached as suppressed rather than replacing it.
18. Imported `UserProfileBackup.isActive` values never switch the target app profile. Cover zero, one, and multiple backup rows marked active in both import paths; preserve the pre-import active ID when it still exists and assert exactly one SQLite identity is active afterward.
19. Cover active-identity fallback deterministically: when the captured active ID is unavailable, choose `default` if present, otherwise the first represented profile that actually exists. A streaming failure after committed identity writes must normalize by the same policy before reconciliation.
20. Inject `selectAllUserProfilesSync()` failure separately into buffered and streaming export. Both exports must fail; neither may emit a successful backup with empty `userProfiles`/`profilePreferences`, and the streaming writer must still clean up its partial file.

Use dependencies bound to the same test database. A recording repository should delegate every operation to the real `UserProfileRepository` and intercept only `reconcileActiveProfileContext()`; do not use a disconnected fake whose profile flows cannot see imported SQL rows.

- [ ] **Step 2: Run the backup tests and prove the v4 implementation fails the new contract**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*BackupSerializationTest*" --tests "*BackupJsonNavigatorTest*" --tests "*StreamingImportRoundTripTest*" --tests "*DataBackupManager*" --console=plain
```

Expected: FAIL because `CURRENT_BACKUP_VERSION` is 4, the rack field cannot distinguish missing from empty in the buffered model, streaming applies rack before it knows the final version, and there is no profile-preference payload or post-import reconciliation.

- [ ] **Step 3: Define the v5 wire model and explicit privacy boundary**

In `BackupModels.kt`, bump the version and add independently decodable section elements:

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
```

Update `BackupContent` without changing the wire name of the legacy field:

```kotlin
@Serializable
data class BackupContent(
    // Existing entity fields remain unchanged and in their current order.
    val userProfiles: List<UserProfileBackup> = emptyList(),
    val profilePreferences: List<ProfilePreferencesBackup> = emptyList(),
    @SerialName("equipmentRackItems")
    val legacyEquipmentRackItems: JsonElement? = null,
    // Remaining existing entity fields remain unchanged.
)
```

`JsonElement` is deliberate for both preference sections and the legacy rack. A scalar, explicit null, or array containing a malformed `RackItem` must survive root decoding so the common v4 decoder can classify it as recoverable, non-destructive invalid input. A typed `List<RackItem>?` is forbidden because it either loses absent-versus-explicit-null presence or aborts the whole buffered backup before valid sibling data can import.

Before decoding buffered `BackupData`, parse the root once and capture property presence independently from its raw element:

```kotlin
val rootElement = json.parseToJsonElement(jsonString)
val rootObject = rootElement.jsonObject
val dataObject = rootObject["data"]?.jsonObject
    ?: throw IllegalArgumentException("Backup data object is missing or invalid")
val legacyRackFieldPresent = dataObject.containsKey("equipmentRackItems")
val legacyRackElement = dataObject["equipmentRackItems"]
val backup = json.decodeFromJsonElement<BackupData>(rootElement)
```

This preserves all five states: absent (`containsKey == false`), valid empty array, explicit `JsonNull`, scalar/wrong JSON kind, and an array whose members fail typed `RackItem` decoding. Only the valid empty array is a destructive clear.

Configure `BaseDataBackupManager.json` as:

```kotlin
protected val json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
```

Update the backup schema history and `BackupPrivacyMetadata.userFacingSummary` to state that full personal-data exports include profile training preferences but exclude auth/runtime secrets, section sync bookkeeping, voice phrase, voice calibration, and adult consent/prompt state.

Do not add `ProfileLocalSafetyPreferences`, `ProfileLocalSafetyStore`, section metadata, `schemaVersion`, or `legacyMigrationVersion` to any backup wire type.

- [ ] **Step 4: Replace the obsolete equipment-rack dependency with the two profile repositories**

After v5 export and v4 restore use the preference aggregate directly, `BaseDataBackupManager` no longer needs `EquipmentRackRepository`. Replace its constructor with:

```kotlin
abstract class BaseDataBackupManager(
    private val database: VitruvianDatabase,
    private val profilePreferencesRepository: ProfilePreferencesRepository,
    private val userProfileRepository: UserProfileRepository,
) : DataBackupManager
```

Thread both dependencies through the platform classes:

```kotlin
class AndroidDataBackupManager(
    private val context: Context,
    database: VitruvianDatabase,
    private val preferencesManager: PreferencesManager,
    private val destinationResolver: BackupDestinationResolver,
    profilePreferencesRepository: ProfilePreferencesRepository,
    userProfileRepository: UserProfileRepository,
) : BaseDataBackupManager(
    database,
    profilePreferencesRepository,
    userProfileRepository,
)
```

```kotlin
class IosDataBackupManager(
    database: VitruvianDatabase,
    private val preferencesManager: PreferencesManager,
    private val destinationResolver: BackupDestinationResolver,
    profilePreferencesRepository: ProfilePreferencesRepository,
    userProfileRepository: UserProfileRepository,
) : BaseDataBackupManager(
    database,
    profilePreferencesRepository,
    userProfileRepository,
)
```

Update the real bindings:

```kotlin
single<DataBackupManager> {
    AndroidDataBackupManager(androidContext(), get(), get(), get(), get(), get())
}
```

```kotlin
single<DataBackupManager> {
    IosDataBackupManager(get(), get(), get(), get(), get())
}
```

Update both Android-host `TestDataBackupManager` subclasses in `DataBackupManagerRoutineNameTest.kt` and `BackupJsonNavigatorTest.kt`. Build a real `ProfilePreferencesRepository` and a real or recording/delegating `UserProfileRepository` against the same `VitruvianDatabase`. Do not pass `SettingsEquipmentRackRepository` or inject/read `ProfileLocalSafetyStore` from backup code.

- [ ] **Step 5: Export the same typed sections in buffered and streaming paths**

Share one validity-aware conversion:

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

In `exportAllData`, identity loading is a required root operation:

```kotlin
val userProfiles = queries.selectAllUserProfilesSync().executeAsList()
```

Do not retain the current `runCatching { selectAllUserProfilesSync() }.getOrElse { emptyList() }` fallback. A failed identity query must fail the buffered export before preferences are assembled. Then load `profilePreferencesRepository.get(profile.id)` for every exported `UserProfile` and populate `BackupContent.profilePreferences`. Do not synthesize typed defaults if the preference row itself is unexpectedly missing; fail the export rather than silently claiming a complete backup.

In `streamExportToWriter`, execute the same direct, fatal `selectAllUserProfilesSync()` query before deriving profile preferences or writing a successful identity/preferences payload. Do not catch it to an empty list. Load the same small per-profile preference list and serialize it with `ProfilePreferencesBackup.serializer()`. Emit `userProfiles` and `profilePreferences` and explicitly omit `equipmentRackItems`. Both export paths must use `toBackup`; neither may inspect raw Settings keys or `ProfileLocalSafetyStore`. The existing outer streaming-export failure path remains responsible for closing and deleting the partial writer.

Invalid/unsupported stored sections serialize as absent/null while valid siblings remain present. Because `explicitNulls = false`, the buffered v5 encoder also omits the nullable legacy rack field.

- [ ] **Step 6: Import buffered profile identities first and seed preference rows atomically**

In `importFromJson`, keep duplicate-detection snapshots outside the transaction, but move the entire user-profile import block to the first operation inside the database transaction, before sessions, routine groups, routines, PRs, cycles, badges, or any other profile-linked entity. Capture the target app's active identity before any import write, using the existing deterministic profile order when repairing a legacy multiple-active state:

```kotlin
val preImportActiveProfileId = queries.getAllProfiles()
    .executeAsList()
    .firstOrNull { it.isActive == 1L }
    ?.id
```

Collect distinct decoded backup identity IDs in wire order:

```kotlin
val representedProfileIds = backup.data.userProfiles
    .map(UserProfileBackup::id)
    .toCollection(linkedSetOf())
```

Backup `isActive` is informational only and must never switch the target app. During entry handling, insert every new identity inactive, leave every existing identity's active flag untouched, and seed the aggregate for both:

```kotlin
backup.data.userProfiles.distinctBy(UserProfileBackup::id).forEach { profile ->
    if (profile.id !in existingUserProfileIds) {
        queries.insertUserProfileIgnore(
            id = profile.id,
            name = profile.name,
            colorIndex = profile.colorIndex.toLong(),
            createdAt = profile.createdAt,
            isActive = 0L,
        )
        userProfilesImported++
    } else {
        userProfilesSkipped++
    }
    queries.insertDefaultProfilePreferences(profile.id, 1L)
}
normalizeImportedActiveIdentity(preImportActiveProfileId, representedProfileIds)
```

Do not catch and continue an infrastructure failure between identity insertion and `insertDefaultProfilePreferences`; those two writes must remain an invariant of the outer transaction. Remove the old late user-profile block. The subsequent deferred helper re-queries SQLite and intersects represented IDs with rows that actually exist, so an orphan preference entry can never create an identity.

Use one identity normalizer from both import paths and post-commit recovery:

```kotlin
private fun normalizeImportedActiveIdentity(
    preImportActiveProfileId: String?,
    representedProfileIds: Set<String>,
): String {
    val existingProfileIds = queries.selectAllUserProfileIds()
        .executeAsList()
        .toSet()
    val activeProfileId = preImportActiveProfileId
        ?.takeIf { it in existingProfileIds }
        ?: "default".takeIf { it in existingProfileIds }
        ?: representedProfileIds.firstOrNull { it in existingProfileIds }
        ?: error("No usable profile identity after backup import")

    queries.setActiveProfile(activeProfileId)
    return activeProfileId
}
```

Call it inside the same outer buffered transaction immediately after identity insertion/seeding. `setActiveProfile` is the one atomic normalization write: it clears every other active flag and selects exactly one preserved/fallback identity. The selection order is fixed: captured pre-import active when still present, otherwise Default, otherwise the first usable represented identity. Never consult imported `isActive` flags.

Declare `var activeIdentityNormalized = false` beside the buffered import's `databaseWorkCommitted`/`reconciliationAttempted` state. Set both `databaseWorkCommitted = true` and `activeIdentityNormalized = true` only after the outer buffered transaction containing identity insertion, seeding, normalization, and entity writes returns successfully.

- [ ] **Step 7: Implement one common deferred restore helper for both import paths**

Define a common payload:

```kotlin
private data class DeferredProfileRestore(
    val backupVersion: Int,
    val profilePreferences: List<ProfilePreferencesBackup>,
    val legacyRackFieldPresent: Boolean,
    val legacyRackElement: JsonElement?,
    val representedProfileIds: Set<String>,
)
```

At restore time, never trust the payload IDs alone:

```kotlin
val profileIdsAfterImport = queries.selectAllUserProfileIds()
    .executeAsList()
    .toSet()
val eligibleProfileIds = deferred.representedProfileIds
    .filterTo(linkedSetOf()) { it in profileIdsAfterImport }
```

Decode and validate without enclosing the repository update in the decode catch:

```kotlin
private inline fun <reified T> decodeBackupSection(
    profileId: String,
    sectionName: String,
    element: JsonElement,
    validate: (T) -> List<String>,
    onInvalid: (String, String, Throwable?) -> Unit,
): T? = runCatching {
    val value = json.decodeFromJsonElement<T>(element.jsonObject)
    val validationErrors = validate(value)
    require(validationErrors.isEmpty()) { validationErrors.joinToString(",") }
    value
}.fold(
    onSuccess = { it },
    onFailure = {
        onInvalid(profileId, sectionName, it)
        null
    },
)
```

For each skipped section, increment `entitiesWithErrors` once and log the profile ID and section name. Decode/validation failures are recoverable. Unexpected failures from `profilePreferencesRepository.get` or `update*` are storage failures and must propagate to the import result rather than being mislabeled as malformed backup data.

Decode the v4 rack independently from root and preference-section decoding:

```kotlin
private fun decodeLegacyV4Rack(
    element: JsonElement?,
    onInvalid: (String, String, Throwable?) -> Unit,
): List<RackItem>? {
    if (element == null) {
        onInvalid("backup", "equipmentRackItems", null)
        return null
    }
    return runCatching {
        json.decodeFromJsonElement<List<RackItem>>(element)
    }.fold(
        onSuccess = { it },
        onFailure = {
            onInvalid("backup", "equipmentRackItems", it)
            null
        },
    )
}
```

This function is called only when the property-presence bit is true and the final version is exactly 4. `JsonNull`, a scalar/object, and an array with any malformed typed member all return null after one recoverable error and make no rack write. A successfully decoded empty list remains distinguishable and clears.

Implement the exact version behavior:

```kotlin
when {
    deferred.backupVersion < 4 -> Unit

    deferred.backupVersion == 4 && !deferred.legacyRackFieldPresent -> Unit

    deferred.backupVersion == 4 -> restoreLegacyV4Rack(
        items = decodeLegacyV4Rack(deferred.legacyRackElement, onInvalid),
        eligibleProfileIds = eligibleProfileIds,
        profileIdsAfterImport = profileIdsAfterImport,
        now = now,
        onInvalid = onInvalid,
    )

    deferred.backupVersion >= 5 -> restoreV5ProfilePreferences(
        entries = deferred.profilePreferences,
        eligibleProfileIds = eligibleProfileIds,
        now = now,
        onInvalid = onInvalid,
    )
}
```

`restoreLegacyV4Rack` returns without mutation when the decoded `items` argument is null. For a valid array, choose targets exactly once:

```kotlin
val targetProfileIds = eligibleProfileIds.ifEmpty {
    listOfNotNull(
        queries.getActiveProfile()
            .executeAsOneOrNull()
            ?.id
            ?.takeIf { it in profileIdsAfterImport },
    )
}
```

Do not add the active profile when eligible represented targets exist. A present empty list writes `RackPreferences(items = emptyList())` to every target. A non-empty list filters blank IDs, resolves duplicate imported IDs deterministically, replaces matching existing items without reordering existing rows, then appends genuinely new imported items in backup order. Validate the final `RackPreferences` before `updateRack`; a malformed candidate is counted and skipped for that target.

For v5 and newer, ignore the legacy rack field unconditionally. Process only entries whose `profileId` is in `eligibleProfileIds`, decode every non-null section independently with the real `ProfilePreferencesValidator`, and call:

```kotlin
profilePreferencesRepository.updateCore(profileId, value, now)
profilePreferencesRepository.updateRack(profileId, value, now)
profilePreferencesRepository.updateWorkout(profileId, value, now)
profilePreferencesRepository.updateLed(profileId, value, now)
profilePreferencesRepository.updateVbt(profileId, value, now)
```

These existing SQLDelight updates intentionally preserve each target server revision, increment local generation, set dirty, and stamp the local import time. Do not write raw preference SQL or restore backup sync metadata.

- [ ] **Step 8: Make streaming import defer all order-dependent profile state until root end**

The root JSON object is unordered. `importFromStream` must not decide v4/v5 behavior when it first sees a data field. Track these values for the entire parse:

```kotlin
val preImportActiveProfileId = queries.getAllProfiles()
    .executeAsList()
    .firstOrNull { it.isActive == 1L }
    ?.id
var backupVersion = 1
val deferredPreferenceEntries = mutableListOf<ProfilePreferencesBackup>()
var legacyRackFieldPresent = false
var legacyRackElement: JsonElement? = null
val representedProfileIds = linkedSetOf<String>()
var databaseWorkCommitted = false
var activeIdentityNormalized = false
var reconciliationAttempted = false
```

Change the data-field handlers:

1. `equipmentRackItems`: set `legacyRackFieldPresent = true`, capture the complete value with `nav.nextValueAsString()`, and parse only that raw value to `JsonElement`. Do not decode it as `List<RackItem>` and do not call the old `importEquipmentRackItems`. This preserves `JsonNull`, scalar/object, empty array, and malformed typed members until the common root-end v4 decoder.
2. `profilePreferences`: stream the outer array one entry at a time, decode each `ProfilePreferencesBackup`, count malformed entry objects, and retain the successfully decoded entries. Do not restore them yet.
3. `userProfiles`: keep streaming identities in their own database transaction. Insert new identities with `isActive = 0L`, never update an existing identity's active flag from backup input, seed `queries.insertDefaultProfilePreferences(profile.id, 1L)` in that same transaction for both new and existing identities, and add successfully decoded IDs to `representedProfileIds` in wire order.
4. Root `version`: update `backupVersion` wherever it appears; no prior data handler may have consumed version-sensitive state.

Set `databaseWorkCommitted = true` immediately after each existing streaming entity-type transaction returns. This matters because streaming import is intentionally multi-transaction and a malformed later field can fail after earlier rows committed.

Only after `nav.endObject()` for the root:

1. In one database transaction, call `normalizeImportedActiveIdentity(preImportActiveProfileId, representedProfileIds)`. Only after that transaction returns, set `databaseWorkCommitted = true` and `activeIdentityNormalized = true`. This is the only operation that applies active flags and guarantees exactly one preserved/fallback identity.
2. Build `DeferredProfileRestore` using the final root version, preference entries, rack presence/raw `JsonElement`, and buffered represented profile IDs. Do not predecode the rack here; the common helper owns typed v4 decoding.
3. Invoke the same deferred restore/reconciliation completion used by buffered import.

The adversarial-order test must prove:

```text
data/profilePreferences
→ data/equipmentRackItems
→ data/userProfiles
→ root version
→ root end
→ normalize exactly one target active identity
→ one version-correct deferred restore
```

Do not buffer sessions, metrics, or other potentially large tables; only the small profile preference/rack payload and identity-ID set are deferred.

- [ ] **Step 9: Reconcile after restore and on every post-commit failure**

Successful buffered and streaming imports must have this exact order:

```text
database identity/entity writes
→ normalize exactly one active identity from pre-import state
→ deferred profile-preference or v4 rack restore
→ userProfileRepository.reconcileActiveProfileContext()
→ Result.success
```

Wrap deferred restore so reconciliation is still attempted when preference storage fails after the database commit:

```kotlin
private suspend fun restoreDeferredAndReconcile(
    deferred: DeferredProfileRestore,
    now: Long,
    onInvalid: (String, String, Throwable?) -> Unit,
) {
    try {
        restoreDeferredProfileState(deferred, now, onInvalid)
    } catch (restoreFailure: Throwable) {
        val reconcileFailure = runCatching {
            withContext(NonCancellable) {
                userProfileRepository.reconcileActiveProfileContext()
            }
        }.exceptionOrNull()
        reconcileFailure?.let(restoreFailure::addSuppressed)
        throw restoreFailure
    }

    userProfileRepository.reconcileActiveProfileContext()
}
```

Set `reconciliationAttempted = true` immediately before calling this wrapper because it guarantees an attempt on both its success and restore-failure paths. If the normal reconciliation call itself fails, the import fails.

In each outer catch, when `databaseWorkCommitted` is true and `reconciliationAttempted` is false, first reapply the same active-identity normalization and then make one best-effort reconciliation. Attempt reconciliation even if normalization itself fails:

```kotlin
withContext(NonCancellable) {
    val normalizationFailure = if (activeIdentityNormalized) {
        null
    } else {
        runCatching {
            database.transaction {
                normalizeImportedActiveIdentity(
                    preImportActiveProfileId,
                    representedProfileIds,
                )
            }
            activeIdentityNormalized = true
        }.exceptionOrNull()
    }
    normalizationFailure?.let(importFailure::addSuppressed)

    val reconcileFailure = runCatching {
        userProfileRepository.reconcileActiveProfileContext()
    }.exceptionOrNull()
    reconcileFailure?.let(importFailure::addSuppressed)
}
```

Keep the original import/parse failure primary. Do not normalize/reconcile a buffered transaction that rolled back before commit. Buffered import sets `activeIdentityNormalized` only after its all-entity transaction commits; streaming sets it only after the root-end normalization transaction commits. A streaming failure after earlier entity work but before root-end normalization therefore runs normalization before reconciliation, while a later failure does not perform a redundant active write. Imported backup `isActive` flags are never consulted on either the success or recovery path.

Construct and return `Result.success(ImportResult(...))` only after the wrapper completes. Reconciliation may read local safety while rebuilding `Ready`, but backup code must never serialize, overwrite, clear, or derive local-safety values.

- [ ] **Step 10: Pass focused backup, parity, DI, and platform compilation checks**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*BackupSerializationTest*" --tests "*BackupJsonNavigatorTest*" --tests "*StreamingImportRoundTripTest*" --tests "*DataBackupManager*" --tests "*KoinModuleVerifyTest*" --console=plain
```

Expected: PASS for the full v1-v6 matrix, A/B section round trip, local-only privacy, metadata semantics, malformed-section isolation, identity allow-listing, buffered/streaming parity, adversarial field ordering, and reconciliation recovery behavior.

Then run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinAndroid :shared:compileKotlinIosSimulatorArm64 --console=plain
```

Expected: PASS with the updated Android/iOS constructors and platform bindings.

- [ ] **Step 11: Commit backup v5**

```powershell
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/util/BackupSerializationTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/DataBackupManagerRoutineNameTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/BackupJsonNavigatorTest.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt
git commit -m "feat: back up profile preferences in schema v5"
```

### Task 9: Wire dependency injection and verify the data foundation

**Files:**
- Verify first; modify only a genuine omission reported by verification:
  - `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`
  - `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt`
  - `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt`
  - `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt`
  - `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt`
  - `shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt`
  - `shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt`
  - any concrete MainViewModel/application host file named by a compiler or Koin failure.

**Interfaces:**
- Consumes: every implementation in this plan.
- Produces: a statically verified common/Android-host Koin graph, successful iOS DI compilation, and the stable foundation required by both later plans.
- Expected scope: Tasks 3-8 already own their bindings and constructor changes, so this task is verification-first and should normally make no production-code change or commit.

- [ ] **Step 1: Verify the existing static Koin graph before changing it**

```kotlin
private val platformProvidedTypes = listOf(
    DriverFactory::class,
    Settings::class,
    BleRepository::class,
    CsvExporter::class,
    CsvImporter::class,
    DataBackupManager::class,
    ConnectivityChecker::class,
    SupabaseConfig::class,
    SafeWordListenerFactory::class,
    HealthIntegration::class,
    HealthWorkoutWriter::class,
    WorkoutServiceController::class,
    Function0::class,
    Function2::class,
    Function3::class,
    Function4::class,
    Function5::class,
)

@Test
fun verifyAppModule() {
    appModule.verify(extraTypes = platformProvidedTypes)
}
```

Do not invent a runtime `startTestKoin().use` fixture: the project has no such helper, and its platform graph needs concrete Android dependencies. The JVM `verify` test cross-checks every constructor definition in `appModule`; Task 5's focused behavior tests establish the concrete gate/rack/health/safe-word implementations, and Step 5 separately compiles the iOS module.

- [ ] **Step 2: Run Koin verification**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*KoinModuleVerifyTest*" --console=plain
```

Expected: PASS. If it fails, a Task 3-8 binding or required `extraTypes` entry was missed; fix that omission before continuing rather than documenting an expected partial graph.

- [ ] **Step 3: Repair only a verified graph omission**

If Step 2 passes, make no graph edit. Retain and verify Task 3's focused-store/expanded `UserProfileRepository` registrations, Task 4's legacy-reader/expanded `MigrationManager`, Task 5's `RequiredMigrationGate`, profile rack, health, and safe-word registrations, Task 7's shared merger, and Task 8's Android/iOS backup-manager constructor bindings; do not add duplicate definitions. `SettingsManager` remains owned by `MainViewModel` because it requires `viewModelScope`; do not register it as an application singleton.

If verification or platform compilation fails, repair only the concrete missing definition, `extraTypes` entry, constructor argument, or platform host binding named by that failure and rerun the failing command immediately. Avoid a dependency cycle: `UserProfileRepository` may depend on focused stores, gamification, and the merger, but neither focused store nor the merger may depend on `UserProfileRepository`.

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
$preferencesFile = 'shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt'
$productionRoots = @(
    'shared/src/commonMain',
    'shared/src/androidMain',
    'shared/src/iosMain'
)

# Derive the complete legacy-writer surface from the concrete Settings manager.
$legacySetterNames = @(
    rg -o --replace '$1' `
        'internal\s+(?:suspend\s+)?fun\s+(set[A-Za-z0-9_]+)\s*\(' `
        $preferencesFile
)
if ($LASTEXITCODE -ne 0 -or $legacySetterNames.Count -eq 0) {
    throw 'Could not derive internal legacy preference setters'
}
$setterAlternation = ($legacySetterNames | ForEach-Object {
    [Regex]::Escape($_)
}) -join '|'

# The only legitimate same-name calls are these reviewed file/receiver/type pairs.
$allowedCallContexts = @(
    [pscustomobject]@{
        File = 'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt'
        Receiver = 'settingsManager'
        TypeProof = '\bval\s+settingsManager\s*=\s*SettingsManager\s*\('
    },
    [pscustomobject]@{
        File = 'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt'
        Receiver = 'viewModel'
        TypeProof = '\bviewModel\s*:\s*MainViewModel\b'
    },
    [pscustomobject]@{
        File = 'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt'
        Receiver = 'settingsManager'
        TypeProof = '\bprivate\s+val\s+settingsManager\s*:\s*SettingsManager\b'
    },
    [pscustomobject]@{
        File = 'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt'
        Receiver = 'bleRepository'
        TypeProof = '\bprivate\s+val\s+bleRepository\s*:\s*BleRepository\b'
    }
)
$allowedFileReceiverPairs = @{}
foreach ($context in $allowedCallContexts) {
    rg -n --pcre2 $context.TypeProof $context.File | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Missing reviewed type proof for $($context.File)|$($context.Receiver)"
    }
    $allowedFileReceiverPairs["$($context.File)|$($context.Receiver)"] = $true
}

# Search every production source set, normalize each path, extract every receiver
# on each matching line, and reject anything outside the proven allowlist.
$callPattern = '\b(?<receiver>[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*(?:' +
    $setterAlternation + ')\s*\('
$callCandidates = @(
    rg -n --pcre2 --glob '*.kt' --glob '!PreferencesManager.kt' `
        $callPattern $productionRoots
)
if ($LASTEXITCODE -gt 1) {
    throw "Legacy-setter call search failed with exit code $LASTEXITCODE"
}
$unexpectedLegacyCalls = [System.Collections.Generic.List[string]]::new()
foreach ($candidate in $callCandidates) {
    $parsed = [Regex]::Match(
        $candidate,
        '^(?<file>.*?):(?<line>[0-9]+):(?<source>.*)$'
    )
    if (-not $parsed.Success) {
        throw "Could not parse legacy-setter candidate: $candidate"
    }
    $normalizedFile = $parsed.Groups['file'].Value -replace '\\', '/'
    $receiverMatches = [Regex]::Matches(
        $parsed.Groups['source'].Value,
        $callPattern
    )
    foreach ($receiverMatch in $receiverMatches) {
        $receiver = $receiverMatch.Groups['receiver'].Value
        $pair = "$normalizedFile|$receiver"
        if (-not $allowedFileReceiverPairs.ContainsKey($pair)) {
            $unexpectedLegacyCalls.Add("$pair :: $candidate")
        }
    }
}
if ($unexpectedLegacyCalls.Count -gt 0) {
    $unexpectedLegacyCalls | Write-Host
    throw 'Production code still calls an internal legacy Settings preference writer'
}

# Build the forbidden wire-name set in camelCase and snake_case. Include local
# safety/calibration/consent aggregates plus unprefixed and per-section sync metadata.
$wireNames = [System.Collections.Generic.List[string]]::new()
@(
    'localSafety', 'local_safety',
    'safeWord', 'safe_word',
    'safeWordCalibrated', 'safe_word_calibrated',
    'safeWordCalibration', 'safe_word_calibration',
    'adultsOnlyConfirmed', 'adults_only_confirmed',
    'adultsOnlyPrompted', 'adults_only_prompted',
    'adultsOnlyConsent', 'adults_only_consent',
    'adultConsent', 'adult_consent',
    'localGeneration', 'local_generation',
    'serverRevision', 'server_revision',
    'dirty'
) | ForEach-Object { $wireNames.Add($_) }
foreach ($section in @('core', 'rack', 'workout', 'led', 'vbt')) {
    $wireNames.Add("${section}UpdatedAt")
    $wireNames.Add("${section}_updated_at")
    $wireNames.Add("${section}LocalGeneration")
    $wireNames.Add("${section}_local_generation")
    $wireNames.Add("${section}ServerRevision")
    $wireNames.Add("${section}_server_revision")
    $wireNames.Add("${section}Dirty")
    $wireNames.Add("${section}_dirty")
}
# Do not deny unprefixed updatedAt: ordinary backup entities legitimately use it.
# ProfilePreferencesBackup has only typed section properties, so preference metadata
# cannot expose an unprefixed updatedAt; section-prefixed forms are denied above.
$wireAlternation = (
    $wireNames |
        Sort-Object -Unique |
        ForEach-Object { [Regex]::Escape($_) }
) -join '|'

# Match only Kotlin declarations, property access/named assignment, or an exact
# serialized key. Prose such as the privacy summary remains allowed.
$wirePattern = '\b(?:val|var)\s+(?:' + $wireAlternation + ')\b' +
    '|\.(?:' + $wireAlternation + ')\b' +
    '|\b(?:' + $wireAlternation + ')\s*=' +
    '|["''](?:' + $wireAlternation + ')["'']'
$wireLeaks = @(
    rg -n --pcre2 $wirePattern `
        shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt `
        shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt
)
if ($LASTEXITCODE -gt 1) {
    throw "Backup privacy search failed with exit code $LASTEXITCODE"
}
if ($wireLeaks.Count -gt 0) {
    $wireLeaks | Write-Host
    throw 'Local safety/consent or sync metadata entered the backup wire path'
}

git diff --check
```

Expected: all internal legacy setter names are derived; every same-name production call maps to one of the four reviewed file/receiver pairs whose `SettingsManager`, `MainViewModel`, or `BleRepository` type proof is present; every other pair fails. No local-safety/consent or unprefixed/section-prefixed sync-metadata property, access, named assignment, or exact camelCase/snake_case serialized key appears in the backup wire path. `git diff --check` prints nothing, and privacy-summary prose is intentionally not matched.

- [ ] **Step 7: Conditionally commit genuine wiring repairs**

```powershell
$stagedState = git diff --cached --quiet
if ($LASTEXITCODE -eq 1) {
    throw 'Index already contains staged changes; review them before Task 9'
}
if ($LASTEXITCODE -gt 1) {
    throw 'Could not inspect the Git index'
}

$reviewableWiringPaths = @(
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt',
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt',
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt',
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt',
    'shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt',
    'shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt',
    'shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt',
    'shared/src/androidMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.android.kt',
    'shared/src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt',
    'shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt',
    'androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt'
)
$reviewedWiringFiles = @(
    git diff --name-only --diff-filter=AM -- $reviewableWiringPaths
)

if ($reviewedWiringFiles.Count -eq 0) {
    Write-Host 'Task 9 verification passed with no wiring changes; skip the commit.'
} else {
    $reviewedWiringFiles | ForEach-Object {
        Write-Host "Reviewing Task 9 wiring change: $_"
    }
    git add -- $reviewedWiringFiles
    git diff --cached --check
    if ($LASTEXITCODE -ne 0) {
        throw 'Staged Task 9 wiring diff failed whitespace validation'
    }
    git diff --cached --quiet
    if ($LASTEXITCODE -eq 0) {
        Write-Host 'No effective staged wiring change; skip the empty commit.'
    } elseif ($LASTEXITCODE -eq 1) {
        git commit -m "chore: wire profile preference foundation"
    } else {
        throw 'Could not inspect the staged Task 9 wiring diff'
    }
}
```

Expected: normally no files changed and no Task 9 commit is created. If a verification command required a reviewed repair, stage exactly the changed common/platform/host files that implement that repair, rerun the affected verification, and create the commit only when the staged diff is non-empty. Add a compiler-named wiring file to `$reviewableWiringPaths` only after reviewing it; never stage unrelated plan or feature work.

## Completion Gate

- Schema/migration/reconciliation all report version 43.
- Every existing profile has `legacy_migration_version = 1` after the awaited migration; a new profile starts with defaults and `vbtEnabled = true`.
- A/B tests prove every profile-owned consumer follows the active context without restart.
- A migration failure cannot expose root navigation; Retry succeeds without overwriting completed rows.
- Profile deletion handles overlapping PR/badge keys and eventually deletes local safety keys.
- Backup v5 round-trips every syncable section while excluding safety and sync metadata.
- The working tree contains only reviewed changes for this plan before either dependent plan begins.

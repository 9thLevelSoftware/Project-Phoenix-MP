package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.RpgProfile
import com.devil.phoenixproject.testutil.createTestSchema
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SqlDelightProfileRecoveryRepositoryTest {
    @Test
    fun `registered recovery moves every content table through the real merger`() = runTest {
        val fixture = fixture()
        val target = fixture.profiles.createProfile("Target", 1)
        fixture.insertAllRecoveryContentRows("default")
        fixture.discovery.discoverProfileData(fixture.profiles.allProfiles.value)
        val pending = fixture.queries.selectPendingProfileRecoveryBySourceKey("profile:default")
            .executeAsOne()

        assertEquals(
            ProfileRecoveryResolution.Resolved,
            fixture.repository.moveToProfile(pending.recovery_id, target.id, null),
        )

        PROFILE_RECOVERY_CONTENT_TABLES.forEach { table ->
            val profileColumn = if (table.startsWith("External")) "profileId" else "profile_id"
            assertEquals(0L, fixture.profileRowCount(table, profileColumn, "default"), table)
            assertTrue(fixture.profileRowCount(table, profileColumn, target.id) > 0L, table)
        }
    }

    @Test
    fun `keep with Default resolves orphan collision without a cloud transfer`() = runTest {
        val fixture = fixture()
        fixture.insertTargetPersonalRecordCollision("default")
        fixture.insertLocalPersonalRecord("deleted-profile", "orphan-pr", 80.0)
        fixture.discovery.discoverProfileData(fixture.profiles.allProfiles.value)
        val pending = fixture.queries.selectPendingProfileRecoveryBySourceKey("profile:deleted-profile")
            .executeAsOne()
        fixture.repository.refresh()

        assertEquals(
            ProfileRecoveryResolution.Resolved,
            fixture.repository.keepWithDefault(pending.recovery_id, signedInOwnerUserId = null),
        )

        assertNotNull(pendingResolvedAt(fixture, pending.recovery_id))
        assertEquals(
            1,
            fixture.queries.selectAllRecords("default").executeAsList()
                .count { it.exerciseId == "deadlift" },
        )
        assertTrue(fixture.queries.selectAllOwnershipTransfers().executeAsList().isEmpty())
    }

    @Test
    fun `known owner recovery keeps exact roots pending and retries derived recompute honestly`() = runTest {
        val fixture = fixture()
        val target = fixture.profiles.createProfile("Target", 1)
        fixture.profiles.linkToSupabase("default", "owner-a")
        fixture.insertTargetPersonalRecordCollision(target.id)
        fixture.insertOwnershipRoots("default")
        fixture.discovery.discoverProfileData(fixture.profiles.allProfiles.value)
        val pending = fixture.queries.selectPendingProfileRecoveryBySourceKey("profile:default")
            .executeAsOne()
        fixture.gamification.failSave = true
        fixture.repository.refresh()

        assertFailsWith<InjectedDerivedRepairFailure> {
            fixture.repository.moveToProfile(pending.recovery_id, target.id, "owner-a")
        }

        assertNull(pendingResolvedAt(fixture, pending.recovery_id))
        assertEquals(target.id, fixture.queries.selectSessionById("component-a").executeAsOne().profile_id)
        assertEquals(target.id, fixture.queries.selectSessionById("component-b").executeAsOne().profile_id)
        assertEquals(target.id, fixture.queries.selectTrainingCycleById("cycle-root").executeAsOne().profile_id)
        assertEquals(
            1,
            fixture.queries.selectAllRecords(target.id).executeAsList()
                .count { it.exerciseId == "deadlift" },
        )
        assertEquals(
            target.id,
            fixture.queries.selectCycleSyncState("cycle-root").executeAsOne().profile_id,
        )
        assertEquals(1, fixture.queries.selectAllOwnershipTransfers().executeAsList().size)

        fixture.gamification.failSave = false
        assertEquals(
            ProfileRecoveryResolution.Resolved,
            fixture.repository.moveToProfile(pending.recovery_id, target.id, "owner-a"),
        )

        assertNotNull(pendingResolvedAt(fixture, pending.recovery_id))
        assertEquals("owner-a", fixture.queries.getProfileById(target.id).executeAsOne().supabase_user_id)
        val transfer = fixture.queries.selectAllOwnershipTransfers().executeAsList().single()
        assertEquals(listOf("portal-parent"), decodeOwnershipIds(transfer.workout_session_ids_json))
        assertEquals(listOf("routine-root"), decodeOwnershipIds(transfer.routine_ids_json))
        assertEquals(listOf("cycle-root"), decodeOwnershipIds(transfer.cycle_ids_json))
        assertEquals(listOf("pr-root"), decodeOwnershipIds(transfer.personal_record_ids_json))
    }

    @Test
    fun `unknown cloud owner is bound only after exact authenticated proof`() = runTest {
        var verifiedSnapshot: ProfileRecoverySourceSnapshot? = null
        val verifier = object : ProfileRecoverySourceVerifier {
            override suspend fun verify(source: ProfileRecoverySourceSnapshot): ProfileRecoverySourceVerification {
                verifiedSnapshot = source
                return ProfileRecoverySourceVerification(
                    verified = true,
                    authenticatedOwnerUserId = "owner-a",
                    verifiedProofCount = source.distinctProofCount,
                )
            }
        }
        val fixture = fixture(verifier)
        val target = fixture.profiles.createProfile("Target", 1)
        fixture.insertWorkoutComponents("default", portalOrigin = 1L)
        fixture.discovery.discoverProfileData(fixture.profiles.allProfiles.value)
        val pending = fixture.queries.selectPendingProfileRecoveryBySourceKey("profile:default")
            .executeAsOne()
        fixture.repository.refresh()

        assertEquals(
            ProfileRecoveryResolution.Resolved,
            fixture.repository.moveToProfile(pending.recovery_id, target.id, "owner-a"),
        )

        assertEquals(listOf("portal-parent"), verifiedSnapshot?.proofWorkoutSessionIds)
        assertEquals("owner-a", fixture.queries.getProfileById(target.id).executeAsOne().supabase_user_id)
        assertNotNull(pendingResolvedAt(fixture, pending.recovery_id))
    }

    @Test
    fun `known owner recovery rejects a target bound to another account`() = runTest {
        val fixture = fixture()
        val target = fixture.profiles.createProfile("Other account", 1)
        fixture.profiles.linkToSupabase("default", "owner-a")
        fixture.profiles.linkToSupabase(target.id, "owner-b")
        fixture.insertWorkoutComponents("default", portalOrigin = 1L)
        fixture.discovery.discoverProfileData(fixture.profiles.allProfiles.value)
        val pending = fixture.queries.selectPendingProfileRecoveryBySourceKey("profile:default")
            .executeAsOne()

        assertEquals(
            ProfileRecoveryResolution.AccountMismatch("owner-a", "owner-b"),
            fixture.repository.moveToProfile(pending.recovery_id, target.id, "owner-a"),
        )
        assertEquals("default", fixture.queries.selectSessionById("component-a").executeAsOne().profile_id)
        assertNull(pendingResolvedAt(fixture, pending.recovery_id))
        assertTrue(fixture.queries.selectAllOwnershipTransfers().executeAsList().isEmpty())
    }

    @Test
    fun `failed cloud proof leaves unknown owner and source rows untouched`() = runTest {
        val verifier = object : ProfileRecoverySourceVerifier {
            override suspend fun verify(source: ProfileRecoverySourceSnapshot) =
                ProfileRecoverySourceVerification(false, null, 0)
        }
        val fixture = fixture(verifier)
        val target = fixture.profiles.createProfile("Target", 1)
        fixture.insertWorkoutComponents("default", portalOrigin = 1L)
        fixture.discovery.discoverProfileData(fixture.profiles.allProfiles.value)
        val pending = fixture.queries.selectPendingProfileRecoveryBySourceKey("profile:default")
            .executeAsOne()

        assertEquals(
            ProfileRecoveryResolution.VerificationFailed,
            fixture.repository.moveToProfile(pending.recovery_id, target.id, "owner-a"),
        )
        assertEquals("default", fixture.queries.selectSessionById("component-a").executeAsOne().profile_id)
        assertNull(
            fixture.queries.selectPendingProfileRecoveryById(pending.recovery_id)
                .executeAsOne().owner_user_id,
        )
        assertTrue(fixture.queries.selectAllOwnershipTransfers().executeAsList().isEmpty())
    }

    private suspend fun fixture(
        verifier: ProfileRecoverySourceVerifier? = null,
    ): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(::createTestSchema)
        val database = PhoenixDatabase(driver)
        val preferences = SqlDelightProfilePreferencesRepository(database)
        val safety = SettingsProfileLocalSafetyStore(MapSettings())
        val realGamification = SqlDelightGamificationRepository(database)
        val gamification = FailingGamificationRepository(realGamification)
        val barrier = ProfileMutationBarrier()
        val merger = ProfileScopedDataMerger(database)
        val profiles = SqlDelightUserProfileRepository(
            database,
            preferences,
            safety,
            realGamification,
            merger,
            barrier,
        )
        preferences.seedMissingProfiles()
        profiles.reconcileActiveProfileContext()
        val baseline = SqlDelightProfileExerciseBaselineRepository(database)
        val discovery = ProfileRecoveryDiscovery(database, driver, now = { 100L })
        val repository = SqlDelightProfileRecoveryRepository(
            database = database,
            driver = driver,
            profileScopedDataMerger = merger,
            baselineRepository = baseline,
            legacyBaselineRepair = LegacyBaselineRepair(baseline),
            userProfileRepository = profiles,
            gamificationRepository = gamification,
            profileMutationBarrier = barrier,
            activityTracker = ProfileRecoveryActivityTracker(),
            profileRecoverySourceVerifier = verifier,
            now = { 200L },
            newId = { "mutation-1" },
        )
        return Fixture(driver, database, profiles, gamification, discovery, repository)
    }

    private fun pendingResolvedAt(fixture: Fixture, recoveryId: String): Long? =
        fixture.queries.selectAllPendingProfileRecoveries().executeAsList()
            .first { it.recovery_id == recoveryId }
            .resolved_at

    private data class Fixture(
        val driver: SqlDriver,
        val database: PhoenixDatabase,
        val profiles: SqlDelightUserProfileRepository,
        val gamification: FailingGamificationRepository,
        val discovery: ProfileRecoveryDiscovery,
        val repository: SqlDelightProfileRecoveryRepository,
    ) {
        val queries get() = database.phoenixDatabaseQueries

        fun profileRowCount(table: String, profileColumn: String, profileId: String): Long {
            var result = 0L
            driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM $table WHERE $profileColumn = ?",
                mapper = { cursor ->
                    if (cursor.next().value) result = cursor.getLong(0) ?: 0L
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) { bindString(0, profileId) }
            return result
        }

        fun insertAllRecoveryContentRows(profileId: String) {
            driver.execute(null, "INSERT INTO Exercise(id,name,muscleGroup,muscleGroups,equipment,defaultCableConfig) VALUES ('bench','Bench','Chest','Chest','BAR','DOUBLE')", 0)
            insertWorkoutComponents(profileId, portalOrigin = 0L)
            execute("UPDATE WorkoutSession SET serverId = NULL WHERE profile_id = ?", profileId)
            execute("INSERT INTO RoutineGroup(id,name,createdAt,profile_id) VALUES ('group','Group',1,?)", profileId)
            execute("INSERT INTO Routine(id,name,createdAt,profile_id,groupId) VALUES ('routine','Routine',1,?,'group')", profileId)
            execute("INSERT INTO TrainingCycle(id,name,created_at,profile_id) VALUES ('cycle','Cycle',1,?)", profileId)
            execute("INSERT INTO AssessmentResult(exerciseId,estimatedOneRepMaxKg,loadVelocityData,createdAt,profile_id) VALUES ('bench',100,'{}',1,?)", profileId)
            execute("INSERT INTO VelocityOneRepMaxEstimate(exerciseId,estimatedPerCableKg,mvtUsedMs,r2,distinctLoads,computedAt,profile_id) VALUES ('bench',50,200,.9,3,1,?)", profileId)
            execute("INSERT INTO PersonalRecord(exerciseId,exerciseName,weight,reps,oneRepMax,achievedAt,workoutMode,prType,volume,phase,profile_id,uuid) VALUES ('bench','Bench',50,5,60,1,'OldSchool','MAX_WEIGHT',250,'COMBINED',?,'all-pr')", profileId)
            execute("INSERT INTO EarnedBadge(badgeId,earnedAt,profile_id) VALUES ('all-badge',1,?)", profileId)
            execute("INSERT INTO StreakHistory(startDate,endDate,length,profile_id) VALUES (1,2,2,?)", profileId)
            execute("INSERT INTO ExerciseMvt(exerciseId,profile_id,personalMvtMs,sampleCount,updatedAt) VALUES ('bench',?,250,2,1)", profileId)
            execute("INSERT INTO ProgressionEvent(id,exercise_id,suggested_weight_kg,previous_weight_kg,reason,timestamp,profile_id) VALUES ('progression','bench',55,50,'test',1,?)", profileId)
            execute("INSERT INTO ExternalActivity(id,externalId,provider,name,startedAt,syncedAt,profileId,rawData) VALUES ('activity','activity','hevy','Activity',1,1,?,'bytes')", profileId)
            execute("INSERT INTO ExternalBodyMeasurement(id,externalId,provider,measurementType,value,unit,measuredAt,syncedAt,profileId,rawData) VALUES ('body','body','health','weight',80,'kg',1,1,?,'bytes')", profileId)
            execute("INSERT INTO ExternalExerciseTemplate(id,externalId,provider,title,profileId,rawData) VALUES ('template','template','hevy','Template',?,'bytes')", profileId)
            execute("INSERT INTO ExternalExerciseTemplateMapping(id,provider,externalTemplateId,localExerciseId,profileId,createdAt,updatedAt,rawData) VALUES ('mapping','hevy','template','bench',?,1,1,'bytes')", profileId)
            execute("INSERT INTO ExternalProgram(id,externalId,provider,name,syncedAt,profileId,rawData) VALUES ('program','program','hevy','Program',1,?,'bytes')", profileId)
            execute("INSERT INTO ExternalRoutine(id,externalId,provider,title,syncedAt,rawData,profileId) VALUES ('external-routine','external-routine','hevy','Routine',1,'bytes',?)", profileId)
            execute("INSERT INTO ExternalRoutineFolder(id,externalId,provider,title,profileId,rawData) VALUES ('folder','folder','hevy','Folder',?,'bytes')", profileId)
        }

        private fun execute(sql: String, profileId: String) {
            driver.execute(null, sql, 1) { bindString(0, profileId) }
        }

        fun insertOwnershipRoots(profileId: String) {
            insertWorkoutComponents(profileId, portalOrigin = 0L)
            driver.execute(
                null,
                "INSERT INTO Routine(id,name,createdAt,serverId,profile_id) VALUES ('routine-root','R',1,'remote-routine',?)",
                1,
            ) { bindString(0, profileId) }
            driver.execute(
                null,
                "INSERT INTO TrainingCycle(id,name,created_at,profile_id,deletedAt) VALUES ('cycle-root','C',1,?,2)",
                1,
            ) { bindString(0, profileId) }
            driver.execute(
                null,
                "INSERT INTO CycleSyncState(cycle_id,profile_id,account_id,dirty_generation,acknowledged_generation,pending_delete_updated_at,pending_delete_generation) VALUES ('cycle-root',?,'owner-a',2,1,2,2)",
                1,
            ) { bindString(0, profileId) }
            driver.execute(
                null,
                "INSERT INTO PersonalRecord(exerciseId,exerciseName,weight,reps,oneRepMax,achievedAt,workoutMode,prType,volume,phase,serverId,profile_id,uuid) VALUES ('deadlift','Deadlift',80,5,90,1,'Old School','MAX_WEIGHT',400,'COMBINED','remote-pr',?,'pr-root')",
                1,
            ) { bindString(0, profileId) }
        }

        fun insertWorkoutComponents(profileId: String, portalOrigin: Long) {
            listOf("component-a", "component-b").forEach { id ->
                driver.execute(
                    null,
                    "INSERT INTO WorkoutSession(id,timestamp,mode,targetReps,weightPerCableKg,routineSessionId,serverId,portalOrigin,profile_id) VALUES (?,1,'OldSchool',5,20,'portal-parent','remote-parent',?,?)",
                    3,
                ) {
                    bindString(0, id)
                    bindLong(1, portalOrigin)
                    bindString(2, profileId)
                }
            }
        }

        fun insertTargetPersonalRecordCollision(profileId: String) {
            driver.execute(
                null,
                "INSERT INTO PersonalRecord(exerciseId,exerciseName,weight,reps,oneRepMax,achievedAt,workoutMode,prType,volume,phase,profile_id,uuid) VALUES ('deadlift','Target Deadlift',60,5,70,1,'Old School','MAX_WEIGHT',300,'COMBINED',?,'target-pr')",
                1,
            ) { bindString(0, profileId) }
        }

        fun insertLocalPersonalRecord(profileId: String, uuid: String, weight: Double) {
            driver.execute(
                null,
                "INSERT INTO PersonalRecord(exerciseId,exerciseName,weight,reps,oneRepMax,achievedAt,workoutMode,prType,volume,phase,profile_id,uuid) VALUES ('deadlift','Deadlift',?,5,90,2,'Old School','MAX_WEIGHT',400,'COMBINED',?,?)",
                3,
            ) {
                bindDouble(0, weight)
                bindString(1, profileId)
                bindString(2, uuid)
            }
        }
    }

    private class FailingGamificationRepository(
        delegate: GamificationRepository,
    ) : GamificationRepository by delegate {
        var failSave: Boolean = false

        override suspend fun saveRpgProfile(profile: RpgProfile, profileId: String) {
            if (failSave) throw InjectedDerivedRepairFailure()
        }
    }

    private class InjectedDerivedRepairFailure : IllegalStateException()
}

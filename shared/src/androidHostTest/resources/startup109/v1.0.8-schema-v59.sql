-- v1.0.8 (622e60332fd43b51a65d43e3f1e8f2e23e41cf78), schema version 59.
-- Source: shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/PhoenixDatabase.sq
-- Extracted all CREATE TABLE / CREATE [UNIQUE] INDEX statements in source order.
-- SQL line comments removed; query declarations and query bodies excluded.
-- Source UTF-8 SHA-256: d3dcb1524c8e7a6dcf54e5b90de55d39f65cddb36cf11576226678e50bcf269a

CREATE TABLE Exercise (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    displayName TEXT,
    description TEXT,
    created INTEGER NOT NULL DEFAULT 0,
    muscleGroup TEXT NOT NULL,
    muscleGroups TEXT NOT NULL,
    muscles TEXT,
    equipment TEXT NOT NULL,
    movement TEXT,
    sidedness TEXT,
    grip TEXT,
    gripWidth TEXT,
    minRepRange REAL,
    popularity REAL NOT NULL DEFAULT 0,
    archived INTEGER NOT NULL DEFAULT 0,
    isFavorite INTEGER NOT NULL DEFAULT 0,
    isCustom INTEGER NOT NULL DEFAULT 0,
    timesPerformed INTEGER NOT NULL DEFAULT 0,
    lastPerformed INTEGER,
    aliases TEXT,
    defaultCableConfig TEXT NOT NULL,
    one_rep_max_kg REAL DEFAULT NULL,

    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER,

    mvtOverrideMs REAL,


    isBodyweight INTEGER
);

CREATE INDEX idx_exercise_popularity ON Exercise(popularity DESC, name ASC);

CREATE INDEX idx_exercise_last_performed ON Exercise(lastPerformed DESC);

CREATE TABLE ExerciseImage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exerciseId TEXT NOT NULL,
    url TEXT NOT NULL,
    sortOrder INTEGER NOT NULL,
    FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
);

CREATE INDEX idx_exercise_image_exercise ON ExerciseImage(exerciseId);

CREATE TABLE WorkoutSession (
    id TEXT NOT NULL PRIMARY KEY,
    timestamp INTEGER NOT NULL,
    mode TEXT NOT NULL,
    targetReps INTEGER NOT NULL,
    weightPerCableKg REAL NOT NULL,
    progressionKg REAL NOT NULL DEFAULT 0.0,
    duration INTEGER NOT NULL DEFAULT 0,
    totalReps INTEGER NOT NULL DEFAULT 0,
    warmupReps INTEGER NOT NULL DEFAULT 0,
    workingReps INTEGER NOT NULL DEFAULT 0,
    isJustLift INTEGER NOT NULL DEFAULT 0,
    stopAtTop INTEGER NOT NULL DEFAULT 0,
    eccentricLoad INTEGER NOT NULL DEFAULT 100,
    echoLevel INTEGER NOT NULL DEFAULT 1,
    exerciseId TEXT,
    exerciseName TEXT,
    routineSessionId TEXT,
    routineName TEXT,
    routineId TEXT,

    safetyFlags INTEGER NOT NULL DEFAULT 0,
    deloadWarningCount INTEGER NOT NULL DEFAULT 0,
    romViolationCount INTEGER NOT NULL DEFAULT 0,
    spotterActivations INTEGER NOT NULL DEFAULT 0,

    peakForceConcentricA REAL,
    peakForceConcentricB REAL,
    peakForceEccentricA REAL,
    peakForceEccentricB REAL,
    avgForceConcentricA REAL,
    avgForceConcentricB REAL,
    avgForceEccentricA REAL,
    avgForceEccentricB REAL,
    heaviestLiftKg REAL,
    totalVolumeKg REAL,
    cableCount INTEGER,
    estimatedCalories REAL,
    warmupAvgWeightKg REAL,
    workingAvgWeightKg REAL,
    burnoutAvgWeightKg REAL,
    peakWeightKg REAL,
    rpe INTEGER,

    avgMcvMmS REAL,
    avgAsymmetryPercent REAL,
    totalVelocityLossPercent REAL,
    dominantSide TEXT,
    strengthProfile TEXT,

    formScore INTEGER,

    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER,

    profile_id TEXT NOT NULL DEFAULT 'default',

    display_multiplier INTEGER,

    externalAddedLoadKg REAL NOT NULL DEFAULT 0,
    counterweightKg REAL NOT NULL DEFAULT 0,
    rackItemsJson TEXT NOT NULL DEFAULT '[]',


    portalOrigin INTEGER NOT NULL DEFAULT 0 CHECK(portalOrigin IN (0, 1)),


    local_sync_generation INTEGER NOT NULL DEFAULT 1 CHECK(local_sync_generation >= 0),
    synced_sync_generation INTEGER NOT NULL DEFAULT 0
        CHECK(synced_sync_generation >= 0 AND synced_sync_generation <= local_sync_generation)
);

CREATE INDEX idx_workout_session_timestamp ON WorkoutSession(timestamp);

CREATE INDEX idx_session_profile ON WorkoutSession(profile_id);

CREATE INDEX idx_session_profile_ts ON WorkoutSession(profile_id, timestamp DESC);

CREATE INDEX idx_session_exercise ON WorkoutSession(exerciseId, profile_id);

CREATE INDEX idx_session_exercise_profile_ts ON WorkoutSession(exerciseId, profile_id, timestamp DESC);

CREATE INDEX idx_session_routine_session ON WorkoutSession(routineSessionId);

CREATE TABLE MetricSample (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    position REAL,
    positionB REAL,
    velocity REAL,
    velocityB REAL,
    load REAL,
    loadB REAL,
    power REAL,
    status INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
);

CREATE INDEX idx_metric_sample_session ON MetricSample(sessionId);

CREATE TABLE PersonalRecord (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exerciseId TEXT NOT NULL,
    exerciseName TEXT NOT NULL,
    weight REAL NOT NULL,
    reps INTEGER NOT NULL,
    oneRepMax REAL NOT NULL,
    achievedAt INTEGER NOT NULL,
    workoutMode TEXT NOT NULL,
    prType TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
    volume REAL NOT NULL DEFAULT 0.0,
    phase TEXT NOT NULL DEFAULT 'COMBINED',

    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER,

    profile_id TEXT NOT NULL DEFAULT 'default',

    cable_count INTEGER,

    uuid TEXT
);

CREATE UNIQUE INDEX idx_pr_unique ON PersonalRecord(exerciseId, workoutMode, prType, phase, profile_id);

CREATE INDEX idx_pr_profile ON PersonalRecord(profile_id);

CREATE UNIQUE INDEX idx_pr_uuid ON PersonalRecord(uuid) WHERE uuid IS NOT NULL;

CREATE TABLE RoutineGroup (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    orderIndex INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL,
    profile_id TEXT NOT NULL DEFAULT 'default'
);

CREATE INDEX idx_routine_group_profile ON RoutineGroup(profile_id);

CREATE TABLE Routine (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    createdAt INTEGER NOT NULL,
    lastUsed INTEGER,
    useCount INTEGER NOT NULL DEFAULT 0,

    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER,

    profile_id TEXT NOT NULL DEFAULT 'default',

    groupId TEXT REFERENCES RoutineGroup(id) ON DELETE SET NULL
);

CREATE INDEX idx_routine_profile ON Routine(profile_id);

CREATE TABLE Superset (
    id TEXT PRIMARY KEY NOT NULL,
    routineId TEXT NOT NULL,
    name TEXT NOT NULL,
    colorIndex INTEGER NOT NULL DEFAULT 0,
    restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
    orderIndex INTEGER NOT NULL,
    FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
);

CREATE INDEX idx_superset_routine ON Superset(routineId);

CREATE TABLE RoutineExercise (
    id TEXT NOT NULL PRIMARY KEY,
    routineId TEXT NOT NULL,

    exerciseName TEXT NOT NULL,
    exerciseMuscleGroup TEXT NOT NULL DEFAULT '',
    exerciseEquipment TEXT NOT NULL DEFAULT '',
    exerciseDefaultCableConfig TEXT NOT NULL DEFAULT 'DOUBLE',
    exerciseId TEXT,

    cableConfig TEXT NOT NULL DEFAULT 'DOUBLE',
    orderIndex INTEGER NOT NULL,
    setReps TEXT NOT NULL DEFAULT '10,10,10',
    weightPerCableKg REAL NOT NULL DEFAULT 0.0,
    setWeights TEXT NOT NULL DEFAULT '',
    mode TEXT NOT NULL DEFAULT 'OldSchool',
    eccentricLoad INTEGER NOT NULL DEFAULT 100,
    echoLevel INTEGER NOT NULL DEFAULT 1,
    progressionKg REAL NOT NULL DEFAULT 0.0,
    restSeconds INTEGER NOT NULL DEFAULT 60,
    duration INTEGER,
    setRestSeconds TEXT NOT NULL DEFAULT '[]',
    perSetRestTime INTEGER NOT NULL DEFAULT 0,
    isAMRAP INTEGER NOT NULL DEFAULT 0,

    supersetId TEXT,
    orderInSuperset INTEGER NOT NULL DEFAULT 0,

    usePercentOfPR INTEGER NOT NULL DEFAULT 0,
    weightPercentOfPR INTEGER NOT NULL DEFAULT 80,
    prTypeForScaling TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
    setWeightsPercentOfPR TEXT,

    scalingBasis TEXT,

    stallDetectionEnabled INTEGER NOT NULL DEFAULT 1,
    stopAtTop INTEGER NOT NULL DEFAULT 0,
    repCountTiming TEXT NOT NULL DEFAULT 'TOP',

    setEchoLevels TEXT NOT NULL DEFAULT '',
    warmupSets TEXT NOT NULL DEFAULT '',
    defaultRackItemIds TEXT NOT NULL DEFAULT '[]',
    rackBehaviorOverrides TEXT NOT NULL DEFAULT '{}',



    isBodyweight INTEGER,

    dropSetEnabled INTEGER NOT NULL DEFAULT 0,
    dropSetMinWeightKg REAL,

    durationSyncKnown INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE,
    FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE SET NULL,
    FOREIGN KEY (supersetId) REFERENCES Superset(id) ON DELETE SET NULL
);

CREATE INDEX idx_routine_exercise_routine ON RoutineExercise(routineId);

CREATE INDEX idx_routine_exercise_superset ON RoutineExercise(supersetId);

CREATE INDEX idx_routine_exercise_exercise ON RoutineExercise(exerciseId);

CREATE TABLE ConnectionLog (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    eventType TEXT NOT NULL,
    level TEXT NOT NULL,
    deviceAddress TEXT,
    deviceName TEXT,
    message TEXT NOT NULL,
    details TEXT,
    metadata TEXT
);

CREATE INDEX idx_connection_log_timestamp ON ConnectionLog(timestamp);

CREATE INDEX idx_connection_log_device ON ConnectionLog(deviceAddress);

CREATE TABLE DiagnosticsHistory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    runtimeSeconds INTEGER NOT NULL,
    faultMask INTEGER NOT NULL,
    temp1 INTEGER NOT NULL,
    temp2 INTEGER NOT NULL,
    temp3 INTEGER NOT NULL,
    temp4 INTEGER NOT NULL,
    temp5 INTEGER NOT NULL,
    temp6 INTEGER NOT NULL,
    temp7 INTEGER NOT NULL,
    temp8 INTEGER NOT NULL,
    containsFaults INTEGER NOT NULL DEFAULT 0,
    timestamp INTEGER NOT NULL
);

CREATE INDEX idx_diagnostics_timestamp ON DiagnosticsHistory(timestamp);

CREATE TABLE PhaseStatistics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT NOT NULL,
    concentricKgAvg REAL NOT NULL,
    concentricKgMax REAL NOT NULL,
    concentricVelAvg REAL NOT NULL,
    concentricVelMax REAL NOT NULL,
    concentricWattAvg REAL NOT NULL,
    concentricWattMax REAL NOT NULL,
    eccentricKgAvg REAL NOT NULL,
    eccentricKgMax REAL NOT NULL,
    eccentricVelAvg REAL NOT NULL,
    eccentricVelMax REAL NOT NULL,
    eccentricWattAvg REAL NOT NULL,
    eccentricWattMax REAL NOT NULL,
    timestamp INTEGER NOT NULL,
    FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
);

CREATE INDEX idx_phase_stats_session ON PhaseStatistics(sessionId);

CREATE TABLE RepMetric (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT NOT NULL,
    repNumber INTEGER NOT NULL,
    isWarmup INTEGER NOT NULL DEFAULT 0,
    startTimestamp INTEGER NOT NULL,
    endTimestamp INTEGER NOT NULL,
    durationMs INTEGER NOT NULL,
    concentricDurationMs INTEGER NOT NULL,
    concentricPositions TEXT NOT NULL,
    concentricLoadsA TEXT NOT NULL,
    concentricLoadsB TEXT NOT NULL,
    concentricVelocities TEXT NOT NULL,
    concentricTimestamps TEXT NOT NULL,
    eccentricDurationMs INTEGER NOT NULL,
    eccentricPositions TEXT NOT NULL,
    eccentricLoadsA TEXT NOT NULL,
    eccentricLoadsB TEXT NOT NULL,
    eccentricVelocities TEXT NOT NULL,
    eccentricTimestamps TEXT NOT NULL,
    peakForceA REAL NOT NULL,
    peakForceB REAL NOT NULL,
    avgForceConcentricA REAL NOT NULL,
    avgForceConcentricB REAL NOT NULL,
    avgForceEccentricA REAL NOT NULL,
    avgForceEccentricB REAL NOT NULL,
    peakVelocity REAL NOT NULL,
    avgVelocityConcentric REAL NOT NULL,
    avgVelocityEccentric REAL NOT NULL,
    rangeOfMotionMm REAL NOT NULL,
    peakPowerWatts REAL NOT NULL,
    avgPowerWatts REAL NOT NULL,
    updatedAt INTEGER,
    serverId TEXT,
    FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
);

CREATE INDEX idx_rep_metric_session ON RepMetric(sessionId);

CREATE INDEX idx_rep_metric_session_rep ON RepMetric(sessionId, repNumber);

CREATE TABLE RepBiomechanics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT NOT NULL,
    repNumber INTEGER NOT NULL,

    mcvMmS REAL NOT NULL,
    peakVelocityMmS REAL NOT NULL,
    velocityZone TEXT NOT NULL,
    velocityLossPercent REAL,
    estimatedRepsRemaining INTEGER,
    shouldStopSet INTEGER NOT NULL DEFAULT 0,

    normalizedForceN TEXT NOT NULL,
    normalizedPositionPct TEXT NOT NULL,
    stickingPointPct REAL,
    strengthProfile TEXT NOT NULL,

    asymmetryPercent REAL NOT NULL,
    dominantSide TEXT NOT NULL,
    avgLoadA REAL NOT NULL,
    avgLoadB REAL NOT NULL,

    timestamp INTEGER NOT NULL,
    FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
);

CREATE INDEX idx_rep_biomechanics_session ON RepBiomechanics(sessionId);

CREATE UNIQUE INDEX idx_rep_biomechanics_session_rep ON RepBiomechanics(sessionId, repNumber);

CREATE TABLE AssessmentResult (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exerciseId TEXT NOT NULL,
    estimatedOneRepMaxKg REAL NOT NULL,
    loadVelocityData TEXT NOT NULL,
    assessmentSessionId TEXT,
    userOverrideKg REAL,
    createdAt INTEGER NOT NULL,

    profile_id TEXT NOT NULL DEFAULT 'default',
    FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE,
    FOREIGN KEY (assessmentSessionId) REFERENCES WorkoutSession(id) ON DELETE SET NULL
);

CREATE INDEX idx_assessment_result_exercise ON AssessmentResult(exerciseId);

CREATE INDEX idx_assessment_profile ON AssessmentResult(profile_id);

CREATE TABLE VelocityOneRepMaxEstimate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exerciseId TEXT NOT NULL,
    estimatedPerCableKg REAL NOT NULL,
    mvtUsedMs REAL NOT NULL,
    r2 REAL NOT NULL,
    distinctLoads INTEGER NOT NULL,
    passedQualityGate INTEGER NOT NULL DEFAULT 0,
    computedAt INTEGER NOT NULL,
    profile_id TEXT NOT NULL DEFAULT 'default',

    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER,
    FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
);

CREATE INDEX idx_velocity_1rm_exercise ON VelocityOneRepMaxEstimate(exerciseId, profile_id);

CREATE TABLE ExerciseMvt (
    exerciseId TEXT NOT NULL,
    profile_id TEXT NOT NULL DEFAULT 'default',
    personalMvtMs REAL NOT NULL,
    sampleCount INTEGER NOT NULL DEFAULT 0,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY (exerciseId, profile_id),
    FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
);

CREATE TABLE EarnedBadge (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    badgeId TEXT NOT NULL,
    earnedAt INTEGER NOT NULL,
    celebratedAt INTEGER,

    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER,

    profile_id TEXT NOT NULL DEFAULT 'default'
);

CREATE UNIQUE INDEX idx_earned_badge_profile ON EarnedBadge(badgeId, profile_id);

CREATE TABLE StreakHistory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    startDate INTEGER NOT NULL,
    endDate INTEGER NOT NULL,
    length INTEGER NOT NULL,

    profile_id TEXT NOT NULL DEFAULT 'default'
);

CREATE INDEX idx_streak_history_profile ON StreakHistory(profile_id);

CREATE TABLE GamificationStats (
    id INTEGER PRIMARY KEY,
    totalWorkouts INTEGER NOT NULL DEFAULT 0,
    totalReps INTEGER NOT NULL DEFAULT 0,
    totalVolumeKg INTEGER NOT NULL DEFAULT 0,
    longestStreak INTEGER NOT NULL DEFAULT 0,
    currentStreak INTEGER NOT NULL DEFAULT 0,
    uniqueExercisesUsed INTEGER NOT NULL DEFAULT 0,
    prsAchieved INTEGER NOT NULL DEFAULT 0,
    lastWorkoutDate INTEGER,
    streakStartDate INTEGER,
    lastUpdated INTEGER NOT NULL,

    updatedAt INTEGER,
    serverId TEXT,

    profile_id TEXT NOT NULL DEFAULT 'default'
);

CREATE UNIQUE INDEX idx_gamification_stats_profile ON GamificationStats(profile_id);

CREATE TABLE RpgAttributes (
    id INTEGER PRIMARY KEY DEFAULT 1,
    strength INTEGER NOT NULL DEFAULT 0,
    power INTEGER NOT NULL DEFAULT 0,
    stamina INTEGER NOT NULL DEFAULT 0,
    consistency INTEGER NOT NULL DEFAULT 0,
    mastery INTEGER NOT NULL DEFAULT 0,
    characterClass TEXT NOT NULL DEFAULT 'Phoenix',
    lastComputed INTEGER NOT NULL DEFAULT 0,

    profile_id TEXT NOT NULL DEFAULT 'default'
);

CREATE INDEX idx_rpg_attributes_profile ON RpgAttributes(profile_id);

CREATE TABLE TrainingCycle (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    created_at INTEGER NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 0,

    profile_id TEXT NOT NULL DEFAULT 'default',

    deletedAt INTEGER,

    template_id TEXT,

    week_number INTEGER NOT NULL DEFAULT 1,

    updatedAt INTEGER NOT NULL DEFAULT 0,

    server_updated_at TEXT
);

CREATE INDEX idx_cycle_profile ON TrainingCycle(profile_id);

CREATE TABLE CycleSyncState (
    cycle_id TEXT PRIMARY KEY NOT NULL,
    profile_id TEXT NOT NULL,
    account_id TEXT,
    dirty_generation INTEGER NOT NULL DEFAULT 1,
    acknowledged_generation INTEGER NOT NULL DEFAULT 0,
    pending_delete_updated_at INTEGER,
    pending_delete_generation INTEGER,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
);

CREATE TABLE CycleConflictDraft (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL,
    original_profile_id TEXT NOT NULL,
    rejected_updated_at INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    resolution TEXT
);

CREATE INDEX idx_cycle_conflict_draft_profile_cycle
ON CycleConflictDraft(original_profile_id, cycle_id);

CREATE TABLE RoutineRecovery (
    id TEXT PRIMARY KEY NOT NULL,
    portal_user_id TEXT NOT NULL DEFAULT '',
    profile_id TEXT NOT NULL,


    canonical_identity TEXT NOT NULL,

    reason TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX idx_routine_recovery_key
ON RoutineRecovery(portal_user_id, profile_id, canonical_identity, reason);

CREATE INDEX idx_routine_recovery_profile
ON RoutineRecovery(profile_id, expires_at);

CREATE TABLE CycleDay (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL,
    day_number INTEGER NOT NULL,
    name TEXT,
    routine_id TEXT,
    is_rest_day INTEGER NOT NULL DEFAULT 0,
    echo_level TEXT,
    eccentric_load_percent INTEGER,
    weight_progression_percent REAL,
    rep_modifier INTEGER,
    rest_time_override_seconds INTEGER,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE,
    FOREIGN KEY (routine_id) REFERENCES Routine(id) ON DELETE SET NULL
);

CREATE TABLE CycleProgress (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL UNIQUE,
    current_day_number INTEGER NOT NULL DEFAULT 1,
    last_completed_date INTEGER,
    cycle_start_date INTEGER NOT NULL,
    last_advanced_at INTEGER,
    completed_days TEXT,
    missed_days TEXT,
    rotation_count INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
);

CREATE TABLE CycleProgression (
    cycle_id TEXT PRIMARY KEY NOT NULL,
    frequency_cycles INTEGER NOT NULL DEFAULT 2,
    weight_increase_percent REAL,
    echo_level_increase INTEGER NOT NULL DEFAULT 0,
    eccentric_load_increase_percent INTEGER,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
);

CREATE TABLE PlannedSet (
    id TEXT PRIMARY KEY NOT NULL,
    routine_exercise_id TEXT NOT NULL,
    set_number INTEGER NOT NULL,
    set_type TEXT NOT NULL DEFAULT 'STANDARD',
    target_reps INTEGER,
    target_weight_kg REAL,
    target_rpe INTEGER,
    rest_seconds INTEGER,
    FOREIGN KEY (routine_exercise_id) REFERENCES RoutineExercise(id) ON DELETE CASCADE
);

CREATE TABLE CompletedSet (
    id TEXT PRIMARY KEY NOT NULL,
    session_id TEXT NOT NULL,
    planned_set_id TEXT,
    routine_exercise_id TEXT,
    set_number INTEGER NOT NULL,
    set_type TEXT NOT NULL DEFAULT 'STANDARD',
    attempt_number INTEGER NOT NULL DEFAULT 1,
    actual_reps INTEGER NOT NULL,
    actual_weight_kg REAL NOT NULL,
    logged_rpe INTEGER,
    is_pr INTEGER NOT NULL DEFAULT 0,
    completed_at INTEGER NOT NULL,
    set_end_reason TEXT NOT NULL DEFAULT 'UNKNOWN',
    FOREIGN KEY (session_id) REFERENCES WorkoutSession(id) ON DELETE CASCADE,
    FOREIGN KEY (planned_set_id) REFERENCES PlannedSet(id) ON DELETE SET NULL
);

CREATE TABLE ProgressionEvent (
    id TEXT PRIMARY KEY NOT NULL,
    exercise_id TEXT NOT NULL,
    suggested_weight_kg REAL NOT NULL,
    previous_weight_kg REAL NOT NULL,
    reason TEXT NOT NULL,
    user_response TEXT,
    actual_weight_kg REAL,
    timestamp INTEGER NOT NULL,

    profile_id TEXT NOT NULL DEFAULT 'default',
    FOREIGN KEY (exercise_id) REFERENCES Exercise(id) ON DELETE CASCADE
);

CREATE INDEX idx_cycle_day_cycle ON CycleDay(cycle_id);

CREATE INDEX idx_cycle_progress_cycle ON CycleProgress(cycle_id);

CREATE INDEX idx_planned_set_exercise ON PlannedSet(routine_exercise_id);

CREATE INDEX idx_completed_set_session ON CompletedSet(session_id);

CREATE INDEX idx_progression_exercise ON ProgressionEvent(exercise_id);

CREATE INDEX idx_progression_profile ON ProgressionEvent(profile_id);

CREATE TABLE UserProfile (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    colorIndex INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL,
    isActive INTEGER NOT NULL DEFAULT 0,

    supabase_user_id TEXT,
    subscription_status TEXT DEFAULT 'free',
    subscription_expires_at INTEGER,
    last_auth_at INTEGER
);

CREATE TABLE ProfileExerciseBaseline (
    profile_id TEXT NOT NULL,
    exercise_id TEXT NOT NULL,
    one_rep_max_per_cable_kg REAL,
    updated_at INTEGER NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    PRIMARY KEY (profile_id, exercise_id),
    FOREIGN KEY (profile_id) REFERENCES UserProfile(id) ON DELETE CASCADE,
    FOREIGN KEY (exercise_id) REFERENCES Exercise(id) ON DELETE CASCADE
);

CREATE INDEX idx_profile_exercise_baseline_exercise ON ProfileExerciseBaseline(exercise_id);

CREATE TABLE AppliedDataRepair (
    repair_key TEXT PRIMARY KEY NOT NULL,
    applied_at INTEGER NOT NULL
);

CREATE TABLE PendingProfileRecovery (
    recovery_id TEXT PRIMARY KEY NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('PROFILE_DATA', 'LEGACY_BASELINE')),
    source_key TEXT NOT NULL UNIQUE,
    source_profile_id TEXT,
    source_profile_name TEXT NOT NULL,
    owner_user_id TEXT,
    counts_json TEXT NOT NULL,
    discovered_at INTEGER NOT NULL,
    resolved_at INTEGER
);

CREATE TABLE OwnershipTransferOutbox (
    mutation_id TEXT PRIMARY KEY NOT NULL,
    owner_user_id TEXT NOT NULL,
    source_profile_id TEXT,
    target_profile_id TEXT NOT NULL,
    workout_session_ids_json TEXT NOT NULL,
    routine_ids_json TEXT NOT NULL,
    cycle_ids_json TEXT NOT NULL,
    personal_record_ids_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    acknowledged_at INTEGER,
    CHECK(
        workout_session_ids_json <> '[]' OR
        routine_ids_json <> '[]' OR
        cycle_ids_json <> '[]' OR
        personal_record_ids_json <> '[]'
    )
);

CREATE TABLE AppliedOwnershipEvent (
    owner_user_id TEXT NOT NULL,
    mutation_id TEXT NOT NULL,
    canonical_body_hash TEXT NOT NULL,
    applied_at INTEGER NOT NULL,
    PRIMARY KEY (owner_user_id, mutation_id)
);

CREATE TABLE LocalOwnershipClaim (
    owner_user_id TEXT NOT NULL,
    entity_type TEXT NOT NULL CHECK(entity_type IN ('WORKOUT', 'ROUTINE', 'CYCLE', 'PERSONAL_RECORD')),
    entity_id TEXT NOT NULL,
    mutation_id TEXT NOT NULL,
    source_profile_id TEXT,
    target_profile_id TEXT NOT NULL,
    transferred_at INTEGER NOT NULL,
    PRIMARY KEY (owner_user_id, entity_type, entity_id)
);

CREATE INDEX idx_local_ownership_claim_mutation
ON LocalOwnershipClaim(owner_user_id, mutation_id);

CREATE TABLE SyncExcludedEntity (
    portal_user_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    PRIMARY KEY (portal_user_id, entity_type, entity_id)
);

CREATE TABLE WorkoutDeletion (
    mutation_id TEXT NOT NULL PRIMARY KEY,
    owner_user_id TEXT,
    profile_id TEXT NOT NULL,
    scope TEXT NOT NULL CHECK(scope IN ('COMPONENT', 'WORKOUT')),
    portal_session_id TEXT NOT NULL,
    component_session_id TEXT,
    deleted_at INTEGER NOT NULL,
    acknowledged_at INTEGER,
    source TEXT NOT NULL CHECK(source IN ('LOCAL', 'REMOTE')),
    CHECK(
        (scope = 'COMPONENT' AND component_session_id IS NOT NULL) OR
        (scope = 'WORKOUT' AND component_session_id IS NULL)
    )
);

CREATE INDEX idx_workout_deletion_pending
ON WorkoutDeletion(owner_user_id, profile_id, source, acknowledged_at, deleted_at, mutation_id);

CREATE INDEX idx_workout_deletion_target
ON WorkoutDeletion(owner_user_id, portal_session_id, component_session_id, scope);

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
    custom_equipment_json TEXT NOT NULL DEFAULT '{"version":1,"items":[]}',
    custom_equipment_updated_at INTEGER NOT NULL DEFAULT 0,
    custom_equipment_local_generation INTEGER NOT NULL DEFAULT 0,
    custom_equipment_server_revision INTEGER NOT NULL DEFAULT 0,
    custom_equipment_dirty INTEGER NOT NULL DEFAULT 0,
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

CREATE TABLE ActiveWorkoutRuntime (
    profile_id TEXT NOT NULL,
    routine_session_id TEXT NOT NULL,
    document_version INTEGER NOT NULL,
    runtime_json TEXT NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL,
    PRIMARY KEY (profile_id, routine_session_id)
);

CREATE TABLE MachineSafetyHazard (
    trainer_address TEXT NOT NULL PRIMARY KEY,
    generation INTEGER NOT NULL,
    document_version INTEGER NOT NULL,
    hazard_json TEXT NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ExternalActivity (
    id TEXT NOT NULL PRIMARY KEY,
    externalId TEXT NOT NULL,
    provider TEXT NOT NULL,
    name TEXT NOT NULL,
    activityType TEXT NOT NULL DEFAULT 'strength',
    startedAt INTEGER NOT NULL,
    durationSeconds INTEGER NOT NULL DEFAULT 0,
    distanceMeters REAL,
    calories INTEGER,
    avgHeartRate INTEGER,
    maxHeartRate INTEGER,
    elevationGainMeters REAL,
    rawData TEXT,
    syncedAt INTEGER NOT NULL,
    profileId TEXT NOT NULL DEFAULT 'default',
    needsSync INTEGER NOT NULL DEFAULT 1,
    deletedAt INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_activity_dedup ON ExternalActivity(provider, externalId, profileId);

CREATE INDEX IF NOT EXISTS idx_external_activity_profile ON ExternalActivity(profileId);

CREATE INDEX IF NOT EXISTS idx_external_activity_provider ON ExternalActivity(provider);

CREATE INDEX IF NOT EXISTS idx_external_activity_started ON ExternalActivity(startedAt DESC);

CREATE TABLE IF NOT EXISTS IntegrationStatus (
    provider TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'disconnected',
    lastSyncAt INTEGER,
    errorMessage TEXT,
    profileId TEXT NOT NULL DEFAULT 'default',
    PRIMARY KEY(provider, profileId)
);

CREATE TABLE IF NOT EXISTS ExternalRoutine (
    id TEXT NOT NULL PRIMARY KEY,
    externalId TEXT NOT NULL,
    provider TEXT NOT NULL,
    title TEXT NOT NULL,
    folderExternalId TEXT,
    folderName TEXT,
    updatedAt INTEGER,
    syncedAt INTEGER NOT NULL,
    rawData TEXT,
    profileId TEXT NOT NULL DEFAULT 'default',
    needsSync INTEGER NOT NULL DEFAULT 0,
    deletedAt INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_routine_dedup ON ExternalRoutine(provider, externalId, profileId);

CREATE INDEX IF NOT EXISTS idx_external_routine_profile_provider ON ExternalRoutine(profileId, provider);

CREATE INDEX IF NOT EXISTS idx_external_routine_folder ON ExternalRoutine(profileId, provider, folderExternalId);

CREATE INDEX IF NOT EXISTS idx_external_routine_updated ON ExternalRoutine(updatedAt DESC);

CREATE TABLE IF NOT EXISTS ExternalRoutineExercise (
    id TEXT NOT NULL PRIMARY KEY,
    externalRoutineId TEXT NOT NULL REFERENCES ExternalRoutine(id) ON DELETE CASCADE,
    externalExerciseTemplateId TEXT,
    title TEXT NOT NULL,
    exerciseType TEXT,
    primaryMuscleGroups TEXT NOT NULL DEFAULT '',
    secondaryMuscleGroups TEXT NOT NULL DEFAULT '',
    orderIndex INTEGER NOT NULL DEFAULT 0,
    rawData TEXT
);

CREATE INDEX IF NOT EXISTS idx_external_routine_exercise_routine ON ExternalRoutineExercise(externalRoutineId);

CREATE INDEX IF NOT EXISTS idx_external_routine_exercise_template ON ExternalRoutineExercise(externalExerciseTemplateId);

CREATE TABLE IF NOT EXISTS ExternalRoutineSet (
    id TEXT NOT NULL PRIMARY KEY,
    externalRoutineExerciseId TEXT NOT NULL REFERENCES ExternalRoutineExercise(id) ON DELETE CASCADE,
    setIndex INTEGER NOT NULL DEFAULT 0,
    setType TEXT,
    weightKg REAL,
    reps INTEGER,
    minReps INTEGER,
    maxReps INTEGER,
    restSeconds INTEGER,
    rpe REAL,
    durationSeconds INTEGER,
    distanceMeters REAL,
    rawData TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_routine_set_dedup ON ExternalRoutineSet(externalRoutineExerciseId, setIndex);

CREATE INDEX IF NOT EXISTS idx_external_routine_set_exercise ON ExternalRoutineSet(externalRoutineExerciseId);

CREATE TABLE IF NOT EXISTS ExternalRoutineFolder (
    id TEXT NOT NULL PRIMARY KEY,
    externalId TEXT NOT NULL,
    provider TEXT NOT NULL,
    title TEXT NOT NULL,
    folderIndex INTEGER,
    createdAt INTEGER,
    updatedAt INTEGER,
    profileId TEXT NOT NULL DEFAULT 'default',
    rawData TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_routine_folder_dedup ON ExternalRoutineFolder(provider, externalId, profileId);

CREATE INDEX IF NOT EXISTS idx_external_routine_folder_profile_provider ON ExternalRoutineFolder(profileId, provider);

CREATE TABLE IF NOT EXISTS ExternalProgram (
    id TEXT NOT NULL PRIMARY KEY,
    externalId TEXT NOT NULL,
    provider TEXT NOT NULL,
    name TEXT NOT NULL,
    isCurrent INTEGER NOT NULL DEFAULT 0,
    scriptText TEXT,
    rawData TEXT,
    updatedAt INTEGER,
    syncedAt INTEGER NOT NULL,
    profileId TEXT NOT NULL DEFAULT 'default',
    needsSync INTEGER NOT NULL DEFAULT 0,
    deletedAt INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_program_dedup ON ExternalProgram(provider, externalId, profileId);

CREATE INDEX IF NOT EXISTS idx_external_program_profile_provider ON ExternalProgram(profileId, provider);

CREATE INDEX IF NOT EXISTS idx_external_program_current ON ExternalProgram(profileId, provider, isCurrent);

CREATE INDEX IF NOT EXISTS idx_external_program_updated ON ExternalProgram(updatedAt DESC);

CREATE TABLE IF NOT EXISTS ExternalProgramStats (
    id TEXT NOT NULL PRIMARY KEY,
    externalProgramId TEXT NOT NULL REFERENCES ExternalProgram(id) ON DELETE CASCADE,
    days INTEGER,
    approximateMinutes INTEGER,
    setCount INTEGER,
    muscleGroupBreakdownJson TEXT,
    rawData TEXT,
    computedAt INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_program_stats_program ON ExternalProgramStats(externalProgramId);

CREATE INDEX IF NOT EXISTS idx_external_program_stats_computed ON ExternalProgramStats(computedAt DESC);

CREATE TABLE IF NOT EXISTS ExternalExerciseTemplate (
    id TEXT NOT NULL PRIMARY KEY,
    externalId TEXT NOT NULL,
    provider TEXT NOT NULL,
    title TEXT NOT NULL,
    exerciseType TEXT,
    primaryMuscleGroups TEXT NOT NULL DEFAULT '',
    secondaryMuscleGroups TEXT NOT NULL DEFAULT '',
    isCustom INTEGER NOT NULL DEFAULT 0,
    rawData TEXT,
    updatedAt INTEGER,
    profileId TEXT NOT NULL DEFAULT 'default'
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_exercise_template_dedup ON ExternalExerciseTemplate(provider, externalId, profileId);

CREATE INDEX IF NOT EXISTS idx_external_exercise_template_profile_provider ON ExternalExerciseTemplate(profileId, provider);

CREATE INDEX IF NOT EXISTS idx_external_exercise_template_title ON ExternalExerciseTemplate(title);

CREATE TABLE IF NOT EXISTS ExternalExerciseTemplateMapping (
    id TEXT NOT NULL PRIMARY KEY,
    provider TEXT NOT NULL,
    externalTemplateId TEXT NOT NULL,
    localExerciseId TEXT NOT NULL,
    profileId TEXT NOT NULL DEFAULT 'default',
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    rawData TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_template_mapping_dedup ON ExternalExerciseTemplateMapping(provider, externalTemplateId, profileId);

CREATE INDEX IF NOT EXISTS idx_external_template_mapping_local ON ExternalExerciseTemplateMapping(localExerciseId);

CREATE TABLE IF NOT EXISTS ExternalBodyMeasurement (
    id TEXT NOT NULL PRIMARY KEY,
    externalId TEXT NOT NULL,
    provider TEXT NOT NULL,
    measurementType TEXT NOT NULL,
    value REAL NOT NULL,
    unit TEXT NOT NULL,
    measuredAt INTEGER NOT NULL,
    syncedAt INTEGER NOT NULL,
    rawData TEXT,
    profileId TEXT NOT NULL DEFAULT 'default'
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_external_body_measurement_dedup ON ExternalBodyMeasurement(provider, externalId, profileId);

CREATE INDEX IF NOT EXISTS idx_external_body_measurement_profile_provider ON ExternalBodyMeasurement(profileId, provider);

CREATE INDEX IF NOT EXISTS idx_external_body_measurement_type_date ON ExternalBodyMeasurement(profileId, measurementType, measuredAt DESC);

CREATE TABLE IF NOT EXISTS IntegrationSyncCursor (
    provider TEXT NOT NULL,
    profileId TEXT NOT NULL DEFAULT 'default',
    cursorType TEXT NOT NULL,
    cursorValue TEXT,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY(provider, profileId, cursorType)
);

CREATE INDEX IF NOT EXISTS idx_integration_sync_cursor_profile_provider ON IntegrationSyncCursor(profileId, provider);

CREATE TABLE IF NOT EXISTS SessionNotes (
    routineSessionId TEXT NOT NULL PRIMARY KEY,
    notes TEXT,
    updatedAt INTEGER
);

CREATE INDEX IF NOT EXISTS idx_session_notes_updated_at
    ON SessionNotes(updatedAt);

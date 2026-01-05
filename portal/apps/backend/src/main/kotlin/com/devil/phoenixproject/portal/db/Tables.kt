package com.devil.phoenixproject.portal.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Users : UUIDTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 255).nullable()
    val isPremium = bool("is_premium").default(false)
    val createdAt = timestamp("created_at")
    val lastLoginAt = timestamp("last_login_at").nullable()
    val lastSyncAt = timestamp("last_sync_at").nullable()
    val subscriptionStatus = varchar("subscription_status", 50).default("free")
    val subscriptionExpiresAt = timestamp("subscription_expires_at").nullable()
}

object WorkoutSessions : UUIDTable("workout_sessions") {
    val userId = reference("user_id", Users)
    val timestamp = long("timestamp")
    val exerciseName = varchar("exercise_name", 255)
    val workoutMode = varchar("workout_mode", 50)
    val targetWeight = float("target_weight")
    val totalReps = integer("total_reps").default(0)
    val totalVolume = float("total_volume").default(0f)
    val durationSeconds = integer("duration_seconds").default(0)
    val createdAt = timestamp("created_at")
    val syncedAt = timestamp("synced_at").nullable()
}

object PersonalRecords : UUIDTable("personal_records") {
    val userId = reference("user_id", Users)
    val exerciseName = varchar("exercise_name", 255)
    val recordType = varchar("record_type", 50)
    val value = float("value")
    val achievedAt = timestamp("achieved_at")
    val sessionId = reference("session_id", WorkoutSessions).nullable()
}

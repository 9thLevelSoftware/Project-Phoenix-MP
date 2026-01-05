package com.devil.phoenixproject.portal.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    private val logger = org.slf4j.LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init() {
        // Railway provides these env vars for PostgreSQL
        val pgHost = System.getenv("PGHOST") ?: "localhost"
        val pgPort = System.getenv("PGPORT") ?: "5432"
        val pgDatabase = System.getenv("PGDATABASE") ?: "phoenix_portal"
        val pgUser = System.getenv("PGUSER") ?: "postgres"
        val pgPassword = System.getenv("PGPASSWORD") ?: "postgres"

        val jdbcUrl = "jdbc:postgresql://$pgHost:$pgPort/$pgDatabase"

        logger.info("Connecting to database at $pgHost:$pgPort/$pgDatabase as $pgUser")

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = pgUser
            password = pgPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            // Allow time for Railway PostgreSQL to be ready
            connectionTimeout = 30000
            initializationFailTimeout = 60000
            validate()
        }

        // Retry connection with backoff
        var connected = false
        var attempts = 0
        val maxAttempts = 5

        while (!connected && attempts < maxAttempts) {
            try {
                attempts++
                logger.info("Database connection attempt $attempts of $maxAttempts")
                Database.connect(HikariDataSource(config))
                connected = true
                logger.info("Database connected successfully")
            } catch (e: Exception) {
                logger.warn("Database connection attempt $attempts failed: ${e.message}")
                if (attempts < maxAttempts) {
                    val sleepTime = attempts * 2000L
                    logger.info("Retrying in ${sleepTime}ms...")
                    Thread.sleep(sleepTime)
                } else {
                    logger.error("All database connection attempts failed", e)
                    throw e
                }
            }
        }

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                WorkoutSessions,
                PersonalRecords,
                PhaseStatistics,
                Routines,
                Supersets,
                RoutineExercises,
                CustomExercises,
                MetricSamples,
                EarnedBadges,
                GamificationStats,
                SyncDevices
            )
        }
        logger.info("Database schema initialized")
    }
}

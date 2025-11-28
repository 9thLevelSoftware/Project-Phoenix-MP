package com.devil.phoenixproject.util

import kotlinx.datetime.*
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Clock

// Helper to get current instant
private fun currentInstant(): Instant = Clock.System.now()

/**
 * Represents a date for streak calculations (KMP-compatible)
 */
data class KmpLocalDate(
    val year: Int,
    val month: Int,
    val dayOfMonth: Int
) : Comparable<KmpLocalDate> {

    fun minusDays(days: Int): KmpLocalDate {
        val localDate = LocalDate(year, month, dayOfMonth)
        val result = localDate.minus(days, DateTimeUnit.DAY)
        return KmpLocalDate(result.year, result.monthNumber, result.dayOfMonth)
    }

    fun plusDays(days: Int): KmpLocalDate {
        val localDate = LocalDate(year, month, dayOfMonth)
        val result = localDate.plus(days, DateTimeUnit.DAY)
        return KmpLocalDate(result.year, result.monthNumber, result.dayOfMonth)
    }

    fun isBefore(other: KmpLocalDate): Boolean {
        return this < other
    }

    fun isAfter(other: KmpLocalDate): Boolean {
        return this > other
    }

    override fun compareTo(other: KmpLocalDate): Int {
        val yearCmp = year.compareTo(other.year)
        if (yearCmp != 0) return yearCmp
        val monthCmp = month.compareTo(other.month)
        if (monthCmp != 0) return monthCmp
        return dayOfMonth.compareTo(other.dayOfMonth)
    }

    /**
     * Returns unique key for grouping (e.g., for distinct dates)
     */
    fun toKey(): String = "$year-$month-$dayOfMonth"

    companion object {
        fun today(): KmpLocalDate {
            val now = currentInstant()
            val localDate = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
            return KmpLocalDate(localDate.year, localDate.monthNumber, localDate.dayOfMonth)
        }

        fun fromTimestamp(timestampMillis: Long): KmpLocalDate {
            val instant = Instant.fromEpochMilliseconds(timestampMillis)
            val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            return KmpLocalDate(localDate.year, localDate.monthNumber, localDate.dayOfMonth)
        }
    }
}

/**
 * KMP-compatible utility functions to replace Java dependencies
 */
object KmpUtils {

    /**
     * Format a timestamp to a human-readable date string
     * @param timestamp Unix timestamp in milliseconds
     * @param pattern Date pattern (simplified KMP-compatible)
     * @return Formatted date string
     */
    fun formatTimestamp(timestamp: Long, pattern: String = "MMM dd, yyyy"): String {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())

        return when (pattern) {
            "MMM dd, yyyy" -> {
                val month = dateTime.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                val day = dateTime.dayOfMonth.toString().padStart(2, '0')
                val year = dateTime.year
                "$month $day, $year"
            }
            "MM/dd/yyyy" -> {
                val month = dateTime.monthNumber.toString().padStart(2, '0')
                val day = dateTime.dayOfMonth.toString().padStart(2, '0')
                val year = dateTime.year
                "$month/$day/$year"
            }
            "yyyy-MM-dd" -> {
                val month = dateTime.monthNumber.toString().padStart(2, '0')
                val day = dateTime.dayOfMonth.toString().padStart(2, '0')
                val year = dateTime.year
                "$year-$month-$day"
            }
            "HH:mm" -> {
                val hour = dateTime.hour.toString().padStart(2, '0')
                val minute = dateTime.minute.toString().padStart(2, '0')
                "$hour:$minute"
            }
            "h:mm a" -> {
                val hour = if (dateTime.hour == 0) 12 else if (dateTime.hour > 12) dateTime.hour - 12 else dateTime.hour
                val minute = dateTime.minute.toString().padStart(2, '0')
                val amPm = if (dateTime.hour < 12) "AM" else "PM"
                "$hour:$minute $amPm"
            }
            "HH:mm:ss" -> {
                val hour = dateTime.hour.toString().padStart(2, '0')
                val minute = dateTime.minute.toString().padStart(2, '0')
                val second = dateTime.second.toString().padStart(2, '0')
                "$hour:$minute:$second"
            }
            "HH:mm:ss.SSS" -> {
                val hour = dateTime.hour.toString().padStart(2, '0')
                val minute = dateTime.minute.toString().padStart(2, '0')
                val second = dateTime.second.toString().padStart(2, '0')
                val millis = (instant.toEpochMilliseconds() % 1000).toString().padStart(3, '0')
                "$hour:$minute:$second.$millis"
            }
            "EEE" -> {
                dateTime.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            }
            "EEEE" -> {
                dateTime.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            else -> {
                // Default fallback
                val month = dateTime.monthNumber.toString().padStart(2, '0')
                val day = dateTime.dayOfMonth.toString().padStart(2, '0')
                val year = dateTime.year
                "$month/$day/$year"
            }
        }
    }

    /**
     * Format a relative timestamp (e.g., "2 hours ago", "Yesterday")
     * @param timestamp Unix timestamp in milliseconds
     * @return Relative time string
     */
    fun formatRelativeTimestamp(timestamp: Long): String {
        val now = currentInstant().toEpochMilliseconds()
        val diffMs = now - timestamp
        val diffMinutes = diffMs / (1000 * 60)
        val diffHours = diffMinutes / 60
        val diffDays = diffHours / 24

        return when {
            diffMinutes < 1 -> "Just now"
            diffMinutes < 60 -> "${diffMinutes}m ago"
            diffHours < 24 -> "${diffHours}h ago"
            diffDays == 1L -> "Yesterday"
            diffDays < 7 -> "${diffDays}d ago"
            else -> formatTimestamp(timestamp, "MMM dd, yyyy")
        }
    }

    /**
     * Get current time in milliseconds
     * @return Current Unix timestamp in milliseconds
     */
    fun currentTimeMillis(): Long {
        return currentInstant().toEpochMilliseconds()
    }

    /**
     * Get current day of week (ISO-8601: 1=Monday, 7=Sunday)
     * @return Day of week value (1-7)
     */
    fun currentDayOfWeek(): Int {
        val now = currentInstant()
        val localDate = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        return localDate.dayOfWeek.isoDayNumber
    }

    /**
     * Format a duration in milliseconds to a human-readable string
     * @param millis Duration in milliseconds
     * @return Formatted duration string (e.g., "1h 23m 45s")
     */
    fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Format a float with specified decimal places
     * @param value Float value to format
     * @param decimals Number of decimal places
     * @return Formatted string
     */
    fun formatFloat(value: Float, decimals: Int): String {
        if (decimals <= 0) {
            return value.roundToInt().toString()
        }

        var factor = 1f
        repeat(decimals) { factor *= 10f }

        val rounded = (value * factor).roundToInt() / factor
        val intPart = rounded.toLong()
        val decPart = ((rounded - intPart) * factor).roundToInt()

        return "$intPart.${"$decPart".padStart(decimals, '0')}"
    }

    /**
     * Format a double with specified decimal places
     * @param value Double value to format
     * @param decimals Number of decimal places
     * @return Formatted string
     */
    fun formatDouble(value: Double, decimals: Int): String {
        return formatFloat(value.toFloat(), decimals)
    }

    /**
     * Generate a random UUID string (KMP-compatible)
     * Format: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
     * where x is a random hex digit and y is 8, 9, a, or b
     * @return UUID string
     */
    fun randomUUID(): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(36)

        for (i in 0 until 36) {
            when (i) {
                8, 13, 18, 23 -> sb.append('-')
                14 -> sb.append('4') // Version 4
                19 -> sb.append(hexChars[Random.nextInt(4) + 8]) // 8, 9, a, b
                else -> sb.append(hexChars[Random.nextInt(16)])
            }
        }

        return sb.toString()
    }
}

/**
 * Extension function to format Float
 */
fun Float.format(decimals: Int): String = KmpUtils.formatFloat(this, decimals)

/**
 * Extension function to format Double
 */
fun Double.format(decimals: Int): String = KmpUtils.formatDouble(this, decimals)

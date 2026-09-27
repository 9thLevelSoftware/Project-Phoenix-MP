package com.devil.phoenixproject.util

import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.*

// Helper to get current instant
private fun currentInstant(): Instant = Clock.System.now()

/**
 * Represents a date for streak calculations (KMP-compatible)
 */
data class KmpLocalDate(val year: Int, val month: Int, val dayOfMonth: Int) : Comparable<KmpLocalDate> {

    fun minusDays(days: Int): KmpLocalDate {
        val localDate = LocalDate(year, month, dayOfMonth)
        val result = localDate.minus(days, DateTimeUnit.DAY)
        return KmpLocalDate(result.year, result.month.number, result.day)
    }

    fun isBefore(other: KmpLocalDate): Boolean = this < other

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
            return KmpLocalDate(localDate.year, localDate.month.number, localDate.day)
        }

        fun fromTimestamp(timestampMillis: Long): KmpLocalDate {
            val instant = Instant.fromEpochMilliseconds(timestampMillis)
            val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            return KmpLocalDate(localDate.year, localDate.month.number, localDate.day)
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
            "MMM d" -> {
                val monthName = dateTime.month.name.take(3).lowercase().replaceFirstChar {
                    it.uppercase()
                }
                "$monthName ${dateTime.day}"
            }

            "MMM dd, yyyy" -> {
                val monthName = dateTime.month.name.take(3).lowercase().replaceFirstChar {
                    it.uppercase()
                }
                val day = dateTime.day.toString().padStart(2, '0')
                val year = dateTime.year
                "$monthName $day, $year"
            }

            "MM/dd/yyyy" -> {
                val month = dateTime.month.number.toString().padStart(2, '0')
                val day = dateTime.day.toString().padStart(2, '0')
                val year = dateTime.year
                "$month/$day/$year"
            }

            "yyyy-MM-dd" -> {
                val month = dateTime.month.number.toString().padStart(2, '0')
                val day = dateTime.day.toString().padStart(2, '0')
                val year = dateTime.year
                "$year-$month-$day"
            }

            "HH:mm" -> {
                val hour = dateTime.hour.toString().padStart(2, '0')
                val minute = dateTime.minute.toString().padStart(2, '0')
                "$hour:$minute"
            }

            "h:mm a" -> {
                val hour = if (dateTime.hour ==
                    0
                ) {
                    12
                } else if (dateTime.hour > 12) {
                    dateTime.hour - 12
                } else {
                    dateTime.hour
                }
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
                val month = dateTime.month.number.toString().padStart(2, '0')
                val day = dateTime.day.toString().padStart(2, '0')
                val year = dateTime.year
                "$month/$day/$year"
            }
        }
    }

    /**
     * Get current time in milliseconds
     * @return Current Unix timestamp in milliseconds
     */
    fun currentTimeMillis(): Long = currentInstant().toEpochMilliseconds()

    /**
     * Format a float with specified decimal places
     * @param value Float value to format
     * @param decimals Number of decimal places
     * @return Formatted string
     */
    fun formatFloat(value: Float, decimals: Int): String {
        // Guard against non-finite values: kotlin.math.roundToInt() throws
        // IllegalArgumentException("Cannot round NaN value.") on NaN, which would
        // crash any screen that formats a corrupt/missing metric (e.g. a workout
        // history entry with a NaN weight). Treat non-finite as 0 for display.
        if (!value.isFinite()) {
            return if (decimals <= 0) "0" else "0.${"0".repeat(decimals)}"
        }

        if (decimals <= 0) {
            return value.roundToInt().toString()
        }

        var factor = 1f
        repeat(decimals) { factor *= 10f }

        // F313: format from a single scaled integer so negatives render correctly.
        // The previous (rounded - intPart) approach produced "-1.-23" / "0.-25"
        // because the integer and fractional parts each carried the sign.
        val scaled = (value * factor).roundToInt()
        val sign = if (scaled < 0) "-" else ""
        val absScaled = kotlin.math.abs(scaled).toLong()
        val factorLong = factor.toLong()
        val intPart = absScaled / factorLong
        val decPart = absScaled % factorLong

        return "$sign$intPart.${"$decPart".padStart(decimals, '0')}"
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

                14 -> sb.append('4')

                // Version 4
                19 -> sb.append(hexChars[Random.nextInt(4) + 8])

                // 8, 9, a, b
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

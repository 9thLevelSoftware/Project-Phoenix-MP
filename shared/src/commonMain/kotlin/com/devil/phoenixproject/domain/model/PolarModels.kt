package com.devil.phoenixproject.domain.model

/**
 * Standard Bluetooth SIG Heart Rate Service (0x180D / 0x2A37) measurement.
 *
 * @param hr Heart rate in beats per minute (BPM).
 * @param contactDetected True if skin contact is actively detected by the sensor.
 * @param energyExpendedKj Optional accumulated energy expended in kiloJoules.
 * @param rrIntervalsMs R-R peak intervals in milliseconds, converted from 1/1024s resolution.
 */
data class PolarHrMeasurement(
    val hr: Int,
    val contactDetected: Boolean,
    val energyExpendedKj: Int? = null,
    val rrIntervalsMs: List<Double> = emptyList(),
)

/**
 * Tri-axial acceleration sample in milli-G (mG).
 */
data class PolarAccSample(
    val xMg: Int,
    val yMg: Int,
    val zMg: Int,
)

/**
 * Polar Measurement Data (PMD) stream packet received on characteristic FB005C82.
 */
sealed class PolarPmdMeasurement {
    abstract val sensorTimestampNs: Long

    /**
     * Electrocardiogram (ECG) data frame (130 Hz, 14-bit resolution).
     *
     * @param sensorTimestampNs Sensor internal clock in nanoseconds.
     * @param samplesMicrovolts ECG voltage samples in microvolts (µV).
     */
    data class Ecg(
        override val sensorTimestampNs: Long,
        val samplesMicrovolts: List<Int>,
    ) : PolarPmdMeasurement()

    /**
     * Accelerometer data frame (typically 50 Hz, 16-bit resolution, ±8G range).
     *
     * @param sensorTimestampNs Sensor internal clock in nanoseconds.
     * @param samples List of 3-axis acceleration samples in milli-G.
     */
    data class Acc(
        override val sensorTimestampNs: Long,
        val samples: List<PolarAccSample>,
    ) : PolarPmdMeasurement()
}

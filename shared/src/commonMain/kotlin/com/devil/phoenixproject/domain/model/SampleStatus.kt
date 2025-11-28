package com.devil.phoenixproject.domain.model

/**
 * Represents the machine's status flags parsed from bytes 16-17 of the Monitor characteristic.
 * Based on official app's SampleStatus class.
 */
@Suppress("unused")  // Protocol reference class - used for machine status parsing
data class SampleStatus(val raw: Int) {

    fun isRepTopReady(): Boolean = (raw and REP_TOP_READY) != 0
    fun isRepBottomReady(): Boolean = (raw and REP_BOTTOM_READY) != 0
    fun isRomOutsideHigh(): Boolean = (raw and ROM_OUTSIDE_HIGH) != 0
    fun isRomOutsideLow(): Boolean = (raw and ROM_OUTSIDE_LOW) != 0
    fun isRomUnloadActive(): Boolean = (raw and ROM_UNLOAD_ACTIVE) != 0
    fun isSpotterActive(): Boolean = (raw and SPOTTER_ACTIVE) != 0
    fun isDeloadWarn(): Boolean = (raw and DELOAD_WARN) != 0
    fun isDeloadOccurred(): Boolean = (raw and DELOAD_OCCURRED) != 0

    override fun toString(): String {
        val flags = mutableListOf<String>()
        if (isRepTopReady()) flags.add("REP_TOP_READY")
        if (isRepBottomReady()) flags.add("REP_BOTTOM_READY")
        if (isRomOutsideHigh()) flags.add("ROM_OUTSIDE_HIGH")
        if (isRomOutsideLow()) flags.add("ROM_OUTSIDE_LOW")
        if (isRomUnloadActive()) flags.add("ROM_UNLOAD_ACTIVE")
        if (isSpotterActive()) flags.add("SPOTTER_ACTIVE")
        if (isDeloadWarn()) flags.add("DELOAD_WARN")
        if (isDeloadOccurred()) flags.add("DELOAD_OCCURRED")

        return "SampleStatus(raw=0x${raw.toString(16)}, flags=$flags)"
    }

    companion object {
        // Constants derived from official app's SampleStatus enum (bit masks)
        // Note: The official app code shows bit shifts like `1 << 0`, `1 << 1`, etc.
        // cVar8 = new c("DELOAD_OCCURRED", 7, He.a.i(s10, 15)); -> 1 << 15 = 0x8000

        const val REP_TOP_READY = 1 shl 0       // 0x0001
        const val REP_BOTTOM_READY = 1 shl 1    // 0x0002
        const val ROM_OUTSIDE_HIGH = 1 shl 2    // 0x0004
        const val ROM_OUTSIDE_LOW = 1 shl 3     // 0x0008
        const val ROM_UNLOAD_ACTIVE = 1 shl 4   // 0x0010
        const val SPOTTER_ACTIVE = 1 shl 5      // 0x0020
        const val DELOAD_WARN = 1 shl 6         // 0x0040
        const val DELOAD_OCCURRED = 1 shl 15    // 0x8000
    }
}

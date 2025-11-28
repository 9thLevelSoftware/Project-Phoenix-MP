package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutType

object BlePacketFactory {
    
    fun createStopCommand(): ByteArray {
        return byteArrayOf(BleConstants.Commands.STOP_COMMAND)
    }

    /**
     * Creates a V1 protocol command (0x4F) for setting weight and mode.
     * Structure (25 bytes):
     * [0]: Command (0x4F)
     * [1]: Mode (0=OldSchool, 2=Pump, 3=TUT, 4=TUTBeast, 6=EccentricOnly)
     * [2-3]: Weight per cable (UInt16, scaled by 100)
     * [4-24]: ? (Padding or other params)
     */
    fun createWorkoutCommand(
        workoutType: WorkoutType.Program,
        weightPerCableKg: Float,
        targetReps: Int
    ): ByteArray {
        val buffer = ByteArray(25)
        buffer[0] = BleConstants.Commands.REGULAR_COMMAND
        buffer[1] = workoutType.mode.modeValue.toByte()
        
        // Weight is scaled by 100 (e.g. 10kg = 1000)
        // Assuming Little Endian for BLE
        val weightScaled = (weightPerCableKg * 100).toInt()
        buffer[2] = (weightScaled and 0xFF).toByte()
        buffer[3] = ((weightScaled shr 8) and 0xFF).toByte()
        
        // Byte 4: Target Reps
        // Fix (Beta 6): Send raw target reps. Do NOT add +1 offset.
        // Machine handles tension release at the end of this count.
        buffer[4] = targetReps.toByte()
        
        // Remaining bytes are 0 for now (Legacy V1 simplified)
        
        return buffer
    }

    /**
     * Creates an Echo command (0x4E).
     * Structure (29 bytes):
     * [0]: Command (0x4E)
     * [1]: Level (0=Hard, 1=Harder, etc.)
     * [2-3]: Eccentric load?
     * ...
     */
    fun createEchoCommand(
        level: Int,
        eccentricLoad: Int
    ): ByteArray {
        val buffer = ByteArray(29)
        buffer[0] = BleConstants.Commands.ECHO_COMMAND
        buffer[1] = level.toByte()
        
        // TODO: Verify Echo packet structure
        // For now, assuming similar scaling or just passing level
        
        return buffer
    }
}

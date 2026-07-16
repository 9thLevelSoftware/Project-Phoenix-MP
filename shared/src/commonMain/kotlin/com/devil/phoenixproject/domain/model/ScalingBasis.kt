package com.devil.phoenixproject.domain.model

import kotlinx.serialization.Serializable

/** Baseline a routine exercise's percentage weight scales from. */
@Serializable
enum class ScalingBasis {
    MAX_WEIGHT_PR,
    MAX_VOLUME_PR,
    ESTIMATED_1RM,
    ;

    companion object {
        fun fromPrType(prType: PRType): ScalingBasis = when (prType) {
            PRType.MAX_WEIGHT -> MAX_WEIGHT_PR
            PRType.MAX_VOLUME -> MAX_VOLUME_PR
        }
    }
}

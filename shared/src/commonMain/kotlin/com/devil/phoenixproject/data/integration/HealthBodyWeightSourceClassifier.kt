package com.devil.phoenixproject.data.integration

enum class HealthBodyWeightSourcePlatform {
    ANDROID,
    IOS,
}

enum class HealthBodyWeightDeviceType {
    SCALE,
    UNKNOWN,
    OTHER,
}

enum class HealthBodyWeightSourceClassification {
    ELIGIBLE_SCALE,
    MANUAL_ENTRY,
    UNKNOWN_SOURCE,
}

data class HealthBodyWeightSourceEvidence(
    val platform: HealthBodyWeightSourcePlatform,
    val wasUserEntered: Boolean? = null,
    val deviceType: HealthBodyWeightDeviceType = HealthBodyWeightDeviceType.UNKNOWN,
    val sourceName: String? = null,
    val sourceBundleIdentifier: String? = null,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val deviceName: String? = null,
)

object HealthBodyWeightSourceClassifier {
    private val scaleLikeTokens = listOf(
        "scale",
        "withings",
        "renpho",
        "eufy",
        "fitbit aria",
        "aria",
        "garmin index",
        "qardio",
        "wyze",
        "omron",
        "tanita",
    )

    fun classify(evidence: HealthBodyWeightSourceEvidence): HealthBodyWeightSourceClassification {
        if (evidence.wasUserEntered == true) {
            return HealthBodyWeightSourceClassification.MANUAL_ENTRY
        }

        return when (evidence.platform) {
            HealthBodyWeightSourcePlatform.ANDROID -> {
                if (evidence.deviceType == HealthBodyWeightDeviceType.SCALE) {
                    HealthBodyWeightSourceClassification.ELIGIBLE_SCALE
                } else {
                    HealthBodyWeightSourceClassification.UNKNOWN_SOURCE
                }
            }

            HealthBodyWeightSourcePlatform.IOS -> {
                if (hasScaleLikeMetadata(evidence)) {
                    HealthBodyWeightSourceClassification.ELIGIBLE_SCALE
                } else {
                    HealthBodyWeightSourceClassification.UNKNOWN_SOURCE
                }
            }
        }
    }

    fun isEligibleScaleSource(evidence: HealthBodyWeightSourceEvidence): Boolean =
        classify(evidence) == HealthBodyWeightSourceClassification.ELIGIBLE_SCALE

    private fun hasScaleLikeMetadata(evidence: HealthBodyWeightSourceEvidence): Boolean {
        val searchable = listOfNotNull(
            evidence.deviceName,
            evidence.deviceManufacturer,
            evidence.deviceModel,
            evidence.sourceName,
            evidence.sourceBundleIdentifier,
        ).joinToString(separator = " ").lowercase()

        return scaleLikeTokens.any { token -> searchable.contains(token) }
    }
}

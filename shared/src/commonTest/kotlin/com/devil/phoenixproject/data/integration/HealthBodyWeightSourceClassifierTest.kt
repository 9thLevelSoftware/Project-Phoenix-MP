package com.devil.phoenixproject.data.integration

import kotlin.test.Test
import kotlin.test.assertEquals

class HealthBodyWeightSourceClassifierTest {

    @Test
    fun androidScaleDeviceIsEligible() {
        val result = HealthBodyWeightSourceClassifier.classify(
            HealthBodyWeightSourceEvidence(
                platform = HealthBodyWeightSourcePlatform.ANDROID,
                wasUserEntered = false,
                deviceType = HealthBodyWeightDeviceType.SCALE,
                deviceManufacturer = "Withings",
                deviceModel = "Body+",
            ),
        )

        assertEquals(HealthBodyWeightSourceClassification.ELIGIBLE_SCALE, result)
    }

    @Test
    fun androidManualEntryIsRejectedEvenWithScaleDevice() {
        val result = HealthBodyWeightSourceClassifier.classify(
            HealthBodyWeightSourceEvidence(
                platform = HealthBodyWeightSourcePlatform.ANDROID,
                wasUserEntered = true,
                deviceType = HealthBodyWeightDeviceType.SCALE,
            ),
        )

        assertEquals(HealthBodyWeightSourceClassification.MANUAL_ENTRY, result)
    }

    @Test
    fun androidUnknownDeviceIsRejected() {
        val result = HealthBodyWeightSourceClassifier.classify(
            HealthBodyWeightSourceEvidence(
                platform = HealthBodyWeightSourcePlatform.ANDROID,
                wasUserEntered = false,
                deviceType = HealthBodyWeightDeviceType.UNKNOWN,
                sourceName = "Health Connect",
            ),
        )

        assertEquals(HealthBodyWeightSourceClassification.UNKNOWN_SOURCE, result)
    }

    @Test
    fun iosScaleLikeSourceIsEligibleWhenNotUserEntered() {
        val result = HealthBodyWeightSourceClassifier.classify(
            HealthBodyWeightSourceEvidence(
                platform = HealthBodyWeightSourcePlatform.IOS,
                wasUserEntered = false,
                sourceName = "Withings",
                deviceModel = "Body Smart Scale",
            ),
        )

        assertEquals(HealthBodyWeightSourceClassification.ELIGIBLE_SCALE, result)
    }

    @Test
    fun iosUserEnteredSampleIsRejected() {
        val result = HealthBodyWeightSourceClassifier.classify(
            HealthBodyWeightSourceEvidence(
                platform = HealthBodyWeightSourcePlatform.IOS,
                wasUserEntered = true,
                sourceName = "Withings",
                deviceModel = "Body Smart Scale",
            ),
        )

        assertEquals(HealthBodyWeightSourceClassification.MANUAL_ENTRY, result)
    }

    @Test
    fun iosGenericHealthSourceWithoutScaleProvenanceIsRejected() {
        val result = HealthBodyWeightSourceClassifier.classify(
            HealthBodyWeightSourceEvidence(
                platform = HealthBodyWeightSourcePlatform.IOS,
                wasUserEntered = false,
                sourceName = "Health",
            ),
        )

        assertEquals(HealthBodyWeightSourceClassification.UNKNOWN_SOURCE, result)
    }
}

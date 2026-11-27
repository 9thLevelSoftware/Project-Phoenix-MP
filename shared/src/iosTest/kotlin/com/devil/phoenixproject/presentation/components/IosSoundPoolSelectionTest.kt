@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.VulgarTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

class IosSoundPoolSelectionTest {

    @Test
    fun namedAndNumberedPoolLoadersPreserveExpectedSoundNames() {
        assertEquals("auto_start_ready", IOS_EVENT_SOUND_FILES[HapticEvent.AUTO_START_READY])
        assertEquals("dominatrix_unlock", IOS_EVENT_SOUND_FILES[HapticEvent.DOMINATRIX_MODE_UNLOCKED])
        assertEquals(33, IOS_BADGE_SOUND_FILES.size)
        assertEquals(2, IOS_PR_SOUND_FILES.size)

        val badgeLoaded = mutableListOf<String?>()
        loadNamedSoundPool(IOS_BADGE_SOUND_FILES, badgeLoaded, "badges") { name ->
            if (name == "missing_clip") null else name
        }
        assertEquals<List<String?>>(IOS_BADGE_SOUND_FILES, badgeLoaded)

        val prLoaded = mutableListOf<String?>()
        loadNamedSoundPool(IOS_PR_SOUND_FILES, prLoaded, "prs") { it }
        assertEquals<List<String?>>(IOS_PR_SOUND_FILES, prLoaded)

        val neutralLoaded = mutableListOf<String?>()
        loadNumberedSoundPool("encouragement", 15, neutralLoaded, "neutral") { name ->
            if (name == "encouragement_03") null else name
        }
        assertEquals(14, neutralLoaded.size)
        assertEquals("encouragement_01", neutralLoaded.first())
        assertEquals("encouragement_15", neutralLoaded.last())

        val repLoaded = mutableListOf<String?>()
        loadRepCountSoundPool(repLoaded) { it }
        assertEquals(25, repLoaded.size)
        assertEquals("rep_01", repLoaded.first())
        assertEquals("rep_25", repLoaded.last())
    }

    @Test
    fun selectIosSoundPlayerRoutesEveryEventAndRespectsEmptyDominatrixPool() {
        val eventPlayers = mapOf(
            HapticEvent.AUTO_START_READY to "cue:auto_start_ready",
            HapticEvent.REP_COMPLETED to "cue:rep",
        )
        val badgePlayers = listOf("badge-1", "badge-2")
        val prPlayers = listOf("pr-1")
        val repPlayers = (1..25).map { "rep-$it" }
        val neutral = listOf("neutral-1")
        val mild = listOf("mild-1")
        val strong = listOf("strong-1")
        val dominatrix = listOf("dom-1")

        assertNull(
            selectIosSoundPlayer(
                HapticEvent.ERROR, eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertEquals(
            "cue:auto_start_ready",
            selectIosSoundPlayer(
                HapticEvent.AUTO_START_READY, eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertEquals(
            "tick",
            selectIosSoundPlayer(
                HapticEvent.COUNTDOWN_TICK(3), eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertEquals(
            "rep-1",
            selectIosSoundPlayer(
                HapticEvent.REP_COUNT_ANNOUNCED(1), eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertEquals(
            "rep-5",
            selectIosSoundPlayer(
                HapticEvent.REP_COUNT_ANNOUNCED(5), eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertEquals(
            "rep-25",
            selectIosSoundPlayer(
                HapticEvent.REP_COUNT_ANNOUNCED(25), eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertNull(
            selectIosSoundPlayer(
                HapticEvent.REP_COUNT_ANNOUNCED(25), eventPlayers, badgePlayers, prPlayers, emptyList(), "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertFailsWith<IllegalArgumentException> { HapticEvent.REP_COUNT_ANNOUNCED(0) }
        assertFailsWith<IllegalArgumentException> { HapticEvent.REP_COUNT_ANNOUNCED(26) }
        assertTrue(
            selectIosSoundPlayer(
                HapticEvent.BADGE_EARNED, eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ) in badgePlayers,
        )
        assertEquals(
            "pr-1",
            selectIosSoundPlayer(
                HapticEvent.PERSONAL_RECORD, eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )

        // Verbal encouragement routing
        assertEquals(
            "neutral-1",
            selectIosSoundPlayer(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarMode = false, vulgarTier = VulgarTier.STRONG, dominatrixMode = true),
                eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertEquals(
            "dom-1",
            selectIosSoundPlayer(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.MILD, dominatrixMode = true, vulgarMode = true),
                eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertNull(
            selectIosSoundPlayer(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.MILD, dominatrixMode = true, vulgarMode = true),
                eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, emptyList(),
            ),
        )
        assertEquals(
            "mild-1",
            selectIosSoundPlayer(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.MILD, dominatrixMode = false, vulgarMode = true),
                eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertEquals(
            "strong-1",
            selectIosSoundPlayer(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.STRONG, dominatrixMode = false, vulgarMode = true),
                eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ),
        )
        assertTrue(
            selectIosSoundPlayer(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.MIX, dominatrixMode = false, vulgarMode = true),
                eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, mild, strong, dominatrix,
            ) in (mild + strong),
        )
        // Empty mild falls back to neutral
        assertEquals(
            "neutral-1",
            selectIosSoundPlayer(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.MILD, dominatrixMode = false, vulgarMode = true),
                eventPlayers, badgePlayers, prPlayers, repPlayers, "tick",
                neutral, emptyList(), strong, dominatrix,
            ),
        )
    }

    @Test
    fun iosSoundManagerLifecycleObserversAndReleaseDoNotThrow() {
        val manager = IosSoundManager()
        val center = NSNotificationCenter.defaultCenter
        try {
            manager.playSound(HapticEvent.ERROR)
            manager.playSound(HapticEvent.AUTO_START_READY)

            center.postNotificationName(UIApplicationDidEnterBackgroundNotification, `object` = null)
            center.postNotificationName(UIApplicationDidBecomeActiveNotification, `object` = null)
            center.postNotificationName(
                AVAudioSessionInterruptionNotification,
                `object` = null,
                userInfo = mapOf(AVAudioSessionInterruptionTypeKey to NSNumber(unsignedLongLong = AVAudioSessionInterruptionTypeBegan)),
            )
            center.postNotificationName(
                AVAudioSessionInterruptionNotification,
                `object` = null,
                userInfo = mapOf(AVAudioSessionInterruptionTypeKey to NSNumber(unsignedLongLong = AVAudioSessionInterruptionTypeEnded)),
            )
            center.postNotificationName(AVAudioSessionMediaServicesWereResetNotification, `object` = null)
        } finally {
            manager.release()
        }

        // Post-release notifications and cues must remain safe no-ops.
        center.postNotificationName(UIApplicationDidBecomeActiveNotification, `object` = null)
        manager.playSound(HapticEvent.AUTO_START_READY)
    }
}

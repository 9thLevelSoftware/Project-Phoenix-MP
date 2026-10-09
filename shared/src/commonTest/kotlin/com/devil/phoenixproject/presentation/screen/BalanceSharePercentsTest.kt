package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BalanceSharePercentsTest {

    @Test
    fun thirds_that_round_down_still_sum_to_100() {
        val shares = balanceSharePercents(pushVolume = 33.3f, pullVolume = 33.3f, legsVolume = 33.4f)

        assertEquals(BalanceSharePercents(push = 33, pull = 33, legs = 34), shares)
        assertEquals(100, shares!!.push + shares.pull + shares.legs)
    }

    @Test
    fun zero_total_volume_has_no_percent_labels() {
        assertNull(balanceSharePercents(pushVolume = 0f, pullVolume = 0f, legsVolume = 0f))
    }

    @Test
    fun one_category_owns_the_whole_bar() {
        assertEquals(
            BalanceSharePercents(push = 100, pull = 0, legs = 0),
            balanceSharePercents(pushVolume = 100f, pullVolume = 0f, legsVolume = 0f),
        )
        assertEquals(
            BalanceSharePercents(push = 0, pull = 0, legs = 100),
            balanceSharePercents(pushVolume = 0f, pullVolume = 0f, legsVolume = 100f),
        )
    }

    @Test
    fun round_ups_on_push_and_pull_do_not_make_legs_negative() {
        val shares = balanceSharePercents(pushVolume = 50.5f, pullVolume = 49.5f, legsVolume = 0f)

        assertEquals(BalanceSharePercents(push = 51, pull = 49, legs = 0), shares)
        assertEquals(100, shares!!.push + shares.pull + shares.legs)
    }
}

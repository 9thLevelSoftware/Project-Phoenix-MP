package com.devil.phoenixproject.tvnative

import com.devil.phoenixproject.util.ConnectivityChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate

class ConnectivityCheckerTest {

    @Test
    fun isOnlineReflectsInitialNetworkPathWithoutArtificialSleep() {
        val checker = ConnectivityChecker()
        val immediate = checker.isOnline()
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.15))
        val settled = checker.isOnline()
        assertEquals(settled, immediate, "Cold-start isOnline() must match settled NWPathMonitor status")
    }
}

package com.devil.phoenixproject.data.repository

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Supplies Android Build fields for DeviceInfo without replacing the production BLE composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KableGattCompositionTest : KableGattCompositionContract()

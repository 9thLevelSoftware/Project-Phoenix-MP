package com.juul.kable

import platform.Foundation.NSData
import kotlin.uuid.Uuid

actual interface PlatformAdvertisement : Advertisement {
    fun serviceDataAsNSData(uuid: Uuid): NSData?
    val manufacturerDataAsNSData: NSData?
    fun manufacturerDataAsNSData(companyIdentifierCode: Int): NSData?
}

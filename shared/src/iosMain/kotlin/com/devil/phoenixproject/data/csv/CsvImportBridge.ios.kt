package com.devil.phoenixproject.data.csv

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS has no intent intake for routine CSV (#1242): the bridge never offers anything and the
 * shared collector stays inert. No Info.plist / document-type change ships with this feature.
 */
actual fun csvImportOffers(): Flow<CsvImportOffer> = emptyFlow()

actual fun acknowledgeCsvImportOffer(deliveryId: String) = Unit

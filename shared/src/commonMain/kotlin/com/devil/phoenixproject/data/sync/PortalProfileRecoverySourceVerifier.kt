package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.ProfileRecoverySourceSnapshot
import com.devil.phoenixproject.data.repository.ProfileRecoverySourceVerification
import com.devil.phoenixproject.data.repository.ProfileRecoverySourceVerifier

class PortalProfileRecoverySourceVerifier(
    private val apiClient: PortalApiClient,
) : ProfileRecoverySourceVerifier {
    override suspend fun verify(
        source: ProfileRecoverySourceSnapshot,
    ): ProfileRecoverySourceVerification = apiClient.verifyProfileRecoverySource(source).getOrThrow()
}

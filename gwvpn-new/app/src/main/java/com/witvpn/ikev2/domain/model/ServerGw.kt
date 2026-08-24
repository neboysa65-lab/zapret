package com.witvpn.ikev2.domain.model

import com.witvpn.gw.crypto.GwCrypto
import com.witvpn.gw.model.GwServerConfig as TunnelConfig

data class ServerGw(
    val id: String,
    val name: String?,
    val country: String?,
    val countryCode: String?,
    val state: String?,
    val premium: Boolean,
    val recommend: Boolean,
    val priority: Int,
    val status: Boolean,
    val config: TunnelConfig?
) {
    val ipAddress: String? get() = config?.ipAddress ?: id

    companion object {
        fun decrypt(
            context: android.content.Context,
            metaId: String,
            name: String?,
            country: String?,
            countryCode: String?,
            state: String?,
            premium: Boolean,
            recommend: Boolean,
            priority: Int,
            status: Boolean,
            eph: String?,
            ct: String?,
            iv: String?
        ): ServerGw {
            val decrypted = if (eph != null && ct != null && iv != null) {
                try {
                    val privHex = GwIdentity.privateKeyHex(context) ?: return ServerGw(
                        metaId, name, country, countryCode, state,
                        premium, recommend, priority, status, null
                    )
                    val privKey = GwCrypto.normalizePrivateKey(privHex)
                    TunnelConfig.fromJson(
                        com.witvpn.gw.crypto.GwCrypto.decryptConfigEnvelope(privKey, eph, ct, iv)
                    )
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            return ServerGw(
                id = metaId,
                name = name,
                country = country,
                countryCode = countryCode,
                state = state,
                premium = premium,
                recommend = recommend,
                priority = priority,
                status = status,
                config = decrypted
            )
        }
    }
}

package com.witvpn.ikev2.vless

import java.io.Serializable

/**
 * Конфиг ноды из подписки.
 * protocol: "vless" | "hysteria2"
 * Для hysteria2 поле uuid = auth/password из URI.
 */
data class VlessConfig(
    var uuid: String = "",
    var address: String = "",
    var port: Int = 443,
    var flow: String = "",
    var encryption: String = "none",
    var security: String = "tls",
    var sni: String = "",
    var fp: String = "", // fingerprint
    var pbk: String = "",
    var sid: String = "",
    var type: String = "tcp",
    var serviceName: String = "", // For gRPC
    var mode: String = "gun", // For gRPC
    var name: String = "",
    var path: String = "", // For WS
    var host: String = "", // For WS/HTTP
    /** vless | hysteria2 */
    var protocol: String = PROTOCOL_VLESS,
    /** Hysteria2: salamander и т.п. */
    var obfs: String = "",
    var obfsPassword: String = "",
    var allowInsecure: Boolean = true,
) : Serializable {
    companion object {
        const val PROTOCOL_VLESS = "vless"
        const val PROTOCOL_HYSTERIA2 = "hysteria2"
    }

    fun isHysteria2(): Boolean =
        protocol.equals(PROTOCOL_HYSTERIA2, ignoreCase = true) ||
            protocol.equals("hy2", ignoreCase = true) ||
            protocol.equals("hysteria", ignoreCase = true)

    fun displayProtocolLabel(): String =
        if (isHysteria2()) "Hysteria2" else "VLESS"
}

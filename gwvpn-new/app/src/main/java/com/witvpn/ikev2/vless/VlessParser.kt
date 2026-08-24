package com.witvpn.ikev2.vless

import android.util.Base64
import com.google.gson.Gson
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object VlessParser {

    private val gson = Gson()

    /**
     * Парсит base64-подписку: vless://, hysteria2://, hy2://
     */
    fun parseSubscription(subscriptionData: String): List<VlessConfig> {
        return try {
            val raw = subscriptionData.trim()
            val decoded = try {
                String(Base64.decode(raw, Base64.DEFAULT), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                // уже plain text
                raw
            }
            decoded.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    when {
                        line.startsWith("vless://", ignoreCase = true) -> parseVlessUrl(line)
                        line.startsWith("hysteria2://", ignoreCase = true) ||
                            line.startsWith("hy2://", ignoreCase = true) -> parseHysteria2Url(line)
                        // классический hysteria:// (v1) — не трогаем
                        else -> null
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun parseQuery(uri: URI): Map<String, String> {
        val q = uri.rawQuery ?: uri.query ?: return emptyMap()
        return q.split("&")
            .filter { it.contains("=") }
            .associate {
                val parts = it.split("=", limit = 2)
                val key = urlDecode(parts[0])
                val value = urlDecode(parts.getOrNull(1) ?: "")
                key to value
            }
    }

    private fun urlDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun parseVlessUrl(url: String): VlessConfig? {
        try {
            val uri = URI(url)
            val uuid = uri.userInfo?.split(":")?.firstOrNull() ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val query = parseQuery(uri)
            val name = uri.rawFragment?.let { urlDecode(it) } ?: uri.fragment ?: host
            val serviceName = query["serviceName"] ?: query["servicename"] ?: ""
            val path = query["path"] ?: ""

            return VlessConfig(
                uuid = uuid,
                address = host,
                port = port,
                name = name,
                encryption = query["encryption"] ?: "none",
                type = query["type"] ?: "tcp",
                security = query["security"] ?: "none",
                path = path,
                host = query["host"] ?: "",
                sni = query["sni"] ?: "",
                flow = query["flow"] ?: "",
                fp = query["fp"] ?: "",
                pbk = query["pbk"] ?: "",
                sid = query["sid"] ?: "",
                serviceName = serviceName,
                mode = query["mode"] ?: "gun",
                protocol = VlessConfig.PROTOCOL_VLESS,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * hysteria2://password@host:port/?sni=...&obfs=salamander&obfs-password=...
     * password в userInfo (как в официальном URI Hysteria2).
     */
    private fun parseHysteria2Url(url: String): VlessConfig? {
        try {
            val uri = URI(url)
            // userInfo = password; может быть URL-encoded
            val password = uri.rawUserInfo?.let { urlDecode(it) }
                ?: uri.userInfo
                ?: return null
            if (password.isBlank()) return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val query = parseQuery(uri)
            val name = uri.rawFragment?.let { urlDecode(it) }
                ?: uri.fragment
                ?: "Hysteria2 $host"

            val insecure = query["insecure"]
                ?: query["allowInsecure"]
                ?: ""
            val allowInsecure = insecure == "1" || insecure.equals("true", ignoreCase = true)

            return VlessConfig(
                uuid = password, // auth
                address = host,
                port = port,
                name = name,
                sni = query["sni"] ?: query["peer"] ?: host,
                security = "tls",
                type = "hysteria",
                protocol = VlessConfig.PROTOCOL_HYSTERIA2,
                obfs = query["obfs"] ?: "",
                obfsPassword = query["obfs-password"]
                    ?: query["obfsPassword"]
                    ?: query["obfs_password"]
                    ?: "",
                allowInsecure = allowInsecure,
                fp = query["fp"] ?: query["fingerprint"] ?: "",
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun toV2RayConfig(config: VlessConfig, localSocksPort: Int, localHttpPort: Int): String {
        return if (config.isHysteria2()) {
            toHysteria2Config(config, localSocksPort, localHttpPort)
        } else {
            toVlessConfig(config, localSocksPort, localHttpPort)
        }
    }

    private fun baseInbounds(localSocksPort: Int, localHttpPort: Int): List<V2RayConfig.InboundObject> {
        return listOf(
            V2RayConfig.InboundObject().apply {
                tag = "socks"
                port = localSocksPort
                listen = "127.0.0.1"
                protocol = "socks"
                settings = V2RayConfig.SocksInboundSettings().apply {
                    auth = "noauth"
                    udp = true
                }
            },
            V2RayConfig.InboundObject().apply {
                tag = "http"
                port = localHttpPort
                listen = "127.0.0.1"
                protocol = "http"
            }
        )
    }

    private fun directAndBlock(): List<V2RayConfig.OutboundObject> {
        return listOf(
            V2RayConfig.OutboundObject().apply {
                tag = "direct"
                protocol = "freedom"
                settings = V2RayConfig.FreedomOutboundSettings()
            },
            V2RayConfig.OutboundObject().apply {
                tag = "block"
                protocol = "blackhole"
                settings = V2RayConfig.BlackholeOutboundSettings()
            }
        )
    }

    private fun toVlessConfig(config: VlessConfig, localSocksPort: Int, localHttpPort: Int): String {
        val v2rayConfig = V2RayConfig()
        v2rayConfig.log = V2RayConfig.LogObject().apply { loglevel = "info" }
        v2rayConfig.inbounds = baseInbounds(localSocksPort, localHttpPort)

        val grpcService = config.serviceName.ifEmpty { config.path }

        val proxyOutbound = V2RayConfig.OutboundObject().apply {
            tag = "proxy"
            protocol = "vless"
            settings = V2RayConfig.VlessOutboundSettings().apply {
                vnext = listOf(
                    V2RayConfig.VlessOutboundSettings.VnextObject().apply {
                        address = config.address
                        port = config.port
                        users = listOf(
                            V2RayConfig.VlessOutboundSettings.UserObject().apply {
                                id = config.uuid
                                encryption = config.encryption
                                flow = config.flow
                            }
                        )
                    }
                )
            }
            streamSettings = V2RayConfig.StreamSettingsObject().apply {
                network = config.type
                security = config.security

                when (config.security) {
                    "tls" -> {
                        tlsSettings = V2RayConfig.TlsSettingsObject().apply {
                            serverName = config.sni.ifEmpty { config.host.ifEmpty { config.address } }
                            allowInsecure = config.allowInsecure
                            if (config.fp.isNotEmpty()) fingerprint = config.fp
                        }
                    }
                    "reality" -> {
                        realitySettings = V2RayConfig.RealitySettingsObject().apply {
                            serverName = config.sni.ifEmpty { config.host.ifEmpty { config.address } }
                            publicKey = config.pbk
                            shortId = config.sid
                            fingerprint = config.fp.ifEmpty { "chrome" }
                        }
                    }
                }

                when (config.type) {
                    "ws" -> {
                        wsSettings = V2RayConfig.WsSettingsObject().apply {
                            path = config.path.ifEmpty { "/" }
                            headers = if (config.host.isNotEmpty()) mapOf("Host" to config.host) else emptyMap()
                        }
                    }
                    "grpc" -> {
                        grpcSettings = V2RayConfig.GrpcSettingsObject().apply {
                            serviceName = grpcService
                        }
                    }
                }
            }
            mux = V2RayConfig.MuxObject().apply {
                enabled = false
                concurrency = -1
            }
        }

        v2rayConfig.outbounds = listOf(proxyOutbound) + directAndBlock()
        return gson.toJson(v2rayConfig)
    }

    /**
     * Xray outbound protocol "hysteria" + version 2 (Hysteria2).
     * Совместимо с libxray (xtls/xray-core proxy/hysteria).
     */
    private fun toHysteria2Config(config: VlessConfig, localSocksPort: Int, localHttpPort: Int): String {
        val v2rayConfig = V2RayConfig()
        v2rayConfig.log = V2RayConfig.LogObject().apply { loglevel = "info" }
        v2rayConfig.inbounds = baseInbounds(localSocksPort, localHttpPort)

        val sni = config.sni.ifEmpty { config.address }

        val proxyOutbound = V2RayConfig.OutboundObject().apply {
            tag = "proxy"
            // В Xray протокол называется "hysteria", версия 2 = Hysteria2
            protocol = "hysteria"
            settings = V2RayConfig.HysteriaOutboundSettings().apply {
                version = 2
                address = config.address
                port = config.port
            }
            streamSettings = V2RayConfig.StreamSettingsObject().apply {
                // transport Hysteria (QUIC)
                network = "hysteria"
                security = "tls"
                tlsSettings = V2RayConfig.TlsSettingsObject().apply {
                    serverName = sni
                    allowInsecure = config.allowInsecure
                    if (config.fp.isNotEmpty()) fingerprint = config.fp
                }
                hysteriaSettings = V2RayConfig.HysteriaSettingsObject().apply {
                    version = 2
                    auth = config.uuid
                    // obfs salamander, если есть в URI
                    if (config.obfs.isNotEmpty()) {
                        obfs = V2RayConfig.HysteriaObfsObject().apply {
                            type = config.obfs
                            if (config.obfsPassword.isNotEmpty()) {
                                password = config.obfsPassword
                            }
                        }
                    }
                }
            }
            // mux не для hysteria
            mux = null
        }

        v2rayConfig.outbounds = listOf(proxyOutbound) + directAndBlock()
        return gson.toJson(v2rayConfig)
    }

    // --- Nested Data Classes mimicking V2Ray Config Structure ---

    class V2RayConfig {
        var log: LogObject? = null
        var inbounds: List<InboundObject>? = null
        var outbounds: List<OutboundObject>? = null

        class LogObject {
            var access: String? = null
            var error: String? = null
            var loglevel: String? = null
        }

        class InboundObject {
            var tag: String? = null
            var listen: String? = null
            var port: Int? = null
            var protocol: String? = null
            var settings: Any? = null
        }

        class SocksInboundSettings {
            var auth: String? = null
            var udp: Boolean? = null
        }

        class OutboundObject {
            var tag: String? = null
            var protocol: String? = null
            var settings: Any? = null
            var streamSettings: StreamSettingsObject? = null
            var mux: MuxObject? = null
        }

        class VlessOutboundSettings {
            var vnext: List<VnextObject>? = null

            class VnextObject {
                var address: String? = null
                var port: Int? = null
                var users: List<UserObject>? = null
            }

            class UserObject {
                var id: String? = null
                var encryption: String? = null
                var flow: String? = null
            }
        }

        /** settings для protocol=hysteria (Xray) */
        class HysteriaOutboundSettings {
            var version: Int? = 2
            var address: String? = null
            var port: Int? = null
        }

        class FreedomOutboundSettings {
            var domainStrategy: String = "AsIs"
        }

        class BlackholeOutboundSettings {
            var response: Any? = null
        }

        class StreamSettingsObject {
            var network: String? = null
            var security: String? = null
            var tlsSettings: TlsSettingsObject? = null
            var realitySettings: RealitySettingsObject? = null
            var wsSettings: WsSettingsObject? = null
            var grpcSettings: GrpcSettingsObject? = null
            var hysteriaSettings: HysteriaSettingsObject? = null
        }

        class HysteriaSettingsObject {
            var version: Int? = 2
            var auth: String? = null
            var udpIdleTimeout: Int? = 60
            var obfs: HysteriaObfsObject? = null
        }

        class HysteriaObfsObject {
            var type: String? = null
            var password: String? = null
        }

        class TlsSettingsObject {
            var serverName: String? = null
            var allowInsecure: Boolean? = null
            var fingerprint: String? = null
        }

        class RealitySettingsObject {
            var serverName: String? = null
            var publicKey: String? = null
            var shortId: String? = null
            var fingerprint: String? = null
        }

        class WsSettingsObject {
            var path: String? = null
            var headers: Map<String, String>? = null
        }

        class GrpcSettingsObject {
            var serviceName: String? = null
        }

        class MuxObject {
            var enabled: Boolean? = null
            var concurrency: Int? = null
        }
    }
}

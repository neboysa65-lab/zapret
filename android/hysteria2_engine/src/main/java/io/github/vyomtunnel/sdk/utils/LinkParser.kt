package io.github.vyomtunnel.sdk.utils

import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hysteria2-only link parser for the ported engine.
 *
 * Turns a `hysteria2://` / `hy2://` URI into an Xray JSON config whose local
 * SOCKS inbound listens on 127.0.0.1:20808 — the exact address the bundled
 * hev-socks5-tunnel (see VyomVpnService.tun.yaml) forwards TUN traffic to.
 *
 * Copied verbatim (minus the VLESS/VMess branches) from the gwapk engine so
 * the generated config stays byte-for-byte compatible with the prebuilt
 * libxray.so that implements the Hysteria2 outbound.
 */
object LinkParser {

    private const val TAG = "VyomLinkParser"

    fun parse(link: String): String {
        return try {
            when {
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2(link)
                else -> throw IllegalArgumentException(
                    "Unsupported protocol: this engine only handles Hysteria2"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse link: $link", e)
            throw e
        }
    }

    /**
     * hysteria2://password@host:port/?sni=...&obfs=salamander&obfs-password=...
     * Собирает Xray JSON: protocol=hysteria, version=2 (Hysteria2 в libxray).
     */
    private fun parseHysteria2(link: String): String {
        val uri = Uri.parse(link)
        val password = uri.userInfo.orEmpty()
        if (password.isEmpty()) {
            throw IllegalArgumentException("Hysteria2 link missing password")
        }
        val host = uri.host.orEmpty()
        if (host.isEmpty()) {
            throw IllegalArgumentException("Hysteria2 link missing host")
        }
        val port = if (uri.port != -1) uri.port else 443
        val sni = uri.getQueryParameter("sni")
            ?: uri.getQueryParameter("peer")
            ?: host
        val insecure = uri.getQueryParameter("insecure")
            ?: uri.getQueryParameter("allowInsecure")
            ?: ""
        val allowInsecure = insecure == "1" || insecure.equals("true", ignoreCase = true)
        val obfs = uri.getQueryParameter("obfs").orEmpty()
        val obfsPassword = uri.getQueryParameter("obfs-password")
            ?: uri.getQueryParameter("obfsPassword")
            ?: ""

        return buildHysteria2ConfigJson(
            host = host,
            port = port,
            auth = password,
            sni = sni,
            allowInsecure = allowInsecure,
            obfs = obfs,
            obfsPassword = obfsPassword,
        )
    }

    /** Xray Hysteria2 outbound (protocol hysteria + version 2). */
    private fun buildHysteria2ConfigJson(
        host: String,
        port: Int,
        auth: String,
        sni: String,
        allowInsecure: Boolean,
        obfs: String,
        obfsPassword: String,
    ): String {
        val config = JSONObject()
        config.put("log", JSONObject().put("loglevel", "warning"))

        config.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("https://1.1.1.1/dns-query")
                put("https://8.8.8.8/dns-query")
                put("localhost")
            })
            put("queryStrategy", "UseIPv4")
        })

        config.put("inbounds", JSONArray().put(
            JSONObject().apply {
                put("listen", "127.0.0.1")
                put("port", 20808)
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http")
                        put("tls")
                    })
                })
            }
        ))

        val hysteriaSettings = JSONObject().apply {
            put("version", 2)
            put("auth", auth)
            put("udpIdleTimeout", 60)
            if (obfs.isNotEmpty()) {
                put("obfs", JSONObject().apply {
                    put("type", obfs)
                    if (obfsPassword.isNotEmpty()) put("password", obfsPassword)
                })
            }
        }

        val proxyOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "hysteria")
            put("settings", JSONObject().apply {
                put("version", 2)
                put("address", host)
                put("port", port)
            })
            put("streamSettings", JSONObject().apply {
                put("network", "hysteria")
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("serverName", sni)
                    put("allowInsecure", allowInsecure)
                })
                put("hysteriaSettings", hysteriaSettings)
            })
        }

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().put("domainStrategy", "UseIP"))
        }

        config.put("outbounds", JSONArray().apply {
            put(proxyOutbound)
            put(directOutbound)
        })

        config.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "proxy")
                })
            })
        })

        return config.toString()
    }
}

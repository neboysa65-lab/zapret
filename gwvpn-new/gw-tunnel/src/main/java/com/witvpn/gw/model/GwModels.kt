package com.witvpn.gw.model

import com.witvpn.gw.crypto.GwObf
import com.witvpn.gw.crypto.GwCrypto
import org.json.JSONObject

data class GwServerMeta(
    val id: String,
    val name: String,
    val country: String,
    val countryCode: String,
    val state: String,
    val premium: Boolean,
    val recommend: Boolean,
    val priority: Int,
    val status: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject): GwServerMeta = GwServerMeta(
            id = o.optString("id"),
            name = o.optString("name"),
            country = o.optString("country"),
            countryCode = o.optString("country_code"),
            state = o.optString("state"),
            premium = o.optBoolean("premium", false),
            recommend = o.optBoolean("recommend", false),
            priority = o.optInt("priority", 0),
            status = o.optBoolean("status", true),
        )
    }
}

data class GwEncryptedServer(
    val meta: GwServerMeta,
    val ephPubHex: String,
    val ctBase64: String,
    val ivBase64: String,
) {
    fun decrypt(privateKey: ByteArray): GwServerConfig? {
        return try {
            GwServerConfig.fromJson(
                JSONObject(
                    GwCrypto.decryptConfigEnvelope(privateKey, ephPubHex, ctBase64, ivBase64)
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun fromJson(o: JSONObject): GwEncryptedServer? {
            val enc = o.optJSONObject("enc") ?: return null
            val metaObj = o.optJSONObject("meta") ?: JSONObject()
            val eph = enc.optString("eph")
            val ct = enc.optString("ct")
            val iv = enc.optString("iv")
            if (eph.isEmpty() || ct.isEmpty() || iv.isEmpty()) return null
            return GwEncryptedServer(GwServerMeta.fromJson(metaObj), eph, ct, iv)
        }
    }
}

data class GwServerConfig(
    val id: String,
    val ipAddress: String,
    val sshPort: Int,
    val sshUsername: String,
    val sshPassword: String,
    val proxyHost: String,
    val proxyPort: Int,
    val proxyScheme: String,
    val payload: String,
    val sni: String,
    val sshHostkey: String,
) {
    fun toJson(): String = JSONObject().apply {
        // Компактный (обфусцированный) формат полей — расшифрованный конверт
        // не должен раскрывать схему протокола при дампе памяти/трафика.
        put(GwObf.K_ID, id)
        put(GwObf.K_IP, ipAddress)
        put(GwObf.K_SSH_PORT, sshPort)
        put(GwObf.K_SSH_USER, sshUsername)
        put(GwObf.K_SSH_PASS, sshPassword)
        put(GwObf.K_PROXY_HOST, proxyHost)
        put(GwObf.K_PROXY_PORT, proxyPort)
        put(GwObf.K_PROXY_SCHEME, proxyScheme)
        put(GwObf.K_PAYLOAD, payload)
        put(GwObf.K_SNI, sni)
        put(GwObf.K_HOSTKEY, sshHostkey)
    }.toString()

    companion object {
        val DEFAULT_UA: String get() = GwObf.UA

        fun fromJson(text: String): GwServerConfig = fromJson(JSONObject(text))

        fun fromJson(o: JSONObject): GwServerConfig {
            // v2: компактные ключи; legacy: старые имена (совместимость со старым сервером)
            fun pick(compact: String, legacy: String, def: String = ""): String =
                if (o.has(compact)) o.optString(compact) else o.optString(legacy, def)

            fun pickInt(compact: String, legacy: String, def: Int): Int =
                if (o.has(compact)) o.optInt(compact, def) else o.optInt(legacy, def)

            return GwServerConfig(
                id = pick(GwObf.K_ID, "id"),
                ipAddress = pick(GwObf.K_IP, "ip_address"),
                sshPort = pickInt(GwObf.K_SSH_PORT, "ssh_port", 22),
                sshUsername = pick(GwObf.K_SSH_USER, "ssh_username", GwObf.DEFAULT_USER),
                sshPassword = pick(GwObf.K_SSH_PASS, "ssh_password"),
                proxyHost = pick(GwObf.K_PROXY_HOST, "proxy_host"),
                proxyPort = pickInt(GwObf.K_PROXY_PORT, "proxy_port", 80),
                proxyScheme = pick(GwObf.K_PROXY_SCHEME, "proxy_scheme", "http"),
                payload = pick(GwObf.K_PAYLOAD, "payload"),
                sni = pick(GwObf.K_SNI, "sni"),
                sshHostkey = pick(GwObf.K_HOSTKEY, "ssh_hostkey"),
            )
        }

        fun defaultPayload(hostPlaceholder: String = GwObf.T_HOST): String =
            GwObf.PAYLOAD_TPL.replace(GwObf.T_HOST, hostPlaceholder)
    }
}

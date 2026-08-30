package com.witvpn.vlee.model

import com.witvpn.vlee.crypto.VleeObf
import com.witvpn.vlee.crypto.VleeCrypto
import org.json.JSONObject

data class VleeServerMeta(
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
        fun fromJson(o: JSONObject): VleeServerMeta = VleeServerMeta(
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

data class VleeEncryptedServer(
    val meta: VleeServerMeta,
    val ephPubHex: String,
    val ctBase64: String,
    val ivBase64: String,
) {
    fun decrypt(privateKey: ByteArray): VleeServerConfig? {
        return try {
            VleeServerConfig.fromJson(
                JSONObject(
                    VleeCrypto.decryptConfigEnvelope(privateKey, ephPubHex, ctBase64, ivBase64)
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun fromJson(o: JSONObject): VleeEncryptedServer? {
            val enc = o.optJSONObject("enc") ?: return null
            val metaObj = o.optJSONObject("meta") ?: JSONObject()
            val eph = enc.optString("eph")
            val ct = enc.optString("ct")
            val iv = enc.optString("iv")
            if (eph.isEmpty() || ct.isEmpty() || iv.isEmpty()) return null
            return VleeEncryptedServer(VleeServerMeta.fromJson(metaObj), eph, ct, iv)
        }
    }
}

data class VleeServerConfig(
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
        put(VleeObf.K_ID, id)
        put(VleeObf.K_IP, ipAddress)
        put(VleeObf.K_SSH_PORT, sshPort)
        put(VleeObf.K_SSH_USER, sshUsername)
        put(VleeObf.K_SSH_PASS, sshPassword)
        put(VleeObf.K_PROXY_HOST, proxyHost)
        put(VleeObf.K_PROXY_PORT, proxyPort)
        put(VleeObf.K_PROXY_SCHEME, proxyScheme)
        put(VleeObf.K_PAYLOAD, payload)
        put(VleeObf.K_SNI, sni)
        put(VleeObf.K_HOSTKEY, sshHostkey)
    }.toString()

    companion object {
        val DEFAULT_UA: String get() = VleeObf.UA

        fun fromJson(text: String): VleeServerConfig = fromJson(JSONObject(text))

        fun fromJson(o: JSONObject): VleeServerConfig {
            // v2: компактные ключи; legacy: старые имена (совместимость со старым сервером)
            fun pick(compact: String, legacy: String, def: String = ""): String =
                if (o.has(compact)) o.optString(compact) else o.optString(legacy, def)

            fun pickInt(compact: String, legacy: String, def: Int): Int =
                if (o.has(compact)) o.optInt(compact, def) else o.optInt(legacy, def)

            return VleeServerConfig(
                id = pick(VleeObf.K_ID, "id"),
                ipAddress = pick(VleeObf.K_IP, "ip_address"),
                sshPort = pickInt(VleeObf.K_SSH_PORT, "ssh_port", 22),
                sshUsername = pick(VleeObf.K_SSH_USER, "ssh_username", VleeObf.DEFAULT_USER),
                sshPassword = pick(VleeObf.K_SSH_PASS, "ssh_password"),
                proxyHost = pick(VleeObf.K_PROXY_HOST, "proxy_host"),
                proxyPort = pickInt(VleeObf.K_PROXY_PORT, "proxy_port", 80),
                proxyScheme = pick(VleeObf.K_PROXY_SCHEME, "proxy_scheme", "http"),
                payload = pick(VleeObf.K_PAYLOAD, "payload"),
                sni = pick(VleeObf.K_SNI, "sni"),
                sshHostkey = pick(VleeObf.K_HOSTKEY, "ssh_hostkey"),
            )
        }

        fun defaultPayload(hostPlaceholder: String = VleeObf.T_HOST): String =
            VleeObf.PAYLOAD_TPL.replace(VleeObf.T_HOST, hostPlaceholder)
    }
}

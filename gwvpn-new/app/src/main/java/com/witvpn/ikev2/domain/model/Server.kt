package com.witvpn.ikev2.domain.model

import com.google.gson.Gson
import com.witvpn.ikev2.presentation.utils.SharePrefs
import com.witvpn.ikev2.presentation.utils.getStringPref
import com.witvpn.ikev2.presentation.utils.putStringPref
import com.witvpn.ikev2.presentation.utils.removePref

/**
 * Единая UI-модель сервера для списка и черновиков выбора. Реальные
 * протоколы — AWG и GW; поля CA/кредов удалены вместе с ядром IKEv2,
 * старые драфты с ними Gson просто проигнорирует.
 */
data class Server(
    val id: String,
    val country: String?,
    val ipAddress: String?,
    val premium: Boolean?,
    val recommend: Boolean?,
    val state: String?,
    val countryCode: String?,
    val protocol: String? = null,
    val configAwg: String? = null
) {
    companion object {
        fun fromAwg(awg: ServerAwg): Server {
            return Server(
                id = "awg_${awg.ipAddress}",
                country = awg.country,
                ipAddress = awg.ipAddress,
                premium = awg.premium,
                recommend = awg.recommend,
                state = if (awg.state.isNullOrBlank()) awg.country else awg.state,
                countryCode = awg.countryCode,
                protocol = "awg",
                configAwg = awg.config
            )
        }

        fun fromGw(gw: ServerGw): Server {
            return Server(
                id = "gw_${gw.id}",
                country = gw.country,
                ipAddress = gw.ipAddress,
                premium = gw.premium,
                recommend = gw.recommend,
                state = if (gw.state.isNullOrBlank()) gw.country else gw.state,
                countryCode = gw.countryCode,
                protocol = "gw",
                configAwg = gw.config?.toJson()
            )
        }

        private fun draftKeyForProtocol(protocol: String?): String {
            return when {
                protocol.equals("awg", ignoreCase = true) -> SharePrefs.KEY_SERVER_AWG
                protocol.equals("gw", ignoreCase = true) -> SharePrefs.KEY_SERVER_GW
                else -> SharePrefs.KEY_SERVER
            }
        }

        fun getDraftForProtocol(protocol: String): Server? {
            return readDraft(draftKeyForProtocol(protocol))
        }

        private fun readDraft(key: String): Server? {
            return getStringPref(key)?.let {
                Gson().fromJson(it, Server::class.java)
            }
        }

        fun clearDraftForProtocol(protocol: String) {
            removePref(draftKeyForProtocol(protocol))
        }
    }

    fun saveDraft() {
        val key = draftKeyForProtocol(this.protocol)
        putStringPref(key, Gson().toJson(this))
    }

    override fun equals(other: Any?): Boolean {
        if (other is Server) {
            return other.id == this.id && other.protocol == this.protocol
        }
        return false
    }

    override fun hashCode(): Int {
        return id.hashCode() * 31 + (protocol?.hashCode() ?: 0)
    }
}

package com.witvpn.ikev2.domain.model

import com.google.gson.Gson
import com.witvpn.ikev2.data.remote.model.ServerObject
import com.witvpn.ikev2.presentation.utils.SharePrefs
import com.witvpn.ikev2.presentation.utils.getStringPref
import com.witvpn.ikev2.presentation.utils.putStringPref
import com.witvpn.ikev2.presentation.utils.removePref

data class Server(
    val id: String,
    val country: String?,
    val ipAddress: String?,
    val premium: Boolean?,
    val recommend: Boolean?,
    val state: String?,
    val countryCode: String?,
    val ca_file: String?,
    val ca_fileName: String?,
    val p_nsm: String?,
    val u_nsm: String?,
    var uuid: String? = null,
    val protocol: String? = "ikev2",
    val configAwg: String? = null
) {
    companion object {
        val AUTO_CONNECT_STAB = Server(id = "android.autoconnect", "Auto Choice", null, null, null, null, "ic_globe", null, null, null, null, null, protocol = "ikev2")

        val Server.isAutoConnect: Boolean
            get() = AUTO_CONNECT_STAB == this

        fun fromObject(serverObject: ServerObject): Server {
            return Server(
                id = serverObject.id,
                country = serverObject.country,
                ipAddress = serverObject.ipAddress,
                premium = serverObject.premium as? Boolean,
                recommend = serverObject.recommend as? Boolean,
                state = serverObject.state,
                countryCode = serverObject.countryCode,
                ca_file = serverObject.caFile,
                ca_fileName = serverObject.caFileName,
                p_nsm = serverObject.p_nsm,
                u_nsm = serverObject.u_nsm,
                protocol = "ikev2"
            )
        }
        
        fun fromAwg(awg: ServerAwg): Server {
            return Server(
                id = "awg_${awg.ipAddress}",
                country = awg.country,
                ipAddress = awg.ipAddress,
                premium = awg.premium,
                recommend = awg.recommend,
                state = if (awg.state.isNullOrBlank()) awg.country else awg.state,
                countryCode = awg.countryCode,
                ca_file = null,
                ca_fileName = null,
                p_nsm = null,
                u_nsm = null,
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
                ca_file = null,
                ca_fileName = null,
                p_nsm = null,
                u_nsm = null,
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

        fun getDraft(): Server? {
            return readDraft(SharePrefs.KEY_SERVER)
        }

        fun getDraftForProtocol(protocol: String): Server? {
            return readDraft(draftKeyForProtocol(protocol))
        }

        private fun readDraft(key: String): Server? {
            return getStringPref(key)?.let {
                Gson().fromJson(it, Server::class.java)
            }
        }

        fun clearDraft() {
            removePref(SharePrefs.KEY_SERVER)
            removePref(SharePrefs.KEY_SERVER_AWG)
            removePref(SharePrefs.KEY_SERVER_GW)
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

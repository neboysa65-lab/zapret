package com.witvpn.ikev2.presentation.ui.servers

import com.witvpn.ikev2.domain.model.Server

class SelectDefaultServerCase {
    fun execute(servers :List<Server>) :Server {
        val draft = Server.getDraft()
        if (draft != null) {
            return draft
        }
        val freeServers = servers.filter { it.premium?.let { !it } ?: true }
        if (freeServers.isEmpty()) {
            return servers.randomOrNull() ?: Server.AUTO_CONNECT_STAB
        }
        return freeServers.random()
    }
}
package com.witvpn.ikev2.domain.repository

import com.witvpn.ikev2.domain.model.Server
import kotlinx.coroutines.flow.StateFlow

interface ServerRepository {
    val stateFlow : StateFlow<List<Server>>
    val serversLoadFlow : StateFlow<Map<String, Int>>
    val awgStateFlow : StateFlow<List<com.witvpn.ikev2.domain.model.ServerAwg>>
    val gwStateFlow : StateFlow<List<com.witvpn.ikev2.domain.model.ServerGw>>
    suspend fun getServers(param: MutableMap<String, Any>)
    suspend fun getServersAwg(param: MutableMap<String, Any>)
    suspend fun getServersGw(context: android.content.Context, param: MutableMap<String, Any>)
    suspend fun fetchServersLoad()
}
package com.witvpn.ikev2.domain.repository

import kotlinx.coroutines.flow.StateFlow

interface ServerRepository {
    val serversLoadFlow : StateFlow<Map<String, Int>>
    val awgStateFlow : StateFlow<List<com.witvpn.ikev2.domain.model.ServerAwg>>
    val gwStateFlow : StateFlow<List<com.witvpn.ikev2.domain.model.ServerGw>>
    suspend fun getServersAwg(param: MutableMap<String, Any>)
    suspend fun getServersGw(context: android.content.Context, param: MutableMap<String, Any>)
    suspend fun fetchServersLoad()
}
package com.witvpn.ikev2.presentation.ui.servers

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.domain.model.User
import com.witvpn.ikev2.domain.repository.ServerRepository
import com.witvpn.ikev2.presentation.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : BaseViewModel() {

    val serversList: LiveData<List<Server>> = serverRepository.stateFlow.asLiveData()
    val serversAwgList: LiveData<List<com.witvpn.ikev2.domain.model.ServerAwg>> = serverRepository.awgStateFlow.asLiveData()
    val serversGwList: LiveData<List<com.witvpn.ikev2.domain.model.ServerGw>> = serverRepository.gwStateFlow.asLiveData()
    val serversLoadMap: LiveData<Map<String, Int>> = serverRepository.serversLoadFlow.asLiveData()

    @Volatile
    private var isExecuting = false

    fun execute(user: User) {
        if (isExecuting) return
        isExecuting = true
        viewModelScope.launch(exceptionHandler) {
            try {
                val param = mutableMapOf("userId" to user.id as Any)
                serverRepository.getServers(param)
                serverRepository.getServersAwg(param)
                com.witvpn.ikev2.presentation.MyApp.self.applicationContext?.let { ctx ->
                    // v=2 — сервер отдаёт конверт с компактными (обфусцированными) ключами полей
                    val gwParam = mutableMapOf<String, Any>("userId" to user.id as Any, "v" to "2")
                    serverRepository.getServersGw(ctx, gwParam)
                }
            } finally {
                isExecuting = false
            }
        }
        fetchLoad()
    }

    fun fetchLoad() {
        viewModelScope.launch(exceptionHandler) {
            serverRepository.fetchServersLoad()
        }
    }
}
package com.witvpn.ikev2.presentation.ui.servers

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.domain.model.User
import com.witvpn.ikev2.domain.repository.ServerRepository
import com.witvpn.ikev2.presentation.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
                // Запросы независимы, поэтому идут параллельно. Раньше это была
                // строгая цепочка getServers -> getServersAwg -> getServersGw в
                // одной корутине, и GW стоял последним: по логам server_awg
                // отвечал 4.3 с, из-за чего GW-список появлялся на ~5.5 с против
                // ~2.2 с у ikev2 — та самая «задержка по сравнению с ikev2».
                // Каждый список теперь отрисовывается сразу по своему ответу.
                //
                // Карта параметров у каждого запроса СВОЯ: getServersGw
                // дописывает в неё pubkey, и общий mutable map превратился бы в
                // гонку между параллельными вызовами.
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                jobs += async {
                    runCatching {
                        serverRepository.getServers(mutableMapOf("userId" to user.id as Any))
                    }.onFailure {
                        android.util.Log.e("ServersViewModel", "getServers failed: ${it.message}", it)
                    }
                    Unit
                }
                jobs += async {
                    runCatching {
                        serverRepository.getServersAwg(mutableMapOf("userId" to user.id as Any))
                    }.onFailure {
                        android.util.Log.e("ServersViewModel", "getServersAwg failed: ${it.message}", it)
                    }
                    Unit
                }
                jobs += async {
                    runCatching { serverRepository.fetchServersLoad() }
                    Unit
                }
                com.witvpn.ikev2.presentation.MyApp.self.applicationContext?.let { ctx ->
                    jobs += async {
                        runCatching {
                            // v=2 — сервер отдаёт конверт с компактными
                            // (обфусцированными) ключами полей
                            serverRepository.getServersGw(
                                ctx,
                                mutableMapOf("userId" to user.id as Any, "v" to "2")
                            )
                        }.onFailure {
                            android.util.Log.e("ServersViewModel", "getServersGw failed: ${it.message}", it)
                        }
                        Unit
                    }
                }

                // runCatching внутри каждой ветки гарантирует, что упавший
                // запрос не отменит остальные (а без него исключение в одном
                // async снесло бы весь скоуп вместе с уже летящими запросами).
                jobs.awaitAll()
            } finally {
                isExecuting = false
            }
        }
    }

    fun fetchLoad() {
        viewModelScope.launch(exceptionHandler) {
            serverRepository.fetchServersLoad()
        }
    }
}

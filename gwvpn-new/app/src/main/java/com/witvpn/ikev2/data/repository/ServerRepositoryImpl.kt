package com.witvpn.ikev2.data.repository

import com.witvpn.ikev2.data.remote.ApiService
import com.witvpn.ikev2.data.remote.model.ServerObject
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.domain.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val api: ApiService
) : ServerRepository {
    private val state = MutableStateFlow<List<Server>>(listOf())
    override val stateFlow: StateFlow<List<Server>> =
        state.asStateFlow()
        
    private val awgState = MutableStateFlow<List<com.witvpn.ikev2.domain.model.ServerAwg>>(listOf())
    override val awgStateFlow: StateFlow<List<com.witvpn.ikev2.domain.model.ServerAwg>> = awgState.asStateFlow()

    private val gwState = MutableStateFlow<List<com.witvpn.ikev2.domain.model.ServerGw>>(listOf())
    override val gwStateFlow: StateFlow<List<com.witvpn.ikev2.domain.model.ServerGw>> = gwState.asStateFlow()

    override suspend fun getServersGw(context: android.content.Context, param: MutableMap<String, Any>) {
        try {
            val response = api.getServersGw(param)
            // success может прийти как 1 (успех). Если поле не парсится (null), но
            // сервер вернул данные — не считаем это ошибкой: старые/иные бэкенды
            // отдают success по-разному. Ошибкой считаем только явный не-успех.
            if (response.success != null && response.success != 1) {
                android.util.Log.e("GwServerRepository", "getServersGw failed: ${response.message}")
                return
            }

            val dtos = response.data
            if (dtos == null) {
                android.util.Log.e("GwServerRepository", "getServersGw: null data, keeping previous list")
                return
            }

            val decoded = dtos.mapNotNull { dto ->
                val meta = dto.meta ?: return@mapNotNull null
                val enc = dto.enc
                com.witvpn.ikev2.domain.model.ServerGw.decrypt(
                    context = context,
                    metaId = meta.id ?: "",
                    name = meta.name,
                    country = meta.country,
                    countryCode = meta.countryCode,
                    state = meta.state,
                    premium = meta.premium ?: false,
                    recommend = meta.recommend ?: false,
                    priority = meta.priority ?: 0,
                    status = meta.status ?: true,
                    eph = enc?.eph,
                    ct = enc?.ct,
                    iv = enc?.iv
                )
            }
            val usable = decoded.filter { it.config != null }
            val failed = decoded.size - usable.size
            if (failed > 0) {
                android.util.Log.w(
                    "GwServerRepository",
                    "getServersGw: $failed/${decoded.size} серверов не расшифровались " +
                        "(ключ устройства ещё не зарегистрирован на сервере?)"
                )
            }

            // Если сервер прислал ноды, но НИ ОДНА не расшифровалась — это почти
            // наверняка проблема ключа/регистрации на этом устройстве, а не удаление
            // серверов. В таком случае НЕ затираем ранее показанный список, чтобы
            // серверы не «пропадали» из UI. Пустой ответ (dtos пуст) — легитимно.
            if (dtos.isNotEmpty() && usable.isEmpty()) {
                android.util.Log.e(
                    "GwServerRepository",
                    "getServersGw: все ${dtos.size} серверов не расшифровались, оставляем прежний список"
                )
                return
            }

            gwState.update { usable }
        } catch (e: Exception) {
            android.util.Log.e("GwServerRepository", "getServersGw EXCEPTION: ${e.message}", e)
        }
    }

    override suspend fun getServersAwg(param: MutableMap<String, Any>) {
        try {
            val response = api.getServersAwg(param)
            val servers = response.data?.map { dto ->
                com.witvpn.ikev2.domain.model.ServerAwg(
                    ipAddress = dto.ipAddress ?: "",
                    country = dto.country ?: "",
                    state = dto.state ?: "",
                    countryCode = dto.countryCode ?: "",
                    premium = dto.premium ?: false,
                    config = dto.config ?: "",
                    status = dto.status ?: false,
                    priority = dto.priority ?: 0,
                    recommend = dto.recommend ?: false
                )
            } ?: emptyList()
            awgState.update { servers }
        } catch (e: Exception) {
            android.util.Log.e("AWGServerRepository", "getServersAwg EXCEPTION: ${e.message}", e)
        }
    }
    private val _serversLoad = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val serversLoadFlow: StateFlow<Map<String, Int>> = _serversLoad.asStateFlow()

    override suspend fun getServers(param: MutableMap<String, Any>) {
        val response = api.getServers(param)
        val servers = response.data.map { unwrap(it) }
        state.update { servers }
        fetchServersLoad()
    }
    
    override suspend fun fetchServersLoad() {
        try {
            val response = api.getServersLoad(mutableMapOf())
            if (response.isSuccessful && response.body()?.success == 1) {
                val data = response.body()?.data ?: emptyMap()
                android.util.Log.d("VlessLoad", "fetchServersLoad SUCCESS. Size: ${data.size}, keys: ${data.keys}")
                _serversLoad.update { data }
            } else {
                android.util.Log.e("VlessLoad", "fetchServersLoad FAILED. code=${response.code()}, errorBody=${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            android.util.Log.e("VlessLoad", "fetchServersLoad EXCEPTION: ${e.message}", e)
        }
    }

    private fun unwrap(serverObject: ServerObject): Server {
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
            u_nsm = serverObject.u_nsm
        )
    }
}
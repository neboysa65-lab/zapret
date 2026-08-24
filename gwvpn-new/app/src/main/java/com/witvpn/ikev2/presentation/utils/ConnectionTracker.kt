package com.witvpn.ikev2.presentation.utils

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Репортинг подключений в API (connect / heartbeat / disconnect).
 *
 * ВАЖНО: используется ТОЛЬКО для VLESS и AWG.
 * Для IKEv2 клиент больше НИЧЕГО не шлёт — онлайн IKEv2 сервер API считает
 * сам, опрашивая каждый IKEv2-сервер напрямую (node_exporter, ipsec_clients).
 */
object ConnectionTracker {
    private val client = OkHttpClient.Builder()
        .addInterceptor(com.witvpn.ikev2.presentation.utils.interceptor.ModifyRequestInterceptor())
        .build()
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun reportConnect(context: Context, userId: String, serverIp: String, protocol: String = "ikev2") {
        if (protocol.equals("ikev2", ignoreCase = true)) {
            // IKEv2 не репортим: онлайн считается на стороне API по метрикам
            // самих серверов. Останавливаем возможный heartbeat от прошлой
            // VLESS/AWG-сессии и выходим.
            stopHeartbeat()
            Timber.d("ConnectionTracker: ikev2 connect not reported (server-side metrics)")
            return
        }
        scope.launch {
            try {
                val body = FormBody.Builder()
                    .add("userId", userId)
                    .add("serverIp", serverIp)
                    .add("protocol", protocol)
                    .add("action", "connect")
                    .build()
                val request = Request.Builder()
                    .url("${getBaseUrl(context)}/user/connection/update")
                    .post(body)
                    .build()
                client.newCall(request).execute().close()
                Timber.d("ConnectionTracker: connect reported for $serverIp")
            } catch (e: Exception) {
                Timber.e(e, "ConnectionTracker: failed to report connect")
            }
        }
        startHeartbeat(userId, serverIp, protocol, context)
    }

    fun reportDisconnect(context: Context, userId: String) {
        stopHeartbeat()
        scope.launch {
            try {
                val body = FormBody.Builder()
                    .add("userId", userId)
                    .add("serverIp", "")
                    .add("protocol", "ikev2")
                    .add("action", "disconnect")
                    .build()
                val request = Request.Builder()
                    .url("${getBaseUrl(context)}/user/connection/update")
                    .post(body)
                    .build()
                client.newCall(request).execute().close()
                Timber.d("ConnectionTracker: disconnect reported")
            } catch (e: Exception) {
                Timber.e(e, "ConnectionTracker: failed to report disconnect")
            }
        }
    }

    private fun startHeartbeat(userId: String, serverIp: String, protocol: String, context: Context) {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(60_000) // every 60 seconds
                try {
                    val body = FormBody.Builder()
                        .add("userId", userId)
                        .add("serverIp", serverIp)
                        .add("protocol", protocol)
                        .add("action", "heartbeat")
                        .build()
                    val request = Request.Builder()
                        .url("${getBaseUrl(context)}/user/connection/update")
                        .post(body)
                        .build()
                    client.newCall(request).execute().close()
                    Timber.d("ConnectionTracker: heartbeat sent")
                } catch (e: Exception) {
                    Timber.e(e, "ConnectionTracker: heartbeat failed")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun getBaseUrl(context: Context): String {
        // Актуальный домен из Remote Config (с fallback на кэш/дефолт).
        return RemoteConfigManager.getApiBaseUrl(context).removeSuffix("/")
    }
}

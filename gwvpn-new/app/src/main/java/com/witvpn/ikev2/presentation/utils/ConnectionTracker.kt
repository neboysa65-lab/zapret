package com.witvpn.ikev2.presentation.utils

import android.content.Context
import com.witvpn.ikev2.presentation.utils.interceptor.DynamicBaseUrlInterceptor
import com.witvpn.ikev2.presentation.utils.interceptor.ModifyRequestInterceptor
import com.witvpn.ikev2.presentation.utils.interceptor.UnsafeOkHttpClient
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Репортинг подключений в API (connect / heartbeat / disconnect).
 *
 * ВАЖНО: используется ТОЛЬКО для VLESS и AWG.
 * Для IKEv2 клиент больше НИЧЕГО не шлёт — онлайн IKEv2 сервер API считает
 * сам, опрашивая каждый IKEv2-сервер напрямую (node_exporter, ipsec_clients).
 */
object ConnectionTracker {
    @Volatile
    private var client: OkHttpClient? = null

    private fun getClient(context: Context): OkHttpClient {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            val appContext = context.applicationContext
            return UnsafeOkHttpClient.getUnsafeOkHttpClientBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(
                    DynamicBaseUrlInterceptor(
                        currentBaseUrlProvider = { RemoteConfigManager.getApiBaseUrl(appContext) },
                        rotateBaseUrl = { failedHost ->
                            RemoteConfigManager.rotateOrFallbackApiBaseUrl(appContext, failedHost)
                        }
                    )
                )
                .addInterceptor(ModifyRequestInterceptor())
                .build()
                .also { client = it }
        }
    }

    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun reportConnect(context: Context, userId: String, serverIp: String, protocol: String = "ikev2") {
        // ApplicationContext: heartbeat живёт дольше экрана, контекст Fragment
        // в долгом цикле утёк бы до Activity.
        val appContext = context.applicationContext
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
                    .url("${getBaseUrl(appContext)}/user/connection/update")
                    .post(body)
                    .build()
                getClient(appContext).newCall(request).execute().close()
                Timber.d("ConnectionTracker: connect reported for $serverIp")
            } catch (e: Exception) {
                Timber.e(e, "ConnectionTracker: failed to report connect")
            }
        }
        startHeartbeat(userId, serverIp, protocol, appContext)
    }

    fun reportDisconnect(context: Context, userId: String) {
        val appContext = context.applicationContext
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
                    .url("${getBaseUrl(appContext)}/user/connection/update")
                    .post(body)
                    .build()
                getClient(appContext).newCall(request).execute().close()
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
                // Интервал и вкл/выкл читаем на каждом такте: меняются из
                // Firebase Remote Config без обновления приложения.
                delay(RemoteConfigManager.getHeartbeatIntervalSeconds(context) * 1000)
                if (!RemoteConfigManager.isHeartbeatEnabled(context)) break
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
                    getClient(context).newCall(request).execute().close()
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

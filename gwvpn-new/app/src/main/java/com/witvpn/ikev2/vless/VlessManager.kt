package com.witvpn.ikev2.vless

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.witvpn.ikev2.R
import com.witvpn.ikev2.features.splittunnel.SplitTunnelStore
import com.witvpn.ikev2.presentation.utils.RemoteConfigManager
import io.github.vyomtunnel.sdk.VyomVpnManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object VlessManager {
    private const val TAG = "VlessManager"
    // Домен подписки больше НЕ зашит здесь — он берётся из Firebase Remote Config
    // (ключ subscription_url) через RemoteConfigManager. Дефолт (если Firebase
    // недоступен) лежит в res string subscription_url и совпадает со старым
    // «зашитым» адресом. Это позволяет менять домен «на лету» из консоли Firebase
    // при блокировке, без выпуска обновления в Google Play.

    var vlessServers: List<VlessConfig> = emptyList()
    var selectedServer: VlessConfig? = null

    private val client = OkHttpClient()

    fun fetchSubscription(context: Context, callback: (Boolean) -> Unit) {
        // Резолвим актуальный домен подписки ДО ухода в фон и держим только
        // applicationContext, чтобы не удерживать Activity/Fragment в GlobalScope.
        val appContext = context.applicationContext
        val subscriptionUrl = RemoteConfigManager.getSubscriptionUrl(appContext)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Fetching subscription from: $subscriptionUrl")
                val request = Request.Builder().url(subscriptionUrl).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                
                if (response.isSuccessful && body.isNotEmpty()) {
                    // vless:// + hysteria2:// / hy2:// из одной base64-подписки
                    val servers = VlessParser.parseSubscription(body)
                    Log.i(TAG, "Subscription parsed: ${servers.size} nodes " +
                        "(vless=${servers.count { !it.isHysteria2() }}, " +
                        "hy2=${servers.count { it.isHysteria2() }})")
                    withContext(Dispatchers.Main) {
                        vlessServers = servers
                        // если выбранной ноды больше нет в списке — сброс
                        if (selectedServer != null &&
                            servers.none {
                                it.address == selectedServer?.address &&
                                    it.port == selectedServer?.port &&
                                    it.uuid == selectedServer?.uuid &&
                                    it.protocol == selectedServer?.protocol
                            }
                        ) {
                            selectedServer = null
                        }
                        if (selectedServer == null && servers.isNotEmpty()) {
                            selectedServer = servers[0]
                        }
                        callback(servers.isNotEmpty())
                    }
                } else {
                    withContext(Dispatchers.Main) { callback(false) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { callback(false) }
            }
        }
    }

    fun initialize(context: Context) {
        VyomVpnManager.initialize(context)
        VyomVpnManager.setNotificationConfig(
            VyomVpnManager.VyomNotificationConfig(
                title = "GW VPN",
                content = "VPN is active",
                iconResId = R.drawable.ic_stat_name,
                channelName = "GW VPN Service"
            )
        )
    }

    fun startVpn(context: Context) {
        val server = selectedServer
        if (server == null) {
            Log.e(TAG, "No server selected")
            return
        }

        Log.i(TAG, "Starting VPN protocol=${server.displayProtocolLabel()} name=${server.name} ${server.address}:${server.port}")
        // SOCKS 20808 — как в TProxy Vyom; JSON: vless или hysteria(v2)
        val config = VlessParser.toV2RayConfig(server, 20808, 20809)

        // Split Tunneling: hand the user's per-app routing choice to the tunnel
        // SDK. At most one of the two lists is non-empty — see
        // SplitTunnelStore.getEffectiveMode().
        VyomVpnManager.setExcludedApps(context, SplitTunnelStore.getInstalledDisallowedPackages(context))
        VyomVpnManager.setAllowedApps(context, SplitTunnelStore.getInstalledAllowedPackages(context))

        val error = VyomVpnManager.connect(context, config)
        if (error != null) {
            Log.e(TAG, "VPN start error: $error")
        }
    }

    fun startVpnWithPermission(activity: Activity) {
        val server = selectedServer
        if (server == null) {
            Log.e(TAG, "No server selected")
            return
        }

        Log.i(TAG, "Starting VPN (permission) protocol=${server.displayProtocolLabel()} name=${server.name}")
        val config = VlessParser.toV2RayConfig(server, 20808, 20809)

        // Split Tunneling: hand the user's per-app routing choice to the tunnel SDK.
        VyomVpnManager.setExcludedApps(activity, SplitTunnelStore.getInstalledDisallowedPackages(activity))
        VyomVpnManager.setAllowedApps(activity, SplitTunnelStore.getInstalledAllowedPackages(activity))

        VyomVpnManager.connectWithPermission(activity, config)
    }

    fun stopVpn(context: Context) {
        VyomVpnManager.stop(context)
    }

    fun isConnected(): Boolean {
        return VyomVpnManager.currentState == io.github.vyomtunnel.sdk.VyomState.CONNECTED
    }
}

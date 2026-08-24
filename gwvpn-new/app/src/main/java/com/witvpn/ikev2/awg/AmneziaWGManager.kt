package com.witvpn.ikev2.awg

import android.content.Context
import android.util.Base64
import android.util.Log
import com.witvpn.ikev2.domain.model.ServerAwg
import com.witvpn.ikev2.features.splittunnel.SplitTunnelMode
import com.witvpn.ikev2.features.splittunnel.SplitTunnelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

object AmneziaWGManager {
    private const val TAG = "AmneziaWGManager"

    var selectedServer: ServerAwg? = null
    private var backend: GoBackend? = null
    private var isConnected = false
    private var trafficJob: kotlinx.coroutines.Job? = null
    
    private val awgTunnel = object : Tunnel {
        override fun getName(): String = "gwvpn_awg"
        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "AWG Tunnel state changed to: $newState")
            val connected = newState == Tunnel.State.UP
            if (isConnected != connected) {
                isConnected = connected
                notifyListeners(newState)
                
                if (connected) {
                    startTrafficPolling()
                } else {
                    stopTrafficPolling()
                }
            }
        }
    }

    private fun startTrafficPolling() {
        trafficJob?.cancel()
        trafficJob = GlobalScope.launch(Dispatchers.IO) {
            while (isConnected) {
                try {
                    val stats = backend?.getStatistics(awgTunnel)
                    val rx = stats?.totalRx() ?: 0L
                    val tx = stats?.totalTx() ?: 0L
                    listeners.forEach { it.onTrafficUpdate(rx, tx) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get traffic stats", e)
                }
                kotlinx.coroutines.delay(1000L)
            }
        }
    }

    /**
     * Lazily creates the [GoBackend] on first use. Constructing [GoBackend] loads
     * libwg-go.so (Go 1.24 runtime). Doing that on the main thread at app startup
     * can trigger a non-catchable native crash on some devices (e.g. Honor 8X /
     * Honor 30i running EMUI 9/10 on Kirin 710). Deferring the load to the first
     * VPN connect makes the app start reliably everywhere.
     */
    private fun ensureBackend(context: Context): GoBackend? {
        val existing = backend
        if (existing != null) return existing
        return try {
            val created = GoBackend(context.applicationContext)
            backend = created
            created
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create AmneziaWG backend", e)
            null
        }
    }

    private fun stopTrafficPolling() {
        trafficJob?.cancel()
        trafficJob = null
    }

    interface StateListener {
        fun onStateChange(state: Tunnel.State)
        fun onTrafficUpdate(rx: Long, tx: Long)
    }

    private val listeners = mutableListOf<StateListener>()

    fun registerListener(listener: StateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: StateListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners(state: Tunnel.State) {
        listeners.forEach { it.onStateChange(state) }
    }

    fun initialize(context: Context) {
        // Intentionally a no-op. See [ensureBackend] for the reason: creating
        // [GoBackend] loads libwg-go.so which can crash the process natively on
        // some devices when triggered from the main thread at app startup.
        // The backend is created lazily on the first VPN connect instead.
    }

    fun startVpn(context: Context) {
        val server = selectedServer
        if (server == null) {
            Log.e(TAG, "No server selected")
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val b = ensureBackend(context) ?: run {
                    Log.e(TAG, "AmneziaWG backend unavailable on this device")
                    notifyListeners(Tunnel.State.DOWN)
                    return@launch
                }
                // Decode base64 config
                val decodedConfig = Base64.decode(server.config, Base64.DEFAULT)
                // Split Tunneling: route the selected apps around the tunnel.
                val configText = applySplitTunneling(context, String(decodedConfig, Charsets.UTF_8))
                val configStream = ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8))
                val awgConfig = Config.parse(configStream)

                b.setState(awgTunnel, Tunnel.State.UP, awgConfig)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AWG VPN", e)
                notifyListeners(Tunnel.State.DOWN)
            }
        }
    }

    /**
     * Injects the user's Split Tunneling choice into the AWG config as an
     * "ExcludedApplications" (mode "All except selected") or
     * "IncludedApplications" (mode "Only selected") attribute of the
     * [Interface] section.
     *
     * In exclude mode the AWG config parser merges repeated attribute lines,
     * so a server-provided exclusion list (if any) is preserved. In include
     * mode any pre-existing app-list attributes are stripped first:
     * VpnService.Builder forbids mixing allowed and disallowed applications
     * and GoBackend would fail the whole tunnel otherwise.
     *
     * Only currently installed packages are passed through: GoBackend applies
     * the lists with VpnService.Builder.addDisallowedApplication /
     * addAllowedApplication, which throw for unknown package names and would
     * fail the whole tunnel.
     */
    private fun applySplitTunneling(context: Context, configText: String): String {
        return try {
            val (attribute, apps) = when (SplitTunnelStore.getEffectiveMode(context)) {
                SplitTunnelMode.OFF -> return configText
                SplitTunnelMode.EXCEPT_SELECTED ->
                    "ExcludedApplications" to SplitTunnelStore.getInstalledDisallowedPackages(context)
                SplitTunnelMode.ONLY_SELECTED ->
                    "IncludedApplications" to SplitTunnelStore.getInstalledAllowedPackages(context)
            }
            if (apps.isEmpty()) return configText

            val lines = if (attribute == "IncludedApplications") {
                configText.lines().filterNot {
                    val trimmed = it.trim()
                    trimmed.startsWith("ExcludedApplications", ignoreCase = true) ||
                        trimmed.startsWith("IncludedApplications", ignoreCase = true)
                }
            } else {
                configText.lines()
            }

            val interfaceIndex = lines.indexOfFirst {
                it.trim().equals("[Interface]", ignoreCase = true)
            }
            if (interfaceIndex == -1) {
                Log.w(TAG, "No [Interface] section in AWG config, skip split tunneling")
                return configText
            }

            val result = lines.toMutableList()
            result.add(interfaceIndex + 1, "$attribute = " + apps.joinToString(", "))
            Log.i(TAG, "Split tunneling: $attribute with ${apps.size} app(s) for the AWG tunnel")
            result.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply split tunneling to AWG config", e)
            configText
        }
    }

    fun stopVpn(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                backend?.setState(awgTunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop AWG VPN", e)
            }
        }
    }

    fun isConnected(): Boolean {
        return isConnected
    }
    
    fun getTrafficStats(): Pair<Long, Long> {
        return try {
            val stats = backend?.getStatistics(awgTunnel)
            val rx = stats?.totalRx() ?: 0L
            val tx = stats?.totalTx() ?: 0L
            Pair(rx, tx)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }
}

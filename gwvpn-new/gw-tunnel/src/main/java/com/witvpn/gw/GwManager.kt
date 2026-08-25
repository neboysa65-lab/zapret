package com.witvpn.gw

import android.content.Context
import android.content.Intent
import android.util.Log
import com.witvpn.gw.crypto.GwObf
import com.witvpn.gw.crypto.GwVault
import com.witvpn.gw.model.GwServerConfig
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface

object GwManager {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    interface StateListener {
        fun onStateChange(state: State)
        fun onTrafficUpdate(rx: Long, tx: Long)
    }

    private const val TAG = "GwManager"
    private val PREFS: String get() = GwObf.PREFS
    private val KEY_CONFIG: String get() = GwObf.KEY_CONFIG
    private val KEY_RUNNING: String get() = GwObf.KEY_RUNNING

    @Volatile var state: State = State.DISCONNECTED
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile var rxTotal: Long = 0
        private set

    @Volatile var txTotal: Long = 0
        private set

    var selectedServer: GwServerConfig? = null

    /** Вызывается после каждого успешного установления туннеля,
     * включая рестарт сервиса системой (START_STICKY). */
    var onTunnelConnected: ((GwServerConfig) -> Unit)? = null

    internal fun notifyConnected(config: GwServerConfig) {
        try {
            onTunnelConnected?.invoke(config)
        } catch (_: Exception) {
        }
    }

    private val listeners = mutableListOf<StateListener>()

    fun registerListener(listener: StateListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
        listener.onTrafficUpdate(rxTotal, txTotal)
    }

    fun unregisterListener(listener: StateListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    internal fun setState(newState: State, error: String? = null) {
        if (error != null) lastError = error
        if (state == newState) {
            listeners.forEach { it.onStateChange(state) }
            return
        }
        state = newState
        Log.i(TAG, "GW state -> $newState${if (error != null) " ($error)" else ""}")
        if (newState == State.DISCONNECTED) {
            resetTraffic()
            clearLastErrorInternal()
        }
        synchronized(listeners) { listeners.toList() }.forEach { it.onStateChange(state) }
    }

    private fun clearLastErrorInternal() {
        // lastError intentionally kept for UI display until next connect attempt
    }

    internal fun updateTraffic(up: Long, down: Long) {
        txTotal = up
        rxTotal = down
        synchronized(listeners) { listeners.toList() }.forEach { it.onTrafficUpdate(down, up) }
    }

    fun resetTraffic() {
        updateTraffic(0, 0)
    }

    fun isConnected(): Boolean = state == State.CONNECTED

    fun saveConfig(context: Context, config: GwServerConfig?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (config == null) {
            prefs.edit().remove(KEY_CONFIG).putBoolean(KEY_RUNNING, false).apply()
        } else {
            // Конфиг ноды (ip/port/ssh_password/payload) храним только в sealed-виде:
            // ключ — в Android Keystore, plaintext не попадает на диск.
            prefs.edit()
                .putString(KEY_CONFIG, GwVault.seal(config.toJson()))
                .putBoolean(KEY_RUNNING, true)
                .apply()
        }
    }

    fun restoreConfig(context: Context): GwServerConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_CONFIG, null) ?: return null
        val json = GwVault.unseal(stored) ?: return null
        val config = try {
            GwServerConfig.fromJson(json)
        } catch (_: Exception) {
            return null
        }
        if (!GwVault.isSealed(stored)) {
            // legacy-plaintext: мигрируем в sealed-вид
            saveConfig(context, config)
        }
        return config
    }

    fun wasRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

    fun clearRunning(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, false).apply()
    }

    fun localAddresses(context: Context): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress) continue
                    if (addr is java.net.Inet4Address) result.add(addr)
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun tunYamlFile(context: Context, socksPort: Int): File {
        val file = File(context.filesDir, "gw_tun.yaml")
        val hevLog = File(context.filesDir, "gw_hev.log")
        // Schema per hev-socks5-tunnel: tunnel.mtu, socks5.*, misc.*. Unknown
        // sections (tcp/udp/dns) are silently ignored by its parser.
        file.writeText(
            """
            tunnel:
              mtu: 8500
            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'udp'
            misc:
              log-level: debug
              log-file: '${hevLog.absolutePath}'
            """.trimIndent()
        )
        return file
    }

    fun startVpn(
        context: Context,
        config: GwServerConfig,
        excludedApps: List<String> = emptyList(),
        allowedApps: List<String> = emptyList(),
    ) {
        val intent = Intent(context, com.witvpn.gw.GwVpnService::class.java).apply {
            action = com.witvpn.gw.GwVpnService.ACTION_START
            putExtra(com.witvpn.gw.GwVpnService.EXTRA_CONFIG, config.toJson())
            putStringArrayListExtra(
                com.witvpn.gw.GwVpnService.EXTRA_EXCLUDED_APPS,
                ArrayList(excludedApps)
            )
            putStringArrayListExtra(
                com.witvpn.gw.GwVpnService.EXTRA_ALLOWED_APPS,
                ArrayList(allowedApps)
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopVpn(context: Context) {
        val intent = Intent(context, com.witvpn.gw.GwVpnService::class.java).apply {
            action = com.witvpn.gw.GwVpnService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun configFromIntentExtra(json: String): GwServerConfig? =
        try {
            GwServerConfig.fromJson(json)
        } catch (_: Exception) {
            null
        }
}

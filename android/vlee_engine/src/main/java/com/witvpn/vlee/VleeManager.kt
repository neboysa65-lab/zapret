package com.witvpn.vlee

import android.content.Context
import android.content.Intent
import android.util.Log
import com.witvpn.vlee.crypto.VleeObf
import com.witvpn.vlee.crypto.VleeVault
import com.witvpn.vlee.model.VleeServerConfig
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface

object VleeManager {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    interface StateListener {
        fun onStateChange(state: State)
        fun onTrafficUpdate(rx: Long, tx: Long)

        /**
         * Туннель НЕ разорван, но SSH под ним пересобирается (обрыв Wi-Fi,
         * переезд на LTE, простой в фоне). Отдельный сигнал, а не новое значение
         * [State]: состояние обязано остаться CONNECTED — VPN активен, маршрут
         * на месте, трафик наружу не утекает, и кнопка «отключить» должна
         * работать в любой момент.
         */
        fun onReconnecting(active: Boolean) {}
    }

    private const val TAG = "VleeManager"
    private val PREFS: String get() = VleeObf.PREFS
    private val KEY_CONFIG: String get() = VleeObf.KEY_CONFIG
    private val KEY_RUNNING: String get() = VleeObf.KEY_RUNNING

    // Списки split-tunnel нужны сервису после рестарта системой, когда intent
    // приходит пустой. Ключи производные от KEY_CONFIG — новых литералов в dex
    // не добавляем, значения так же уходят под Keystore.
    private val KEY_EXCLUDED_APPS: String get() = VleeObf.KEY_CONFIG + "_x"
    private val KEY_ALLOWED_APPS: String get() = VleeObf.KEY_CONFIG + "_a"

    @Volatile var state: State = State.DISCONNECTED
        private set

    /** Идёт автоматическое восстановление связи под живым туннелем. */
    @Volatile var isReconnecting: Boolean = false
        private set

    /**
     * Пользователь нажал «стоп», но туннель ещё фактически разбирается:
     * state останется CONNECTING/CONNECTED до завершения teardown.
     * UI сверяется с этим флагом, чтобы тап «подключиться» во время разборки
     * сразу запускал новый коннект (epoch-механика сервиса это безопасно
     * разруливает), а не упирался в повторную остановку.
     */
    @Volatile var isStopping: Boolean = false
        private set

    internal fun markStopping(stopping: Boolean) {
        isStopping = stopping
    }

    @Volatile var lastError: String? = null
        private set

    @Volatile var rxTotal: Long = 0
        private set

    @Volatile var txTotal: Long = 0
        private set

    var selectedServer: VleeServerConfig? = null

    /**
     * Конфиг, под который идёт или уже поднята текущая сессия. Ставится
     * сервисом в момент старта и не меняется UI'ом. Сверка «пользователь
     * выбрал другой сервер?» выполняется именно с ним: [selectedServer] —
     * черновик UI, он перезаписывается выбранным узлом ещё до сверки, и
     * проверка по нему всегда говорила «не изменился».
     */
    @Volatile var activeServer: VleeServerConfig? = null

    /** Вызывается после каждого успешного установления туннеля,
     * включая рестарт сервиса системой (START_STICKY). */
    var onTunnelConnected: ((VleeServerConfig) -> Unit)? = null

    internal fun notifyConnected(config: VleeServerConfig) {
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
        // Экран мог открыться посреди переподключения — сообщаем текущее
        // состояние сразу, иначе UI покажет обычное «подключено».
        if (isReconnecting) runCatching { listener.onReconnecting(true) }
    }

    fun unregisterListener(listener: StateListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    internal fun setReconnecting(active: Boolean) {
        if (isReconnecting == active) return
        isReconnecting = active
        Log.i(TAG, "Vlee reconnecting -> $active")
        synchronized(listeners) { listeners.toList() }.forEach {
            runCatching { it.onReconnecting(active) }
        }
    }

    internal fun setState(newState: State, error: String? = null) {
        if (error != null) lastError = error
        if (state == newState) {
            notifyState(newState)
            return
        }
        state = newState
        Log.i(TAG, "Vlee state -> $newState${if (error != null) " ($error)" else ""}")
        if (newState == State.DISCONNECTED) {
            setReconnecting(false)
            resetTraffic()
            clearLastErrorInternal()
        }
        notifyState(state)
    }

    /**
     * Дубли состояния — рутина (DISCONNECTED приходит и из отменённого
     * воркера, и из stop-потока), а register/unregister крутятся на main
     * thread. «Сырой» forEach здесь ловил ConcurrentModificationException,
     * а необработанное исключение в сервисном потоке убивало процесс.
     */
    private fun notifyState(state: State) {
        synchronized(listeners) { listeners.toList() }.forEach {
            runCatching { it.onStateChange(state) }
        }
    }

    private fun clearLastErrorInternal() {
        // lastError intentionally kept for UI display until next connect attempt
    }

    internal fun updateTraffic(up: Long, down: Long) {
        txTotal = up
        rxTotal = down
        synchronized(listeners) { listeners.toList() }.forEach {
            runCatching { it.onTrafficUpdate(down, up) }
        }
    }

    fun resetTraffic() {
        updateTraffic(0, 0)
    }

    fun isConnected(): Boolean = state == State.CONNECTED

    fun saveConfig(context: Context, config: VleeServerConfig?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (config == null) {
            prefs.edit()
                .remove(KEY_CONFIG)
                .remove(KEY_EXCLUDED_APPS)
                .remove(KEY_ALLOWED_APPS)
                .putBoolean(KEY_RUNNING, false)
                .apply()
        } else {
            // Конфиг ноды (ip/port/ssh_password/payload) храним только в sealed-виде:
            // ключ — в Android Keystore, plaintext не попадает на диск.
            prefs.edit()
                .putString(KEY_CONFIG, VleeVault.seal(config.toJson()))
                .putBoolean(KEY_RUNNING, true)
                .apply()
        }
    }

    /**
     * Сохраняет списки split-tunnel рядом с конфигом. Нужны при рестарте сервиса
     * системой: тогда intent приходит без extras, и раньше туннель поднимался
     * вообще без фильтров — исключённые приложения молча заворачивались в VPN.
     */
    fun saveAppFilters(context: Context, excludedApps: List<String>, allowedApps: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXCLUDED_APPS, VleeVault.seal(excludedApps.joinToString("\n")))
            .putString(KEY_ALLOWED_APPS, VleeVault.seal(allowedApps.joinToString("\n")))
            .apply()
    }

    /** @return excluded to allowed. */
    fun restoreAppFilters(context: Context): Pair<List<String>, List<String>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun read(key: String): List<String> =
            VleeVault.unseal(prefs.getString(key, null))
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        return read(KEY_EXCLUDED_APPS) to read(KEY_ALLOWED_APPS)
    }

    fun restoreConfig(context: Context): VleeServerConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_CONFIG, null) ?: return null
        val json = VleeVault.unseal(stored) ?: return null
        val config = try {
            VleeServerConfig.fromJson(json)
        } catch (_: Exception) {
            return null
        }
        if (!VleeVault.isSealed(stored)) {
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
        val file = File(context.filesDir, "vlee_tun.yaml")
        val hevLog = File(context.filesDir, "vlee_hev.log")
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
        config: VleeServerConfig,
        excludedApps: List<String> = emptyList(),
        allowedApps: List<String> = emptyList(),
    ) {
        val intent = Intent(context, com.witvpn.vlee.VleeVpnService::class.java).apply {
            action = com.witvpn.vlee.VleeVpnService.ACTION_START
            putExtra(com.witvpn.vlee.VleeVpnService.EXTRA_CONFIG, config.toJson())
            putStringArrayListExtra(
                com.witvpn.vlee.VleeVpnService.EXTRA_EXCLUDED_APPS,
                ArrayList(excludedApps)
            )
            putStringArrayListExtra(
                com.witvpn.vlee.VleeVpnService.EXTRA_ALLOWED_APPS,
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
        // Прямой вызов работает и из фона: startService(ACTION_STOP) при
        // свёрнутом приложении кидает IllegalStateException, а таймаут-раннейблы
        // срабатывают как раз когда приложение свёрнуто. Fallback на интент
        // нужен, когда сервиса в процессе уже нет (сбросить KEY_RUNNING,
        // иначе рестарт системы поднимет туннель заново).
        val service = com.witvpn.vlee.VleeVpnService.instance
        if (service != null) {
            service.stopEverything()
            return
        }
        val intent = Intent(context, com.witvpn.vlee.VleeVpnService::class.java).apply {
            action = com.witvpn.vlee.VleeVpnService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
            clearRunning(context)
        }
    }

    fun configFromIntentExtra(json: String): VleeServerConfig? =
        try {
            VleeServerConfig.fromJson(json)
        } catch (_: Exception) {
            null
        }
}

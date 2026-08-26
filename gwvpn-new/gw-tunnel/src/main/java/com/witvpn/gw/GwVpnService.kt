package com.witvpn.gw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.witvpn.gw.model.GwServerConfig
import com.witvpn.gw.socks.GwSocksServer
import com.witvpn.gw.ssh.GwSshTransport
import hev.htproxy.TProxyService
import java.util.Timer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.timerTask
import kotlin.concurrent.withLock

class GwVpnService : TProxyService() {

    companion object {
        const val ACTION_START = "com.witvpn.gw.START"
        const val ACTION_STOP = "com.witvpn.gw.STOP"
        const val EXTRA_CONFIG = "EXTRA_CONFIG_JSON"
        const val EXTRA_EXCLUDED_APPS = "EXTRA_EXCLUDED_APPS"
        const val EXTRA_ALLOWED_APPS = "EXTRA_ALLOWED_APPS"

        private const val TAG = "GwVpnService"
        private const val CHANNEL_ID = "gw_vpn_service"
        private const val NOTIFICATION_ID = 4711
        private const val MTU = 8500
        private const val TUN_ADDRESS = "198.18.0.1"
        private const val MAX_CONNECT_ATTEMPTS = 4
        private const val CONNECT_RETRY_DELAY_MS = 4000L

        /**
         * Рестарт системой (START_STICKY) — не то же самое, что нажатие
         * «подключить»: пользователь ничего не нажимал, показать ему ошибку
         * некому, а сети в первые секунды после перезапуска может ещё не быть.
         * Поэтому попыток больше, а сдаваться нельзя — дальше подхватит supervisor.
         */
        private const val RESTORE_CONNECT_ATTEMPTS = 10

        /** Сколько ждём появления сети перед попыткой подключения. */
        private const val NETWORK_WAIT_MS = 30_000L

        /** Как часто supervisor просыпается сам, без внешнего пинка. */
        private const val SUPERVISOR_POLL_MS = 5_000L
        private const val RECONNECT_BACKOFF_MIN_MS = 2_000L
        private const val RECONNECT_BACKOFF_MAX_MS = 30_000L

        private const val NOTIF_TEXT_CONNECTED = "Connected via GW"
        private const val NOTIF_TEXT_RECONNECTING = "Reconnecting…"
    }

    private var tun: ParcelFileDescriptor? = null
    private var socksServer: GwSocksServer? = null

    /** Меняется под нагрузкой (переподключение), читается воркерами SOCKS. */
    @Volatile private var transport: GwSshTransport? = null
    private var statsTimer: Timer? = null
    private val lifecycleLock = ReentrantLock()

    @Volatile private var stopRequested = false
    private var startThread: Thread? = null

    /**
     * Туннель поднят и обязан жить, пока пользователь сам его не выключит.
     * Пока флаг взведён, supervisor будет бесконечно пересобирать SSH при любом
     * сбое, НЕ разрушая TUN: маршрут остаётся на VPN, трафик в открытую сеть не
     * утекает, а приложения просто получают отказы до восстановления канала.
     */
    @Volatile private var tunnelUp = false
    private var supervisorThread: Thread? = null
    private val supervisorGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    // Монитор для пробуждения supervisor'а: смена сети или отказ SOCKS должны
    // подниматься мгновенно, а не ждать очередного опроса.
    private val wakeLock = ReentrantLock()
    private val wakeCondition = wakeLock.newCondition()

    private val availableNetworks = LinkedHashSet<Network>()
    @Volatile private var networkCallbackActive = false
    @Volatile private var boundNetwork: Network? = null
    /** Сеть пропадала целиком — значит текущий SSH-сокет уже не восстановить. */
    @Volatile private var networkWasLost = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        when (intent?.action) {
            ACTION_START -> {
                val config = GwManager.configFromIntentExtra(
                    intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                )
                if (config == null || config.ipAddress.isBlank()) {
                    Log.e(TAG, "START without valid config")
                    stopEverything()
                    return START_NOT_STICKY
                }
                val excluded = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS) ?: emptyList()
                val allowed = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS) ?: emptyList()
                GwManager.selectedServer = config
                GwManager.saveConfig(this, config)
                // Списки split-tunnel сохраняем вместе с конфигом: при рестарте
                // сервиса системой intent приходит пустой, и раньше туннель
                // поднимался БЕЗ них — то есть исключённые приложения молча
                // заворачивались в VPN, а режим «только выбранные» терялся.
                GwManager.saveAppFilters(this, excluded, allowed)
                startTunnel(config, excluded, allowed, restarted = false)
            }
            ACTION_STOP -> stopEverything()
            else -> {
                if (GwManager.wasRunning(this)) {
                    GwManager.restoreConfig(this)?.let { restored ->
                        GwManager.selectedServer = restored
                        val (excluded, allowed) = GwManager.restoreAppFilters(this)
                        startTunnel(restored, excluded, allowed, restarted = true)
                        return START_STICKY
                    }
                }
                stopEverything()
            }
        }
        return START_STICKY
    }

    private fun startTunnel(
        config: GwServerConfig,
        excludedApps: List<String>?,
        allowedApps: List<String>?,
        restarted: Boolean,
    ) {
        GwManager.setState(GwManager.State.CONNECTING)
        val worker = thread(name = "gw-tunnel-start") {
            stopRequested = false
            lifecycleLock.withLock {
                try {
                    teardownTunnel()

                    // Fail fast and loudly: without libhev-socks5-tunnel.so the
                    // tun device would be established and then nothing would
                    // forward its packets, which looks exactly like "connected
                    // but no internet". Check before spending four SSH retries.
                    if (!TProxyService.isTProxyLoaded) {
                        throw IllegalStateException(
                            "tun2socks native library unavailable: " +
                                (TProxyService.tproxyLoadError ?: "libhev-socks5-tunnel.so not loaded")
                        )
                    }

                    val maxAttempts = if (restarted) RESTORE_CONNECT_ATTEMPTS else MAX_CONNECT_ATTEMPTS
                    var ssh: GwSshTransport? = null
                    var lastError: Exception? = null
                    for (attempt in 1..maxAttempts) {
                        if (stopRequested) throw java.io.IOException("cancelled")
                        // Без сети попытка гарантированно провалится и просто
                        // сожжёт одну из попыток — типичная картина при рестарте
                        // после перезагрузки телефона или при возврате из
                        // авиарежима.
                        awaitNetwork(NETWORK_WAIT_MS)
                        try {
                            val candidate = GwSshTransport(config)
                            candidate.connect { socket -> runCatching { protect(socket) } }
                            ssh = candidate
                            transport = candidate
                            break
                        } catch (e: Exception) {
                            lastError = e
                            Log.w(TAG, "GW connect attempt $attempt/$maxAttempts failed: ${e.message}")
                            runCatching { transport?.close() }
                            transport = null
                            if (stopRequested) throw java.io.IOException("cancelled")
                            if (attempt < maxAttempts) {
                                Thread.sleep(
                                    (CONNECT_RETRY_DELAY_MS * attempt)
                                        .coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
                                )
                            }
                        }
                    }

                    val ready = ssh ?: throw lastError ?: IllegalStateException("connect failed")

                    // Транспорт отдаётся провайдером, а не ссылкой: при обрыве
                    // сети SSH пересобирается, а SOCKS-порт, TUN и tun2socks
                    // остаются на месте.
                    val socks = GwSocksServer(
                        transportProvider = { transport },
                        onTransportFailure = { onTransportFailure() },
                    )
                    socks.start()
                    socksServer = socks

                    val pfd = establishTun(excludedApps ?: emptyList(), allowedApps ?: emptyList())
                        ?: throw IllegalStateException("TUN establish failed")
                    tun = pfd

                    val yaml = GwManager.tunYamlFile(this, socks.port)
                    TProxyStartService(yaml.absolutePath, pfd.fd)

                    startStatsTimer()
                    tunnelUp = true
                    applyUnderlyingNetworks()
                    startSupervisor()
                    GwManager.setState(GwManager.State.CONNECTED)
                    GwManager.selectedServer?.let { GwManager.notifyConnected(it) }
                } catch (t: Throwable) {
                    // Throwable, not Exception: a missing/mismatched .so raises
                    // UnsatisfiedLinkError, which is an Error. Catching only
                    // Exception let it kill this thread silently — no log line
                    // and no state transition, so the UI span forever on
                    // CONNECTING. That was the actual GW "no logs" bug.
                    Log.e(TAG, "GW tunnel failed", t)
                    teardownTunnel()
                    val userError = if (stopRequested) null else (t.message ?: "connection error")
                    GwManager.setState(GwManager.State.DISCONNECTED, userError)
                }
            }
        }
        startThread = worker
    }

    // ---------------------------------------------------------------------
    // Автовосстановление
    // ---------------------------------------------------------------------

    /** Пинок supervisor'у: проснуться сейчас, а не через SUPERVISOR_POLL_MS. */
    private fun kickSupervisor() {
        wakeLock.withLock { wakeCondition.signalAll() }
    }

    private fun sleepUntilKick(millis: Long) {
        wakeLock.withLock {
            runCatching { wakeCondition.await(millis, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * Вызывается из SOCKS, когда открытие каналов стабильно падает: транспорт
     * мёртв, хотя TCP об этом ещё не сообщил.
     */
    private fun onTransportFailure() {
        if (!tunnelUp || stopRequested) return
        kickSupervisor()
    }

    /**
     * Единственный сторож туннеля. Пока [tunnelUp], проверяет живость SSH и
     * пересобирает его с экспоненциальной задержкой — без ограничения по числу
     * попыток. Именно этого не хватало: keepalive/канальные таймауты честно
     * обнаруживали обрыв, но никто не смотрел на результат, состояние оставалось
     * CONNECTED, UI показывал «подключено», а трафик не шёл до ручного
     * переподключения.
     */
    private fun startSupervisor() {
        // Поколение вместо проверки isAlive: старый сторож может ещё доживать
        // свой sleep, и правило «раз живой — второй не нужен» оставило бы туннель
        // вообще без присмотра (старый выходит, новый не запустился).
        val generation = supervisorGeneration.incrementAndGet()
        supervisorThread = thread(name = "gw-supervisor", isDaemon = true) {
            val active = { !stopRequested && tunnelUp && supervisorGeneration.get() == generation }
            var backoff = RECONNECT_BACKOFF_MIN_MS
            Log.i(TAG, "supervisor started (gen=$generation)")
            while (active()) {
                sleepUntilKick(SUPERVISOR_POLL_MS)
                if (!active()) break

                if (transport?.isConnected == true) {
                    backoff = RECONNECT_BACKOFF_MIN_MS
                    if (GwManager.isReconnecting) {
                        GwManager.setReconnecting(false)
                        updateNotificationText(NOTIF_TEXT_CONNECTED)
                    }
                    continue
                }

                GwManager.setReconnecting(true)
                updateNotificationText(NOTIF_TEXT_RECONNECTING)

                if (!hasUsableNetwork()) {
                    // Сети нет вообще — переподключаться некуда. Ждём callback'а
                    // о появлении сети: он разбудит нас мгновенно.
                    Log.i(TAG, "нет доступной сети, ждём восстановления")
                    continue
                }

                Log.i(TAG, "транспорт мёртв — пересобираем SSH")
                if (reconnectTransport()) {
                    backoff = RECONNECT_BACKOFF_MIN_MS
                    GwManager.setReconnecting(false)
                    updateNotificationText(NOTIF_TEXT_CONNECTED)
                    Log.i(TAG, "туннель восстановлен")
                } else {
                    if (!active()) break
                    Log.w(TAG, "переподключение не удалось, следующая попытка через ${backoff}мс")
                    sleepUntilKick(backoff)
                    backoff = (backoff * 2).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
                }
            }
            Log.i(TAG, "supervisor stopped (gen=$generation)")
        }
    }

    /**
     * Пересобирает SSH под работающим TUN. TUN и tun2socks не трогаем: их
     * пересоздание сменило бы SOCKS-порт и оборвало бы туннель целиком, а
     * пользователь всё это время должен видеть активный VPN.
     */
    private fun reconnectTransport(): Boolean {
        val config = GwManager.selectedServer ?: GwManager.restoreConfig(this) ?: return false
        // tryLock: если параллельно идёт start/stop — не мешаем и не блокируем
        // остановку по кнопке (connect может длиться десятки секунд).
        if (!lifecycleLock.tryLock()) return false
        try {
            if (stopRequested || !tunnelUp) return false
            runCatching { transport?.close() }
            transport = null
            val candidate = GwSshTransport(config)
            return try {
                candidate.connect { socket -> runCatching { protect(socket) } }
                if (stopRequested || !tunnelUp) {
                    runCatching { candidate.close() }
                    false
                } else {
                    transport = candidate
                    socksServer?.onTransportReplaced()
                    GwManager.selectedServer?.let { GwManager.notifyConnected(it) }
                    true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "reconnect failed: ${t.message}")
                runCatching { candidate.close() }
                false
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    // ---------------------------------------------------------------------
    // Слежение за сетью
    // ---------------------------------------------------------------------

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // NOT_VPN обязателен: иначе наш собственный туннель попадёт в список
            // «доступных сетей» и мы будем считать его путём наружу.
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(availableNetworks) { availableNetworks.add(network) }
                Log.i(TAG, "сеть доступна: $network")
                onNetworkSetChanged()
            }

            override fun onLost(network: Network) {
                synchronized(availableNetworks) { availableNetworks.remove(network) }
                Log.i(TAG, "сеть потеряна: $network")
                onNetworkSetChanged()
            }
        }
        val ok = runCatching { cm.registerNetworkCallback(request, callback) }.isSuccess
        if (ok) {
            networkCallback = callback
            networkCallbackActive = true
        } else {
            Log.w(TAG, "registerNetworkCallback failed — supervisor будет работать без сигналов о сети")
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        networkCallbackActive = false
        synchronized(availableNetworks) { availableNetworks.clear() }
    }

    /**
     * Есть ли путь наружу. Если callback зарегистрировать не удалось, считаем
     * что сеть есть: иначе supervisor замолчал бы навсегда.
     */
    private fun hasUsableNetwork(): Boolean =
        !networkCallbackActive || synchronized(availableNetworks) { availableNetworks.isNotEmpty() }

    private fun awaitNetwork(timeoutMs: Long) {
        if (hasUsableNetwork()) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!stopRequested && !hasUsableNetwork()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            sleepUntilKick(left.coerceAtMost(1_000L))
        }
    }

    /** Текущая физическая сеть под VPN (наш процесс вне туннеля, см. establishTun). */
    private fun currentUnderlying(): Network? {
        val snapshot = synchronized(availableNetworks) { availableNetworks.toList() }
        if (snapshot.isEmpty()) return null
        val active = runCatching {
            getSystemService(ConnectivityManager::class.java)?.activeNetwork
        }.getOrNull()
        return if (active != null && snapshot.contains(active)) active else snapshot.last()
    }

    private fun applyUnderlyingNetworks() {
        val chosen = currentUnderlying()
        // Без этого система считает VPN висящим на дефолтной сети «навсегда»:
        // после Wi-Fi → LTE туннель остаётся привязанным к исчезнувшему
        // интерфейсу, и Android не сообщает приложениям о смене связности.
        runCatching { setUnderlyingNetworks(chosen?.let { arrayOf(it) }) }
        boundNetwork = chosen
        networkWasLost = false
    }

    private fun onNetworkSetChanged() {
        val previous = boundNetwork
        val chosen = currentUnderlying()
        runCatching { setUnderlyingNetworks(chosen?.let { arrayOf(it) }) }
        boundNetwork = chosen
        if (!tunnelUp || stopRequested) return

        if (chosen == null) {
            // Сети нет совсем (Wi-Fi выключили, лифт, авиарежим). Рвать транспорт
            // прямо сейчас смысла нет — переподключаться некуда, — но запомнить
            // факт обрыва обязательно: сокет уже мёртв, и когда сеть вернётся,
            // ждать минуту keepalive не придётся.
            if (previous != null) networkWasLost = true
            Log.i(TAG, "сети нет — туннель ждёт восстановления")
            kickSupervisor()
            return
        }

        // previous == null без networkWasLost — это ещё не смена сети, а первый
        // callback после старта: туннель мог подняться раньше, чем пришло
        // onAvailable. Рвать в этот момент свежий рабочий SSH нельзя.
        val handover = previous != null && previous != chosen
        if (handover || networkWasLost) {
            // Wi-Fi отвалился и телефон уехал на LTE (или наоборот, или Wi-Fi
            // вернулся). Старый SSH-сокет висит на исчезнувшем интерфейсе и
            // никогда ничего не доставит, но и ошибки не даст — до конца
            // TCP-ретрансмиссий это минуты. Не ждём keepalive: помечаем мёртвым
            // сразу и переподключаемся.
            networkWasLost = false
            Log.i(TAG, "смена сети $previous -> $chosen: транспорт помечен мёртвым")
            transport?.markDead()
        }
        kickSupervisor()
    }

    private fun establishTun(excludedApps: List<String>, allowedApps: List<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("GW VPN")
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .allowFamily(android.system.OsConstants.AF_INET)

        val effectiveAllowed = allowedApps.filter { it != packageName }
        if (effectiveAllowed.isNotEmpty()) {
            for (pkg in effectiveAllowed) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: Exception) {
                }
            }
        } else {
            builder.addDisallowedApplication(packageName)
            for (pkg in excludedApps) {
                if (pkg == packageName) continue
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: Exception) {
                }
            }
        }
        return builder.establish()
    }

    private fun stopEverything() {
        stopRequested = true
        // tunnelUp снимаем ДО пинка: supervisor должен увидеть «уже не нужно»
        // и выйти, а не начать очередное переподключение.
        tunnelUp = false
        kickSupervisor()
        runCatching { transport?.close() }
        runCatching { startThread?.interrupt() }
        thread(name = "gw-tunnel-stop") {
            lifecycleLock.withLock {
                GwManager.clearRunning(this@GwVpnService)
                teardownTunnel()
                GwManager.setState(GwManager.State.DISCONNECTED)
                stopSelf()
            }
        }
    }

    private fun teardownTunnel() {
        tunnelUp = false
        kickSupervisor()
        GwManager.setReconnecting(false)
        stopStatsTimer()
        runCatching { TProxyStopService() }
        Thread.sleep(200)
        runCatching { socksServer?.stop() }
        runCatching { transport?.close() }
        socksServer = null
        transport = null
        runCatching { tun?.close() }
        tun = null
        GwManager.resetTraffic()
    }

    private fun startStatsTimer() {
        stopStatsTimer()
        statsTimer = Timer("gw-stats", true)
        statsTimer?.scheduleAtFixedRate(timerTask {
            if (!lifecycleLock.tryLock()) return@timerTask
            try {
                val stats = TProxyGetStats()
                if (stats.size >= 4) {
                    GwManager.updateTraffic(up = stats[1], down = stats[3])
                }
            } catch (_: Throwable) {
            } finally {
                lifecycleLock.unlock()
            }
        }, 1000L, 1000L)
    }

    private fun stopStatsTimer() {
        statsTimer?.cancel()
        statsTimer = null
    }

    private fun startForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "GW VPN Service", NotificationManager.IMPORTANCE_LOW
                )
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
            startForeground(NOTIFICATION_ID, buildNotification(NOTIF_TEXT_CONNECTED))
        } catch (e: Exception) {
            Log.e(TAG, "notification failed", e)
        }
    }

    /** Обновляет текст уже показанного уведомления (переподключение/восстановление). */
    private fun updateNotificationText(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = openIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return notificationBuilder
            .setContentTitle("GW VPN")
            .setContentText(text)
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        // Сначала гасим сторожа и слежку за сетью: иначе они будут пытаться
        // переподключаться уже разрушаемый туннель.
        stopRequested = true
        tunnelUp = false
        supervisorGeneration.incrementAndGet()
        kickSupervisor()
        unregisterNetworkCallback()
        lifecycleLock.withLock { runCatching { teardownTunnel() } }
        super.onDestroy()
    }
}

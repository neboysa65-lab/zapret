package com.witvpn.vlee

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
import com.witvpn.vlee.R
import com.witvpn.vlee.model.VleeServerConfig
import com.witvpn.vlee.socks.VleeSocksServer
import com.witvpn.vlee.ssh.VleeSshTransport
import hev.htproxy.TProxyService
import java.util.Timer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.timerTask
import kotlin.concurrent.withLock

class VleeVpnService : TProxyService() {

    companion object {
        const val ACTION_START = "com.witvpn.vlee.START"
        const val ACTION_STOP = "com.witvpn.vlee.STOP"
        const val EXTRA_CONFIG = "EXTRA_CONFIG_JSON"
        const val EXTRA_EXCLUDED_APPS = "EXTRA_EXCLUDED_APPS"
        const val EXTRA_ALLOWED_APPS = "EXTRA_ALLOWED_APPS"

        private const val TAG = "VleeVpnService"
        private const val CHANNEL_ID = "vlee_vpn_service"
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


        /**
         * Сколько опросов подряд транспорт должен выглядеть мёртвым, прежде чем
         * supervisor объявит «реконнект». Один промах — не приговор: просевший
         * keepalive или мигнувший сетевой callback не должны рвать туннель.
         */
        private const val DEAD_POLLS = 2

        /** Сколько новая сеть должна продержаться, чтобы переезд считался настоящим. */
        private const val HANDOVER_STABLE_MS = 8_000L

        /** Живой экземпляр: стоп без интентов, которые из фона киать нельзя. */
        @Volatile internal var instance: VleeVpnService? = null
    }

    private var tun: ParcelFileDescriptor? = null
    private var socksServer: VleeSocksServer? = null

    /** Меняется под нагрузкой (переподключение), читается воркерами SOCKS. */
    @Volatile private var transport: VleeSshTransport? = null
    private var statsTimer: Timer? = null
    private val lifecycleLock = ReentrantLock()

    @Volatile private var stopRequested = false
    private var startThread: Thread? = null

    /**
     * startId последнего onStartCommand. Остановка делает stopSelf(этого id):
     * если после запроса на остановку прилетел новый START (его startId новее),
     * система игнорирует наш stopSelf и сервис остаётся жить под новым
     * коннектом. Без этого no-arg stopSelf() уничтожал сервис ВМЕСТЕ с уже
     * поставленным в очередь новым START — туннель не поднимался вовсе, а
     * состояние застревало в CONNECTING.
     */
    @Volatile private var lastStartId = 0

    /** Поднят ли нативный tun2socks: stop без старта не вызываем никогда. */
    @Volatile private var tproxyStarted = false

    /**
     * Эпоха действий: каждый START/STOP увеличивает счётчик, и только воркер
     * текущей эпохи имеет право трогать состояние и TProxy. Без этого два
     * быстрых START плодили два воркера (двойной establish()/TProxyStartService —
     * нативный краш), а STOP, пришедший до или во время коннекта, позже
     * разрушал уже НОВЫЙ туннель и гасил свежезапущенный сервис.
     */
    private val actionEpoch = java.util.concurrent.atomic.AtomicLong(0)

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

    /** Сеть, на которой поднимался текущий транспорт (для детекции переезда). */
    @Volatile private var transportNetwork: Network? = null
    /** Сокет точно мёртв (сеть пропала/сменилась подтверждённо) — без гистерезиса. */
    @Volatile private var transportCertainlyDead = false
    /** Кандидат на переезд: ждём, устоится ли новая сеть, прежде чем рвать SSH. */
    @Volatile private var handoverCandidate: Network? = null
    @Volatile private var handoverCandidateSince = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        startForegroundNotification()
        when (intent?.action) {
            ACTION_START -> {
                val config = VleeManager.configFromIntentExtra(
                    intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                )
                if (config == null || config.ipAddress.isBlank()) {
                    Log.e(TAG, "START without valid config")
                    stopEverything()
                    return START_NOT_STICKY
                }
                val excluded = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS) ?: emptyList()
                val allowed = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPS) ?: emptyList()
                VleeManager.selectedServer = config
                VleeManager.activeServer = config
                VleeManager.saveConfig(this, config)
                // Списки split-tunnel сохраняем вместе с конфигом: при рестарте
                // сервиса системой intent приходит пустой, и раньше туннель
                // поднимался БЕЗ них — то есть исключённые приложения молча
                // заворачивались в VPN, а режим «только выбранные» терялся.
                VleeManager.saveAppFilters(this, excluded, allowed)
                startTunnel(config, excluded, allowed, restarted = false)
            }
            ACTION_STOP -> stopEverything()
            else -> {
                if (VleeManager.wasRunning(this)) {
                    VleeManager.restoreConfig(this)?.let { restored ->
                        VleeManager.selectedServer = restored
                        VleeManager.activeServer = restored
                        val (excluded, allowed) = VleeManager.restoreAppFilters(this)
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
        config: VleeServerConfig,
        excludedApps: List<String>?,
        allowedApps: List<String>?,
        restarted: Boolean,
    ) {
        VleeManager.setState(VleeManager.State.CONNECTING)
        val myEpoch = actionEpoch.incrementAndGet()
        val worker = thread(name = "vlee-tunnel-start") {
            lifecycleLock.withLock {
                try {
                    // Пока ждали лок, мог прийти новый START/STOP — тогда этот
                    // воркер уже не актуален и ничего не делает.
                    if (actionEpoch.get() != myEpoch) return@withLock
                    stopRequested = false
                    VleeManager.markStopping(false)
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
                    var ssh: VleeSshTransport? = null
                    var lastError: Exception? = null
                    for (attempt in 1..maxAttempts) {
                        if (stopRequested || actionEpoch.get() != myEpoch) {
                            throw java.io.IOException("cancelled")
                        }
                        // Без сети попытка гарантированно провалится и просто
                        // сожжёт одну из попыток — типичная картина при рестарте
                        // после перезагрузки телефона или при возврате из
                        // авиарежима.
                        awaitNetwork(NETWORK_WAIT_MS)
                        try {
                            val candidate = VleeSshTransport(config)
                            candidate.connect { socket -> runCatching { protect(socket) } }
                            ssh = candidate
                            transport = candidate
                            break
                        } catch (e: Exception) {
                            lastError = e
                            Log.w(TAG, "Vlee connect attempt $attempt/$maxAttempts failed: ${e.message}")
                            runCatching { transport?.close() }
                            transport = null
                            if (stopRequested || actionEpoch.get() != myEpoch) {
                                throw java.io.IOException("cancelled")
                            }
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
                    val socks = VleeSocksServer(
                        transportProvider = { transport },
                        onTransportFailure = { onTransportFailure() },
                    )
                    socks.start()
                    socksServer = socks

                    val pfd = establishTun(excludedApps ?: emptyList(), allowedApps ?: emptyList())
                        ?: throw IllegalStateException("TUN establish failed")
                    tun = pfd

                    val yaml = VleeManager.tunYamlFile(this, socks.port)
                    if (stopRequested || actionEpoch.get() != myEpoch) {
                        throw java.io.IOException("cancelled")
                    }
                    TProxyStartService(yaml.absolutePath, pfd.fd)
                    tproxyStarted = true

                    startStatsTimer()
                    tunnelUp = true
                    applyUnderlyingNetworks()
                    startSupervisor()
                    VleeManager.setState(VleeManager.State.CONNECTED)
                    VleeManager.selectedServer?.let { VleeManager.notifyConnected(it) }
                } catch (t: Throwable) {
                    // Throwable, not Exception: a missing/mismatched .so raises
                    // UnsatisfiedLinkError, which is an Error. Catching only
                    // Exception let it kill this thread silently — no log line
                    // and no state transition, so the UI span forever on
                    // CONNECTING. That was the actual Vlee "no logs" bug.
                    Log.e(TAG, "Vlee tunnel failed", t)
                    teardownTunnel()
                    // Состояние выставляет только воркер текущей эпохи:
                    // отменённый воркер не должен затирать то, что уже выставил
                    // новый START/STOP, и плодить дубли DISCONNECTED.
                    if (actionEpoch.get() == myEpoch && !stopRequested) {
                        VleeManager.setState(VleeManager.State.DISCONNECTED, t.message ?: "connection error")
                    }
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
            // Сколько опросов подряд транспорт выглядел мёртвым. Двух подряд
            // достаточно: одиночный «промах» (просевший keepalive, мигнувший
            // callback) не должен превращаться в «реконнект» и пересборку SSH —
            // именно из-за этого статус фапал при простое телефона.
            var deadStreak = 0
            Log.i(TAG, "supervisor started (gen=$generation)")
            while (active()) {
                sleepUntilKick(SUPERVISOR_POLL_MS)
                if (!active()) break

                if (transport?.isConnected == true) {
                    deadStreak = 0
                    backoff = RECONNECT_BACKOFF_MIN_MS
                    if (VleeManager.isReconnecting) {
                        VleeManager.setReconnecting(false)
                        updateNotificationText(getString(R.string.vlee_notif_connected))
                    }
                    maybeHandleHandover()
                    continue
                }

                deadStreak++
                // Реальная потеря/смена сети — приговор сразу, ждать второго
                // промаха смысла нет: сокет уже не восстановить.
                if (deadStreak < DEAD_POLLS && !networkWasLost && !transportCertainlyDead) continue

                VleeManager.setReconnecting(true)
                updateNotificationText(getString(R.string.vlee_notif_reconnecting))

                if (!hasUsableNetwork()) {
                    // Сети нет вообще — переподключаться некуда. Ждём callback'а
                    // о появлении сети: он разбудит нас мгновенно.
                    Log.i(TAG, "нет доступной сети, ждём восстановления")
                    continue
                }

                Log.i(TAG, "транспорт мёртв — пересобираем SSH")
                if (reconnectTransport()) {
                    backoff = RECONNECT_BACKOFF_MIN_MS
                    // Свежий транспорт получает полный запас гистерезиса,
                    // иначе смерть в первые секунды продлевала бы серию.
                    deadStreak = 0
                    VleeManager.setReconnecting(false)
                    updateNotificationText(getString(R.string.vlee_notif_connected))
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
     * Переезд сети (Wi-Fi ↔ LTE) больше не рвёт транспорт мгновенно из
     * сетевого callback'а: активная сеть умеет «мигать» (валидация Wi-Fi,
     * короткий провал), и каждое такое колебание убивало здоровый SSH, а
     * свежеподнятый после пробуждения телефона приговаривало снова — отсюда
     * «подключилось и тут же реконнект». Решение принимает supervisor: новая
     * сеть должна продержаться [HANDOVER_STABLE_MS], и только тогда сокет,
     * висящий на старом интерфейсе, помечается мёртвым.
     */
    private fun maybeHandleHandover() {
        val live = transport ?: return
        val built = transportNetwork ?: return
        val active = activeNetworkNow() ?: return
        if (active == built) {
            handoverCandidate = null
            return
        }
        if (handoverCandidate != active) {
            handoverCandidate = active
            handoverCandidateSince = System.currentTimeMillis()
            return
        }
        if (System.currentTimeMillis() - handoverCandidateSince < HANDOVER_STABLE_MS) return
        handoverCandidate = null
        Log.i(TAG, "переезд $built -> $active устоялся: транспорт помечен мёртвым")
        transportCertainlyDead = true
        live.markDead()
        kickSupervisor()
    }

    private fun activeNetworkNow(): Network? = runCatching {
        getSystemService(ConnectivityManager::class.java)?.activeNetwork
    }.getOrNull()

    /**
     * Пересобирает SSH под работающим TUN. TUN и tun2socks не трогаем: их
     * пересоздание сменило бы SOCKS-порт и оборвало бы туннель целиком, а
     * пользователь всё это время должен видеть активный VPN.
     */
    private fun reconnectTransport(): Boolean {
        val config = VleeManager.selectedServer ?: VleeManager.restoreConfig(this) ?: return false
        // tryLock: если параллельно идёт start/stop — не мешаем и не блокируем
        // остановку по кнопке (connect может длиться десятки секунд).
        if (!lifecycleLock.tryLock()) return false
        try {
            if (stopRequested || !tunnelUp) return false
            runCatching { transport?.close() }
            transport = null
            val candidate = VleeSshTransport(config)
            return try {
                candidate.connect { socket -> runCatching { protect(socket) } }
                if (stopRequested || !tunnelUp) {
                    runCatching { candidate.close() }
                    false
                } else {
                    transport = candidate
                    transportNetwork = currentUnderlying()
                    networkWasLost = false
                    transportCertainlyDead = false
                    handoverCandidate = null
                    socksServer?.onTransportReplaced()
                    VleeManager.selectedServer?.let { VleeManager.notifyConnected(it) }
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
        transportNetwork = chosen
        networkWasLost = false
        transportCertainlyDead = false
        handoverCandidate = null
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
            if (previous != null) {
                networkWasLost = true
                transportCertainlyDead = true
            }
            Log.i(TAG, "сети нет — туннель ждёт восстановления")
            kickSupervisor()
            return
        }

        // Мгновенно транспорт больше НЕ рвём: активная сеть может «мигнуть»,
        // и каждое колебание раньше убивало здоровый SSH. Решение о переезде
        // принимает supervisor в maybeHandleHandover() — после того, как новая
        // сеть устоится. Здесь только обновляем привязку VPN и будим сторожа.
        kickSupervisor()
    }

    private fun establishTun(excludedApps: List<String>, allowedApps: List<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Vlee VPN")
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

    fun stopEverything() {
        // Новая эпоха делает любой идущий коннект устаревшим: его чекпоинты
        // сработают и воркер выйдет, не тронув состояние.
        actionEpoch.incrementAndGet()
        stopRequested = true
        VleeManager.markStopping(true)
        // tunnelUp снимаем ДО пинка: supervisor должен увидеть «уже не нужно»
        // и выйти, а не начать очередное переподключение.
        tunnelUp = false
        kickSupervisor()
        runCatching { transport?.close() }
        runCatching { startThread?.interrupt() }
        val myEpoch = actionEpoch.get()
        val myStartId = lastStartId
        thread(name = "vlee-tunnel-stop") {
            val stopped = lifecycleLock.withLock {
                // После нас прилетел новый START — остановка отменена: свежий
                // туннель не трогаем и сервис не гасим. Раньше этот поток
                // разрушал уже новый туннель, а его stopSelf() уничтожал
                // сервис прямо посреди нового коннекта — и onDestroy на
                // main thread зависал на lifecycleLock до ANR.
                if (actionEpoch.get() != myEpoch) {
                    false
                } else {
                    VleeManager.clearRunning(this@VleeVpnService)
                    teardownTunnel()
                    VleeManager.setState(VleeManager.State.DISCONNECTED)
                    VleeManager.markStopping(false)
                    true
                }
            }
            // stopSelf(наш startId): если после нас пришёл новый START,
            // система проигнорирует остановку. Именно no-arg stopSelf()
            // убивал сервис вместе с уже queued-новым START. Вызов — строго
            // вне лока: иначе onDestroy (main thread) блокируется на нём же.
            if (stopped) stopSelf(myStartId)
        }
    }

    private fun teardownTunnel() {
        tunnelUp = false
        kickSupervisor()
        VleeManager.setReconnecting(false)
        stopStatsTimer()
        // Нативный stop зовём только если туннель реально стартовали:
        // stop «вхолостую» (например, обрыв на этапе коннекта до
        // TProxyStartService) не должен дёргать нативную сторону.
        if (tproxyStarted) {
            runCatching { TProxyStopService() }
            tproxyStarted = false
            // Даём нативной стороне время дохлопнуть перед возможным
            // следующим TProxyStartService.
            Thread.sleep(500)
        }
        runCatching { socksServer?.stop() }
        runCatching { transport?.close() }
        socksServer = null
        transport = null
        runCatching { tun?.close() }
        tun = null
        VleeManager.resetTraffic()
    }

    private fun startStatsTimer() {
        stopStatsTimer()
        statsTimer = Timer("vlee-stats", true)
        statsTimer?.scheduleAtFixedRate(timerTask {
            if (!lifecycleLock.tryLock()) return@timerTask
            try {
                val stats = TProxyGetStats()
                if (stats.size >= 4) {
                    VleeManager.updateTraffic(up = stats[1], down = stats[3])
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
                    CHANNEL_ID, getString(R.string.vlee_notif_channel), NotificationManager.IMPORTANCE_LOW
                )
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.vlee_notif_connected)))
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
            .setContentTitle(getString(R.string.vlee_notif_title))
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
        actionEpoch.incrementAndGet()
        supervisorGeneration.incrementAndGet()
        kickSupervisor()
        unregisterNetworkCallback()
        if (instance === this) instance = null
        // Если сервис уничтожили, пока состояние висело в CONNECTING
        // (например, новый START был потерян из-за stopSelf), — приводим
        // состояние в порядок, иначе UI застрянет в «подключении» навсегда.
        if (VleeManager.state == VleeManager.State.CONNECTING) {
            VleeManager.setState(VleeManager.State.DISCONNECTED)
        }
        // Никогда не ждём lifecycleLock на main thread: воркер коннекта держит
        // его десятки секунд, и зависший onDestroy выглядел как «приложение
        // вылетело» (ANR). Не дождались — teardown делает фоновый поток.
        if (!lifecycleLock.tryLock()) {
            thread(name = "vlee-tunnel-destroy", isDaemon = true) {
                lifecycleLock.withLock { runCatching { teardownTunnel() } }
            }
        } else {
            try {
                runCatching { teardownTunnel() }
            } finally {
                lifecycleLock.unlock()
            }
        }
        super.onDestroy()
    }
}

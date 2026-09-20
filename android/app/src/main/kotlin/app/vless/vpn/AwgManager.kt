package app.vless.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the tunnel alive when Flutter's Activity is recreated. */
object AwgManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private var backend: GoBackend? = null
    private var tunnel: Tunnel? = null
    private var polling: Job? = null
    private var connectedAt = 0L
    private var previousAt = 0L
    private var previousRx = 0L
    private var previousTx = 0L
    @Volatile var listener: ((Map<String, Any>) -> Unit)? = null
    @Volatile var snapshot: Map<String, Any> = mapOf("state" to "DISCONNECTED")
        private set

    fun connect(context: Context, configBase64: String, packages: List<String>, done: (String?) -> Unit) {
        val ticket = generation.incrementAndGet()
        scope.launch {
            mutex.withLock {
                try {
                    stopTunnel()
                    if (ticket != generation.get()) {
                        main.post { done("cancelled") }
                        return@withLock
                    }
                    emit("CONNECTING")
                    // Loading Go on the UI thread crashes some older Kirin devices.
                    val engine = backend ?: GoBackend(context.applicationContext).also { backend = it }
                    val text = String(Base64.decode(configBase64, Base64.DEFAULT), Charsets.UTF_8)
                    val lines = text.lines().toMutableList()
                    if (packages.isNotEmpty()) {
                        require(packages.all { it.matches(Regex("[a-zA-Z0-9_.]+")) })
                        val header = lines.indexOfFirst { it.substringBefore('#').trim().equals("[Interface]", ignoreCase = true) }
                        require(header >= 0)
                        lines.add(header + 1, "ExcludedApplications = ${packages.joinToString(", ")}")
                    }
                    val config = Config.parse(ByteArrayInputStream(lines.joinToString("\n").toByteArray()))
                    val next = object : Tunnel {
                        override fun getName() = "vless_awg"
                        override fun onStateChange(state: Tunnel.State) {
                            if (state == Tunnel.State.DOWN) emit("DISCONNECTED")
                        }
                    }
                    tunnel = next
                    engine.setState(next, Tunnel.State.UP, config)
                    if (ticket != generation.get()) {
                        stopTunnel()
                        main.post { done("cancelled") }
                        return@withLock
                    }
                    connectedAt = SystemClock.elapsedRealtime()
                    previousAt = connectedAt
                    previousRx = 0
                    previousTx = 0
                    emit("CONNECTED")
                    startPolling()
                    main.post { done(null) }
                } catch (e: Exception) {
                    runCatching { stopTunnel() }
                    context.stopService(android.content.Intent(context, GoBackend.VpnService::class.java))
                    emit("ERROR")
                    main.post { done("AWG connection failed (${e.javaClass.simpleName})") }
                } catch (e: LinkageError) {
                    emit("ERROR")
                    main.post { done("AWG native library is unavailable on this device") }
                }
            }
        }
    }

    fun disconnect(done: (Boolean) -> Unit) {
        generation.incrementAndGet()
        scope.launch {
            mutex.withLock {
                val ok = runCatching { stopTunnel() }.isSuccess
                emit(if (ok) "DISCONNECTED" else "ERROR")
                main.post { done(ok) }
            }
        }
    }

    private fun stopTunnel() {
        polling?.cancel()
        polling = null
        tunnel?.let { backend?.setState(it, Tunnel.State.DOWN, null) }
        tunnel = null
    }

    private fun startPolling() {
        polling?.cancel()
        polling = scope.launch {
            while (isActive) {
                delay(1000)
                mutex.withLock {
                    val current = tunnel ?: return@launch
                    val engine = backend ?: return@launch
                    if (engine.getState(current) != Tunnel.State.UP) {
                        emit("DISCONNECTED")
                        return@launch
                    }
                    val stats = try { engine.getStatistics(current) } catch (_: Exception) {
                        emit("ERROR")
                        return@launch
                    }
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = (now - previousAt).coerceAtLeast(1)
                    val rx = stats.totalRx()
                    val tx = stats.totalTx()
                    emit("CONNECTED", mapOf(
                        "download" to rx, "upload" to tx,
                        "downloadSpeed" to ((rx - previousRx).coerceAtLeast(0) * 1000 / elapsed),
                        "uploadSpeed" to ((tx - previousTx).coerceAtLeast(0) * 1000 / elapsed),
                        "duration" to ((now - connectedAt) / 1000),
                    ))
                    previousRx = rx
                    previousTx = tx
                    previousAt = now
                }
            }
        }
    }

    private fun emit(state: String, traffic: Map<String, Any> = emptyMap()) {
        val event = mapOf("state" to state) + traffic
        snapshot = event
        main.post { listener?.invoke(event) }
    }
}

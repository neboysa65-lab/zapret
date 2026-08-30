package com.witvpn.vlee.ssh

import com.witvpn.vlee.injector.PayloadInjector
import com.witvpn.vlee.model.VleeServerConfig
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class VleeSshTransport(private val config: VleeServerConfig) : Closeable {

    private val client: SSHClient = SSHClient(DefaultConfig().apply {
        // KEEP_ALIVE, а не HEARTBEAT. Heartbeater только ПИШЕТ в сокет
        // (SSH_MSG_IGNORE) и ответа не ждёт, а запись в «чёрную дыру» — оборвался
        // Wi-Fi, телефон переехал на LTE, NAT оператора выкинул простоявшее
        // соединение — не даёт ошибки до конца TCP-ретрансмиссий, то есть до
        // ~15 минут. Всё это время туннель считался живым, каналы не
        // открывались, и «подключено, но интернета нет» держалось до ручного
        // переподключения. KEEP_ALIVE отправляет global request с want_reply и
        // считает неотвеченные: транспорт объявляется мёртвым за
        // KEEPALIVE_MAX_MISSED * KEEPALIVE_INTERVAL_SEC.
        keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
    })
    @Volatile private var connected = false

    /** Сокет под SSH — нужен, чтобы дешёво замечать закрытие снизу. */
    @Volatile private var wire: java.net.Socket? = null

    val isConnected: Boolean
        get() = connected && client.isConnected && wire?.isClosed != true

    /**
     * Помечает транспорт мёртвым, не дожидаясь keepalive.
     * Вызывается, когда открытие каналов начало стабильно падать: supervisor
     * сервиса увидит [isConnected] == false и пересоберёт соединение.
     */
    fun markDead() {
        connected = false
    }

    fun connect(protect: ((java.net.Socket) -> Unit)? = null) {
        VleeCrypto.ensureFullBouncyCastle()
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean =
                key != null && verifyHostKey(key)

            override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> =
                mutableListOf()
        })
        val prepared = PayloadInjector.connect(config, protect)
        wire = prepared
        client.setSocketFactory(object : javax.net.SocketFactory() {
            override fun createSocket(): java.net.Socket = prepared
            override fun createSocket(host: String?, port: Int): java.net.Socket =
                throw IOException("unsupported")
            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket =
                throw IOException("unsupported")
            override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket =
                throw IOException("unsupported")
            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket =
                throw IOException("unsupported")
        })
        client.setConnectTimeout(CONNECT_TIMEOUT_MS)
        // Чтение сокета — с конечным таймаутом, а не нулевым: при «TCP принял,
        // а SSH молчит» (дохлый узел за CDN) ридер с SO_TIMEOUT=0 блокировался
        // навсегда, воркер коннекта вечно держал lifecycleLock, и стоп/рестарт
        // сервиса висел до ANR. 90с безопасны: живой транспорт гарантированно
        // подкладывает пакеты keepalive'ом каждые 20с, а таймаут честно убивает
        // молчащий. Мультиплексному простаивающему каналу это не мешает.
        client.setTimeout(SOCKET_READ_TIMEOUT_MS)
        client.connection.timeoutMs = CHANNEL_OPEN_TIMEOUT_MS
        client.connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_SEC
        (client.connection.keepAlive as? KeepAliveRunner)?.maxAliveCount = KEEPALIVE_MAX_MISSED
        client.connect(config.ipAddress, config.sshPort)
        try {
            client.authPassword(config.sshUsername, config.sshPassword)
        } catch (e: Exception) {
            connected = false
            throw e
        }
        connected = true
    }

    fun openChannel(host: String, port: Int): DirectConnection {
        check(connected) { "transport not connected" }
        // Открытие каналов НЕ сериализуем: sshj сам синхронизирует запись в
        // транспорт и раздачу channel id. Раньше channelLock держался на всё
        // время открытия — то есть на полный RTT до сервера, — и загрузка одной
        // страницы (30-50 соединений) платила 30-50 последовательных RTT.
        // Именно так «работающий» туннель выглядел мёртвым по скорости.
        return client.newDirectConnection(host, port)
    }

    override fun close() {
        connected = false
        // Сокет закрываем ПЕРВЫМ: client.disconnect() пишет в него прощальный
        // пакет, а на мёртвой сети эта запись может подвиснуть — close() же
        // вызывается из supervisor'а прямо перед переподключением, и застревать
        // здесь означало бы не восстановить связь вообще.
        runCatching { wire?.close() }
        runCatching { client.disconnect() }
        wire = null
    }

    private fun verifyHostKey(presented: PublicKey): Boolean {
        val pinned = config.sshHostkey.trim()
        if (pinned.isEmpty()) return true

        val presentedB64 = Base64.getEncoder().encodeToString(presented.encoded)
        val presentedMd5 = MessageDigest.getInstance("MD5").digest(presented.encoded)
            .joinToString(":") { String.format("%02x", it) }

        val pinClean = pinned.lowercase().removePrefix("sha256:")
        return if (pinClean.contains(":")) {
            pinClean == presentedMd5
        } else {
            runCatching {
                val expected = Base64.getDecoder().decode(pinClean.replace("=", ""))
                val actual = Base64.getDecoder().decode(presentedB64)
                MessageDigest.isEqual(expected, actual)
            }.getOrDefault(false)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000

        /**
         * SO_TIMEOUT ридера: больше трёх keepalive-интервалов, чтобы живой
         * простаивающий транспорт не убивало, но конечный — чтобы «TCP принял,
         * SSH молчит» не вешало воркер коннекта навсегда.
         */
        private const val SOCKET_READ_TIMEOUT_MS = 90_000

        /** Ограничение на открытие одного канала (direct-tcpip). */
        private const val CHANNEL_OPEN_TIMEOUT_MS = 20_000

        private const val KEEPALIVE_INTERVAL_SEC = 20

        /**
         * Сколько неотвеченных keepalive терпим. 3 * 20 с ≈ минута до вердикта
         * «транспорт мёртв» — дальше supervisor сервиса пересоберёт соединение.
         * Меньше ставить не стоит: на плохой мобильной сети одиночная потеря
         * пакета не должна выглядеть как обрыв.
         */
        private const val KEEPALIVE_MAX_MISSED = 3

        fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(key, "HmacSHA512"))
            return mac.doFinal(data)
        }
    }
}

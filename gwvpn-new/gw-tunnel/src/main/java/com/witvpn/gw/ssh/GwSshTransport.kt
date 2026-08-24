package com.witvpn.gw.ssh

import com.witvpn.gw.injector.PayloadInjector
import com.witvpn.gw.model.GwServerConfig
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class GwSshTransport(private val config: GwServerConfig) : Closeable {

    private val client: SSHClient = SSHClient(DefaultConfig().apply {
        keepAliveProvider = KeepAliveProvider.HEARTBEAT
    })
    private val channelLock = ReentrantLock()
    @Volatile private var connected = false

    val isConnected: Boolean get() = connected && client.isConnected

    fun connect(protect: ((java.net.Socket) -> Unit)? = null) {
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean =
                key != null && verifyHostKey(key)

            override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> =
                mutableListOf()
        })
        val prepared = PayloadInjector.connect(config, protect)
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
        client.setTimeout(0)
        client.connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_SEC
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
        channelLock.withLock {
            check(connected) { "transport not connected" }
            return client.newDirectConnection(host, port)
        }
    }

    override fun close() {
        connected = false
        runCatching { client.disconnect() }
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
        private const val KEEPALIVE_INTERVAL_SEC = 30

        fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(key, "HmacSHA512"))
            return mac.doFinal(data)
        }

        fun pipe(src: InputStream, dst: OutputStream, onDone: Runnable) {
            thread(name = "gw-pipe") {
                try {
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                        dst.flush()
                    }
                } catch (_: IOException) {
                } finally {
                    runCatching { dst.close() }
                    onDone.run()
                }
            }
        }
    }
}

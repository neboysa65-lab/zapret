package com.witvpn.gw.injector

import com.witvpn.gw.crypto.GwObf
import com.witvpn.gw.model.GwServerConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

object PayloadInjector {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val RESPONSE_TIMEOUT_MS = 15_000

    // Пауза ожидания ответа сервера на промежуточную часть payload ([split]):
    // если за это время ответа нет — считаем, что сервер молчит, и шлём следующую часть.
    private const val SPLIT_IDLE_TIMEOUT_MS = 1_500
    private const val SPLIT_MAX_WAIT_MS = 8_000

    // Служебные маркеры сегментов (в сокет не пишутся).
    private const val TOK_WAIT_RESPONSE = "\u0000WAITRESP\u0000"
    private val DEFAULT_UA: String get() = GwServerConfig.DEFAULT_UA

    fun expandPayload(
        template: String,
        host: String,
        port: Int,
        sshHost: String,
        sshPort: Int,
        ua: String = DEFAULT_UA,
    ): List<String> {
        var text = template
            .replace(GwObf.T_SPLIT, GwObf.TOK_SPLIT, ignoreCase = true)
            .replace(GwObf.T_CRLF, "\r\n", ignoreCase = true)
            .replace(GwObf.T_CR, "\r", ignoreCase = true)
            .replace(GwObf.T_LF, "\n", ignoreCase = true)
            .replace(GwObf.T_UA, ua, ignoreCase = true)
            .replace(GwObf.T_METHOD, "GET", ignoreCase = true)
            .replace(GwObf.T_PROTOCOL, "HTTP/1.1", ignoreCase = true)
            .replace(GwObf.T_HOST_PORT, "$sshHost:$sshPort", ignoreCase = true)
            .replace(GwObf.T_SSH, "$sshHost:$sshPort", ignoreCase = true)
            .replace(GwObf.T_PORT, port.toString(), ignoreCase = true)
            .replace(GwObf.T_HOST, host, ignoreCase = true)

        if (!text.endsWith("\r\n\r\n")) {
            while (text.endsWith("\r\n")) text = text.dropLast(2)
            text += "\r\n\r\n"
        }

        val out = mutableListOf<String>()
        // [split] делит payload на части: часть отправляется — ждём ответ сервера —
        // отправляем следующую. Сам маркер и ведущие CR/LF в сокет не попадают:
        // pipelining и мусор между запросами CDN воспринимает как ошибку (400/close).
        val splitParts = text.split(GwObf.TOK_SPLIT)
        splitParts.forEachIndexed { partIndex, part ->
            if (part.isEmpty()) return@forEachIndexed
            val segments = part.split(Regex("=\\s*\\d+\\s*="))
            val delays = Regex("=\\s*(\\d+)\\s*=").findAll(part).map { it.groupValues[1].toInt() }.toList()
            segments.forEachIndexed { index, seg ->
                val cleaned = seg.trimStart('\r', '\n')
                if (cleaned.isNotEmpty()) out.add(cleaned)
                if (index < delays.size) out.add(GwObf.TOK_DELAY + delays[index])
            }
            if (partIndex < splitParts.lastIndex) out.add(TOK_WAIT_RESPONSE)
        }
        return if (out.isEmpty()) listOf(text) else out
    }

    fun connect(config: GwServerConfig, protect: ((Socket) -> Unit)? = null): Socket {
        return if (config.proxyHost.isBlank()) {
            connectDirect(config, protect)
        } else {
            connectViaProxy(config, protect)
        }
    }

    private fun connectDirect(config: GwServerConfig, protect: ((Socket) -> Unit)?): Socket {
        val socket = Socket()
        protect?.invoke(socket)
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(config.ipAddress, config.sshPort), CONNECT_TIMEOUT_MS)
        return socket
    }

    private fun connectViaProxy(config: GwServerConfig, protect: ((Socket) -> Unit)?): Socket {
        val host = config.proxyHost
        val useTls = config.proxyScheme.equals(GwObf.HTTPS, ignoreCase = true) || config.sni.isNotBlank()

        val tcp = Socket()
        protect?.invoke(tcp)
        tcp.tcpNoDelay = true
        tcp.connect(InetSocketAddress(host, config.proxyPort), CONNECT_TIMEOUT_MS)

        val socket: Socket = if (useTls) {
            wrapTls(tcp, host, config.sni.ifBlank { host })
        } else {
            tcp
        }

        socket.soTimeout = RESPONSE_TIMEOUT_MS
        try {
            val requestHost = resolveRequestHost(config)
            val segments = expandPayload(
                config.payload.ifBlank { GwServerConfig.defaultPayload() },
                host = requestHost,
                port = config.proxyPort,
                sshHost = config.ipAddress,
                sshPort = config.sshPort,
            )
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val skipper = ResponseSkipper(input)
            var earlyLeftover: ByteArray? = null

            for (segment in segments) {
                when {
                    segment.startsWith(GwObf.TOK_DELAY) ->
                        Thread.sleep(segment.removePrefix(GwObf.TOK_DELAY).toLong())

                    segment == TOK_WAIT_RESPONSE -> {
                        // Ждём и съедаем ответ(ы) сервера на предыдущую часть,
                        // чтобы следующий запрос не ушёл pipelining'ом.
                        val lo = skipper.waitForResponses(socket, earlyLeftover)
                        if (lo != null) earlyLeftover = lo
                    }

                    else -> {
                        out.write(segment.toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                    }
                }
            }

            socket.soTimeout = RESPONSE_TIMEOUT_MS
            skipper.skipUntilTunnel()
            socket.soTimeout = 0

            val leftover = earlyLeftover
            return if (leftover != null) {
                LeftoverSocket(socket, leftover + skipper.leftover)
            } else {
                LeftoverSocket(socket, skipper.leftover)
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || host.contains(":")

    private fun wrapTls(tcp: Socket, peerHost: String, sniHost: String): SSLSocket {
        val factory = SocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val ssl = factory.createSocket(tcp, peerHost, tcp.port, true) as SSLSocket
        try {
            val params: SSLParameters = ssl.sslParameters
            val names = ArrayList<SNIServerName>()
            names.add(SNIHostName(sniHost))
            params.serverNames = names
            ssl.sslParameters = params
        } catch (_: Exception) {
        }
        return ssl
    }

    fun resolveRequestHost(config: GwServerConfig): String {
        config.sni.isNotBlank() && return config.sni
        if (!isIpLiteral(config.ipAddress)) return config.ipAddress
        if (config.proxyHost.isNotBlank() && !isIpLiteral(config.proxyHost)) return config.proxyHost
        return config.ipAddress
    }

    private const val SKIP_CAP_BYTES = 262144

    /**
     * Пропуск HTTP-ответов CDN/прокси до данных туннеля (SSH-баннера).
     * Корректно обрабатывает тела ответов (chunked / content-length) — иначе
     * терминатор чанка "0\r\n\r\n" попадает в туннель и ломает SSH-баннер.
     */
    private class ResponseSkipper(private val input: InputStream) {
        val collected = ByteArrayOutputStream()
        var leftover: ByteArray = ByteArray(0)
            private set

        private fun readByte(): Int {
            val b = input.read()
            if (b == -1) throw IOException("connection closed before tunnel established")
            collected.write(b)
            return b
        }

        private fun readLine(): String {
            val sb = StringBuilder()
            while (true) {
                val b = readByte()
                sb.append((b and 0xFF).toChar())
                if (b == '\n'.code) return sb.toString()
                if (sb.length > 16384) throw IOException("oversized HTTP line")
            }
        }

        /** Читает заголовки (начиная с уже прочитанного probe) и тело ответа. */
        private fun skipResponse(probe: StringBuilder) {
            val head = StringBuilder(probe)
            var tail = probe.toString().takeLast(4)
            while (!(tail.endsWith("\r\n\r\n") || tail.endsWith("\n\n"))) {
                val b = readByte()
                tail += (b and 0xFF).toChar()
                if (tail.length > 4) tail = tail.substring(tail.length - 4)
                head.append((b and 0xFF).toChar())
                if (head.length > 65536) throw IOException("oversized HTTP headers")
            }
            val lower = head.toString().lowercase()

            val isChunked = lower.replace(" ", "").contains("transfer-encoding:chunked")
            if (isChunked) {
                while (true) {
                    val sizeLine = readLine().trim().substringBefore(';')
                    val size = sizeLine.toIntOrNull(16) ?: throw IOException("bad chunk size")
                    if (size == 0) {
                        // trailer-секция: читаем строки до пустой
                        while (readLine() != "\r\n") {
                            // skip trailers
                        }
                        break
                    }
                    var remaining = size
                    while (remaining > 0) {
                        readByte()
                        remaining--
                    }
                    readByte() // \r
                    readByte() // \n
                }
            } else {
                Regex("content-length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(head.toString())?.groupValues?.get(1)?.toIntOrNull()?.let { len ->
                        var remaining = len
                        while (remaining > 0) {
                            readByte()
                            remaining--
                        }
                    }
            }
        }

        /** Читает до 5 байт; true — поток начинается с "HTTP/" (ещё один ответ). */
        private fun probeIsHttp(probe: StringBuilder): Boolean {
            while (probe.length < 5) {
                val b = readByte()
                probe.append((b and 0xFF).toChar())
                if (probe.length < 5 && (b == '\r'.code || b == '\n'.code)) break
            }
            return probe.startsWith("HTTP/")
        }

        private fun takeLeftover(probeStart: Int): ByteArray {
            val all = collected.toByteArray()
            return all.copyOfRange(probeStart, all.size)
        }

        /** Основной цикл: скипает все HTTP-ответы, останавливается на данных туннеля. */
        fun skipUntilTunnel() {
            while (true) {
                if (collected.size() > SKIP_CAP_BYTES) throw IOException("oversized HTTP response")
                val probeStart = collected.size()
                val probe = StringBuilder()
                if (probeIsHttp(probe)) {
                    skipResponse(probe)
                } else {
                    leftover = takeLeftover(probeStart)
                    return
                }
            }
        }

        /**
         * Ожидание ответов после промежуточной части payload ([split]).
         * Ответы съедаются; если за SPLIT_IDLE_TIMEOUT_MS тишина — выходим (null).
         * Если пришли данные туннеля — возвращаем их как leftover.
         */
        fun waitForResponses(socket: Socket, existingLeftover: ByteArray?): ByteArray? {
            val startedAt = System.currentTimeMillis()
            while (true) {
                socket.soTimeout = SPLIT_IDLE_TIMEOUT_MS
                val first = try {
                    input.read()
                } catch (e: java.io.InterruptedIOException) {
                    return null // тишина — сервер пока не ответил, шлём следующую часть
                } finally {
                    socket.soTimeout = RESPONSE_TIMEOUT_MS
                }
                if (first == -1) throw IOException("connection closed before tunnel established")
                if (collected.size() > SKIP_CAP_BYTES) throw IOException("oversized HTTP response")

                val probeStart = collected.size()
                collected.write(first)
                val probe = StringBuilder().append((first and 0xFF).toChar())
                if (probeIsHttp(probe)) {
                    skipResponse(probe)
                } else {
                    val lo = takeLeftover(probeStart)
                    return if (existingLeftover != null) existingLeftover + lo else lo
                }
                if (System.currentTimeMillis() - startedAt > SPLIT_MAX_WAIT_MS) return null
            }
        }
    }

    private class LeftoverSocket(
        private val delegate: Socket,
        leftover: ByteArray,
    ) : Socket() {
        private val prefix = ByteArrayInputStream(leftover)

        override fun getInputStream(): InputStream =
            SequenceInputStream(prefix, delegate.getInputStream())

        override fun getOutputStream() = delegate.getOutputStream()
        override fun isConnected(): Boolean = delegate.isConnected
        override fun isClosed(): Boolean = delegate.isClosed
        override fun close() = delegate.close()
        override fun getInetAddress(): InetAddress? = delegate.inetAddress
        override fun getPort(): Int = delegate.port
        override fun getLocalAddress(): InetAddress = delegate.localAddress
        override fun getLocalPort(): Int = delegate.localPort
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setSoTimeout(timeout: Int) = delegate.setSoTimeout(timeout)
        override fun getSoLinger(): Int = delegate.soLinger
        override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
        override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
        override fun setTcpNoDelay(on: Boolean) = delegate.setTcpNoDelay(on)
        override fun getKeepAlive(): Boolean = delegate.keepAlive
        override fun setKeepAlive(on: Boolean) = delegate.setKeepAlive(on)
        override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
        override fun setReceiveBufferSize(size: Int) = delegate.setReceiveBufferSize(size)
        override fun getSendBufferSize(): Int = delegate.sendBufferSize
        override fun setSendBufferSize(size: Int) = delegate.setSendBufferSize(size)
        override fun getTrafficClass(): Int = delegate.trafficClass
        override fun setTrafficClass(tc: Int) = delegate.setTrafficClass(tc)
        override fun getRemoteSocketAddress(): InetSocketAddress =
            delegate.remoteSocketAddress as InetSocketAddress

        override fun getLocalSocketAddress(): InetSocketAddress? =
            delegate.localSocketAddress as InetSocketAddress?

        override fun bind(bindpoint: java.net.SocketAddress?) = delegate.bind(bindpoint)
        override fun shutdownInput() = delegate.shutdownInput()
        override fun shutdownOutput() = delegate.shutdownOutput()
        override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
            delegate.setPerformancePreferences(connectionTime, latency, bandwidth)

        override fun toString(): String = delegate.toString()
    }
}

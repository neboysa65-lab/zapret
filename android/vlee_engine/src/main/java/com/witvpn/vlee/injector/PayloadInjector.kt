package com.witvpn.vlee.injector

import android.util.Log
import com.witvpn.vlee.crypto.VleeObf
import com.witvpn.vlee.model.VleeServerConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object PayloadInjector {

    private const val TAG = "VleeInjector"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val RESPONSE_TIMEOUT_MS = 15_000

    private val DEFAULT_UA: String get() = VleeServerConfig.DEFAULT_UA

    /**
     * Разворачивает payload-шаблон в список сегментов для отправки.
     *
     * Правила (совместимо с поведением HTTP Injector):
     *  - токены [crlf]/[cr]/[lf]/[host]/[port]/[ssh]/[ua]/... подставляются;
     *  - [split] делит payload на части — каждая часть пишется в сокет ОТДЕЛЬНОЙ
     *    записью (отдельным TCP-сегментом) СРАЗУ друг за другом, БЕЗ ожидания ответа
     *    сервера. Это фрагментация для обхода DPI, а не запрос-ответ;
     *  - байты сегментов НЕ обрезаются (ведущий [cr]/[lf] сохраняется как есть —
     *    он часть техники обхода и должен уходить на провод дословно);
     *  - =N= внутри части — необязательная задержка N мс между кусками этой части.
     */
    fun expandPayload(
        template: String,
        host: String,
        port: Int,
        sshHost: String,
        sshPort: Int,
        ua: String = DEFAULT_UA,
    ): List<String> {
        var text = template
            .replace(VleeObf.T_SPLIT, VleeObf.TOK_SPLIT, ignoreCase = true)
            .replace(VleeObf.T_CRLF, "\r\n", ignoreCase = true)
            .replace(VleeObf.T_CR, "\r", ignoreCase = true)
            .replace(VleeObf.T_LF, "\n", ignoreCase = true)
            .replace(VleeObf.T_UA, ua, ignoreCase = true)
            .replace(VleeObf.T_METHOD, "GET", ignoreCase = true)
            .replace(VleeObf.T_PROTOCOL, "HTTP/1.1", ignoreCase = true)
            .replace(VleeObf.T_HOST_PORT, "$sshHost:$sshPort", ignoreCase = true)
            .replace(VleeObf.T_SSH, "$sshHost:$sshPort", ignoreCase = true)
            .replace(VleeObf.T_PORT, port.toString(), ignoreCase = true)
            .replace(VleeObf.T_HOST, host, ignoreCase = true)

        // Заголовки HTTP-запроса должны завершаться пустой строкой. Если автор
        // payload забыл финальный [crlf][crlf] — добавляем, не трогая остальное.
        if (!text.endsWith("\r\n\r\n")) {
            while (text.endsWith("\r\n")) text = text.dropLast(2)
            text += "\r\n\r\n"
        }

        val out = mutableListOf<String>()
        val splitParts = text.split(VleeObf.TOK_SPLIT)
        splitParts.forEach { part ->
            if (part.isEmpty()) return@forEach
            val segments = part.split(Regex("=\\s*\\d+\\s*="))
            val delays = Regex("=\\s*(\\d+)\\s*=").findAll(part).map { it.groupValues[1].toLong() }.toList()
            segments.forEachIndexed { index, seg ->
                // НЕ обрезаем ведущие \r/\n — байты уходят на провод дословно.
                if (seg.isNotEmpty()) out.add(seg)
                if (index < delays.size) out.add(VleeObf.TOK_DELAY + delays[index])
            }
        }
        return if (out.isEmpty()) listOf(text) else out
    }

    fun connect(config: VleeServerConfig, protect: ((Socket) -> Unit)? = null): Socket {
        val hasProxy = config.proxyHost.isNotBlank()
        val hasPayload = config.payload.isNotBlank()
        return when {
            // Через промежуточный прокси/CDN (обычный режим Vlee).
            hasProxy -> connectAndInject(config, protect, viaProxy = true)
            // Прямо на Vlee-эндпоинт (ws-мост) — ему тоже нужен upgrade-payload,
            // иначе мост ответит 400 и SSH не поднимется.
            hasPayload -> connectAndInject(config, protect, viaProxy = false)
            // Ни прокси, ни payload — обычный SSH напрямую.
            else -> connectDirect(config, protect)
        }
    }

    private fun connectDirect(config: VleeServerConfig, protect: ((Socket) -> Unit)?): Socket {
        val socket = Socket()
        protect?.invoke(socket)
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(config.ipAddress, config.sshPort), CONNECT_TIMEOUT_MS)
        return socket
    }

    /**
     * Только авторский payload — никакого чистого WebSocket-upgrade фолбэка:
     * открытый upgrade выдаёт туннель DPI-наблюдателю и обесценивает обфускацию.
     */
    private fun connectAndInject(
        config: VleeServerConfig,
        protect: ((Socket) -> Unit)?,
        viaProxy: Boolean,
    ): Socket {
        val payload = config.payload.ifBlank { VleeServerConfig.defaultPayload() }
        return attemptInject(config, protect, viaProxy, payload)
    }

    private fun attemptInject(
        config: VleeServerConfig,
        protect: ((Socket) -> Unit)?,
        viaProxy: Boolean,
        payloadTemplate: String,
    ): Socket {
        val peerHost = if (viaProxy) config.proxyHost else config.ipAddress
        val peerPort = if (viaProxy) config.proxyPort else config.sshPort
        val useTls = config.proxyScheme.equals(VleeObf.HTTPS, ignoreCase = true) ||
            (viaProxy && config.sni.isNotBlank() && peerPort == 443)

        val tcp = Socket()
        protect?.invoke(tcp)
        tcp.tcpNoDelay = true
        tcp.connect(InetSocketAddress(peerHost, peerPort), CONNECT_TIMEOUT_MS)

        val socket: Socket = if (useTls) {
            wrapTls(tcp, peerHost, config.sni.ifBlank { peerHost })
        } else {
            tcp
        }

        socket.soTimeout = RESPONSE_TIMEOUT_MS
        try {
            val requestHost = resolveRequestHost(config)
            val segments = expandPayload(
                payloadTemplate,
                host = requestHost,
                port = peerPort,
                sshHost = config.ipAddress,
                sshPort = config.sshPort,
            )
            val out = socket.getOutputStream()

            // Пишем все сегменты подряд (учитывая задержки). [split] уже разбил
            // payload на отдельные записи — каждая уходит своим TCP-сегментом.
            for (segment in segments) {
                if (segment.startsWith(VleeObf.TOK_DELAY)) {
                    val ms = segment.removePrefix(VleeObf.TOK_DELAY).toLongOrNull() ?: 0L
                    if (ms > 0) Thread.sleep(ms)
                } else {
                    out.write(segment.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
            }

            // Пропускаем HTTP-ответ(ы) CDN/моста до начала данных туннеля (SSH-баннер).
            socket.soTimeout = RESPONSE_TIMEOUT_MS
            val skipper = ResponseSkipper(socket.getInputStream())
            skipper.skipUntilTunnel()
            socket.soTimeout = 0

            return LeftoverSocket(socket, skipper.leftover)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || host.contains(":")

    private fun wrapTls(tcp: Socket, peerHost: String, sniHost: String): SSLSocket {
        // ВАЖНО: именно SSLSocketFactory.getDefault(), а не SocketFactory.getDefault()
        // (последний возвращает обычную фабрику и падал с ClassCastException на TLS).
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(tcp, peerHost, tcp.port, true) as SSLSocket
        try {
            val params: SSLParameters = ssl.sslParameters
            val names = ArrayList<SNIServerName>()
            names.add(SNIHostName(sniHost))
            params.serverNames = names
            ssl.sslParameters = params
        } catch (_: Exception) {
        }
        runCatching { ssl.startHandshake() }
        return ssl
    }

    fun resolveRequestHost(config: VleeServerConfig): String {
        if (config.sni.isNotBlank()) return config.sni
        if (!isIpLiteral(config.ipAddress)) return config.ipAddress
        if (config.proxyHost.isNotBlank() && !isIpLiteral(config.proxyHost)) return config.proxyHost
        return config.ipAddress
    }

    private const val SKIP_CAP_BYTES = 262144

    private enum class SkipResult { MORE, TUNNEL, UNKNOWN_BODY, CLOSED }

    /**
     * Пропуск HTTP-ответов CDN/прокси/моста до данных туннеля (SSH-баннера).
     * Корректно обрабатывает:
     *  - тела ответов (chunked / content-length);
     *  - 101 Switching Protocols (после заголовков сразу начинается туннель);
     *  - ответы без явной длины тела (Connection: close и т.п.) — в этом случае
     *    переходим на поиск SSH-баннера в потоке (надёжнее строгого HTTP-парсинга).
     */
    private class ResponseSkipper(private val input: InputStream) {
        private val collected = ByteArrayOutputStream()
        private var lastStatus: Int = -1
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
        private fun skipResponse(probe: StringBuilder): SkipResult {
            val head = StringBuilder(probe)
            var tail = probe.toString().takeLast(4)
            while (!(tail.endsWith("\r\n\r\n") || tail.endsWith("\n\n"))) {
                val b = readByte()
                tail += (b and 0xFF).toChar()
                if (tail.length > 4) tail = tail.substring(tail.length - 4)
                head.append((b and 0xFF).toChar())
                if (head.length > 65536) throw IOException("oversized HTTP headers")
            }
            val headStr = head.toString()
            val lower = headStr.lowercase()
            val status = parseStatus(headStr)
            lastStatus = status

            // 101 Switching Protocols: туннель начинается сразу после заголовков.
            if (status == 101) return SkipResult.TUNNEL

            val willClose = lower.replace(" ", "").contains("connection:close")

            // Ответы без тела по определению.
            if (status == 204 || status == 304 || status in 100..199) {
                return if (willClose) SkipResult.CLOSED else SkipResult.MORE
            }

            val isChunked = lower.replace(" ", "").contains("transfer-encoding:chunked")
            if (isChunked) {
                while (true) {
                    val sizeLine = readLine().trim().substringBefore(';')
                    val size = sizeLine.toIntOrNull(16) ?: throw IOException("bad chunk size")
                    if (size == 0) {
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
                return if (willClose) SkipResult.CLOSED else SkipResult.MORE
            }

            val contentLength = Regex("content-length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(headStr)?.groupValues?.get(1)?.toIntOrNull()
            if (contentLength != null) {
                var remaining = contentLength
                while (remaining > 0) {
                    readByte()
                    remaining--
                }
                // Сервер явно закрывает соединение и туннеля не будет — не ждём.
                return if (willClose) SkipResult.CLOSED else SkipResult.MORE
            }

            // Длина тела неизвестна (нет Content-Length, не chunked). Дальше строго
            // парсить нельзя — ищем SSH-баннер в потоке напрямую.
            return SkipResult.UNKNOWN_BODY
        }

        private fun parseStatus(head: String): Int {
            val firstLine = head.substringBefore('\n').trim()
            val parts = firstLine.split(' ')
            if (parts.size >= 2 && parts[0].startsWith("HTTP/", ignoreCase = true)) {
                return parts[1].toIntOrNull() ?: -1
            }
            return -1
        }

        /** Читает ровно 5 байт; true — поток начинается с "HTTP/" (ещё один ответ). */
        private fun probeIsHttp(probe: StringBuilder): Boolean {
            while (probe.length < 5) {
                val b = readByte()
                probe.append((b and 0xFF).toChar())
            }
            return probe.startsWith("HTTP/")
        }

        private fun takeLeftover(probeStart: Int): ByteArray {
            val all = collected.toByteArray()
            return all.copyOfRange(probeStart, all.size)
        }

        /**
         * Поиск SSH-баннера ("SSH-") в потоке. Всё до него — мусор ответа,
         * начиная с него — данные туннеля (сохраняются в leftover).
         */
        private fun scanForSshBanner() {
            val window = ByteArray(4)
            var filled = 0
            var scanned = 0
            while (true) {
                val b = input.read()
                if (b == -1) throw IOException("connection closed before tunnel established")
                if (filled < 4) {
                    window[filled++] = b.toByte()
                } else {
                    window[0] = window[1]; window[1] = window[2]; window[2] = window[3]
                    window[3] = b.toByte()
                }
                if (filled == 4 &&
                    window[0] == 'S'.code.toByte() && window[1] == 'S'.code.toByte() &&
                    window[2] == 'H'.code.toByte() && window[3] == '-'.code.toByte()
                ) {
                    leftover = "SSH-".toByteArray(Charsets.US_ASCII)
                    return
                }
                if (++scanned > SKIP_CAP_BYTES) throw IOException("oversized HTTP response")
            }
        }

        /** Основной цикл: скипает HTTP-ответы, останавливается на данных туннеля. */
        fun skipUntilTunnel() {
            while (true) {
                if (collected.size() > SKIP_CAP_BYTES) throw IOException("oversized HTTP response")
                val probeStart = collected.size()
                val probe = StringBuilder()
                if (!probeIsHttp(probe)) {
                    // Поток не начинается с "HTTP/" — это уже данные туннеля.
                    leftover = takeLeftover(probeStart)
                    return
                }
                when (skipResponse(probe)) {
                    SkipResult.MORE -> continue
                    SkipResult.TUNNEL -> {
                        // После 101 туннель начинается немедленно.
                        leftover = ByteArray(0)
                        return
                    }
                    SkipResult.UNKNOWN_BODY -> {
                        scanForSshBanner()
                        return
                    }
                    SkipResult.CLOSED -> throw IOException(
                        "gateway rejected payload (HTTP $lastStatus) and closed the connection"
                    )
                }
            }
        }
    }

    private class LeftoverSocket(
        private val delegate: Socket,
        leftover: ByteArray,
    ) : Socket() {
        // ОДИН экземпляр на сокет. Раньше SequenceInputStream создавался в каждом
        // вызове getInputStream() поверх общего, уже вычитанного prefix: второй
        // читатель (sshj читает поток и из reader-потока, и при negotiation)
        // получал поток с потерянным началом.
        private val stream: InputStream by lazy {
            SequenceInputStream(ByteArrayInputStream(leftover), delegate.getInputStream())
        }

        override fun getInputStream(): InputStream = stream

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

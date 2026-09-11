package com.witvpn.gw.socks

import android.util.Log
import com.witvpn.gw.ssh.GwSshTransport
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SOCKS5-фронтенд для tun2socks.
 *
 * Транспорт берётся через [transportProvider], а не передаётся один раз: при
 * обрыве сети SSH пересобирается заново, и если бы ссылка была фиксированной,
 * пришлось бы поднимать заново весь стек — новый порт, новый yaml, перезапуск
 * tun2socks, пересоздание TUN. Теперь порт и tun живут через переподключения
 * нетронутыми, а меняется только транспорт под ними.
 */
class GwSocksServer(
    private val transportProvider: () -> GwSshTransport?,
    private val onTransportFailure: () -> Unit = {},
    private val dnsOverTcp: Boolean = true,
) {
    companion object {
        private const val TAG = "GwSocks"
        private const val VERSION = 0x05
        private const val CMD_CONNECT = 0x01
        private const val CMD_UDP_ASSOCIATE = 0x03
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04

        /** Максимум одновременных UDP-сессий (каждая = поток + сокет + буфер). */
        private const val MAX_UDP_SESSIONS = 200

        /** UDP-сессия без трафика дольше этого закрывается, освобождая поток/сокет. */
        private const val UDP_IDLE_TIMEOUT_MS = 60_000L
        private const val DNS_PORT = 53
        private const val DNS_TCP_TIMEOUT_MS = 5000
        private const val DNS_WORKERS = 8
        private const val RELAY_BUFFER_BYTES = 16 * 1024
        /** Сколько ждём догрузки ответа после EOF от приложения. */
        private const val RELAY_DRAIN_TIMEOUT_MS = 30_000L

        /**
         * Сколько подряд неудачных открытий канала считаем приговором транспорту.
         * Один отказ — это чаще всего недоступный адрес и рвать туннель незачем;
         * а вот когда падает всё подряд, соединение мертво, даже если TCP об этом
         * ещё не знает (оборванный Wi-Fi выглядит именно так).
         */
        private const val DEAD_AFTER_FAILURES = 3
        private val SUCCESS_REPLY = byteArrayOf(VERSION.toByte(), 0x00, 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
        private val GENERAL_FAILURE = byteArrayOf(VERSION.toByte(), 0x01, 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
        private val CMD_UNSUPPORTED = byteArrayOf(VERSION.toByte(), 0x07, 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
    }

    private var serverSocket: ServerSocket? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "gw-socks-worker").apply { isDaemon = true }
    }
    // DNS-over-TCP резолвы идут параллельно: один медленный апстрим не должен
    // задерживать остальные запросы (раньше единственный поток + таймаут 5 с
    // на запрос выстраивали все резолвы страницы в очередь).
    private val dnsExecutor: ExecutorService = Executors.newFixedThreadPool(DNS_WORKERS) { r ->
        Thread(r, "gw-dns-tcp").apply { isDaemon = true }
    }

    // ─── DNS-кэш и дедупликация ───
    // Каждый DNS-over-TCP запрос открывал НОВЫЙ SSH-канал, а нода — новое
    // TCP-соединение на публичный резолвер. Тысячи туннелей × повторяющиеся
    // запросы = 13Kpps TCP SYN на 8.8.8.8:53 → OVH забанил ноду как источник
    // DNS-флуда. Кэш (TTL из ответа) + дедупликация одинаковых запросов
    // в полёте срезают большую часть апстрим-трафика.
    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, Long>>()
    private val dnsInFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<ByteArray?>>()
    /** Одновременных DNS-over-TCP каналов не больше этого (остальные ждут/кэш). */
    private val dnsChannelCap = java.util.concurrent.Semaphore(24)
    /**
     * Предпочитаемый апстрим: локальный резолвер САМОЙ НОДЫ (systemd-resolved
     * на 127.0.0.53). Канал через SSH открывается к localhost ноды — наружные
     * запросы идут обычным UDP от её же резолвера с внутренним кэшем, и OVH
     * не видит TCP-флуда на публичные DNS. При неудаче — фолбэк на исходный
     * хост (8.8.8.8/1.1.1.1), флаг перевыставляется периодически.
     */
    @Volatile private var localResolverUsable = true
    private var localResolverLastTry = 0L
    private val connections = AtomicLong(0)

    /** Текущее число UDP-сессий (каждая держит поток + сокет + буфер). */
    private val udpSessions = java.util.concurrent.atomic.AtomicInteger(0)

    /** Подряд идущие неудачи открытия канала; сбрасывается на первом успехе. */
    private val openFailures = AtomicInteger(0)

    /**
     * Открывает direct-tcpip канал через текущий транспорт.
     *
     * Возвращает null при неудаче и, если похоже, что виноват не адрес, а сам
     * транспорт, дёргает [onTransportFailure] — сервис пересоберёт SSH. Без
     * этого сигнала мёртвое соединение никто не замечал: keepalive ждёт своей
     * минуты, а приложения всё это время получали отказы.
     *
     * [countsTowardDeath] = false — для DNS-over-TCP: неудачный резолв чаще
     * говорит о проблемах апстрим-резолвера, а не туннеля, и всплеск DNS-запросов
     * при пробуждении телефона не должен приговаривать только что собранный SSH.
     */
    private fun openChannel(
        host: String,
        port: Int,
        countsTowardDeath: Boolean = true,
    ): net.schmizz.sshj.connection.channel.direct.DirectConnection? {
        val transport = transportProvider()
        if (transport == null || !transport.isConnected) {
            Log.w(TAG, "open $host:$port — транспорт недоступен, просим переподключение")
            onTransportFailure()
            return null
        }
        return try {
            val ch = transport.openChannel(host, port)
            openFailures.set(0)
            ch
        } catch (e: Exception) {
            val failures = if (countsTowardDeath) openFailures.incrementAndGet() else openFailures.get()
            // Обрыв транспорта — приговор сразу. Одиночный отказ по адресу
            // (закрытый порт, недоступный хост) приговором быть не должен, иначе
            // туннель переподключался бы от каждого неудачного соединения.
            // А вот когда падает подряд DEAD_AFTER_FAILURES штук — соединение
            // мертво, даже если TCP об этом ещё не сообщил (типичная картина
            // после потери Wi-Fi: каждое открытие канала уходит в таймаут).
            val dead = e is net.schmizz.sshj.transport.TransportException ||
                !transport.isConnected ||
                (countsTowardDeath && failures >= DEAD_AFTER_FAILURES)
            Log.w(TAG, "open $host:$port failed (подряд=$failures, dead=$dead): $e")
            if (dead) {
                transport.markDead()
                onTransportFailure()
            }
            null
        }
    }

    @Volatile var port: Int = 0
        private set

    /** Живые CONNECT/UDP-сессии. Раньше это было отдельное поле, которое никто
     *  не обновлял, и статистика всегда показывала 0. */
    val activeConnections: Long get() = connections.get()

    /**
     * Транспорт заменён — счётчик подряд идущих отказов больше не относится к
     * делу. Без сброса пара «висячих» отказов старого соединения плюс один
     * честный отказ по адресу дали бы приговор уже живому транспорту.
     */
    fun onTransportReplaced() {
        openFailures.set(0)
    }

    fun start() {
        val ss = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        Log.i(TAG, "listening on 127.0.0.1:$port")
        Thread({
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    Log.d(TAG, "accepted ${client.remoteSocketAddress}")
                    executor.execute { handleClient(client) }
                } catch (_: IOException) {
                    break
                }
            }
        }, "gw-socks-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
        dnsExecutor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            if (input.readByte() != VERSION.toByte()) {
                Log.w(TAG, "bad socks version from ${socket.remoteSocketAddress}")
                return closeQuietly(socket)
            }
            val nMethods = input.readByte().toInt() and 0xFF
            var chosen = -1
            repeat(nMethods) {
                val m = input.readByte().toInt()
                if (m == 0x00 && chosen == -1) chosen = m
            }
            if (chosen != 0x00) {
                Log.w(TAG, "no acceptable auth method from ${socket.remoteSocketAddress}")
                output.write(byteArrayOf(VERSION.toByte(), 0xFF.toByte()))
                output.flush()
                return closeQuietly(socket)
            }
            output.write(byteArrayOf(VERSION.toByte(), 0x00))
            output.flush()

            socket.soTimeout = 0

            // Запрос: VER CMD RSV ATYP DST.ADDR DST.PORT (RFC 1928, §4).
            // ВАЖНО: у запроса СВОЙ байт версии — он идёт ПЕРЕД командой.
            // Раньше первый байт запроса (0x05) читался как CMD, не совпадал
            // ни с CONNECT (0x01), ни с UDP ASSOCIATE (0x03), и каждому
            // соединению уходил rep=0x07 «command not supported». Именно это
            // видно в hev-логах как сплошные "socks5 client res.rep 7":
            // туннель поднимался, а данные не шли ни по одному соединению.
            val reqVersion = input.readByte().toInt() and 0xFF
            if (reqVersion != VERSION) {
                Log.w(TAG, "bad request version $reqVersion from ${socket.remoteSocketAddress}")
                return replyAndClose(output, GENERAL_FAILURE, socket)
            }

            when (val cmd = input.readByte().toInt() and 0xFF) {
                CMD_CONNECT -> handleConnect(socket, input, output)
                CMD_UDP_ASSOCIATE -> handleUdpAssociate(socket, input, output)
                else -> {
                    // Тихий выход отсюда и был причиной «логов нет»: клиент
                    // получал ошибку, а в logcat не появлялось ни строки.
                    Log.w(TAG, "unsupported SOCKS command $cmd from ${socket.remoteSocketAddress}")
                    output.write(CMD_UNSUPPORTED)
                    output.flush()
                    closeQuietly(socket)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "socks session failed for ${socket.remoteSocketAddress}: $e")
            closeQuietly(socket)
        }
    }

    private fun handleConnect(socket: Socket, input: DataInputStream, output: DataOutputStream) {
        readSkipReserved(input)
        val target = readAddress(input)
        if (target == null) {
            Log.w(TAG, "CONNECT with unparsable address from ${socket.remoteSocketAddress}")
            return replyAndClose(output, GENERAL_FAILURE, socket)
        }
        Log.d(TAG, "CONNECT ${target.first}:${target.second}")

        val channel = openChannel(target.first, target.second)
            ?: return replyAndClose(output, GENERAL_FAILURE, socket)

        output.write(SUCCESS_REPLY)
        output.flush()

        connections.incrementAndGet()
        try {
            val chIn = channel.inputStream
            val chOut = channel.outputStream

            // Обе половины дуплекса живут независимо. Раньше выход из upload-цикла
            // по EOF от приложения сразу закрывал канал и сокет в finally — ответ
            // сервера, ещё летящий в обратную сторону, обрезался на полуслове.
            val upstream = Thread({
                try {
                    val buf = ByteArray(RELAY_BUFFER_BYTES)
                    val localIn = socket.getInputStream()
                    while (true) {
                        val n = localIn.read(buf)
                        if (n < 0) break
                        chOut.write(buf, 0, n)
                        chOut.flush()
                    }
                } catch (_: IOException) {
                } finally {
                    // EOF вверх по потоку — сообщаем его SSH-каналу, но канал не
                    // рвём: сервер ещё может досылать ответ.
                    runCatching { chOut.close() }
                }
            }, "gw-socks-up").apply { isDaemon = true }

            upstream.start()

            // Downstream крутим прямо в текущем воркере: на соединение уходит два
            // потока, а не три (на загрузке страницы это десятки соединений).
            try {
                val buf = ByteArray(RELAY_BUFFER_BYTES)
                val localOut = socket.getOutputStream()
                while (true) {
                    val n = chIn.read(buf)
                    if (n < 0) break
                    localOut.write(buf, 0, n)
                    localOut.flush()
                }
            } catch (_: IOException) {
            } finally {
                // Полузакрытие: приложение видит EOF, но может продолжать слать.
                runCatching { socket.shutdownOutput() }
            }

            // Ответ уже дочитан; на догрузку запроса даём ограниченное время:
            // иначе застрявший на read() upload-поток жил бы до конца сессии,
            // даже когда удалённая сторона давно закрылась.
            upstream.join(RELAY_DRAIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            connections.decrementAndGet()
            // Закрытие здесь же разблокирует upstream, если он всё ещё в read().
            runCatching { channel.close() }
            closeQuietly(socket)
        }
    }

    private fun handleUdpAssociate(socket: Socket, input: DataInputStream, output: DataOutputStream) {
        // Каждая сессия держит отдельный поток + сокет + буфер. Без лимита
        // QUIC-трафик приложений плодил десятки сессий: потоки/сокеты/буферы
        // накапливались до OutOfMemoryError в udp.receive (крэш целиком
        // убивал VPN). Лимит + idle-таймаут ограничивают объём: лишний
        // ASSOCIATE просто закрывается, клиент переустановит сессию.
        if (udpSessions.incrementAndGet() > MAX_UDP_SESSIONS) {
            udpSessions.decrementAndGet()
            Log.w(TAG, "UDP associate rejected: too many sessions")
            replyAndClose(output, GENERAL_FAILURE, socket)
            return
        }
        try {
            handleUdpAssociateInner(socket, input, output)
        } finally {
            udpSessions.decrementAndGet()
        }
    }

    private fun handleUdpAssociateInner(
        socket: Socket,
        input: DataInputStream,
        output: DataOutputStream,
    ) {
        readSkipReserved(input)
        readAddress(input)

        val udp = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        udp.reuseAddress = true
        udp.soTimeout = 1000

        val reply = byteArrayOf(
            VERSION.toByte(), 0x00, 0x00, ATYP_IPV4.toByte(),
            127, 0, 0, 1,
            (udp.localPort shr 8).toByte(), udp.localPort.toByte(),
        )
        output.write(reply)
        output.flush()

        connections.incrementAndGet()
        // 16КБ хватает с запасом: tun-MTU ≈1500, максимальный QUIC-датаграмма
        // ~1350. 64КБ на сессию × десятки сессий фрагментировали кучу до OOM.
        val buffer = ByteArray(16 * 1024)
        var lastActivity = System.nanoTime()
        try {
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    // Сессия без трафика дольше UDP_IDLE_TIMEOUT_MS освобождает
                    // поток и сокет (контрол-сокет прикладухи может не закрываться).
                    val idleMs = (System.nanoTime() - lastActivity) / 1_000_000
                    if (idleMs > UDP_IDLE_TIMEOUT_MS) {
                        Log.d(TAG, "UDP associate idle ${idleMs}ms — закрываю сессию")
                        break
                    }
                    continue
                } catch (_: IOException) {
                    break
                }
                lastActivity = System.nanoTime()

                val datagram = parseUdpDatagram(packet) ?: continue
                val replyTo = packet.socketAddress
                // Ответ ждём в отдельном воркере — цикл приёма остаётся свободным
                // и следующие датаграммы не стоят в очереди за этой.
                executor.execute {
                    val response = relayUdp(datagram)
                    if (response == null) {
                        Log.w(
                            TAG,
                            "UDP ${datagram.dstHost.hostAddress}:${datagram.dstPort} " +
                                "len=${datagram.payload.size} no relay path"
                        )
                        return@execute
                    }
                    val out = DatagramPacket(response, response.size, replyTo)
                    runCatching { udp.send(out) }
                }
            }
        } finally {
            connections.decrementAndGet()
            runCatching { udp.close() }
            closeQuietly(socket)
        }
    }

    private class UdpDatagram(
        val dstHost: InetAddress,
        val dstPort: Int,
        val payload: ByteArray,
    )

    private fun parseUdpDatagram(packet: DatagramPacket): UdpDatagram? {
        try {
            val data = packet.data
            val offset = packet.offset
            val len = packet.length
            if (len < 10) return null
            if (data[offset + 2].toInt() != 0) return null
            var pos = offset + 3
            val atyp = data[pos].toInt(); pos += 1
            val host: InetAddress
            when (atyp) {
                ATYP_IPV4 -> {
                    host = InetAddress.getByAddress(data.copyOfRange(pos, pos + 4)); pos += 4
                }
                ATYP_IPV6 -> {
                    host = InetAddress.getByAddress(data.copyOfRange(pos, pos + 16)); pos += 16
                }
                else -> {
                    Log.w(TAG, "UDP datagram with unsupported ATYP $atyp dropped")
                    return null
                }
            }
            val port = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val payloadLen = offset + len - pos
            if (payloadLen <= 0) return null
            val payload = data.copyOfRange(pos, pos + payloadLen)
            return UdpDatagram(host, port, payload)
        } catch (_: Exception) {
            return null
        }
    }

    private fun relayUdp(dgram: UdpDatagram): ByteArray? {
        if (dnsOverTcp && dgram.dstPort == DNS_PORT && dgram.payload.isNotEmpty()) {
            val dstHost = dgram.dstHost.hostAddress ?: "8.8.8.8"
            var future: java.util.concurrent.CompletableFuture<ByteArray?>? = null
            val key = dstHost + ":" + java.util.Arrays.hashCode(dgram.payload)
            // Кэш: повторные запросы (основная масса DNS-трафика) не создают
            // новых SSH-каналов и TCP-соединений на апстрим.
            dnsCache[key]?.let { entry ->
                if (entry.second > System.currentTimeMillis()) return entry.first
                dnsCache.remove(key, entry)
            }
            // Дедупликация: идентичный запрос уже в полёте — ждём его результат,
            // вместо второго параллельного апстрим-соединения.
            while (true) {
                val inFlight = dnsInFlight[key]
                if (inFlight != null) {
                    return try {
                        inFlight.get(DNS_TCP_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        null
                    }
                }
                val leader = java.util.concurrent.CompletableFuture<ByteArray?>()
                if (dnsInFlight.putIfAbsent(key, leader) == null) {
                    future = leader
                    break
                }
            }
            try {
                val response = try {
                    dnsExecutor.submit(
                        java.util.concurrent.Callable<ByteArray?> {
                            resolveDnsOverTcp(dstHost, dgram.payload)
                        }
                    ).get(DNS_TCP_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                } finally {
                    dnsInFlight.remove(key)
                }
                if (response != null) {
                    dnsCache[key] = response to System.currentTimeMillis() + dnsResponseTtl(response)
                }
                future.complete(response)
                return response
            } catch (e: Exception) {
                Log.w(TAG, "DNS-TCP $dstHost timed out or failed: $e")
                future.complete(null)
                return null
            }
        }
        return null
    }

    /**
     * Грубый парсер TTL из секции Answer (для кэша). Нестандартный ответ —
     * безопасный дефолт 15 секунд.
     */
    private fun dnsResponseTtl(response: ByteArray): Long {
        return try {
            var pos = 12
            while (response[pos].toInt() != 0) pos += 1 + response[pos]
            pos += 5 // нулевой байт + QTYPE + QCLASS
            var ttl = Long.MAX_VALUE
            var answers = ((response[pos].toInt() and 0xFF) shl 8) or (response[pos + 1].toInt() and 0xFF)
            pos += 2
            while (answers-- > 0 && pos + 10 <= response.size) {
                if (response[pos].toInt() and 0xC0 == 0xC0) pos += 2 else {
                    while (response[pos].toInt() != 0) pos += 1 + response[pos]
                    pos += 1
                }
                pos += 8 // TYPE + CLASS
                val recTtl = ((response[pos].toLong() and 0xFF) shl 24) or
                    ((response[pos + 1].toLong() and 0xFF) shl 16) or
                    ((response[pos + 2].toLong() and 0xFF) shl 8) or
                    (response[pos + 3].toLong() and 0xFF)
                if (recTtl in 1 until ttl) ttl = recTtl
                pos += 4 + (((response[pos + 8].toInt() and 0xFF) shl 8) or (response[pos + 9].toInt() and 0xFF))
            }
            if (ttl == Long.MAX_VALUE) 15_000L else (ttl.coerceIn(1, 300) * 1000)
        } catch (_: Exception) {
            15_000L
        }
    }

    private fun resolveDnsOverTcp(host: String, query: ByteArray): ByteArray? {
        // Предпочитаем резолвер самой ноды: меньше внешних TCP и внутренний
        // кэш systemd-resolved. Упал (не все ноды имеют локальный резолвер) —
        // 2 минуты держим фолбэк на исходный хост.
        var upstream = host
        if (localResolverUsable || System.currentTimeMillis() - localResolverLastTry > 120_000) {
            upstream = "127.0.0.53"
        }
        // Лимит одновременных DNS-каналов: даже без кэша исходящий поток
        // ограничен (семантика Semaphore — остальные ждут освобождения).
        dnsChannelCap.tryAcquire(DNS_TCP_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            val result = resolveDnsOverTcpInner(upstream, query)
            if (upstream == "127.0.0.53") {
                if (result != null) localResolverUsable = true
                else {
                    localResolverUsable = false
                    localResolverLastTry = System.currentTimeMillis()
                    Log.w(TAG, "DNS-TCP: локальный резолвер 127.0.0.53 не ответил — фолбэк на публичный при следующих запросах")
                }
            }
            return result
        } finally {
            dnsChannelCap.release()
        }
    }

    private fun resolveDnsOverTcpInner(host: String, query: ByteArray): ByteArray? {
        val channel = openChannel(host, DNS_PORT, countsTowardDeath = false) ?: return null
        try {
            val chOut = DataOutputStream(channel.outputStream)
            val chIn = DataInputStream(channel.inputStream)
            chOut.writeShort(query.size)
            chOut.write(query)
            chOut.flush()

            val lenHi = chIn.read()
            val lenLo = chIn.read()
            if (lenHi < 0 || lenLo < 0) {
                Log.w(TAG, "DNS-TCP $host closed before response")
                return null
            }
            val respLen = (lenHi shl 8) or lenLo
            if (respLen <= 0 || respLen > 65535) return null
            val response = ByteArray(respLen)
            chIn.readFully(response)
            return buildUdpResponse(host, DNS_PORT, response)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { channel.close() }
        }
    }

    private fun buildUdpResponse(host: String, port: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0))
        val addr = InetAddress.getByName(host)
        if (addr.address.size == 4) {
            out.write(ATYP_IPV4)
            out.write(addr.address)
        } else {
            out.write(ATYP_IPV6)
            out.write(addr.address)
        }
        out.write((port shr 8) and 0xFF)
        out.write(port and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** Consumes the single RSV byte between CMD and ATYP. */
    private fun readSkipReserved(input: DataInputStream) {
        input.readByte()
    }

    private fun readAddress(input: DataInputStream): Pair<String, Int>? {
        return try {
            when (input.readByte().toInt()) {
                ATYP_IPV4 -> {
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress to readPort(input)
                }
                ATYP_IPV6 -> {
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress to readPort(input)
                }
                ATYP_DOMAIN -> {
                    val len = input.readByte().toInt() and 0xFF
                    val domain = ByteArray(len)
                    input.readFully(domain)
                    String(domain, Charsets.US_ASCII) to readPort(input)
                }
                else -> null
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun readPort(input: DataInputStream): Int {
        val hi = input.readUnsignedByte()
        val lo = input.readUnsignedByte()
        return (hi shl 8) or lo
    }

    private fun replyAndClose(output: DataOutputStream, reply: ByteArray, socket: Socket) {
        runCatching {
            output.write(reply)
            output.flush()
        }
        closeQuietly(socket)
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }
}

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
import java.util.concurrent.atomic.AtomicLong

class GwSocksServer(
    private val transport: GwSshTransport,
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
        private const val DNS_PORT = 53
        private const val DNS_TCP_TIMEOUT_MS = 5000
        private val SUCCESS_REPLY = byteArrayOf(VERSION.toByte(), 0x00, 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
        private val GENERAL_FAILURE = byteArrayOf(VERSION.toByte(), 0x01, 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
        private val CMD_UNSUPPORTED = byteArrayOf(VERSION.toByte(), 0x07, 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
    }

    private var serverSocket: ServerSocket? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "gw-socks-worker").apply { isDaemon = true }
    }
    // Serial DNS-over-TCP worker + bounded wait in relayUdp keep a stuck
    // resolver from accumulating blocked threads and silent failures.
    private val dnsExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gw-dns-tcp").apply { isDaemon = true }
    }
    private val connections = AtomicLong(0)

    @Volatile var port: Int = 0
        private set

    @Volatile var activeConnections: Long = 0
        private set

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

            when (input.readByte().toInt()) {
                CMD_CONNECT -> handleConnect(socket, input, output)
                CMD_UDP_ASSOCIATE -> handleUdpAssociate(socket, input, output)
                else -> {
                    output.write(CMD_UNSUPPORTED)
                    output.flush()
                    closeQuietly(socket)
                }
            }
        } catch (_: Exception) {
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

        val channel = try {
            transport.openChannel(target.first, target.second)
        } catch (e: Exception) {
            Log.w(TAG, "CONNECT ${target.first}:${target.second} channel failed: $e")
            return replyAndClose(output, GENERAL_FAILURE, socket)
        }

        output.write(SUCCESS_REPLY)
        output.flush()

        connections.incrementAndGet()
        try {
            val chIn = channel.inputStream
            val chOut = channel.outputStream
            GwSshTransport.pipe(chIn, socket.getOutputStream()) {
                runCatching { socket.shutdownOutput() }
            }
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = try {
                    socket.getInputStream().read(buf)
                } catch (_: IOException) {
                    -1
                }
                if (n < 0) break
                chOut.write(buf, 0, n)
                chOut.flush()
            }
        } catch (_: IOException) {
        } finally {
            connections.decrementAndGet()
            runCatching { channel.close() }
            closeQuietly(socket)
        }
    }

    private fun handleUdpAssociate(socket: Socket, input: DataInputStream, output: DataOutputStream) {
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
        try {
            val buffer = ByteArray(65536)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }

                val datagram = parseUdpDatagram(packet) ?: continue
                val response = relayUdp(datagram)
                if (response == null) {
                    Log.w(
                        TAG,
                        "UDP ${datagram.dstHost.hostAddress}:${datagram.dstPort} " +
                            "len=${datagram.payload.size} no relay path"
                    )
                    continue
                }
                val out = DatagramPacket(response, response.size, packet.socketAddress)
                runCatching { udp.send(out) }
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
            // Bounded: an unresponsive remote resolver must not pin this UDP
            // control session forever with a blocked channel read.
            val future = dnsExecutor.submit {
                resolveDnsOverTcp(dgram.dstHost.hostAddress ?: "8.8.8.8", dgram.payload)
            }
            return try {
                future.get(DNS_TCP_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                future.cancel(true)
                Log.w(TAG, "DNS-TCP ${dgram.dstHost.hostAddress} timed out or failed: $e")
                null
            }
        }
        return null
    }

    private fun resolveDnsOverTcp(host: String, query: ByteArray): ByteArray? {
        val channel = try {
            transport.openChannel(host, DNS_PORT)
        } catch (e: Exception) {
            Log.w(TAG, "DNS-TCP $host channel failed: $e")
            return null
        }
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

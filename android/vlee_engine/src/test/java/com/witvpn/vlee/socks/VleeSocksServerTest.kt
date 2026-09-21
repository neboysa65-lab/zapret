package com.witvpn.vlee.socks

import java.net.Socket
import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test

class VleeSocksServerTest {
    @Test fun stopClosesStalledHandshakesAndWorkers() {
        repeat(3) {
            val server = VleeSocksServer({ null })
            server.start()
            val clients = (1..150).map { Socket("127.0.0.1", server.port).apply { soTimeout = 3000 } }
            try {
                server.stop()
                clients.forEach {
                    try {
                        assertEquals(-1, it.getInputStream().read())
                    } catch (error: SocketException) {
                        // Closing the listener resets connections still in its backlog.
                        assertTrue(error.message.orEmpty().contains("reset", ignoreCase = true))
                    }
                }
                eventually { Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "vlee-socks-worker" } }
            } finally {
                clients.forEach { it.close() }
                server.stop()
            }
        }
    }

    @Test fun closingUdpControlSocketReleasesAssociation() {
        val server = VleeSocksServer({ null })
        server.start()
        try {
            Socket("127.0.0.1", server.port).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write(byteArrayOf(5, 1, 0))
                val input = java.io.DataInputStream(client.getInputStream())
                assertEquals(5, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                client.getOutputStream().write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                val reply = ByteArray(10)
                input.readFully(reply)
                assertEquals(0, reply[1].toInt())
                assertEquals(1, server.activeConnections)
            }
            eventually { server.activeConnections == 0L }
        } finally { server.stop() }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
}

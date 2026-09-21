package com.witvpn.vlee.socks

import java.util.Base64

internal class DnsResponseCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val response: ByteArray, val storedAt: Long, val ttl: Long, val offsets: List<Int>)
    private val entries = LinkedHashMap<String, Entry>(128, 0.75f, true)

    @Synchronized fun clear() = entries.clear()

    @Synchronized fun get(key: String): ByteArray? {
        val entry = entries[key] ?: return null
        val age = (now() - entry.storedAt).coerceAtLeast(0) / 1000
        if (age >= entry.ttl) {
            entries.remove(key)
            return null
        }
        return entry.response.copyOf().also { result ->
            for (offset in entry.offsets) {
                val ttl = (u32(result, offset) - age).coerceAtLeast(0)
                for (i in 0..3) result[offset + i] = (ttl shr (24 - i * 8)).toByte()
            }
        }
    }

    @Synchronized fun put(key: String, response: ByteArray) {
        if (response.size !in 12..4096 || response[2].toInt() and 0x82 != 0x80 ||
            response[3].toInt() and 0x0f != 0 || u16(response, 6) == 0) return
        try {
            var pos = 12
            repeat(u16(response, 4)) { pos = skipName(response, pos) + 4 }
            val offsets = mutableListOf<Int>()
            repeat(u16(response, 6) + u16(response, 8) + u16(response, 10)) {
                pos = skipName(response, pos)
                if (u16(response, pos) != 41) offsets.add(pos + 4) // OPT's field is not a TTL.
                pos += 10 + u16(response, pos + 8)
                require(pos <= response.size)
            }
            val ttl = offsets.minOfOrNull { u32(response, it) }?.coerceAtMost(300) ?: return
            if (ttl == 0L) return
            entries[key] = Entry(response.copyOf(), now(), ttl, offsets)
            while (entries.size > 128) entries.remove(entries.keys.first())
        } catch (_: IndexOutOfBoundsException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    companion object {
        fun key(host: String, query: ByteArray): String =
            host + ":" + Base64.getEncoder().encodeToString(query.copyOfRange(2, query.size))

        fun withId(response: ByteArray, query: ByteArray): ByteArray = response.copyOf().also {
            it[0] = query[0]
            it[1] = query[1]
        }

        private fun u16(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)

        private fun u32(data: ByteArray, offset: Int): Long =
            (0..3).fold(0L) { value, i -> (value shl 8) or (data[offset + i].toLong() and 255) }

        private fun skipName(data: ByteArray, start: Int): Int {
            var pos = start
            while (true) {
                val length = data[pos++].toInt() and 255
                if (length == 0) return pos
                if (length and 0xc0 == 0xc0) {
                    require(pos < data.size)
                    return pos + 1
                }
                require(length <= 63)
                pos += length
            }
        }
    }
}

package com.witvpn.vlee.socks

import org.junit.Assert.*
import org.junit.Test

class DnsResponseCacheTest {
    private fun response() = byteArrayOf(
        1, 2, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0,
        1, 97, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1,
        0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 30, 0, 4, 1, 2, 3, 4,
    )

    @Test fun idsDoNotDefeatCacheOrDeduplication() {
        val first = response()
        val second = first.copyOf().apply { this[0] = 5; this[1] = 6 }
        assertEquals(DnsResponseCache.key("192.0.2.1", first), DnsResponseCache.key("192.0.2.1", second))
        assertNotEquals(DnsResponseCache.key("192.0.2.2", first), DnsResponseCache.key("192.0.2.1", second))
        assertArrayEquals(second, DnsResponseCache.withId(first, second))
        assertEquals(1, first[0].toInt())
    }

    @Test fun respectsAndDecrementsDnsTtl() {
        var now = 0L
        val cache = DnsResponseCache { now }
        cache.put("key", response())
        assertEquals(30, cache.get("key")!![33].toInt())
        now = 5000
        assertEquals(25, cache.get("key")!![33].toInt())
        now = 30000
        assertNull(cache.get("key"))
    }

    @Test fun memoryIsBoundedAndBadResponsesAreNotCached() {
        val cache = DnsResponseCache()
        repeat(150) { cache.put("$it", response()) }
        assertNull(cache.get("0"))
        assertNotNull(cache.get("149"))
        cache.put("zero", response().apply { this[33] = 0 })
        assertNull(cache.get("zero"))
        cache.put("truncated", response().apply { this[2] = 0x83.toByte() })
        assertNull(cache.get("truncated"))
        repeat(100) { cache.put("bad", ByteArray(it)) }
        assertNull(cache.get("bad"))
        cache.clear()
        assertNull(cache.get("149"))
    }
}

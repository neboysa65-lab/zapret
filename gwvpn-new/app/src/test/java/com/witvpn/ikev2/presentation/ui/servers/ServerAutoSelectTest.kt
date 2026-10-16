package com.witvpn.ikev2.presentation.ui.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAutoSelectTest {

    private data class Node(val id: String, val load: Int?)

    private fun pick(nodes: List<Node>, current: Node?) =
        ServerAutoSelect.pickBest(nodes, current, keyOf = { it.id }, loadOf = { it.load })

    @Test
    fun `picks the least loaded server`() {
        val nodes = listOf(Node("de", 40), Node("pl", 10), Node("fi", 25))
        assertEquals("pl", pick(nodes, null)?.id)
    }

    @Test
    fun `keeps current server when it is within the switch margin`() {
        val nodes = listOf(Node("de", 14), Node("pl", 10))
        assertEquals("de", pick(nodes, Node("de", 14))?.id)
    }

    @Test
    fun `switches when current server is clearly busier`() {
        val nodes = listOf(Node("de", 60), Node("pl", 10))
        assertEquals("pl", pick(nodes, Node("de", 60))?.id)
    }

    @Test
    fun `without load data keeps current or falls back to first`() {
        val nodes = listOf(Node("de", null), Node("pl", null))
        assertEquals("pl", pick(nodes, Node("pl", null))?.id)
        assertEquals("de", pick(nodes, Node("gone", null))?.id)
        assertEquals("de", pick(nodes, null)?.id)
    }

    @Test
    fun `ignores servers without load when others have it`() {
        val nodes = listOf(Node("de", null), Node("pl", 70), Node("fi", 30))
        assertEquals("fi", pick(nodes, null)?.id)
    }

    @Test
    fun `empty list gives nothing`() {
        assertNull(pick(emptyList(), null))
    }
}

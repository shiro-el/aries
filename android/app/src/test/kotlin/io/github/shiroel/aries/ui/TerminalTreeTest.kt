package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Test

class TerminalTreeTest {
    private fun terminal(address: String, parent: String? = null) =
        TerminalInfo(address, address, "", emptyList(), null, "live", parent = parent)

    @Test fun ordersSiblingsByWholeAddressAndKeepsCyclesAndOrphansVisible() {
        val input = listOf(terminal("t3", "t1"), terminal("t2", "t1"), terminal("t1"),
            terminal("orphan", "missing"), terminal("a", "b"), terminal("b", "a"))
        val rows = terminalTree(input) { it }
        assertEquals(listOf("t1", "t2", "t3", "orphan", "a", "b"), rows.map { it.value.address })
        assertEquals(listOf("", "├─", "└─", "", "", "└─"), rows.map { it.guide })
        assertEquals(input.size, rows.size)
    }

    @Test fun agentFamilyIncludesPlainParentsAndNeverConflatesMachineAddresses() {
        val host = Host("h1", "host", Endpoint.Ssh("host", 22u, "me"), null)
        val input = listOf(terminal("t1"), terminal("box/t1"), terminal("box/t2", "box/t1").copy(agentStatus = AgentStatus.Working))
        assertEquals(listOf("box/t1", "box/t2"), agentFamily(host, input).map { it.terminal.address })
    }

    @Test fun deepChainsDoNotDropRowsOrOverflow() {
        val input = listOf(terminal("t0")) + (1..100).map { terminal("t$it", "t${it - 1}") }
        val rows = terminalTree(input) { it }
        assertEquals(input.size, rows.size)
        assertTrue(rows.all { it.depth <= 32 })
    }
    @Test fun hostNamespacesKeepFamiliesSeparateInGlobalAgentOrder() {
        val h1 = Host("h1", "first", Endpoint.Ssh("host", 22u, "me"), null)
        val h2 = h1.copy(id = "h2", name = "second")
        val input = listOf(AgentEntry(h1, terminal("t2", "t1")), AgentEntry(h2, terminal("t1")), AgentEntry(h1, terminal("t1")))
        val rows = terminalTree(input, namespace = { it.host.id }) { it.terminal }
        assertEquals(listOf("h2/t1", "h1/t1", "h1/t2"), rows.map { "${it.value.host.id}/${it.value.terminal.address}" })
        assertEquals(listOf(0, 0, 1), rows.map { it.depth })
    }

}

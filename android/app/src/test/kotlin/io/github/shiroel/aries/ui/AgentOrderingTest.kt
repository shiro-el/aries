package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Test

/** Literal case table from herdr-app defaults/mods/agents/main.js. */
class AgentOrderingTest {
    private val host = Host("h1", "home", Endpoint.Ssh("host", 22u, "me"), null)
    private fun agent(id: String, status: AgentStatus, pinned: Boolean = false, parent: String? = null) =
        TerminalInfo(id, id, "", emptyList(), null, "live", agentStatus = status, pinned = pinned, parent = parent)

    @Test fun matchesHerdrPriorityCaseTable() {
        data class Case(val entries: List<TerminalInfo>, val order: List<String>)
        val cases = listOf(
            Case(listOf(agent("waiting", AgentStatus.Error(null)), agent("pin", AgentStatus.Idle, pinned = true)), listOf("pin", "waiting")),
            Case(listOf(agent("idle", AgentStatus.Idle), agent("work", AgentStatus.Working), agent("done", AgentStatus.Done), agent("blocked", AgentStatus.Blocked(null, null))), listOf("blocked", "done", "work", "idle")),
            Case(listOf(agent("done", AgentStatus.Done), agent("parent", AgentStatus.Working), agent("child", AgentStatus.Error(null), parent = "parent")), listOf("parent", "child", "done")),
            Case(listOf(agent("parent", AgentStatus.Idle), agent("child", AgentStatus.Done, parent = "parent"), agent("work", AgentStatus.Working)), listOf("work", "parent", "child")),
            Case(listOf(agent("first", AgentStatus.Working), agent("second", AgentStatus.Working)), listOf("second", "first")),
            Case(listOf(agent("newer", AgentStatus.Working).copy(statusMs = 2_000uL), agent("older", AgentStatus.Working).copy(statusMs = 1_000uL)), listOf("newer", "older")),
        )
        cases.forEach { case ->
            val board = AgentBoard(listOf(host)).apply { update(host.id, case.entries) }
            assertEquals(case.order, board.agents.map { it.terminal.address })
        }
    }

    @Test fun groupedViewUsesSpaceTabPaneOrderRatherThanTerminalCreationOrder() {
        val a = agent("a", AgentStatus.Error(null))
        val b = agent("b", AgentStatus.Idle)
        val c = agent("c", AgentStatus.Working)
        val board = AgentBoard(listOf(host)).apply {
            update(host.id, listOf(a, b, c))
            updateStructure(host.id, HostStructure(listOf(
                SpaceInfo("first", "First", null, listOf(
                    TabInfo("tab1", "one", listOf(PaneInfo("pb", "b", b))),
                    TabInfo("tab2", "two", listOf(PaneInfo("pa", "a", a))),
                )),
                SpaceInfo("second", "Second", null, listOf(TabInfo("tab3", "three", listOf(PaneInfo("pc", "c", c))))),
                ), emptyList(), null))
            view = AgentListView.Grouped
        }
        assertEquals(listOf("b", "a", "c"), board.agents.map { it.terminal.address })
        assertEquals(listOf("First", "Second"), agentGroups(board.agents, board.view).map { it.space?.name })
        assertEquals(board.agents, orderAgents(board.agents, board.view))
    }

    @Test fun familiesIncludeLivePlainChildrenForTheFoldCountAndExcludeFinishedOnes() {
        val parent = agent("parent", AgentStatus.Working)
        val plain = parent.copy(address = "plain", agentStatus = null, parent = "parent")
        val finished = plain.copy(address = "finished", state = "finished")
        val board = AgentBoard(listOf(host)).apply { update(host.id, listOf(parent, plain, finished)) }
        assertEquals(listOf("parent", "plain"), board.agents.map { it.terminal.address })
        assertEquals(board.agents, orderAgents(board.agents))
    }
}

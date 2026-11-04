package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression: leaving Changes during a refresh must not cache the cancelled, incomplete fetch. */
@RunWith(RobolectricTestRunner::class)
class TasksChangesTest {
    /** Regression D47: turn increments at completion, so entering Working must refresh even with the same turn. */
    @Test fun startingWorkDoesNotReuseThePreviousCompletedResult() = runBlocking {
        val done = TerminalInfo("t1", "Agent", "", listOf("codex"), "/repo", "live", AgentStatus.Done)
        val connection = FakeConnection(listOf(done), HostStructure(emptyList(), listOf(done), null))
        val changes = TasksChangesState()
        changes.load(connection, done)
        changes.load(connection, done.copy(agentStatus = AgentStatus.Working))
        assertEquals(2, connection.gitChangesRequests)
    }

    @Test fun cancelledRefreshIsRetriedOnTheNextVisit() = runBlocking {
        val terminal = TerminalInfo("t1", "Agent", "", listOf("codex"), "/repo", "live", AgentStatus.Done)
        val connection = FakeConnection(listOf(terminal), HostStructure(emptyList(), listOf(terminal), null)).apply {
            gitFiles = GitChanges("/repo", listOf(GitFile("x", null, " M", false, true, false)), false, false)
            gitPatch = GitDiff(listOf(GitDiffLine("+hello", null, 1u)), false, false)
            gitTotals = GitStats(1u, 0u, false, false)
        }
        val changes = TasksChangesState()
        changes.load(connection, terminal)
        assertEquals(TaskChangeStats(1, 0), changes.stats)
        connection.gitStatsHold = CompletableDeferred()
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { changes.load(connection, terminal, force = true) }
        refresh.cancelAndJoin()
        connection.gitStatsHold = null
        changes.load(connection, terminal)
        assertEquals(TaskChangeStats(1, 0), changes.stats)
        assertEquals(3, connection.gitChangesRequests)
    }

    /** D47: a badge must be invalid before a replacement connection's delayed refresh completes. */
    @Test fun reconnectInvalidatesTheCompletedBadgeImmediately() = runBlocking {
        val terminal = TerminalInfo("t1", "Agent", "", listOf("codex"), "/repo", "live", AgentStatus.Done)
        val old = FakeConnection(listOf(terminal), HostStructure(emptyList(), listOf(terminal), null))
        val replacement = FakeConnection(listOf(terminal), HostStructure(emptyList(), listOf(terminal), null)).apply {
            gitChangesHold = CompletableDeferred()
        }
        val changes = TasksChangesState()
        changes.load(old, terminal)
        assertTrue(changes.matches(terminal, old))
        assertFalse(changes.matches(terminal, null))
        assertFalse(changes.matches(terminal, replacement))
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { changes.load(replacement, terminal) }
        assertNull(changes.stats)
        assertFalse(changes.matches(terminal, replacement))
        refresh.cancelAndJoin()
    }

    /** D47: a Working transition seen offscreen must invalidate even if status later returns to Done at the same turn. */
    @Test fun statusFeedInvalidatesChangesWhileAnotherTabIsOpen() = runBlocking {
        val done = TerminalInfo("t1", "Agent", "", listOf("codex"), "/repo", "live", AgentStatus.Done)
        val conn = FakeConnection(listOf(done), HostStructure(emptyList(), listOf(done), null))
        val changes = TasksChangesState()
        changes.load(conn, done)
        changes.updateTerminal(done.copy(agentStatus = AgentStatus.Working))
        changes.updateTerminal(done)
        assertFalse(changes.matches(done, conn))
        changes.load(conn, done)
        assertEquals(2, conn.gitChangesRequests)
    }

    /** D48: a response from the old cwd must not overwrite a newer folder's result. */
    @Test fun delayedOldFolderResponseCannotReplaceTheNewFolder() = runBlocking {
        val terminal = TerminalInfo("t1", "Agent", "", listOf("codex"), "/old", "live", AgentStatus.Done)
        val conn = FakeConnection(listOf(terminal), HostStructure(emptyList(), listOf(terminal), null)).apply {
            gitFiles = GitChanges("/old", emptyList(), false, false)
            gitChangesHold = CompletableDeferred()
        }
        val changes = TasksChangesState()
        val held = conn.gitChangesHold!!
        val old = launch(start = CoroutineStart.UNDISPATCHED) { changes.load(conn, terminal) }
        conn.gitChangesHold = null
        conn.gitFiles = GitChanges("/new", emptyList(), false, false)
        val moved = terminal.copy(cwd = "/new")
        changes.load(conn, moved)
        held.complete(Unit)
        old.join()
        assertEquals("/new", changes.changes?.cwd)
        assertTrue(changes.matches(moved, conn))
    }
}

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
    @Test fun cancelledRefreshIsRetriedOnTheNextVisit() = runBlocking {
        val terminal = TerminalInfo("t1", "Agent", "", listOf("codex"), "/repo", "live", AgentStatus.Done)
        val connection = FakeConnection(listOf(terminal), HostStructure(emptyList(), listOf(terminal), null)).apply {
            gitFiles = GitChanges("/repo", listOf(GitFile("x", null, " M", false, true, false)), false, false)
            gitPatch = GitDiff(listOf(GitDiffLine("+hello", null, 1u)), false, false)
        }
        val changes = TasksChangesState()
        changes.load(connection, terminal)
        assertEquals(TaskChangeStats(1, 0), changes.stats)
        connection.gitDiffHold = CompletableDeferred()
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { changes.load(connection, terminal, force = true) }
        refresh.cancelAndJoin()
        connection.gitDiffHold = null
        changes.load(connection, terminal)
        assertEquals(TaskChangeStats(1, 0), changes.stats)
        assertEquals(3, connection.gitChangesRequests)
    }
}

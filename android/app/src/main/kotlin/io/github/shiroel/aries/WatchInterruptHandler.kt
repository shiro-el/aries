package io.github.shiroel.aries

import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.InterruptOutcome
import io.github.shiroel.aries.weardata.OpenWatchAgent
import io.github.shiroel.aries.weardata.WatchInterruptResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A short, noninteractive connection. No queue and no retry after an uncertain reply. */
internal class WatchInterruptHandler(private val core: AriesCoreInterface) {
    private val sending = Mutex()

    suspend fun interrupt(command: OpenWatchAgent): WatchInterruptResult {
        if (!sending.tryLock()) return WatchInterruptResult.BUSY
        try {
            return withTimeout(20_000) {
                if (!core.canAnswerInBackground(command.hostId)) return@withTimeout WatchInterruptResult.OPEN_PHONE
                val connection = core.connect(command.hostId)
                try {
                    val agent = connection.terminals().firstOrNull { it.address == command.terminal }
                    if (agent?.state != "live" || agent.agentStatus != AgentStatus.Working) {
                        WatchInterruptResult.NOT_WORKING
                    } else when (connection.interruptAgent(command.terminal)) {
                        // interruptAgent rechecks terminal.get and reuses herdr-core's stop-key policy.
                        InterruptOutcome.STOPPED -> WatchInterruptResult.STOPPED
                        InterruptOutcome.EXITED -> WatchInterruptResult.EXITED
                        InterruptOutcome.STILL_WORKING -> WatchInterruptResult.STILL_WORKING
                        InterruptOutcome.UNKNOWN -> WatchInterruptResult.UNKNOWN
                        InterruptOutcome.NOT_WORKING -> WatchInterruptResult.NOT_WORKING
                    }
                } finally {
                    withContext(NonCancellable) {
                        try { connection.disconnect() } finally { connection.close() }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            return WatchInterruptResult.UNKNOWN
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return WatchInterruptResult.UNKNOWN
        } finally { sending.unlock() }
    }
}

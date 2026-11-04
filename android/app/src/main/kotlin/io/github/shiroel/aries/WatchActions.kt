package io.github.shiroel.aries

import android.content.Context
import io.github.shiroel.aries.core.*
import io.github.shiroel.aries.weardata.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object WatchActions {
    private fun hidden(context: Context): Boolean = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE).let {
        it.getBoolean("enabled", false) && it.getBoolean("hide_previews", true)
    }

    private suspend fun row(core: AriesCoreInterface, connection: HostConnection, target: OpenWatchAgent): WatchAgent? {
        val state = LastKnownState(target.hostId, connection.terminals(), System.currentTimeMillis().toULong())
        return WatchPhone.summary(core.hosts(), listOf(state)).inbox.firstOrNull {
            it.hostId == target.hostId && it.terminal == target.terminal
        }
    }

    private suspend fun <T> connected(core: AriesCoreInterface, host: String, block: suspend (HostConnection) -> T): T {
        val connection = core.connect(host)
        try { return block(connection) }
        finally { withContext(NonCancellable) { try { connection.disconnect() } finally { connection.close() } } }
    }

    private suspend fun liveEntries(connection: HostConnection, target: OpenWatchAgent): List<ChatEntry> {
        val snapshot = connection.readChat(target.terminal)
        check(snapshot.status in listOf(ChatStatus.LIVE, ChatStatus.ENDED, ChatStatus.WAITING))
        return snapshot.entries
    }

    suspend fun details(context: Context, core: AriesCoreInterface, target: OpenWatchAgent): WatchAgent? {
        if (hidden(context) || !core.canAnswerInBackground(target.hostId)) return null
        return withTimeout(45_000) { connected(core, target.hostId) { connection ->
            val row = row(core, connection, target) ?: return@connected null
            val detailed = WatchPhone.withEntries(row, liveEntries(connection, target))
            if (hidden(context) || !core.canAnswerInBackground(target.hostId)) null else detailed
        } }
    }

    suspend fun execute(context: Context, core: AriesCoreInterface, command: WatchCommand): WatchResult {
        var dispatched = false
        try {
            if (hidden(context)) return WatchResult.PHONE
            if (!core.canAnswerInBackground(command.agent.hostId)) return WatchResult.LOCKED
            val target = OpenWatchAgent(command.agent.hostId, command.agent.terminal)
            return connected(core, target.hostId) { connection ->
                val current = row(core, connection, target) ?: return@connected WatchResult.CHANGED
                if (current.stateToken != command.agent.stateToken || command.agent.stateToken.isBlank()) return@connected WatchResult.CHANGED
                if (command.text.isNotBlank()) {
                    if (current.status !in listOf("done", "error") || command.choice != -1) return@connected WatchResult.CHANGED
                    if (command.text.length > 4096) return@connected WatchResult.FAILED
                    if (hidden(context) || !core.canAnswerInBackground(target.hostId)) return@connected WatchResult.LOCKED
                    dispatched = true
                    return@connected if (connection.sendMessage(target.terminal, command.text,
                            object : SendListener { override fun onHeld() {} }) == SendOutcome.Sent)
                        WatchResult.SENT else WatchResult.CHANGED
                }
                if (current.status != "blocked" || current.requestKind != command.agent.requestKind) return@connected WatchResult.CHANGED
                val detail = WatchPhone.withEntries(current, liveEntries(connection, target))
                if (detail.requestId.isBlank() || detail.requestId != command.agent.requestId) return@connected WatchResult.CHANGED
                if (!detail.canAnswerOnWatch) return@connected WatchResult.PHONE
                if (command.choice !in detail.choices.indices) return@connected WatchResult.FAILED
                if (detail.requestKind == "permission" && detail.choices[command.choice] !in listOf("Allow", "Deny")) return@connected WatchResult.PHONE
                // The Beacon re-reads the menu identity immediately before pressing its keys.
                if (hidden(context) || !core.canAnswerInBackground(target.hostId)) return@connected WatchResult.LOCKED
                dispatched = true
                connection.answerQuestion(target.terminal, detail.requestId, listOf(listOf(command.choice.toUInt())))
                runCatching {
                    val localized = AppLanguage.context(context)
                    val notice = QuestionNotice(target.hostId, core.hosts().first { it.id == target.hostId }.name, target.terminal,
                        PushQuestion(detail.requestId, detail.requestBody, detail.choices))
                    QuestionNotifications.post(context, notice, QuestionView.Done(localized.getString(
                        R.string.question_notify_sent, notice.choiceLabel(localized, command.choice)), openApp = false))
                }
                return@connected WatchResult.SENT
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CoreException) {
            return when (error) {
                is CoreException.KeyLocked, is CoreException.KeyInvalidated, is CoreException.BiometricUnavailable -> WatchResult.LOCKED
                is CoreException.AnswerRefused -> if (error.reason == AnswerProblem.FAILED) WatchResult.UNKNOWN else WatchResult.CHANGED
                else -> if (dispatched) WatchResult.UNKNOWN else WatchResult.FAILED
            }
        } catch (_: Exception) { return if (dispatched) WatchResult.UNKNOWN else WatchResult.FAILED }
    }
}

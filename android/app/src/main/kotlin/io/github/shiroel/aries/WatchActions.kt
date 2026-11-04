package io.github.shiroel.aries

import android.content.Context
import io.github.shiroel.aries.core.*
import io.github.shiroel.aries.weardata.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

object WatchActions {
    private fun hidden(context: Context): Boolean = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE).let {
        it.getBoolean("enabled", false) && it.getBoolean("hide_previews", true)
    }

    private suspend fun row(core: AriesCoreInterface, target: OpenWatchAgent): WatchAgent? {
        val state = core.snapshot(target.hostId)
        return WatchPhone.summary(core.hosts(), listOf(state)).inbox.firstOrNull {
            it.hostId == target.hostId && it.terminal == target.terminal
        }
    }

    private suspend fun liveEntries(core: AriesCoreInterface, target: OpenWatchAgent): List<ChatEntry> {
        val watch = core.watchChat(target.hostId, target.terminal, null)
        try {
            while (true) {
                val snapshot = watch.next()
                if (snapshot.status in listOf(ChatStatus.LIVE, ChatStatus.ENDED, ChatStatus.WAITING)) return snapshot.entries
                check(snapshot.status in listOf(ChatStatus.CONNECTING, ChatStatus.RECONNECTING))
            }
        } finally { try { watch.stop() } finally { watch.close() } }
    }

    suspend fun details(context: Context, core: AriesCoreInterface, target: OpenWatchAgent): WatchAgent? {
        if (hidden(context) || !core.canAnswerInBackground(target.hostId)) return null
        return withTimeout(12_000) {
            val row = row(core, target) ?: return@withTimeout null
            val detailed = WatchPhone.withEntries(row, liveEntries(core, target))
            if (hidden(context) || !core.canAnswerInBackground(target.hostId)) null else detailed
        }
    }

    suspend fun execute(context: Context, core: AriesCoreInterface, command: WatchCommand): WatchResult {
        var dispatched = false
        try {
            if (hidden(context)) return WatchResult.PHONE
            if (!core.canAnswerInBackground(command.agent.hostId)) return WatchResult.LOCKED
            val target = OpenWatchAgent(command.agent.hostId, command.agent.terminal)
            val current = row(core, target) ?: return WatchResult.CHANGED
            if (current.stateToken != command.agent.stateToken || command.agent.stateToken.isBlank()) return WatchResult.CHANGED
            if (command.text.isNotBlank()) {
                if (current.status !in listOf("done", "error") || command.choice != -1) return WatchResult.CHANGED
                if (command.text.length > 4096) return WatchResult.FAILED
                dispatched = true
                return if (core.sendMessageBackground(target.hostId, target.terminal, command.text,
                        object : SendListener { override fun onHeld() {} }) == SendOutcome.Sent)
                    WatchResult.SENT else WatchResult.CHANGED
            }
            if (current.status != "blocked" || current.requestKind != command.agent.requestKind) return WatchResult.CHANGED
            val detail = WatchPhone.withEntries(current, liveEntries(core, target))
            if (detail.requestId.isBlank() || detail.requestId != command.agent.requestId) return WatchResult.CHANGED
            if (!detail.canAnswerOnWatch) return WatchResult.PHONE
            if (command.choice !in detail.choices.indices) return WatchResult.FAILED
            if (detail.requestKind == "permission" && detail.choices[command.choice] !in listOf("Allow", "Deny")) return WatchResult.PHONE
            // The Beacon re-reads the menu identity immediately before pressing its keys.
            dispatched = true
            core.answerQuestionBackground(target.hostId, target.terminal, detail.requestId, listOf(listOf(command.choice.toUInt())))
            runCatching {
                val localized = AppLanguage.context(context)
                val notice = QuestionNotice(target.hostId, core.host(target.hostId).name, target.terminal,
                    PushQuestion(detail.requestId, detail.requestBody, detail.choices))
                QuestionNotifications.post(context, notice, QuestionView.Done(localized.getString(
                    R.string.question_notify_sent, notice.choiceLabel(localized, command.choice)), openApp = false))
            }
            return WatchResult.SENT
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

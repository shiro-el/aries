package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AriesCoreInterface
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.Host
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retention periods the user can pick, in days. The core's default is 30. */
val CHAT_CACHE_DAYS = listOf(7, 30, 90, 365)

/**
 * The encrypted Chat cache's settings: how long Chats stay, and deleting one
 * Host's Chats. Cache calls open the device key, so they run in [work].
 */
class ChatCacheModel(
    private val core: AriesCoreInterface,
    private val scope: CoroutineScope,
    private val work: CoroutineContext = EmptyCoroutineContext,
) {
    var retentionDays by mutableStateOf(core.chatCacheRetentionDays().toInt())
        private set
    /** What the last clear did, to show under the Host list. */
    var notice by mutableStateOf<Message?>(null)
        private set

    fun setRetention(days: Int) {
        try {
            core.setChatCacheRetentionDays(days.toUInt())
        } catch (e: CoreException) {
            notice = Message(R.string.chat_cache_failed)
        }
        retentionDays = core.chatCacheRetentionDays().toInt()
    }

    fun clear(host: Host) {
        scope.launch {
            notice = try {
                withContext(work) { core.clearChatCache(host.id) }
                Message(R.string.chat_cache_cleared, listOf(host.name))
            } catch (e: CoreException) {
                Message(R.string.chat_cache_failed)
            }
        }
    }
}

@Composable
fun ChatCacheSettings(model: ChatCacheModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.chat_cache_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.chat_cache_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (days in CHAT_CACHE_DAYS) {
                FilterChip(
                    selected = model.retentionDays == days,
                    onClick = { model.setRetention(days) },
                    label = { Text(stringResource(R.string.chat_cache_days, days)) },
                )
            }
        }
        model.notice?.let { Text(stringResource(it.res, *it.args.toTypedArray()), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun ClearChatCacheButton(model: ChatCacheModel, host: Host) {
    val label = stringResource(R.string.chat_cache_clear_label, host.name)
    TextButton(onClick = { model.clear(host) }, modifier = Modifier.semantics { contentDescription = label }) {
        Text(stringResource(R.string.chat_cache_clear_button))
    }
}

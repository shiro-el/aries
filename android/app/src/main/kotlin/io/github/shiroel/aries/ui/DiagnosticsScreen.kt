package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reading diagnostics never connects to a Host or calls the Relay. */
class DiagnosticsModel(
    private val core: AriesCoreInterface,
    private val scope: CoroutineScope,
    private val work: CoroutineContext,
) {
    var entries by mutableStateOf<List<DiagnosticEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                entries = withContext(work) { core.diagnostics(null, null, false) }
                failed = false
            } catch (_: CoreException) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(history: DiagnosticsModel, hosts: List<Host>) {
    val context = LocalContext.current
    var source by remember { mutableStateOf<DiagnosticSource?>(null) }
    var hostId by remember { mutableStateOf<String?>(null) }
    var failures by remember { mutableStateOf(false) }
    var choosingHost by remember { mutableStateOf(false) }
    var notice by remember { mutableIntStateOf(0) }
    LaunchedEffect(history) { history.refresh() }
    val visible = history.entries.filter {
        (source == null || source == it.source) && (hostId == null || hostId == it.hostId) && (!failures || it.error != null)
    }
    val failuresLabel = stringResource(R.string.diagnostics_failures)
    val names = hosts.associate { it.id to it.name }
    val text = visible.joinToString("\n\n") { diagnosticText(context, it, names) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.diagnostics_title), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf(null) + DiagnosticSource.entries).forEach { option ->
                FilterChip(selected = source == option, onClick = { source = option; notice = 0 },
                    label = { Text(stringResource(option?.label() ?: R.string.diagnostics_all)) },
                    modifier = Modifier.testTag("diagnostics-source:${option ?: "ALL"}"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { choosingHost = true }, modifier = Modifier.testTag("diagnostics-host")) {
                    Text(hostId?.let { names[it] } ?: stringResource(R.string.diagnostics_all_hosts))
                }
                DropdownMenu(expanded = choosingHost, onDismissRequest = { choosingHost = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.diagnostics_all_hosts)) },
                        onClick = { hostId = null; choosingHost = false; notice = 0 })
                    hosts.forEach { host ->
                        DropdownMenuItem(text = { Text(host.name) }, modifier = Modifier.testTag("diagnostics-host:${host.id}"),
                            onClick = { hostId = host.id; choosingHost = false; notice = 0 })
                    }
                }
            }
            Checkbox(checked = failures, onCheckedChange = { failures = it; notice = 0 }, modifier = Modifier.testTag("diagnostics-failures").semantics { contentDescription = failuresLabel })
            Text(failuresLabel, style = MaterialTheme.typography.bodySmall)
        }
        Row {
            TextButton(onClick = { history.refresh(); notice = 0 }, enabled = !history.loading) {
                Text(stringResource(R.string.diagnostics_refresh))
            }
            TextButton(enabled = visible.isNotEmpty(), modifier = Modifier.testTag("diagnostics-copy"), onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(context.getString(R.string.diagnostics_title), text))
                notice = R.string.diagnostics_copied
            }) { Text(stringResource(R.string.diagnostics_copy)) }
            TextButton(enabled = visible.isNotEmpty(), modifier = Modifier.testTag("diagnostics-export"), onClick = {
                try {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.diagnostics_export))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    notice = R.string.diagnostics_export_failed
                }
            }) { Text(stringResource(R.string.diagnostics_export)) }
        }
        if (history.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (history.failed) Text(stringResource(R.string.diagnostics_read_failed), color = MaterialTheme.colorScheme.error)
        if (notice != 0) Text(stringResource(notice), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        if (visible.isEmpty() && !history.loading) Text(stringResource(R.string.diagnostics_empty), Modifier.padding(vertical = 16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(visible) { entry ->
                Text(diagnosticText(context, entry, names), Modifier.fillMaxWidth().testTag("diagnostic-entry").padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}

private fun diagnosticText(context: Context, entry: DiagnosticEntry, names: Map<String, String>): String {
    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(entry.timestampMs.toLong()))
    val host = entry.hostId?.let { names[it] } ?: context.getString(R.string.diagnostics_unknown_host)
    val result = context.getString(entry.error?.label() ?: R.string.diagnostics_success)
    val outcome = entry.httpStatus?.let { context.getString(R.string.diagnostics_http, result, it.toInt()) } ?: result
    return context.getString(R.string.diagnostics_entry, time, host, context.getString(entry.source.label()),
        context.getString(entry.event.label()), outcome)
}

private fun DiagnosticSource.label(): Int = when (this) {
    DiagnosticSource.SSH -> R.string.diagnostics_ssh
    DiagnosticSource.BEACON -> R.string.diagnostics_beacon
    DiagnosticSource.RELAY -> R.string.diagnostics_relay
    DiagnosticSource.PUSH -> R.string.diagnostics_push
}

private fun DiagnosticEvent.label(): Int = when (this) {
    DiagnosticEvent.HERDR_INSTALL -> R.string.herdr_diagnostics_install
    DiagnosticEvent.HERDR_STATUS -> R.string.herdr_diagnostics_status
    DiagnosticEvent.CONNECT -> R.string.diagnostics_connect
    DiagnosticEvent.BEACON_INSTALL -> R.string.diagnostics_beacon_install
    DiagnosticEvent.BEACON_STATUS -> R.string.diagnostics_beacon_status
    DiagnosticEvent.BEACON_CHECK -> R.string.diagnostics_beacon_check
    DiagnosticEvent.BEACON_REMOVE -> R.string.diagnostics_beacon_remove
    DiagnosticEvent.PUSH_ENABLE -> R.string.diagnostics_push_enable
    DiagnosticEvent.PUSH_DISABLE -> R.string.diagnostics_push_disable
    DiagnosticEvent.PUSH_TEST -> R.string.diagnostics_push_test
    DiagnosticEvent.RELAY_REQUEST -> R.string.diagnostics_relay_request
    DiagnosticEvent.PUSH_RECEIVE -> R.string.diagnostics_push_receive
}

private fun DiagnosticError.label(): Int = when (this) {
    DiagnosticError.HERDR_INSTALL -> R.string.herdr_diagnostics_failed
    DiagnosticError.HERDR_CHECKSUM -> R.string.herdr_diagnostics_checksum
    DiagnosticError.KEY_UNAVAILABLE -> R.string.diagnostics_error_key
    DiagnosticError.HOST_KEY_UNTRUSTED -> R.string.diagnostics_error_untrusted
    DiagnosticError.HOST_KEY_CHANGED -> R.string.diagnostics_error_changed
    DiagnosticError.AUTHENTICATION -> R.string.diagnostics_error_auth
    DiagnosticError.UNREACHABLE -> R.string.diagnostics_error_unreachable
    DiagnosticError.INCOMPATIBLE -> R.string.diagnostics_error_incompatible
    DiagnosticError.PROTOCOL -> R.string.diagnostics_error_protocol
    DiagnosticError.CLOSED -> R.string.diagnostics_error_closed
    DiagnosticError.STORAGE -> R.string.diagnostics_error_storage
    DiagnosticError.HOST_UNAVAILABLE -> R.string.diagnostics_error_host
    DiagnosticError.BEACON_UNAVAILABLE -> R.string.diagnostics_error_beacon
    DiagnosticError.BEACON_DOWNLOAD -> R.string.diagnostics_error_download
    DiagnosticError.BEACON_CHECKSUM -> R.string.diagnostics_error_checksum
    DiagnosticError.BEACON_OPERATION -> R.string.diagnostics_error_operation
    DiagnosticError.RELAY_REGISTRATION -> R.string.diagnostics_error_register
    DiagnosticError.RELAY_REMOVAL -> R.string.diagnostics_error_revoke
    DiagnosticError.PUSH_REJECTED -> R.string.diagnostics_error_push
    DiagnosticError.HTTP -> R.string.diagnostics_error_http
}

package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.AgentStatus
import io.github.shiroel.aries.core.BlockedReason
import io.github.shiroel.aries.core.Endpoint
import io.github.shiroel.aries.core.Host
import io.github.shiroel.aries.core.TerminalInfo

@Composable
fun AriesApp(model: HostsModel) {
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = model.screen) {
                Screen.Hosts -> HostListScreen(model)
                is Screen.Connecting -> Connecting(s.host)
                is Screen.Terminals -> {
                    BackHandler { model.back() }
                    TerminalListScreen(s.host, s.terminals, onBack = model::back)
                }
            }
        }
        model.prompt?.let { PromptDialog(it, model) }
    }
}

@Composable
private fun HostListScreen(model: HostsModel) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.hosts_title), style = MaterialTheme.typography.headlineMedium) }
        item { KeySection(model) }
        item { HorizontalDivider() }
        if (model.hosts.isEmpty()) {
            item { Text(stringResource(R.string.hosts_empty)) }
        }
        items(model.hosts, key = { it.id }) { host -> HostRow(host) { model.connect(host) } }
        item { HorizontalDivider() }
        item { AddHostForm(model) }
    }
}

@Composable
private fun KeySection(model: HostsModel) {
    val key = model.key
    if (key == null) {
        Text(stringResource(R.string.key_missing))
        Button(onClick = model::createKey) { Text(stringResource(R.string.key_create_button)) }
        return
    }
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.key_clip_label)
    Text(stringResource(R.string.key_title), style = MaterialTheme.typography.titleMedium)
    Text(key.openssh, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.key_fingerprint, key.fingerprint), style = MaterialTheme.typography.bodySmall)
    Button(onClick = {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(clipLabel, key.openssh))
    }) { Text(stringResource(R.string.key_copy_button)) }
    Text(stringResource(R.string.key_copy_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HostRow(host: Host, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(host.name, style = MaterialTheme.typography.titleMedium)
        val where = when (val e = host.endpoint) {
            is Endpoint.Ssh -> stringResource(R.string.host_ssh_address, e.user, e.address, e.port.toInt())
            is Endpoint.Local -> stringResource(R.string.host_local_address)
        }
        Text(where, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AddHostForm(model: HostsModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("") }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    Text(stringResource(R.string.host_add_title), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.host_add_name)) }, singleLine = true)
    OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.host_add_address)) }, singleLine = true)
    OutlinedTextField(
        port, { port = it },
        label = { Text(stringResource(R.string.host_add_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.host_add_user)) }, singleLine = true)
    Button(
        enabled = address.isNotBlank() && user.isNotBlank() && portNumber != null,
        onClick = {
            model.addHost(name, address, portNumber ?: 22, user)
            name = ""; address = ""; port = "22"; user = ""
        },
    ) { Text(stringResource(R.string.host_add_button)) }
}

@Composable
private fun Connecting(host: Host) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(stringResource(R.string.connecting, host.name))
    }
}

@Composable
private fun TerminalListScreen(host: Host, terminals: List<TerminalInfo>, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
            }
            Text(stringResource(R.string.terminals_title, host.name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
        }
        if (terminals.isEmpty()) item { Text(stringResource(R.string.terminals_empty)) }
        items(terminals, key = { it.address }) { t ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(t.name ?: t.title.ifBlank { t.argv.joinToString(" ") }, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.terminal_subtitle, t.address, t.state), style = MaterialTheme.typography.bodySmall)
                // A program that does not report status gets no status line.
                t.agentStatus?.let { AgentStatusLine(it, t.needsAttention) }
            }
        }
    }
}

@Composable
private fun PromptDialog(prompt: Prompt, model: HostsModel) {
    when (prompt) {
        is Prompt.TrustHostKey -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.host_key_trust_title)) },
            text = { Text(stringResource(R.string.host_key_trust_body, prompt.host.name, prompt.fingerprint)) },
            confirmButton = { TextButton(onClick = { model.trust(prompt) }) { Text(stringResource(R.string.host_key_trust_button)) } },
            dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.cancel_button)) } },
        )
        is Prompt.Problem -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(text(prompt.title)) },
            text = { Text(text(prompt.body)) },
            confirmButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.ok_button)) } },
        )
    }
}

@Composable
private fun text(m: Message): String = stringResource(m.res, *m.args.toTypedArray())

@Composable
private fun AgentStatusLine(status: AgentStatus, needsAttention: Boolean) {
    val color = if (needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val label = when (status) {
        AgentStatus.Working -> R.string.agent_status_working
        is AgentStatus.Blocked -> when (status.reason) {
            BlockedReason.PERMISSION -> R.string.agent_status_blocked_permission
            BlockedReason.QUESTION -> R.string.agent_status_blocked_question
            BlockedReason.AUTH -> R.string.agent_status_blocked_auth
            null -> R.string.agent_status_blocked
        }
        is AgentStatus.Error -> R.string.agent_status_error
        AgentStatus.Done -> R.string.agent_status_done
        AgentStatus.Idle -> R.string.agent_status_idle
    }
    val message = when (status) {
        is AgentStatus.Blocked -> status.message
        is AgentStatus.Error -> status.message
        else -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelLarge)
        if (needsAttention) {
            Text(stringResource(R.string.agent_attention_label), color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
    message?.let { Text(it, color = color, style = MaterialTheme.typography.bodySmall) }
}

package io.github.shiroel.aries.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException
import io.github.shiroel.aries.core.HostPairingDetails
import kotlinx.coroutines.launch

/** QR credentials deliberately use remember, never saved state or disk. */
@Composable
internal fun QrHostPairing(
    model: HostsModel,
    scanQr: (((String) -> Unit) -> Unit)? = null,
    onPaired: (io.github.shiroel.aries.core.Host) -> Unit = {},
) {
    var qr by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<HostPairingDetails?>(null) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scanned: (String) -> Unit = { value ->
        qr = null; details = null; error = null
        try {
            val parsed = model.inspectPairingQr(value)
            details = parsed; name = parsed.address; qr = value
        } catch (_: CoreException) { error = R.string.host_pair_invalid }
    }
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(scanned) }
    val prompt = stringResource(R.string.host_pair_scan_prompt)
    val scan = rememberCameraAccess {
        launcher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt).setBeepEnabled(false).setOrientationLocked(false))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = !busy, modifier = Modifier.testTag("host-pair-scan"),
            onClick = {
                if (scanQr != null) scanQr(scanned)
                else scan()
            },
        ) { Text(stringResource(R.string.host_pair_scan_button)) }
        details?.let { offer ->
            Text(stringResource(R.string.host_pair_review_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, Modifier.testTag("host-pair-name"), enabled = !busy, label = { Text(stringResource(R.string.host_add_name)) }, singleLine = true)
            OutlinedTextField(offer.address, {}, Modifier.testTag("host-pair-address"), readOnly = true, label = { Text(stringResource(R.string.host_add_address)) })
            OutlinedTextField(offer.port.toString(), {}, Modifier.testTag("host-pair-port"), readOnly = true, label = { Text(stringResource(R.string.host_add_port)) })
            OutlinedTextField(offer.user, {}, Modifier.testTag("host-pair-user"), readOnly = true, label = { Text(stringResource(R.string.host_add_user)) })
            Text(stringResource(R.string.host_pair_fingerprint, offer.hostKey), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsoleButton(
                    modifier = Modifier.testTag("host-pair-confirm"),
                    enabled = !busy && qr != null && validHostInput(model.hosts, null, name, offer.address, offer.port.toInt(), offer.user),
                    onClick = {
                        val credential = qr ?: return@ConsoleButton
                        busy = true; error = null
                        scope.launch {
                            try {
                                val host = model.pairHostQr(name, credential)
                                qr = null; details = null; name = ""
                                onPaired(host)
                            } catch (e: CoreException) {
                                error = when (e) {
                                    is CoreException.InvalidPairingQr -> R.string.host_pair_invalid
                                    is CoreException.HostKeyChanged -> R.string.host_pair_key_changed
                                    else -> R.string.host_pair_refused
                                }
                                // A lost reply may mean the token was consumed. Require
                                // another scan, never retry the write automatically.
                                qr = null
                            } finally { busy = false }
                        }
                    },
                ) { Text(stringResource(if (busy) R.string.host_pair_busy else R.string.host_pair_confirm_button)) }
                TextButton(enabled = !busy, onClick = { qr = null; details = null; error = null }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

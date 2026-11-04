package io.github.shiroel.aries.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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

/** Review survives UI recreation in memory; QR credentials never enter saved state or disk. */
internal class QrPairingReview {
    var qr by mutableStateOf<String?>(null)
    var details by mutableStateOf<HostPairingDetails?>(null)
    var name by mutableStateOf("")
    var error by mutableStateOf<Int?>(null)
    var busy by mutableStateOf(false)
    var paired by mutableStateOf<io.github.shiroel.aries.core.Host?>(null)
    var revision = 0
        private set
    fun clear() { revision++; qr = null; details = null; name = ""; error = null; busy = false; paired = null }
}

@Composable
internal fun QrHostPairing(
    model: HostsModel,
    scanQr: (((String) -> Unit) -> Unit)? = null,
    connectAfterPair: Boolean = false,
) {
    val review = model.pairingReview
    var qr by review::qr
    var details by review::details
    var name by review::name
    var error by review::error
    val busy = review.busy
    val scanned: (String) -> Unit = { value ->
        review.clear()
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
            val errors = hostInputErrors(model.hosts, null, name, offer.address, offer.port.toInt(), offer.user)
            Text(stringResource(R.string.host_pair_review_title), style = MaterialTheme.typography.titleMedium)
            ConsoleField(name, { name = it }, Modifier.testTag("host-pair-name"), enabled = !busy, label = { Text(stringResource(R.string.host_add_name)) }, singleLine = true, isError = errors.name != null, supportingText = hostInputSupport(errors.name))
            ConsoleField(offer.address, {}, Modifier.testTag("host-pair-address"), readOnly = true, label = { Text(stringResource(R.string.host_add_address)) })
            ConsoleField(offer.port.toString(), {}, Modifier.testTag("host-pair-port"), readOnly = true, label = { Text(stringResource(R.string.host_add_port)) })
            ConsoleField(offer.user, {}, Modifier.testTag("host-pair-user"), readOnly = true, label = { Text(stringResource(R.string.host_add_user)) })
            Text(stringResource(R.string.host_pair_fingerprint, offer.hostKey), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsoleButton(
                    modifier = Modifier.testTag("host-pair-confirm"),
                    enabled = !busy && qr != null && validHostInput(model.hosts, null, name, offer.address, offer.port.toInt(), offer.user),
                    onClick = { model.pairReviewedHost(connectAfterPair) },
                ) { Text(stringResource(if (busy) R.string.host_pair_busy else R.string.host_pair_confirm_button)) }
                TextButton(enabled = !busy, onClick = review::clear) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        }
        error?.let { AnnouncedText(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

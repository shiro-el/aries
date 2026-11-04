package io.github.shiroel.aries.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CoreException

/** Safe localized reasons, without generated exception field dumps or Host diagnostics. */
internal fun errorMessage(error: Throwable): Message = Message(when (error) {
    is CoreException.Closed, is CoreException.Unreachable, is java.io.IOException -> R.string.error_connection
    is CoreException.Rpc -> when (error.kind) {
        "no_agent" -> R.string.error_no_agent
        "status_mismatch" -> R.string.error_status_mismatch
        "permission_denied" -> R.string.error_permission
        "not_found", "terminal_not_found" -> R.string.error_missing
        else -> R.string.error_retry
    }
    is SecurityException -> R.string.error_permission
    is CoreException.HerdrCoreMissing -> R.string.herdr_install_connect_reason
    is CoreException.Protocol -> R.string.error_protocol
    is CoreException.Storage -> R.string.error_storage
    is CoreException.AuthRejected -> R.string.connect_auth_rejected
    is CoreException.NoKey -> R.string.connect_no_key
    is CoreException.KeyLocked -> R.string.key_locked_body
    is CoreException.KeyInvalidated -> R.string.key_invalidated_body
    is CoreException.BeaconNotInstalled -> R.string.chat_status_no_beacon
    else -> R.string.error_retry
})

/** Resolve nested localized reasons at display time, including notification actions. */
internal fun Message.resolve(context: Context): String =
    context.getString(res, *args.map { if (it is Message) it.resolve(context) else it }.toTypedArray())

@Composable
internal fun messageText(message: Message): String = stringResource(message.res,
    *message.args.map { if (it is Message) messageText(it) else it }.toTypedArray())

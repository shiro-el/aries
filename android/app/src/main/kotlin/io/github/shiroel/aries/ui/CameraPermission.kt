package io.github.shiroel.aries.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import io.github.shiroel.aries.R

/** Camera entry points share permission handling before opening capture or a scanner. */
@Composable
internal fun rememberCameraAccess(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var requesting by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requesting = false
        if (granted) onGranted()
        else denied = activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }

    denied?.let { permanent ->
        AlertDialog(
            onDismissRequest = { denied = null },
            title = { Text(stringResource(R.string.camera_permission_title)) },
            text = { Text(stringResource(if (permanent) R.string.camera_permission_settings_body else R.string.camera_permission_denied_body)) },
            confirmButton = {
                TextButton(onClick = {
                    denied = null
                    if (permanent) context.startActivity(Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ))
                }) {
                    Text(stringResource(if (permanent) R.string.camera_permission_settings_button else R.string.ok_button))
                }
            },
            dismissButton = {
                if (permanent) TextButton(onClick = { denied = null }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }

    return {
        if (!requesting) {
            denied = null
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                requesting = true
                permission.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

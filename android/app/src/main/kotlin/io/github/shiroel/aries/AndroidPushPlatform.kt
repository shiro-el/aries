package io.github.shiroel.aries

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.github.shiroel.aries.ui.PushPlatform
import io.github.shiroel.aries.ui.PushUnavailable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * FCM and notification permission state outlive configuration changes. Each
 * Activity binds its result registry in onCreate and releases it in onDestroy.
 */
class AndroidPushPlatform(context: Context) : PushPlatform {
    private val context = context.applicationContext
    override val relayUrl: String? = BuildConfig.ARIES_RELAY_URL.trimEnd('/').ifBlank { null }

    private var pending: CompletableDeferred<Boolean>? = null
    private var launcher: ActivityResultLauncher<String>? = null

    fun bind(activity: ComponentActivity) {
        launcher = activity.activityResultRegistry.register(
            "aries-notification-permission", activity, ActivityResultContracts.RequestPermission(),
        ) {
            pending?.complete(it)
            pending = null
        }
    }

    fun unbind() {
        launcher = null
    }

    override suspend fun deviceToken(): String {
        if (FirebaseApp.getApps(context).isEmpty()) {
            throw PushUnavailable(context.getString(R.string.push_no_firebase))
        }
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(PushUnavailable(it.message ?: it.toString())) }
        }
    }

    override suspend fun allowNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        val result = pending ?: run {
            val request = launcher ?: return false
            CompletableDeferred<Boolean>().also {
                pending = it
                request.launch(permission)
            }
        }
        return result.await()
    }
}

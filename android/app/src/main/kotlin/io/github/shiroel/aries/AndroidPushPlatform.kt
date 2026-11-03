package io.github.shiroel.aries

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.github.shiroel.aries.ui.PushPlatform
import io.github.shiroel.aries.ui.PushUnavailable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * FCM and the notification permission. Create it in the activity's
 * `onCreate`: the permission request is registered there.
 */
class AndroidPushPlatform(private val activity: ComponentActivity) : PushPlatform {
    override val relayUrl: String? = BuildConfig.ARIES_RELAY_URL.trimEnd('/').ifBlank { null }

    private var pending: CompletableDeferred<Boolean>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.complete(it)
        pending = null
    }

    override suspend fun deviceToken(): String {
        if (FirebaseApp.getApps(activity).isEmpty()) {
            throw PushUnavailable(activity.getString(R.string.push_no_firebase))
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
        if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        val result = CompletableDeferred<Boolean>().also { pending = it }
        launcher.launch(permission)
        return result.await()
    }
}

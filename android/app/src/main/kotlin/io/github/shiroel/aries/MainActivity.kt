package io.github.shiroel.aries

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.shiroel.aries.ui.AriesApp
import io.github.shiroel.aries.ui.HostsModel
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private val app get() = application as AriesApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val core = app.core
        val push = AndroidPushPlatform(this)
        setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope, keyWork = Dispatchers.IO, pushPlatform = push) }
            MaterialTheme { AriesApp(model) }
        }
    }

    override fun onResume() {
        super.onResume()
        app.vault.authorizer = BiometricUnlock(this)
    }

    override fun onPause() {
        app.vault.authorizer = null
        super.onPause()
    }
}

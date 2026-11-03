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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val core = (application as AriesApplication).core
        val push = AndroidPushPlatform(this)
        setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope, pushPlatform = push) }
            MaterialTheme { AriesApp(model) }
        }
    }
}

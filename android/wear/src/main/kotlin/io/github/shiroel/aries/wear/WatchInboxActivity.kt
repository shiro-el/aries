package io.github.shiroel.aries.wear

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import com.google.android.gms.wearable.Wearable
import io.github.shiroel.aries.weardata.WatchAgent
import io.github.shiroel.aries.weardata.WatchSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Local Inbox is also the entry point for Tile/complication Agent deep links. */
class WatchInboxActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store by lazy { WatchStateStore(this) }
    private var summary by mutableStateOf(WatchSummary.EMPTY)
    private var now by mutableStateOf(System.currentTimeMillis())
    private var refreshing by mutableStateOf(false)
    private var notice by mutableStateOf<Int?>(null)
    private var target by mutableStateOf<Pair<String, String>?>(null)
    private val preferences by lazy { getSharedPreferences("watch-summary", MODE_PRIVATE) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { summary = store.summary() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        summary = store.summary()
        target = if (savedInstanceState != null) savedInstanceState.let { state ->
            state.getString("host")?.let { it to state.getString("terminal").orEmpty() }
        } else targetFrom(intent)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        setContent {
            CompositionLocalProvider(LocalContext provides summary.localizedContext(this)) {
                WatchInbox(summary, now, target, { target = it }, refreshing, notice, ::refresh, ::openPhone)
            }
        }
        scope.launch {
            while (true) { now = System.currentTimeMillis(); delay(30_000) }
        }
    }

    override fun onResume() {
        super.onResume()
        scope.launch { store.hydrate(this@WatchInboxActivity); summary = store.summary() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = targetFrom(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        target?.let { outState.putString("host", it.first); outState.putString("terminal", it.second) }
        super.onSaveInstanceState(outState)
    }

    private fun targetFrom(intent: Intent): Pair<String, String>? =
        intent.getStringExtra("host")?.let { host -> intent.getStringExtra("terminal")?.let { host to it } }

    private fun openPhone(agent: WatchAgent) {
        startActivity(Intent(this, OpenOnPhoneActivity::class.java)
            .putExtra("host", agent.hostId).putExtra("terminal", agent.terminal))
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        notice = null
        scope.launch {
            try {
                withTimeout(10_000) {
                    store.hydrate(this@WatchInboxActivity)
                    val phone = requireNotNull(store.phoneNode())
                    val reply = Wearable.getMessageClient(this@WatchInboxActivity)
                        .sendRequest(phone, WatchSummary.REFRESH_PATH, byteArrayOf()).await()
                    check(reply.contentEquals(byteArrayOf(1)))
                }
                // Acknowledges scheduling only; the received DataItem owns fetchedAtMs.
                notice = R.string.wear_inbox_requested
            } catch (cancelled: CancellationException) {
                if (!scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive) throw cancelled
                notice = R.string.wear_connect_phone
            } catch (_: Exception) { notice = R.string.wear_connect_phone }
            finally { refreshing = false }
        }
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        scope.cancel()
        super.onDestroy()
    }
}

package io.github.shiroel.aries.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class DiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun settingsFiltersHistoryAndCopiesAndExportsOnlyVisibleRecords() {
        val core = FakeCore()
        val alpha = core.addHost("Alpha", Endpoint.Ssh("alpha", 22u, "u"))
        val beta = core.addHost("Beta", Endpoint.Ssh("beta", 22u, "u"))
        core.diagnosticEntries = listOf(
            DiagnosticEntry(1_700_000_002_000uL, alpha.id, DiagnosticSource.SSH, DiagnosticEvent.CONNECT, DiagnosticError.UNREACHABLE, null),
            DiagnosticEntry(1_700_000_001_000uL, alpha.id, DiagnosticSource.PUSH, DiagnosticEvent.PUSH_RECEIVE, null, null),
            DiagnosticEntry(1_700_000_000_000uL, beta.id, DiagnosticSource.PUSH, DiagnosticEvent.PUSH_RECEIVE, DiagnosticError.PUSH_REJECTED, null),
        )
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { HostsModel(core, scope) }
            AriesApp(model)
        }
        compose.openConsoleDrawer()
        compose.onNodeWithTag("settings-open").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("diagnostics")
        compose.onNodeWithTag("settings-diagnostics").performClick()
        compose.onAllNodesWithTag("diagnostic-entry").assertCountEquals(3)
        compose.onNodeWithTag("diagnostics-source:PUSH").performClick()
        compose.onAllNodesWithTag("diagnostic-entry").assertCountEquals(2)
        compose.onNodeWithTag("diagnostics-host").performClick()
        compose.onNodeWithTag("diagnostics-host:${beta.id}").performClick()
        compose.onNodeWithTag("diagnostics-failures").performClick()
        compose.onAllNodesWithTag("diagnostic-entry").assertCountEquals(1)
        compose.onNodeWithTag("diagnostics-copy").performClick()
        var copied = ""
        compose.runOnIdle {
            copied = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied.contains("Beta"))
            assertFalse(copied.contains("Alpha"))
        }
        compose.onNodeWithTag("diagnostics-export").performClick()
        compose.runOnIdle {
            val chooser = shadowOf(context as android.app.Application).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals("text/plain", send.type)
            assertEquals(copied, send.getStringExtra(Intent.EXTRA_TEXT))
        }
        compose.onNodeWithTag("diagnostics-host").performClick()
        compose.onNodeWithTag("diagnostics-host:${alpha.id}").performClick()
        compose.onAllNodesWithTag("diagnostic-entry").assertCountEquals(0)
        compose.onNodeWithTag("diagnostics-copy").assertIsNotEnabled()
        compose.onNodeWithTag("diagnostics-export").assertIsNotEnabled()
    }
}

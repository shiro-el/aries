package io.github.shiroel.aries.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatFileLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recognizesFileReferencesAndLeavesUrlsEmailAndInvalidLinesAlone() {
        val text = "See `../lib/a.rs:7`, /etc/hosts and README.md. \"src/a file.kt:9\" " +
            "https://example.com/a.kt:4 me@example.com a.kt:0 b.kt:18446744073709551616 ordinary words."
        val opened = mutableListOf<FileLocation>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalOpenRemoteFile provides { opened += it }) {
                    Column(Modifier.padding(32.dp)) { ChatFileText(text) }
                }
            }
        }
        val node = compose.onNodeWithText(text)
        var layout: TextLayoutResult? = null
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<TextLayoutResult>()
            action(results)
            layout = results.single()
        }
        val links = layout!!.layoutInput.text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("../lib/a.rs:7", "/etc/hosts", "README.md", "src/a file.kt:9"),
            links.map { text.substring(it.start, it.end) })
        for (link in links) node.performFirstLinkClick { it.start == link.start }
        assertEquals(listOf(FileLocation("../lib/a.rs", 7u), FileLocation("/etc/hosts"),
            FileLocation("README.md"), FileLocation("src/a file.kt", 9u)), opened)
    }
}

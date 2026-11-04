package io.github.shiroel.aries

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeCoreApp::class, sdk = [33])
class NotificationMarkdownTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun shown() = manager.activeNotifications.single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.notification
    private fun done(text: String) = PushService.show(context, PushNotice("markdown", "Host",
        PushContent.Status("t219", "Agent", null, true, AgentStatus.Done, 1uL, 1uL, null, text)))

    @Test fun doneUsesPlainCollapsedTextAndRetainsInlineStylesWhenExpanded() {
        done("# Result\n\n**Passed** with *care* and `x_y * 2`\n\n- [Review](https://example.com)\n\n> Ready")
        val n = shown()
        assertEquals("Result Passed with care and x_y * 2 • Review Ready", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) as Spanned
        assertEquals("Result\nPassed with care and x_y * 2\n• Review\nReady", big.toString())
        val styles = big.getSpans(0, big.length, StyleSpan::class.java).associate {
            big.subSequence(big.getSpanStart(it), big.getSpanEnd(it)).toString() to it.style
        }
        assertEquals(Typeface.BOLD, styles["Passed"])
        assertEquals(Typeface.ITALIC, styles["care"])
        val code = big.getSpans(0, big.length, TypefaceSpan::class.java).single()
        assertEquals("monospace", code.family)
        assertEquals("x_y * 2", big.subSequence(big.getSpanStart(code), big.getSpanEnd(code)).toString())
    }
    @Test fun questionAndPermissionBodiesRemainRenderedWhileAnAnswerIsSending() {
        for (permission in listOf(false, true)) {
            manager.cancelAll()
            val question = PushQuestion(if (permission) "permission:219" else "question:219",
                "# Review\n\nUse **care** with `config`?", if (permission) listOf("Allow", "Deny") else listOf("**Yes**", "No"))
            val status = PushContent.Status("t219", "Agent", null, true,
                AgentStatus.Blocked(if (permission) BlockedReason.PERMISSION else BlockedReason.QUESTION, "**Review**"),
                1uL, 1uL, question, null)
            PushService.show(context, PushNotice("markdown", "Host", status)) { true }
            assertEquals("Review\nUse care with config?", shown().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
            assertEquals(if (permission) context.getString(R.string.chat_permission_allow) else "Yes", shown().actions.first().title.toString())
            QuestionNotifications.post(context, QuestionNotice("markdown", "Host", "t219", question), QuestionView.Sending(0))
            val expanded = shown().extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            assertTrue(expanded.toString().startsWith("Review\nUse care with config?\n"))
            assertFalse(expanded.toString().contains("**"))
            assertTrue(expanded is Spanned && expanded.getSpans(0, expanded.length, TypefaceSpan::class.java).isNotEmpty())
        }
    }

    @Test fun callAnswerUpdatesKeepTheRenderedBodyAndPlainChoiceLabels() {
        val call = PushContent.Call("c219", "t219", true, "**Review**", "# Result\n\n**Ready** with `config`",
            CallUrgency.CHECK, listOf("**Yes**", "No"), 1uL)
        val notice = CallNotice("markdown", "Host", call)
        for (view in listOf(CallView.Choices, CallView.Sending(0), CallView.Done("Delivered"))) {
            CallNotifications.post(context, notice, view)
            val n = shown()
            assertEquals("Review", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            assertTrue(big.toString().startsWith("Result\nReady with config"))
            assertFalse(big.toString().contains("**"))
            assertTrue(big is Spanned && big.getSpans(0, big.length, TypefaceSpan::class.java).isNotEmpty())
            assertFalse(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("**"))
            if (view == CallView.Choices) assertEquals("Yes", n.actions.first().title.toString())
        }
    }

    @Test fun clippedMarkdownAndBlocksDoNotLeakSyntaxOrEatPlainOperators() {
        for ((input, expected) in listOf(
            "Before **unfinished" to "Before unfinished",
            "Before `unfinished" to "Before unfinished",
            "```kotlin\nval result = 2 * 3\nsecond line" to "val result = 2 * 3",
            "Before\n\n    val result = 2 * 3\n    second line" to "Before\nval result = 2 * 3",
            "| Name | Result |\n| --- | --- |\n| Test | Passed |" to "Name · Result\nTest · Passed",
            "<div>Ready <b>now</b></div>" to "Ready now",
            "Compute 2 * 3; **unfinished" to "Compute 2 * 3; unfinished",
        )) {
            manager.cancelAll()
            done(input)
            assertEquals(input, expected, shown().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        }
    }

}

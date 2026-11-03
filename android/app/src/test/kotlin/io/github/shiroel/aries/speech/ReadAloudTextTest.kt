package io.github.shiroel.aries.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudTextTest {
    @Test fun readsProseWithoutMarkdownOrCodeBlocks() {
        val markdown = """
            # Result
            **Done**: use `Aries` and [the guide](https://example.com).

            ```kotlin
            println("do not read")
            ```

                indented_secret()

            > 안녕하세요.

            - First
            - Second
        """.trimIndent()
        assertEquals("Result\nDone: use Aries and the guide.\n안녕하세요.\nFirst\nSecond", ReadAloudText.clean(markdown))
    }
    @Test fun anUnclosedOrNestedFenceNeverSpeaksPartialCode() {
        assertEquals("Before.", ReadAloudText.clean("Before.\n\n~~~sh\nrm private_file"))
        assertEquals("After 한글🙂.", ReadAloudText.clean("> ```js\n> secret()\n> ```\n\nAfter 한글🙂."))
        assertEquals("", ReadAloudText.clean("    only_code()\n\n```\nmore_code\n```"))
    }
}

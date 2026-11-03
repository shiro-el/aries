package io.github.shiroel.aries.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentsTest {
    @Test fun hostPathsAreOneLiteralShellArgument() {
        assertEquals("'/host/files/a b.jpg'", shellQuote("/host/files/a b.jpg"))
        assertEquals("'/host/it'\"'\"'s; $(touch nope)\n사진.jpg'", shellQuote("/host/it's; $(touch nope)\n사진.jpg"))
        assertEquals("''", shellQuote(""))
    }
}

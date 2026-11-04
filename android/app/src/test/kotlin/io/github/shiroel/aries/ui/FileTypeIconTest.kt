package io.github.shiroel.aries.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileTypeIconTest {
    @Test fun compoundSuffixesAreNotShadowedByTheFinalExtension() {
        assertNotEquals(fileTypeIcon("foo.ts"), fileTypeIcon("foo.test.ts"))
        assertNotEquals(fileTypeIcon("foo.ts"), fileTypeIcon("foo.d.ts"))
    }

    @Test fun knownBasenameWinsOverItsUnrelatedExtension() {
        assertEquals(fileTypeIcon("Dockerfile"), fileTypeIcon("/repo/Dockerfile.dev"))
        assertNotEquals(fileTypeIcon("unknown.dev"), fileTypeIcon("/repo/Dockerfile.dev"))
    }
}

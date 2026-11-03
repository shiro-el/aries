package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.GitDiffLine
import org.junit.Assert.assertEquals
import org.junit.Test

class GitReferenceTest {
    @Test fun replacementUsesNewSideAndEmbeddedFencesStayInsideTheSnippet() {
        assertEquals("x:3\n````diff\n-old\n+```\n````", formatGitReference("x", listOf(
            GitDiffLine("-old", 100u, null), GitDiffLine("+```", null, 3u),
        )))
    }
    @Test fun deletionUsesOldSideAndFilesWithoutTextStillHaveAReference() {
        assertEquals("/repo/x:9\n```diff\n-removed\n```", formatGitReference("/repo/x", listOf(GitDiffLine("-removed", 9u, null))))
        assertEquals("/repo/x", formatGitReference("/repo/x", emptyList()))
    }
    @Test fun rangeIncludesBothEndpoints() {
        assertEquals("/repo/한글 x:3-4\n```diff\n+new\n+extra\n```", formatGitReference("/repo/한글 x", listOf(
            GitDiffLine("+new", null, 3u), GitDiffLine("+extra", null, 4u),
        )))
    }
}

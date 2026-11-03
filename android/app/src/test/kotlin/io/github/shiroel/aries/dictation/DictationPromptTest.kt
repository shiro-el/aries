package io.github.shiroel.aries.dictation

import org.junit.Assert.*
import org.junit.Test

class DictationPromptTest {
    @Test fun vocabularyPrecedesNamesAndTheLastResponseTail() {
        assertEquals("Aries, Herdr\nhome, coder, aries\n마지막 응답", DictationPrompt.build(
            "Aries, Herdr", listOf("home", "coder", "aries"), "마지막 응답", true,
        ))
        assertEquals("Aries", DictationPrompt.build("Aries", listOf("private"), "private response", false))
    }
    @Test fun longVocabularyWinsAndUnicodeIsNeverSplit() {
        val prompt = DictationPrompt.build("가".repeat(100), listOf("private-name"), "private-response", true)
        assertEquals("가".repeat(74), prompt)
        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= 224)
        assertFalse(prompt.contains("\uFFFD"))
    }

    @Test fun responseKeepsItsEndAfterHigherPriorityContext() {
        val prompt = DictationPrompt.build("v".repeat(200), listOf("name"), "x".repeat(100) + " END", true)
        assertEquals("v".repeat(200) + "\nname\nxxxxxxxxxxxxxx END", prompt)
        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= 224)
    }
}

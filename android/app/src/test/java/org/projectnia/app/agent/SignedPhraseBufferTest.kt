package org.projectnia.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedPhraseBufferTest {
    @Test
    fun appendsUpToCapacityWithoutDroppingExistingTokens() {
        val buffer = SignedPhraseBuffer(capacity = 2)

        assertTrue(buffer.append(token("hello")))
        assertTrue(buffer.append(token("please")))
        assertFalse(buffer.append(token("thanks")))

        assertEquals(listOf("hello", "please"), buffer.snapshot().map { it.label })
        assertTrue(buffer.isFull())
    }

    @Test
    fun correctionReplacesOnlyLatestToken() {
        val buffer = SignedPhraseBuffer()
        buffer.append(token("hello"))
        buffer.append(token("yes"))

        assertTrue(buffer.replaceLast(token("no")))

        assertEquals(listOf("hello", "no"), buffer.snapshot().map { it.label })
    }

    @Test
    fun displayMakesEmptyAndCurrentPhraseExplicit() {
        val buffer = SignedPhraseBuffer(capacity = 3)
        assertEquals("Signed phrase: empty (0/3)", buffer.displayText())

        buffer.append(token("i_love_you"))

        assertEquals("Signed phrase: I_LOVE_YOU (1/3)", buffer.displayText())
    }

    private fun token(label: String) = RecognizedSignToken(label, 0.8f, 0.2f)
}

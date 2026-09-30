package org.projectnia.app.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationProgressTest {
    @Test
    fun `partial samples stay inactive`() {
        val progress = PersonalizationProgressPresenter.summarize(listOf(3, 2, 0), 3)

        assertFalse(progress.active)
        assertEquals(5, progress.collectedSamples)
        assertEquals(9, progress.requiredSamples)
        assertEquals(1, progress.completedSigns)
    }

    @Test
    fun `only the complete protocol becomes active`() {
        val progress = PersonalizationProgressPresenter.summarize(List(32) { 3 }, 3)

        assertTrue(progress.active)
        assertEquals("Personalization: ACTIVE | 96/96 samples", progress.displayText())
    }

    @Test
    fun `labels distinguish incomplete and complete signs`() {
        assertEquals("HELLO  |  2/3 samples", PersonalizationProgressPresenter.label("hello", 2, 3))
        assertEquals("HELLO  |  COMPLETE", PersonalizationProgressPresenter.label("hello", 3, 3))
    }
}

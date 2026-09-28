package org.projectnia.app.avatar

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionLibraryTest {
    @Test
    fun `summary separates approved motions from drafts`() {
        val entries = listOf(
            MotionLibraryEntry("hello", approved = false),
            MotionLibraryEntry("thanks", approved = true, signLanguage = "USL", reviewerName = "Amina"),
            MotionLibraryEntry("please", approved = false),
        )

        assertEquals(
            MotionLibrarySummary(total = 3, approved = 1, drafts = 2),
            MotionLibraryPresenter.summarize(entries),
        )
    }

    @Test
    fun `labels never disguise a draft as an approved sign`() {
        val draft = MotionLibraryPresenter.label(MotionLibraryEntry("hello", approved = false))
        val approved = MotionLibraryPresenter.label(
            MotionLibraryEntry("thanks", approved = true, signLanguage = "USL", reviewerName = "Amina"),
        )

        assertEquals("HELLO  |  DRAFT - needs fluent signer review", draft)
        assertEquals("THANKS  |  APPROVED (USL)", approved)
    }

    @Test
    fun `empty summary gives an actionable library state`() {
        assertEquals(
            "Avatar library: no motions yet",
            MotionLibraryPresenter.summarize(emptyList()).displayText(),
        )
    }
}

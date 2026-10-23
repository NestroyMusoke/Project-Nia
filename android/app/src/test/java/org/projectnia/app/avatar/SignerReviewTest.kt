package org.projectnia.app.avatar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignerReviewTest {
    @Test
    fun requiresReviewerLanguageAndExactMotionDigest() {
        val digest = "a".repeat(64)
        SignerReview("Fluent Reviewer", "ASL", 1L, digest, "Handshape confirmed")

        assertFailsWith<IllegalArgumentException> {
            SignerReview("", "ASL", 1L, digest)
        }
        assertFailsWith<IllegalArgumentException> {
            SignerReview("Reviewer", "", 1L, digest)
        }
        assertFailsWith<IllegalArgumentException> {
            SignerReview("Reviewer", "ASL", 1L, "not-a-digest")
        }
    }

    @Test
    fun distinguishesCompleteStructuredChecklistFromLegacyReview() {
        val digest = "b".repeat(64)
        val legacy = SignerReview("Reviewer", "ASL", 1L, digest)
        val structured = legacy.copy(confirmedCriteria = SignerReviewCriterion.entries.toSet())

        assertFalse(legacy.hasCompleteChecklist())
        assertTrue(legacy.checklistStatus().contains("legacy"))
        assertTrue(structured.hasCompleteChecklist())
        assertTrue(structured.checklistStatus().contains("complete"))
    }
}

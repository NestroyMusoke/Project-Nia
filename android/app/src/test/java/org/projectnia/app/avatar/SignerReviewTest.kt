package org.projectnia.app.avatar

import kotlin.test.Test
import kotlin.test.assertFailsWith

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
}

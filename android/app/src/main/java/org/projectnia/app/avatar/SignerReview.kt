package org.projectnia.app.avatar

enum class SignerReviewCriterion(val displayText: String) {
    HANDSHAPE("Handshape and finger positions are correct"),
    ORIENTATION("Palm orientation and handedness are correct"),
    LOCATION_MOVEMENT("Sign location and movement path are correct"),
    TIMING("Timing, holds, and repetition are correct"),
    NON_MANUAL("Facial and other non-manual information is acceptable"),
}

data class SignerReview(
    val reviewerName: String,
    val signLanguage: String,
    val reviewedAtEpochMillis: Long,
    val motionSha256: String,
    val notes: String = "",
    val confirmedCriteria: Set<SignerReviewCriterion> = emptySet(),
) {
    init {
        require(reviewerName.trim().length in 2..120) { "Enter the fluent signer's name" }
        require(signLanguage.trim().length in 2..40) { "Enter the sign language that was reviewed" }
        require(reviewedAtEpochMillis > 0L)
        require(SHA256.matches(motionSha256)) { "Motion digest must be SHA-256" }
        require(notes.length <= 500)
    }

    fun hasCompleteChecklist(): Boolean = confirmedCriteria.containsAll(SignerReviewCriterion.entries)

    fun checklistStatus(): String =
        if (hasCompleteChecklist()) "structured checklist complete" else "legacy review without structured checklist"

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

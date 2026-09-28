package org.projectnia.app.avatar

data class SignerReview(
    val reviewerName: String,
    val signLanguage: String,
    val reviewedAtEpochMillis: Long,
    val motionSha256: String,
    val notes: String = "",
) {
    init {
        require(reviewerName.trim().length in 2..120) { "Enter the fluent signer's name" }
        require(signLanguage.trim().length in 2..40) { "Enter the sign language that was reviewed" }
        require(reviewedAtEpochMillis > 0L)
        require(SHA256.matches(motionSha256)) { "Motion digest must be SHA-256" }
        require(notes.length <= 500)
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

package org.projectnia.app.avatar

data class MotionLibraryEntry(
    val gloss: String,
    val approved: Boolean,
    val signLanguage: String? = null,
    val reviewerName: String? = null,
)

data class MotionLibrarySummary(
    val total: Int,
    val approved: Int,
    val drafts: Int,
) {
    fun displayText(): String = when (total) {
        0 -> "Avatar library: no motions yet"
        else -> "Avatar library: $total total | $approved approved | $drafts awaiting review"
    }
}

object MotionLibraryPresenter {
    fun summarize(entries: List<MotionLibraryEntry>): MotionLibrarySummary {
        val approved = entries.count { it.approved }
        return MotionLibrarySummary(
            total = entries.size,
            approved = approved,
            drafts = entries.size - approved,
        )
    }

    fun label(entry: MotionLibraryEntry): String {
        val gloss = entry.gloss.uppercase()
        return if (entry.approved) {
            val language = entry.signLanguage?.takeIf { it.isNotBlank() } ?: "sign language"
            "$gloss  |  APPROVED ($language)"
        } else {
            "$gloss  |  DRAFT - needs fluent signer review"
        }
    }
}

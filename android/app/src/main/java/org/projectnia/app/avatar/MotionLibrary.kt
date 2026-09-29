package org.projectnia.app.avatar

data class MotionLibraryEntry(
    val gloss: String,
    val approved: Boolean,
    val available: Boolean = true,
    val signLanguage: String? = null,
    val reviewerName: String? = null,
)

data class MotionLibrarySummary(
    val total: Int,
    val approved: Int,
    val drafts: Int,
    val missing: Int,
) {
    fun displayText(): String = when (total) {
        0 -> "Avatar library: no motions yet"
        else -> "Avatar signs: $approved/$total approved | $drafts drafts | $missing not recorded"
    }
}

enum class MotionLibraryAction {
    START_CAPTURE,
    REVIEW_DRAFT,
    PREVIEW_APPROVED,
}

object MotionLibraryPresenter {
    fun summarize(entries: List<MotionLibraryEntry>): MotionLibrarySummary {
        val approved = entries.count { it.approved }
        val drafts = entries.count { it.available && !it.approved }
        return MotionLibrarySummary(
            total = entries.size,
            approved = approved,
            drafts = drafts,
            missing = entries.size - approved - drafts,
        )
    }

    fun label(entry: MotionLibraryEntry): String {
        val gloss = entry.gloss.uppercase()
        return if (!entry.available) {
            "$gloss  |  NOT RECORDED"
        } else if (entry.approved) {
            val language = entry.signLanguage?.takeIf { it.isNotBlank() } ?: "sign language"
            "$gloss  |  APPROVED ($language)"
        } else {
            "$gloss  |  DRAFT - needs fluent signer review"
        }
    }

    fun actionFor(entry: MotionLibraryEntry): MotionLibraryAction = when {
        entry.approved -> MotionLibraryAction.PREVIEW_APPROVED
        entry.available -> MotionLibraryAction.REVIEW_DRAFT
        else -> MotionLibraryAction.START_CAPTURE
    }
}

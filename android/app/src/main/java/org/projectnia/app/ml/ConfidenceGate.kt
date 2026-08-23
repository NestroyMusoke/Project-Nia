package org.projectnia.app.ml

data class Recognition(
    val label: String?,
    val topLabel: String,
    val confidence: Float,
    val margin: Float,
    val needsClarification: Boolean,
)

/** Operational defaults only; these thresholds were not selected on the final test set. */
class ConfidenceGate(
    private val minimumConfidence: Float = 0.55f,
    private val minimumMargin: Float = 0.12f,
) {
    fun decide(probabilities: FloatArray): Recognition {
        require(probabilities.size == NiaVocabulary.labels.size)
        val ranked = probabilities.indices.sortedByDescending { probabilities[it] }
        val top = ranked[0]
        val second = ranked[1]
        val margin = probabilities[top] - probabilities[second]
        val accepted = probabilities[top] >= minimumConfidence && margin >= minimumMargin
        return Recognition(
            label = NiaVocabulary.labels[top].takeIf { accepted },
            topLabel = NiaVocabulary.labels[top],
            confidence = probabilities[top],
            margin = margin,
            needsClarification = !accepted,
        )
    }
}


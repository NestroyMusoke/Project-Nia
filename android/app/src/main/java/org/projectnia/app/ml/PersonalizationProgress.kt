package org.projectnia.app.ml

data class PersonalizationProgress(
    val collectedSamples: Int,
    val requiredSamples: Int,
    val completedSigns: Int,
    val totalSigns: Int,
) {
    val active: Boolean get() = collectedSamples == requiredSamples

    fun displayText(): String = if (active) {
        "Personalization: ACTIVE | $collectedSamples/$requiredSamples samples"
    } else {
        "Personalization: inactive | $collectedSamples/$requiredSamples samples | $completedSigns/$totalSigns signs complete"
    }
}

object PersonalizationProgressPresenter {
    fun summarize(counts: List<Int>, shotsPerSign: Int): PersonalizationProgress {
        require(shotsPerSign > 0)
        require(counts.all { it in 0..shotsPerSign })
        return PersonalizationProgress(
            collectedSamples = counts.sum(),
            requiredSamples = counts.size * shotsPerSign,
            completedSigns = counts.count { it == shotsPerSign },
            totalSigns = counts.size,
        )
    }

    fun label(gloss: String, count: Int, shotsPerSign: Int): String {
        require(count in 0..shotsPerSign)
        val status = if (count == shotsPerSign) "COMPLETE" else "$count/$shotsPerSign samples"
        return "${gloss.uppercase()}  |  $status"
    }
}

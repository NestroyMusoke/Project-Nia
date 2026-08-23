package org.projectnia.app.ml

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Reproduces the frozen 3-shot / 0.65 blend / temperature 12 protocol.
 * The published 92.11% claim applies only when all 32 classes have exactly
 * three calibration examples and is never used to tune this implementation.
 */
class PersonalizationMemory {
    companion object {
        const val SHOTS_PER_SIGN = 3
        const val CLASSIFIER_WEIGHT = 0.35f
        const val PROTOTYPE_WEIGHT = 0.65f
        const val TEMPERATURE = 12f
    }

    private val examples = Array(NiaModel.CLASS_COUNT) { mutableListOf<FloatArray>() }

    @Synchronized
    fun addCorrection(classId: Int, embedding: FloatArray): Boolean {
        require(classId in 0 until NiaModel.CLASS_COUNT)
        require(embedding.size == NiaModel.EMBEDDING_SIZE)
        if (examples[classId].size >= SHOTS_PER_SIGN) return false
        examples[classId] += normalize(embedding)
        return true
    }

    @Synchronized
    fun apply(generalProbabilities: FloatArray, embedding: FloatArray): FloatArray {
        require(generalProbabilities.size == NiaModel.CLASS_COUNT)
        val query = normalize(embedding)
        val scores = FloatArray(NiaModel.CLASS_COUNT)
        examples.indices.forEach { classId ->
            val classExamples = examples[classId]
            if (classExamples.isNotEmpty()) {
                val prototype = normalize(FloatArray(NiaModel.EMBEDDING_SIZE) { d ->
                    classExamples.sumOf { it[d].toDouble() }.toFloat() / classExamples.size
                })
                scores[classId] = dot(query, prototype)
            }
        }
        val prototypeProbabilities = softmax(scores, TEMPERATURE)
        return FloatArray(NiaModel.CLASS_COUNT) { classId ->
            CLASSIFIER_WEIGHT * generalProbabilities[classId] +
                PROTOTYPE_WEIGHT * prototypeProbabilities[classId]
        }
    }

    fun count(classId: Int): Int = examples[classId].size
    fun hasAny(): Boolean = examples.any { it.isNotEmpty() }
    fun isFrozenProtocolComplete(): Boolean = examples.all { it.size == SHOTS_PER_SIGN }

    @Synchronized
    fun snapshot(): List<List<FloatArray>> = examples.map { classExamples ->
        classExamples.map(FloatArray::copyOf)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat() + 1e-8f
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var result = 0f
        for (i in a.indices) result += a[i] * b[i]
        return result
    }

    private fun softmax(values: FloatArray, temperature: Float): FloatArray {
        val scaled = DoubleArray(values.size) { values[it] * temperature.toDouble() }
        val max = scaled.max()
        val exponentials = DoubleArray(values.size) { exp(scaled[it] - max) }
        val sum = exponentials.sum()
        return FloatArray(values.size) { (exponentials[it] / sum).toFloat() }
    }
}

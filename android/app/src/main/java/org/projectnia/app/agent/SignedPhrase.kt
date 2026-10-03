package org.projectnia.app.agent

import java.util.Locale

data class RecognizedSignToken(
    val label: String,
    val confidence: Float,
    val margin: Float,
    val userCorrected: Boolean = false,
) {
    init {
        require(label.isNotBlank())
        require(confidence.isFinite() && confidence in 0f..1f)
        require(margin.isFinite() && margin in -1f..1f)
    }
}

class SignedPhraseBuffer(private val capacity: Int = MAX_TOKENS) {
    private val tokens = mutableListOf<RecognizedSignToken>()

    init {
        require(capacity > 0)
    }

    fun append(token: RecognizedSignToken): Boolean {
        if (tokens.size >= capacity) return false
        tokens += token
        return true
    }

    fun replaceLast(token: RecognizedSignToken): Boolean {
        if (tokens.isEmpty()) return false
        tokens[tokens.lastIndex] = token
        return true
    }

    fun removeLast(): Boolean {
        if (tokens.isEmpty()) return false
        tokens.removeAt(tokens.lastIndex)
        return true
    }

    fun clear() = tokens.clear()

    fun snapshot(): List<RecognizedSignToken> = tokens.toList()

    fun isEmpty(): Boolean = tokens.isEmpty()

    fun isFull(): Boolean = tokens.size >= capacity

    fun size(): Int = tokens.size

    fun displayText(): String = if (tokens.isEmpty()) {
        "Signed phrase: empty (0/$capacity)"
    } else {
        "Signed phrase: ${tokens.joinToString(" ") { it.label.uppercase(Locale.ROOT) }} (${tokens.size}/$capacity)"
    }

    companion object {
        const val MAX_TOKENS = 20
    }
}

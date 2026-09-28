package org.projectnia.app.avatar

import java.util.Locale

data class SignedMessagePlan(
    val glosses: List<String>,
    val unsupportedWords: List<String>,
    val usesFingerspelling: Boolean,
) {
    val isPlayable: Boolean get() = glosses.isNotEmpty() && unsupportedWords.isEmpty()
}

/**
 * Plans only motions that actually exist on the device. Unsupported words are
 * never silently dropped. A word falls back to fingerspelling only when every
 * required letter has its own approved `fs_<letter>` motion.
 */
object SignedMessagePlanner {
    private val aliases = mapOf("hi" to "hello", "hey" to "hello")

    fun plan(message: String, availableGlosses: Set<String>): SignedMessagePlan {
        val words = message
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_ ]"), " ")
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)

        val planned = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        var fingerspelled = false
        words.forEach { original ->
            val word = aliases[original] ?: original
            when {
                word in availableGlosses -> planned += word
                word.isNotEmpty() -> {
                    val letters = word.map { "fs_$it" }
                    if (letters.all(availableGlosses::contains)) {
                        planned += letters
                        fingerspelled = true
                    } else {
                        unsupported += original
                    }
                }
            }
        }
        return SignedMessagePlan(planned, unsupported.distinct(), fingerspelled)
    }
}

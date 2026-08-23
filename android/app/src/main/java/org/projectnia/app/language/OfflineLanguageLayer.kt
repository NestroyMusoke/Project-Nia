package org.projectnia.app.language

interface OfflineLanguageLayer {
    suspend fun naturalize(isolatedSigns: List<String>): String
}

class PassThroughLanguageLayer : OfflineLanguageLayer {
    override suspend fun naturalize(isolatedSigns: List<String>): String =
        isolatedSigns.joinToString(" ")
}

/**
 * Deliberate extension seam for LiteRT-LM/Gemma. Core visual inference does
 * not depend on a language model and remains available offline without it.
 */
class GemmaLanguageLayer : OfflineLanguageLayer {
    override suspend fun naturalize(isolatedSigns: List<String>): String {
        error("Gemma is optional and has not been enabled in the core milestone")
    }
}


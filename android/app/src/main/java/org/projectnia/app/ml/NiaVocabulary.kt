package org.projectnia.app.ml

object NiaVocabulary {
    val labels = listOf(
        "hello", "bye", "please", "thankyou",
        "yes", "no", "fine", "same",
        "wait", "say", "talk", "listen", "look", "see",
        "where", "who", "why",
        "give", "go", "find", "finish", "open", "close",
        "now", "later", "tomorrow", "time",
        "home", "person", "sick", "police", "callonphone",
    )

    init {
        require(labels.size == NiaModel.CLASS_COUNT)
    }
}


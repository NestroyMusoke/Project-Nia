package org.projectnia.app.avatar

enum class AvatarPlaybackSpeed(val timeScale: Float, val displayName: String) {
    VERY_SLOW(0.28f, "Very slow"),
    SLOW(0.42f, "Slow"),
    RELAXED(0.58f, "Relaxed"),
    ;

    fun next(): AvatarPlaybackSpeed {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

object AvatarPlaybackPresenter {
    fun speedLabel(speed: AvatarPlaybackSpeed): String = "Speed: ${speed.displayName}"
}

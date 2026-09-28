package org.projectnia.app.ml

data class CaptureQuality(
    val accepted: Boolean,
    val message: String,
    val handVisibleRatio: Float,
    val shouldersVisibleRatio: Float,
)

/**
 * Rejects unusable recordings before the frozen model sees them. These are
 * operational camera-quality checks, not thresholds selected on the final test set.
 */
class CaptureQualityGate(
    private val minimumFrames: Int = 12,
    private val minimumHandVisibleRatio: Float = 0.60f,
    private val minimumShouldersVisibleRatio: Float = 0.70f,
) {
    fun evaluate(frames: List<LandmarkFrame>): CaptureQuality {
        if (frames.size < minimumFrames) {
            return CaptureQuality(
                accepted = false,
                message = "Record the complete sign for a little longer",
                handVisibleRatio = visibleRatio(frames, ::hasUsableHand),
                shouldersVisibleRatio = visibleRatio(frames, ::hasShoulders),
            )
        }

        val handRatio = visibleRatio(frames, ::hasUsableHand)
        if (handRatio < minimumHandVisibleRatio) {
            return CaptureQuality(
                accepted = false,
                message = "Keep at least one complete hand inside the camera frame",
                handVisibleRatio = handRatio,
                shouldersVisibleRatio = visibleRatio(frames, ::hasShoulders),
            )
        }

        val shouldersRatio = visibleRatio(frames, ::hasShoulders)
        if (shouldersRatio < minimumShouldersVisibleRatio) {
            return CaptureQuality(
                accepted = false,
                message = "Move back so your head, shoulders and hands are visible",
                handVisibleRatio = handRatio,
                shouldersVisibleRatio = shouldersRatio,
            )
        }

        return CaptureQuality(
            accepted = true,
            message = "Capture quality is sufficient",
            handVisibleRatio = handRatio,
            shouldersVisibleRatio = shouldersRatio,
        )
    }

    private fun hasUsableHand(frame: LandmarkFrame): Boolean =
        maxOf(validCount(frame.leftHand), validCount(frame.rightHand)) >= MINIMUM_HAND_POINTS

    private fun hasShoulders(frame: LandmarkFrame): Boolean =
        frame.pose.getOrNull(11).isFinitePoint() && frame.pose.getOrNull(12).isFinitePoint()

    private fun validCount(points: List<Point3?>): Int = points.count { it.isFinitePoint() }

    private fun Point3?.isFinitePoint(): Boolean =
        this != null && x.isFinite() && y.isFinite()

    private fun visibleRatio(
        frames: List<LandmarkFrame>,
        predicate: (LandmarkFrame) -> Boolean,
    ): Float = if (frames.isEmpty()) 0f else frames.count(predicate).toFloat() / frames.size

    private companion object {
        const val MINIMUM_HAND_POINTS = 15
    }
}

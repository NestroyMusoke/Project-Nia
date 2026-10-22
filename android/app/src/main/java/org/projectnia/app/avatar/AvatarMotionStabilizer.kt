package org.projectnia.app.avatar

import kotlin.math.sqrt

data class AvatarStabilizationResult(
    val clip: AvatarMotionClip,
    val repairedSingleFrameGaps: Int,
    val correctedTrackingSpikes: Int,
)

/**
 * Repairs only isolated tracking failures in a captured avatar draft.
 *
 * This is deliberately not a general smoothing filter: ordinary motion and
 * sustained handshape changes pass through unchanged. A point is replaced
 * only when both neighbouring frames agree and the middle frame is an obvious
 * spatial outlier, or when one missing frame sits between two tracked points.
 */
object AvatarMotionStabilizer {
    fun stabilize(clip: AvatarMotionClip): AvatarStabilizationResult {
        if (clip.frames.size < 3) return AvatarStabilizationResult(clip, 0, 0)
        var repairedGaps = 0
        var correctedSpikes = 0

        fun stabilizeGroup(
            frameIndex: Int,
            selector: (AvatarMotionFrame) -> List<AvatarJoint?>,
        ): List<AvatarJoint?> {
            val previous = selector(clip.frames[frameIndex - 1])
            val current = selector(clip.frames[frameIndex])
            val next = selector(clip.frames[frameIndex + 1])
            return List(maxOf(previous.size, current.size, next.size)) { jointIndex ->
                val before = previous.getOrNull(jointIndex)?.takeIf(::finite)
                val value = current.getOrNull(jointIndex)?.takeIf(::finite)
                val after = next.getOrNull(jointIndex)?.takeIf(::finite)
                when {
                    value == null && before != null && after != null -> {
                        repairedGaps += 1
                        midpoint(before, after)
                    }
                    value != null && before != null && after != null &&
                        distance(before, after) <= MAX_NEIGHBOUR_DISTANCE &&
                        distance(value, midpoint(before, after)) >= MIN_SPIKE_DISTANCE -> {
                        correctedSpikes += 1
                        midpoint(before, after)
                    }
                    else -> value
                }
            }
        }

        val stabilized = clip.frames.mapIndexed { index, frame ->
            if (index == 0 || index == clip.frames.lastIndex) return@mapIndexed frame
            AvatarMotionFrame(
                pose = stabilizeGroup(index, AvatarMotionFrame::pose),
                leftHand = stabilizeGroup(index, AvatarMotionFrame::leftHand),
                rightHand = stabilizeGroup(index, AvatarMotionFrame::rightHand),
                face = stabilizeFace(
                    clip.frames[index - 1].face,
                    frame.face,
                    clip.frames[index + 1].face,
                ) { correctedSpikes += 1 },
            )
        }
        return AvatarStabilizationResult(
            clip = clip.copy(frames = stabilized),
            repairedSingleFrameGaps = repairedGaps,
            correctedTrackingSpikes = correctedSpikes,
        )
    }

    private fun stabilizeFace(
        before: AvatarFacePose,
        value: AvatarFacePose,
        after: AvatarFacePose,
        onCorrection: () -> Unit,
    ) = AvatarFacePose(
        mouthOpen = stabilizeScalar(before.mouthOpen, value.mouthOpen, after.mouthOpen, onCorrection),
        browRaise = stabilizeScalar(before.browRaise, value.browRaise, after.browRaise, onCorrection),
        eyeOpen = stabilizeScalar(before.eyeOpen, value.eyeOpen, after.eyeOpen, onCorrection),
        tracked = value.tracked,
    )

    private fun stabilizeScalar(before: Float, value: Float, after: Float, onCorrection: () -> Unit): Float {
        if (!before.isFinite() || !value.isFinite() || !after.isFinite()) return value
        val midpoint = (before + after) / 2f
        return if (
            kotlin.math.abs(before - after) <= MAX_FACE_NEIGHBOUR_DISTANCE &&
            kotlin.math.abs(value - midpoint) >= MIN_FACE_SPIKE_DISTANCE
        ) {
            onCorrection()
            midpoint
        } else value
    }

    private fun midpoint(first: AvatarJoint, second: AvatarJoint) = AvatarJoint(
        x = (first.x + second.x) / 2f,
        y = (first.y + second.y) / 2f,
        z = (first.z + second.z) / 2f,
    )

    private fun distance(first: AvatarJoint, second: AvatarJoint): Float {
        val x = first.x - second.x
        val y = first.y - second.y
        val z = first.z - second.z
        return sqrt(x * x + y * y + z * z)
    }

    private fun finite(joint: AvatarJoint): Boolean =
        joint.x.isFinite() && joint.y.isFinite() && joint.z.isFinite()

    private const val MAX_NEIGHBOUR_DISTANCE = 0.18f
    private const val MIN_SPIKE_DISTANCE = 0.55f
    private const val MAX_FACE_NEIGHBOUR_DISTANCE = 0.12f
    private const val MIN_FACE_SPIKE_DISTANCE = 0.45f
}

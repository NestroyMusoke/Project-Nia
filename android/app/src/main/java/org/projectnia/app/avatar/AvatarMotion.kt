package org.projectnia.app.avatar

import org.projectnia.app.ml.LandmarkFrame
import org.projectnia.app.ml.Point3
import kotlin.math.hypot

data class AvatarJoint(
    val x: Float,
    val y: Float,
    val z: Float,
)

data class AvatarMotionFrame(
    val pose: List<AvatarJoint?>,
    val leftHand: List<AvatarJoint?>,
    val rightHand: List<AvatarJoint?>,
    val face: AvatarFacePose = AvatarFacePose(),
)

data class AvatarFacePose(
    val mouthOpen: Float = 0f,
    val browRaise: Float = 0.5f,
    val eyeOpen: Float = 1f,
)

data class AvatarMotionClip(
    val gloss: String,
    val frames: List<AvatarMotionFrame>,
    val signerValidated: Boolean = false,
    val framesPerSecond: Int = 30,
) {
    init {
        require(gloss.isNotBlank())
        require(frames.isNotEmpty())
        require(framesPerSecond in 1..120)
    }
}

/**
 * Retargets MediaPipe landmarks into a signer-independent avatar coordinate system.
 * This does not decide linguistic meaning: a captured clip remains a draft until a
 * fluent signer validates the label and motion.
 */
object AvatarMotionRetargeter {
    const val OUTPUT_FRAMES = 64

    fun fromLandmarks(gloss: String, source: List<LandmarkFrame>): AvatarMotionClip {
        require(source.size >= 2) { "At least two landmark frames are required" }
        val sampled = List(OUTPUT_FRAMES) { outputIndex ->
            val position = outputIndex.toFloat() * (source.lastIndex.toFloat() / (OUTPUT_FRAMES - 1))
            val low = position.toInt().coerceIn(source.indices)
            val high = (low + 1).coerceAtMost(source.lastIndex)
            val amount = position - low
            interpolate(source[low], source[high], amount)
        }
        return AvatarMotionClip(gloss = gloss, frames = sampled)
    }

    private fun interpolate(a: LandmarkFrame, b: LandmarkFrame, t: Float): AvatarMotionFrame {
        val anchorA = bodyAnchor(a)
        val anchorB = bodyAnchor(b)
        return AvatarMotionFrame(
            pose = interpolateGroup(a.pose, b.pose, anchorA, anchorB, t),
            leftHand = interpolateGroup(a.leftHand, b.leftHand, anchorA, anchorB, t),
            rightHand = interpolateGroup(a.rightHand, b.rightHand, anchorA, anchorB, t),
            face = interpolateFace(facePose(a), facePose(b), t),
        )
    }

    private fun interpolateGroup(
        a: List<Point3?>,
        b: List<Point3?>,
        anchorA: BodyAnchor,
        anchorB: BodyAnchor,
        t: Float,
    ): List<AvatarJoint?> = List(a.size) { index ->
        val first = a[index]?.takeIf(::finite)?.let { normalize(it, anchorA) }
        val second = b.getOrNull(index)?.takeIf(::finite)?.let { normalize(it, anchorB) }
        when {
            first != null && second != null -> AvatarJoint(
                lerp(first.x, second.x, t),
                lerp(first.y, second.y, t),
                lerp(first.z, second.z, t),
            )
            t < 0.5f -> first ?: second
            else -> second ?: first
        }
    }

    private fun bodyAnchor(frame: LandmarkFrame): BodyAnchor {
        val leftShoulder = frame.pose.getOrNull(11)?.takeIf(::finite)
        val rightShoulder = frame.pose.getOrNull(12)?.takeIf(::finite)
        if (leftShoulder != null && rightShoulder != null) {
            val scale = hypot(leftShoulder.x - rightShoulder.x, leftShoulder.y - rightShoulder.y)
                .coerceAtLeast(0.05f)
            return BodyAnchor(
                (leftShoulder.x + rightShoulder.x) / 2f,
                (leftShoulder.y + rightShoulder.y) / 2f,
                (leftShoulder.z + rightShoulder.z) / 2f,
                scale,
            )
        }
        return BodyAnchor(0.5f, 0.42f, 0f, 0.28f)
    }

    private fun normalize(point: Point3, anchor: BodyAnchor): AvatarJoint = AvatarJoint(
        x = (point.x - anchor.x) / anchor.scale,
        y = (anchor.y - point.y) / anchor.scale,
        z = -(point.z - anchor.z) / anchor.scale,
    )

    private fun finite(point: Point3): Boolean =
        point.x.isFinite() && point.y.isFinite() && point.z.isFinite()

    private fun facePose(frame: LandmarkFrame): AvatarFacePose {
        fun distance(first: Int, second: Int): Float? {
            val a = frame.face.getOrNull(first) ?: return null
            val b = frame.face.getOrNull(second) ?: return null
            if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) return null
            return hypot(a.x - b.x, a.y - b.y)
        }
        val faceWidth = distance(33, 263)?.coerceAtLeast(0.01f) ?: return AvatarFacePose()
        val mouth = ((distance(13, 14) ?: 0f) / faceWidth * 8f).coerceIn(0f, 1f)
        val eyeDistances = listOfNotNull(distance(159, 145), distance(386, 374))
        val eye = if (eyeDistances.isEmpty()) 1f else {
            (eyeDistances.average().toFloat() / faceWidth * 12f).coerceIn(0.15f, 1f)
        }
        val browDistances = listOfNotNull(distance(105, 159), distance(334, 386))
        val browDistance = if (browDistances.isEmpty()) 0.16f else {
            browDistances.average().toFloat() / faceWidth
        }
        val brow = ((browDistance - 0.08f) * 6f).coerceIn(0f, 1f)
        return AvatarFacePose(mouth, brow, eye)
    }

    private fun interpolateFace(a: AvatarFacePose, b: AvatarFacePose, t: Float) = AvatarFacePose(
        mouthOpen = lerp(a.mouthOpen, b.mouthOpen, t),
        browRaise = lerp(a.browRaise, b.browRaise, t),
        eyeOpen = lerp(a.eyeOpen, b.eyeOpen, t),
    )

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private data class BodyAnchor(
        val x: Float,
        val y: Float,
        val z: Float,
        val scale: Float,
    )
}

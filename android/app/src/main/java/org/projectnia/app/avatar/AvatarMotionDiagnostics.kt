package org.projectnia.app.avatar

import java.util.Locale

data class AvatarTrackingDiagnostics(
    val leftHandCoverage: Float,
    val rightHandCoverage: Float,
    val leftArmCoverage: Float,
    val rightArmCoverage: Float,
    val faceCoverage: Float,
) {
    fun displayText(): String = String.format(
        Locale.ROOT,
        "Avatar tracking | hands L %.0f%% R %.0f%% | arms L %.0f%% R %.0f%% | face %.0f%%",
        leftHandCoverage * 100f,
        rightHandCoverage * 100f,
        leftArmCoverage * 100f,
        rightArmCoverage * 100f,
        faceCoverage * 100f,
    )

    fun reviewGuidance(): String = when {
        maxOf(leftHandCoverage, rightHandCoverage) < MIN_PRIMARY_COVERAGE ->
            "Re-record recommended: neither hand was tracked reliably."
        maxOf(leftArmCoverage, rightArmCoverage) < MIN_PRIMARY_COVERAGE ->
            "Re-record recommended: arm tracking is incomplete."
        faceCoverage < MIN_FACE_COVERAGE ->
            "Face tracking is limited. A fluent signer must inspect non-manual information carefully."
        minOf(leftHandCoverage, rightHandCoverage) < MIN_SECONDARY_COVERAGE ->
            "One hand has limited tracking. Confirm whether this sign requires both hands."
        else -> "Tracking coverage is suitable for visual signer review."
    }

    private companion object {
        const val MIN_PRIMARY_COVERAGE = 0.70f
        const val MIN_SECONDARY_COVERAGE = 0.45f
        const val MIN_FACE_COVERAGE = 0.60f
    }
}

object AvatarMotionDiagnostics {
    fun analyze(clip: AvatarMotionClip): AvatarTrackingDiagnostics {
        val frames = clip.frames
        return AvatarTrackingDiagnostics(
            leftHandCoverage = ratio(frames) { validCount(it.leftHand) >= MINIMUM_HAND_POINTS },
            rightHandCoverage = ratio(frames) { validCount(it.rightHand) >= MINIMUM_HAND_POINTS },
            leftArmCoverage = ratio(frames) { hasArm(it.pose, 11, 13, 15) },
            rightArmCoverage = ratio(frames) { hasArm(it.pose, 12, 14, 16) },
            faceCoverage = ratio(frames) { it.face.tracked },
        )
    }

    private fun validCount(joints: List<AvatarJoint?>): Int = joints.count { joint ->
        joint != null && joint.x.isFinite() && joint.y.isFinite() && joint.z.isFinite()
    }

    private fun hasArm(joints: List<AvatarJoint?>, shoulder: Int, elbow: Int, wrist: Int): Boolean =
        listOf(shoulder, elbow, wrist).all { index ->
            joints.getOrNull(index)?.let { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() } == true
        }

    private fun ratio(frames: List<AvatarMotionFrame>, predicate: (AvatarMotionFrame) -> Boolean): Float =
        if (frames.isEmpty()) 0f else frames.count(predicate).toFloat() / frames.size

    private const val MINIMUM_HAND_POINTS = 15
}

package org.projectnia.app.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarMotionDiagnosticsTest {
    @Test
    fun reportsEachTrackingChannelSeparately() {
        val clip = AvatarMotionClip(
            gloss = "hello",
            frames = listOf(
                frame(leftHand = true, rightHand = false, leftArm = true, rightArm = true, face = true),
                frame(leftHand = true, rightHand = true, leftArm = true, rightArm = false, face = false),
            ),
        )

        val result = AvatarMotionDiagnostics.analyze(clip)

        assertEquals(1f, result.leftHandCoverage)
        assertEquals(0.5f, result.rightHandCoverage)
        assertEquals(1f, result.leftArmCoverage)
        assertEquals(0.5f, result.rightArmCoverage)
        assertEquals(0.5f, result.faceCoverage)
        assertTrue(result.displayText().contains("hands L 100% R 50%"))
    }

    @Test
    fun warnsWithoutAssumingEverySignIsTwoHanded() {
        val oneHanded = AvatarTrackingDiagnostics(1f, 0.1f, 1f, 1f, 1f)
        val noHands = AvatarTrackingDiagnostics(0.2f, 0.3f, 1f, 1f, 1f)

        assertTrue(oneHanded.reviewGuidance().contains("Confirm whether"))
        assertTrue(noHands.reviewGuidance().contains("Re-record recommended"))
    }

    private fun frame(
        leftHand: Boolean,
        rightHand: Boolean,
        leftArm: Boolean,
        rightArm: Boolean,
        face: Boolean,
    ): AvatarMotionFrame {
        val joint = AvatarJoint(0f, 0f, 0f)
        val pose = MutableList<AvatarJoint?>(33) { null }
        if (leftArm) listOf(11, 13, 15).forEach { pose[it] = joint }
        if (rightArm) listOf(12, 14, 16).forEach { pose[it] = joint }
        return AvatarMotionFrame(
            pose = pose,
            leftHand = if (leftHand) List(21) { joint } else emptyList(),
            rightHand = if (rightHand) List(21) { joint } else emptyList(),
            face = AvatarFacePose(tracked = face),
        )
    }
}

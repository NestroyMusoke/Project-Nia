package org.projectnia.app.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AvatarMotionStabilizerTest {
    @Test
    fun repairsSingleFrameGapAndObviousTrackerSpike() {
        val clip = clip(
            joint(0f),
            null,
            joint(0.1f),
            joint(2f),
            joint(0.12f),
        )

        val result = AvatarMotionStabilizer.stabilize(clip)

        assertEquals(0.05f, result.clip.frames[1].leftHand[0]!!.x, 0.0001f)
        assertEquals(0.11f, result.clip.frames[3].leftHand[0]!!.x, 0.0001f)
        assertEquals(1, result.repairedSingleFrameGaps)
        assertEquals(1, result.correctedTrackingSpikes)
        assertFalse(result.clip.signerValidated)
    }

    @Test
    fun preservesFastButConsistentDirectionalMovement() {
        val clip = clip(joint(0f), joint(0.5f), joint(1f))

        val result = AvatarMotionStabilizer.stabilize(clip)

        assertEquals(0.5f, result.clip.frames[1].leftHand[0]!!.x, 0.0001f)
        assertEquals(0, result.correctedTrackingSpikes)
        assertEquals(0, result.repairedSingleFrameGaps)
    }

    @Test
    fun preservesClipIdentityTimingAndApprovalFlag() {
        val approved = clip(joint(0f), joint(0.1f), joint(0.2f)).copy(
            signerValidated = true,
            framesPerSecond = 24,
        )

        val result = AvatarMotionStabilizer.stabilize(approved).clip

        assertEquals("hello", result.gloss)
        assertEquals(24, result.framesPerSecond)
        assertEquals(true, result.signerValidated)
    }

    private fun clip(vararg joints: AvatarJoint?): AvatarMotionClip = AvatarMotionClip(
        gloss = "hello",
        frames = joints.map { value ->
            AvatarMotionFrame(
                pose = emptyList(),
                leftHand = listOf(value),
                rightHand = emptyList(),
            )
        },
    )

    private fun joint(x: Float) = AvatarJoint(x, 0f, 0f)
}

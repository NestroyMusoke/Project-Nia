package org.projectnia.app.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.projectnia.app.ml.LandmarkFrame
import org.projectnia.app.ml.Point3

class AvatarMotionRetargeterTest {
    @Test
    fun producesFixedLengthFiniteMotionWithoutApprovingIt() {
        val source = List(12) { frameIndex -> frame(frameIndex / 11f) }
        val clip = AvatarMotionRetargeter.fromLandmarks("hello", source)

        assertEquals(64, clip.frames.size)
        assertFalse(clip.signerValidated)
        val first = assertNotNull(clip.frames.first().pose[11])
        val lastWrist = assertNotNull(clip.frames.last().rightHand[0])
        assertTrue(first.x.isFinite() && first.y.isFinite() && first.z.isFinite())
        assertTrue(lastWrist.x > first.x)
    }

    private fun frame(progress: Float): LandmarkFrame {
        val pose = MutableList<Point3?>(33) { null }
        pose[0] = Point3(0.5f, 0.22f, -0.05f)
        pose[11] = Point3(0.4f, 0.4f, 0f)
        pose[12] = Point3(0.6f, 0.4f, 0f)
        pose[13] = Point3(0.35f, 0.55f, 0f)
        pose[14] = Point3(0.65f, 0.55f, 0f)
        pose[15] = Point3(0.32f, 0.68f, 0f)
        pose[16] = Point3(0.68f + progress * 0.05f, 0.5f, -0.02f)
        pose[23] = Point3(0.44f, 0.72f, 0f)
        pose[24] = Point3(0.56f, 0.72f, 0f)

        fun hand(wristX: Float): List<Point3?> = List(21) { index ->
            Point3(wristX + index * 0.002f, 0.5f - index * 0.004f, -index * 0.001f)
        }
        return LandmarkFrame(
            leftHand = hand(0.32f),
            rightHand = hand(0.68f + progress * 0.05f),
            pose = pose,
        )
    }
}

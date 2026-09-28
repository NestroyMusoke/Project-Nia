package org.projectnia.app.ml

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureQualityGateTest {
    private val gate = CaptureQualityGate()

    @Test
    fun rejectsShortOrMissingLandmarkCaptures() {
        assertFalse(gate.evaluate(List(6) { goodFrame() }).accepted)
        assertFalse(gate.evaluate(List(20) { LandmarkFrame() }).accepted)
    }

    @Test
    fun acceptsCaptureWithStableHandAndShoulderVisibility() {
        val result = gate.evaluate(List(20) { goodFrame() })
        assertTrue(result.accepted)
        assertTrue(result.handVisibleRatio == 1f)
        assertTrue(result.shouldersVisibleRatio == 1f)
    }

    private fun goodFrame(): LandmarkFrame {
        val hand = List<Point3?>(21) { Point3(0.4f, 0.4f, 0f) }
        val pose = MutableList<Point3?>(33) { null }
        pose[11] = Point3(0.4f, 0.5f, 0f)
        pose[12] = Point3(0.6f, 0.5f, 0f)
        return LandmarkFrame(leftHand = hand, pose = pose)
    }
}

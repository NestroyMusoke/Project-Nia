package org.projectnia.app.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V3PreprocessorTest {
    @Test
    fun producesFiniteExactShapeAndCanonicalizesLeftDominance() {
        val frames = List(10) { frame ->
            val pose = MutableList<Point3?>(33) { null }
            pose[11] = Point3(0.4f, 0.5f)
            pose[12] = Point3(0.6f, 0.5f)
            val face = MutableList<Point3?>(478) { null }
            V3Preprocessor.LIP_INDICES.forEach { face[it] = Point3(0.5f, 0.4f) }
            val left = MutableList<Point3?>(21) { null }
            if (frame in 2..7) {
                repeat(21) { i -> left[i] = Point3(0.42f + i * 0.001f, 0.48f, i * 0.001f) }
            }
            LandmarkFrame(leftHand = left, pose = pose, face = face)
        }

        val output = V3Preprocessor().process(frames)

        assertEquals(64, output.size)
        assertTrue(output.all { row -> row.size == 272 && row.all { it.isFinite() } })
        assertTrue(output.map { it[268] }.average() < 0.01)
        assertTrue(output.map { it[269] }.average() > 0.5)
    }

    @Test
    fun missingModalitiesBecomeZerosNotNaNs() {
        val output = V3Preprocessor().process(List(6) { LandmarkFrame() })
        assertTrue(output.all { row -> row.all { it == 0f } })
    }
}

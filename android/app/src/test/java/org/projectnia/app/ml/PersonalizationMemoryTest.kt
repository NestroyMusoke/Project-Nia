package org.projectnia.app.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalizationMemoryTest {
    @Test
    fun partialCalibrationDoesNotChangeFrozenClassifierOutput() {
        val memory = PersonalizationMemory()
        val embedding = FloatArray(384).also { it[0] = 1f }
        repeat(3) { assertTrue(memory.addCorrection(4, embedding)) }
        assertFalse(memory.addCorrection(4, embedding))

        val general = FloatArray(32) { 1f / 32f }
        val personalized = memory.apply(general, embedding)
        assertTrue(general.contentEquals(personalized))
        assertFalse(memory.isFrozenProtocolComplete())
        assertEquals(1, memory.completedClassCount())
    }

    @Test
    fun reproducesFrozenBlendOnlyAfterCompleteThreeShotSupportSet() {
        val memory = PersonalizationMemory()
        repeat(32) { classId ->
            val embedding = FloatArray(384).also { it[classId] = 1f }
            repeat(3) { assertTrue(memory.addCorrection(classId, embedding)) }
        }
        val query = FloatArray(384).also { it[4] = 1f }
        val general = FloatArray(32) { 1f / 32f }
        val personalized = memory.apply(general, query)

        assertTrue(memory.isFrozenProtocolComplete())
        assertEquals(1f, personalized.sum(), 1e-5f)
        assertTrue(personalized[4] > personalized[0])
    }

    @Test
    fun clearingOneClassDisablesPersonalizationUntilItIsRecalibrated() {
        val memory = PersonalizationMemory()
        repeat(32) { classId ->
            val embedding = FloatArray(384).also { it[classId] = 1f }
            repeat(3) { memory.addCorrection(classId, embedding) }
        }

        memory.clearClass(4)

        assertEquals(0, memory.count(4))
        assertFalse(memory.isFrozenProtocolComplete())
        assertEquals(31, memory.completedClassCount())
    }
}

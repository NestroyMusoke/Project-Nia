package org.projectnia.app.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalizationMemoryTest {
    @Test
    fun reproducesFrozenCapAndBlend() {
        val memory = PersonalizationMemory()
        val embedding = FloatArray(384).also { it[0] = 1f }
        repeat(3) { assertTrue(memory.addCorrection(4, embedding)) }
        assertFalse(memory.addCorrection(4, embedding))

        val general = FloatArray(32) { 1f / 32f }
        val personalized = memory.apply(general, embedding)
        assertEquals(1f, personalized.sum(), 1e-5f)
        assertTrue(personalized[4] > personalized[0])
    }
}


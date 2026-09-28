package org.projectnia.app.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignedMessagePlannerTest {
    @Test
    fun usesKnownWordMotionAndAlias() {
        val plan = SignedMessagePlanner.plan("Hi", setOf("hello"))
        assertTrue(plan.isPlayable)
        assertEquals(listOf("hello"), plan.glosses)
    }

    @Test
    fun fingerspellsOnlyWhenEveryLetterMotionExists() {
        val alphabet = "nia".mapTo(linkedSetOf()) { "fs_$it" }
        val plan = SignedMessagePlanner.plan("Nia", alphabet)
        assertTrue(plan.isPlayable)
        assertTrue(plan.usesFingerspelling)
        assertEquals(listOf("fs_n", "fs_i", "fs_a"), plan.glosses)
    }

    @Test
    fun neverSilentlyDropsUnsupportedWords() {
        val plan = SignedMessagePlanner.plan("hello friend", setOf("hello"))
        assertFalse(plan.isPlayable)
        assertEquals(listOf("friend"), plan.unsupportedWords)
    }
}

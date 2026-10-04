package org.projectnia.app.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarPlaybackControlsTest {
    @Test
    fun speedCycleIsBoundedAndReturnsToVerySlow() {
        assertEquals(AvatarPlaybackSpeed.SLOW, AvatarPlaybackSpeed.VERY_SLOW.next())
        assertEquals(AvatarPlaybackSpeed.RELAXED, AvatarPlaybackSpeed.SLOW.next())
        assertEquals(AvatarPlaybackSpeed.VERY_SLOW, AvatarPlaybackSpeed.RELAXED.next())
    }

    @Test
    fun everyPresentationSpeedRemainsSlowerThanCapturedTiming() {
        assertTrue(AvatarPlaybackSpeed.entries.all { it.timeScale in 0.2f..0.75f })
        assertEquals("Speed: Slow", AvatarPlaybackPresenter.speedLabel(AvatarPlaybackSpeed.SLOW))
    }
}

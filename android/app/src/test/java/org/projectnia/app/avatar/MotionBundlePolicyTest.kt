package org.projectnia.app.avatar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionBundlePolicyTest {
    @Test
    fun `accepts matching motion and review pairs`() {
        MotionBundlePolicy.validateShape(
            listOf("hello.niamotion", "hello.niareview", "thankyou.niamotion", "thankyou.niareview")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects path traversal`() {
        MotionBundlePolicy.validateShape(listOf("../hello.niamotion", "hello.niareview"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects motion without matching review`() {
        MotionBundlePolicy.validateShape(listOf("hello.niamotion"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate archive entry`() {
        MotionBundlePolicy.validateShape(
            listOf("hello.niamotion", "hello.niamotion", "hello.niareview")
        )
    }

    @Test
    fun `entry names are intentionally narrow`() {
        assertTrue(MotionBundlePolicy.isSafeEntryName("callonphone.niamotion"))
        assertFalse(MotionBundlePolicy.isSafeEntryName("HELLO.niamotion"))
        assertFalse(MotionBundlePolicy.isSafeEntryName("folder/hello.niamotion"))
        assertFalse(MotionBundlePolicy.isSafeEntryName("hello.zip"))
    }
}

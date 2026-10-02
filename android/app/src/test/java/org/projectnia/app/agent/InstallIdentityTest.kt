package org.projectnia.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallIdentityTest {
    @Test
    fun `generated identities are valid and nonconstant`() {
        val first = InstallIdentityPolicy.create()
        val second = InstallIdentityPolicy.create()

        assertTrue(InstallIdentityPolicy.isValid(first))
        assertTrue(InstallIdentityPolicy.isValid(second))
        assertNotEquals(first, second)
    }

    @Test
    fun `hardware and shared placeholder identifiers are rejected`() {
        assertFalse(InstallIdentityPolicy.isValid("android"))
        assertFalse(InstallIdentityPolicy.isValid("SM-A175F"))
        assertFalse(InstallIdentityPolicy.isValid("install-../../other-user"))
    }
}

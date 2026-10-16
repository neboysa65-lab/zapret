package com.witvpn.ikev2.presentation.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePromptTest {

    @Test
    fun `compares versions numerically, not as strings`() {
        assertTrue(AppVersion.compare("3.1.10", "3.1.9") > 0)
        assertTrue(AppVersion.compare("3.2", "3.1.99") > 0)
        assertTrue(AppVersion.compare("3.1.4", "3.1.5") < 0)
        assertEquals(0, AppVersion.compare("3.1", "3.1.0"))
        assertEquals(0, AppVersion.compare("v3.1.4", "3.1.4"))
        assertEquals(0, AppVersion.compare("3.1.4-beta", "3.1.4"))
    }

    @Test
    fun `rejects values that are not versions`() {
        assertFalse(AppVersion.isValid(""))
        assertFalse(AppVersion.isValid("latest"))
        assertFalse(AppVersion.isValid(null))
        assertTrue(AppVersion.isValid(" 3.1.5 "))
    }

    @Test
    fun `optional when a newer version is published`() {
        assertEquals(UpdateKind.OPTIONAL, AppUpdatePrompt.evaluate("3.1.4", "3.1.5", ""))
    }

    @Test
    fun `nothing when installed is current, newer, or config is empty or broken`() {
        assertEquals(UpdateKind.NONE, AppUpdatePrompt.evaluate("3.1.4", "3.1.4", ""))
        assertEquals(UpdateKind.NONE, AppUpdatePrompt.evaluate("3.1.5", "3.1.4", ""))
        assertEquals(UpdateKind.NONE, AppUpdatePrompt.evaluate("3.1.4", "", ""))
        assertEquals(UpdateKind.NONE, AppUpdatePrompt.evaluate("3.1.4", "soon", "x"))
    }

    @Test
    fun `required when installed is below minimum supported`() {
        assertEquals(UpdateKind.REQUIRED, AppUpdatePrompt.evaluate("3.1.4", "3.2.0", "3.1.5"))
        assertEquals(UpdateKind.REQUIRED, AppUpdatePrompt.evaluate("3.1.4", "", "3.2"))
        assertEquals(UpdateKind.OPTIONAL, AppUpdatePrompt.evaluate("3.1.5", "3.2.0", "3.1.5"))
    }
}

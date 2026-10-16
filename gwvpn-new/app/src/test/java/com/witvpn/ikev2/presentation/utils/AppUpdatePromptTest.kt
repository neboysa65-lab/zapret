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
    private fun info(kind: UpdateKind, target: String, notes: String = "") =
        UpdateInfo(kind, "3.1.6", target, "", notes)

    @Test
    fun `config that arrives after a no-update check still shows the prompt`() {
        // Регрессия: первая проверка по старому конфигу больше не «закрывает» окно навсегда
        assertEquals(PromptAction.None, AppUpdatePrompt.decide(null, null, false, null))
        val fresh = info(UpdateKind.REQUIRED, "3.1.7")
        assertEquals(PromptAction.Show(fresh), AppUpdatePrompt.decide(fresh, null, false, null))
    }

    @Test
    fun `later hides only the dismissed optional version`() {
        val v317 = info(UpdateKind.OPTIONAL, "3.1.7")
        assertEquals(PromptAction.None, AppUpdatePrompt.decide(v317, "3.1.7", false, null))
        val v318 = info(UpdateKind.OPTIONAL, "3.1.8")
        assertEquals(PromptAction.Show(v318), AppUpdatePrompt.decide(v318, "3.1.7", false, null))
    }

    @Test
    fun `required ignores later and play flow`() {
        val required = info(UpdateKind.REQUIRED, "3.1.7")
        assertEquals(PromptAction.Show(required), AppUpdatePrompt.decide(required, "3.1.7", true, null))
    }

    @Test
    fun `play update flow suppresses only the optional prompt`() {
        val optional = info(UpdateKind.OPTIONAL, "3.1.7")
        assertEquals(PromptAction.None, AppUpdatePrompt.decide(optional, null, true, null))
    }

    @Test
    fun `shown prompt is kept, replaced or hidden as the config changes`() {
        val optional = info(UpdateKind.OPTIONAL, "3.1.7")
        assertEquals(PromptAction.None, AppUpdatePrompt.decide(optional, null, false, optional))

        val required = info(UpdateKind.REQUIRED, "3.1.7")
        assertEquals(PromptAction.Show(required), AppUpdatePrompt.decide(required, null, false, optional))

        val newNotes = info(UpdateKind.OPTIONAL, "3.1.7", notes = "fixes")
        assertEquals(PromptAction.Show(newNotes), AppUpdatePrompt.decide(newNotes, null, false, optional))

        assertEquals(PromptAction.Hide, AppUpdatePrompt.decide(null, null, false, required))
    }
}

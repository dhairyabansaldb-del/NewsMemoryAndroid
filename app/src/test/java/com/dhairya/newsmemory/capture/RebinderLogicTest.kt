package com.dhairya.newsmemory.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebinderLogicTest {

    private val now = 10_000_000_000L

    @Test
    fun `no access granted means nothing to rebind`() {
        assertFalse(RebinderLogic.isListenerDead(accessGranted = false, lastAlive = null, now = now))
    }

    @Test
    fun `granted but never heartbeat is dead`() {
        assertTrue(RebinderLogic.isListenerDead(accessGranted = true, lastAlive = null, now = now))
    }

    @Test
    fun `fresh heartbeat is alive`() {
        assertFalse(
            RebinderLogic.isListenerDead(
                accessGranted = true,
                lastAlive = now - 10L * 60 * 1000,   // 10 min ago
                now = now
            )
        )
    }

    @Test
    fun `heartbeat older than two missed beats is dead`() {
        assertTrue(
            RebinderLogic.isListenerDead(
                accessGranted = true,
                lastAlive = now - 36L * 60 * 1000,   // 36 min ago > 35-min threshold
                now = now
            )
        )
    }
}

package com.dhairya.newsmemory.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dhairya.newsmemory.pipeline.DigestTimes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = SettingsStore(context)

    @Test
    fun `allowlist round-trips and snapshot follows edits`() = runTest {
        assertTrue(store.allowlist.first().isEmpty())          // zero defaults (EDD §2)

        store.setAllowlist(setOf("com.app.a", "com.app.b"))
        assertEquals(setOf("com.app.a", "com.app.b"), store.allowlist.first())

        store.setAllowlist(setOf("com.app.a"))
        assertEquals(setOf("com.app.a"), store.allowlist.first())
    }

    @Test
    fun `digest times default per EDD and round-trip`() = runTest {
        val defaults = store.digestTimesSnapshot()
        assertEquals(8 * 60 + 30, defaults.morningMinutes)
        assertEquals(18 * 60, defaults.eveningMinutes)
        assertEquals(22 * 60 + 30, defaults.nightMinutes)

        store.setDigestTimes(DigestTimes(7 * 60, 19 * 60, 23 * 60))
        val updated = store.digestTimesSnapshot()
        assertEquals(7 * 60, updated.morningMinutes)
        assertEquals(19 * 60, updated.eveningMinutes)
        assertEquals(23 * 60, updated.nightMinutes)
    }

    @Test
    fun `acks and heartbeat persist`() = runTest {
        store.setBatteryAcknowledged(true)
        assertTrue(store.batteryAcknowledged.first())

        store.setOnboardingDone(true)
        assertTrue(store.onboardingDone.first())

        store.heartbeat(12345L)
        assertEquals(12345L, store.lastAlive.first())
    }
}

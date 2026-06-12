package com.dhairya.newsmemory.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class NotificationRepositoryTest {

    private lateinit var db: ArchiveDatabase
    private lateinit var repo: NotificationRepository
    private val zone = ZoneId.of("Asia/Kolkata")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArchiveDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NotificationRepository(db.rawNotificationDao(), SettingsStore(context)) { zone }
    }

    @After
    fun teardown() = db.close()

    private fun millisAt(h: Int, m: Int): Long =
        LocalDateTime.of(2026, 6, 10, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `insert computes hash and window bucket from configured times`() = runTest {
        val id = repo.insertRaw("com.news.a", "Sensex falls 800 points", "FII selling", millisAt(12, 0))
        assertTrue(id > 0)

        val row = db.rawNotificationDao().latest(1).single()
        assertEquals("2026-06-10-E", row.windowBucket)        // noon → evening window
        assertEquals(64, row.contentHash.length)
        assertEquals("com.news.a", row.packageName)
    }

    @Test
    fun `reworded duplicate from another app in same window is dropped`() = runTest {
        val first = repo.insertRaw("com.news.a", "Sensex falls 800 points!", null, millisAt(12, 0))
        val dupe = repo.insertRaw("com.news.b", "🚨 SENSEX falls 800 points", null, millisAt(13, 0))
        assertTrue(first > 0)
        assertEquals(-1L, dupe)                                // same normalized hash, same bucket
    }

    @Test
    fun `same story after the window boundary is kept`() = runTest {
        repo.insertRaw("com.news.a", "Sensex falls 800 points", null, millisAt(12, 0))
        val evening = repo.insertRaw("com.news.a", "Sensex falls 800 points", null, millisAt(19, 0))
        assertTrue(evening > 0)                                // 19:00 → night bucket, new window
    }

    @Test
    fun `late-night capture lands in tomorrow's morning bucket`() = runTest {
        repo.insertRaw("com.news.a", "Overnight story", null, millisAt(23, 30))
        assertEquals("2026-06-11-M", db.rawNotificationDao().latest(1).single().windowBucket)
    }
}

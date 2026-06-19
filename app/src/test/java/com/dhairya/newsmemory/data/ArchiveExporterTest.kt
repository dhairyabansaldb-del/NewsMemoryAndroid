package com.dhairya.newsmemory.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.ItemSource
import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.util.Normalizer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArchiveExporterTest {

    private lateinit var db: ArchiveDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArchiveDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `export captures every table and round-trips`() = runTest {
        // Seed one digest with an item, a source raw, and a linked entity.
        val rawId = db.rawNotificationDao().insert(
            RawNotification(
                packageName = "com.news", title = "Sensex falls on FII selling", body = null,
                contentHash = Normalizer.contentHash("Sensex falls on FII selling", null),
                postedAt = 1_000, capturedAt = 1_000, windowBucket = "2026-06-10-E"
            )
        )
        db.digestDao().insertDigest(
            Digest(
                id = "2026-06-10-E", slot = "EVENING", windowStart = 0, windowEnd = 100,
                createdAt = 100, itemCount = 1, sourceCount = 1, pipelineMode = "LLM"
            )
        )
        val itemId = db.digestDao().insertItem(
            DigestItem(digestId = "2026-06-10-E", topicLabel = "Markets", headline = "Sensex falls", sourceCount = 1, position = 0)
        )
        db.digestDao().insertItemSources(listOf(ItemSource(itemId, rawId)))
        val entityId = db.entityDao().upsert("Sensex", "sensex", 100)
        db.entityDao().link(listOf(com.dhairya.newsmemory.data.db.ItemEntityCrossRef(itemId, entityId)))

        val payload = ArchiveExporter.toJson(db)
        val parsed = Json.decodeFromString<ArchiveExport>(payload)

        assertEquals(ArchiveExporter.EXPORT_VERSION, parsed.exportVersion)
        assertEquals(ArchiveExporter.SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals(1, parsed.rawNotifications.size)
        assertEquals(1, parsed.digests.size)
        assertEquals(1, parsed.digestItems.size)
        assertEquals(1, parsed.itemSources.size)
        assertEquals(1, parsed.entities.size)
        assertEquals(1, parsed.itemEntities.size)
        // Relational integrity preserved through the round-trip.
        assertEquals(parsed.digestItems[0].id, parsed.itemSources[0].itemId)
        assertEquals("Sensex falls on FII selling", parsed.rawNotifications[0].title)
        assertEquals("sensex", parsed.entities[0].normalized)
    }

    @Test
    fun `empty archive exports empty lists`() = runTest {
        val parsed = Json.decodeFromString<ArchiveExport>(ArchiveExporter.toJson(db))
        assertEquals(0, parsed.rawNotifications.size)
        assertEquals(0, parsed.digests.size)
        assertEquals(0, parsed.itemEntities.size)
    }
}

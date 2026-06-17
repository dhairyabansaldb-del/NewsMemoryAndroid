package com.dhairya.newsmemory.data

import com.dhairya.newsmemory.capture.ExtractedItem
import com.dhairya.newsmemory.capture.ParseQuality
import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.data.db.RawNotificationDao
import com.dhairya.newsmemory.pipeline.WindowCalculator
import com.dhairya.newsmemory.util.Normalizer
import java.time.Instant
import java.time.ZoneId

/**
 * Insert path (EDD §4.1–4.2): hash + window bucket + insert, dedupe via the UNIQUE
 * constraint. Capture stays dumb and fast: no network, no LLM, no clustering.
 */
class NotificationRepository(
    private val dao: RawNotificationDao,
    private val settingsStore: SettingsStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    data class InsertResult(val inserted: Int, val anyUnparseable: Boolean)

    /** Persist every story resolved from one notification (InboxStyle yields several). */
    suspend fun insertExtracted(
        packageName: String,
        items: List<ExtractedItem>,
        postedAt: Long
    ): InsertResult {
        val times = settingsStore.digestTimesSnapshot()
        val bucket = WindowCalculator.windowIdFor(Instant.ofEpochMilli(postedAt), times, zone())
        val now = System.currentTimeMillis()
        var inserted = 0
        var anyUnparseable = false
        for (it in items) {
            if (it.quality == ParseQuality.UNPARSEABLE) anyUnparseable = true
            val id = dao.insert(
                RawNotification(
                    packageName = packageName,
                    publisher = it.publisher,
                    title = it.title,
                    body = it.snippet,
                    rawTitle = it.rawTitle,
                    rawText = it.rawText,
                    bigText = it.bigText,
                    subText = it.subText,
                    textLines = it.textLines,
                    template = it.template,
                    parseQuality = it.quality.name,
                    contentHash = Normalizer.contentHash(it.title, it.snippet),
                    postedAt = postedAt,
                    capturedAt = now,
                    windowBucket = bucket
                )
            )
            if (id != -1L) inserted++
        }
        return InsertResult(inserted, anyUnparseable)
    }

    /** Convenience used by tests and any standard single-story path. */
    suspend fun insertRaw(packageName: String, title: String?, body: String?, postedAt: Long): Long {
        val times = settingsStore.digestTimesSnapshot()
        val bucket = WindowCalculator.windowIdFor(Instant.ofEpochMilli(postedAt), times, zone())
        return dao.insert(
            RawNotification(
                packageName = packageName,
                publisher = packageName,
                title = title,
                body = body,
                contentHash = Normalizer.contentHash(title, body),
                postedAt = postedAt,
                capturedAt = System.currentTimeMillis(),
                windowBucket = bucket
            )
        )
    }
}

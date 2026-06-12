package com.dhairya.newsmemory.data

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

    /** @return row id, or -1 if dropped as an exact dupe within the window. */
    suspend fun insertRaw(packageName: String, title: String?, body: String?, postedAt: Long): Long {
        val times = settingsStore.digestTimesSnapshot()
        val bucket = WindowCalculator.windowIdFor(Instant.ofEpochMilli(postedAt), times, zone())
        return dao.insert(
            RawNotification(
                packageName = packageName,
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

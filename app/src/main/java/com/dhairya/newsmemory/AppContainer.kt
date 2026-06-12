package com.dhairya.newsmemory

import android.content.Context
import com.dhairya.newsmemory.data.NotificationRepository
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.pipeline.DigestNotifier
import com.dhairya.newsmemory.pipeline.DigestPipeline

/**
 * Hand-rolled DI (EDD §1): singletons created once in Application.onCreate().
 * Phase 5 adds GroqClient; v1 adds RecurrenceEngine and QueryEngine.
 */
class AppContainer(private val context: Context) {
    val database: ArchiveDatabase by lazy { ArchiveDatabase.build(context) }
    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
    val notificationRepository: NotificationRepository by lazy {
        NotificationRepository(database.rawNotificationDao(), settingsStore)
    }
    val digestNotifier: DigestNotifier by lazy { DigestNotifier(context) }
    val digestPipeline: DigestPipeline by lazy {
        DigestPipeline(database, settingsStore, notifier = digestNotifier)
    }
}

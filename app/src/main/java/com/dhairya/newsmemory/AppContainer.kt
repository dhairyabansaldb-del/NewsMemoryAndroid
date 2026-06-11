package com.dhairya.newsmemory

import android.content.Context
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase

/**
 * Hand-rolled DI (EDD §1): singletons created once in Application.onCreate().
 * Later phases add NotificationRepository, DigestPipeline, GroqClient, etc.
 */
class AppContainer(context: Context) {
    val database: ArchiveDatabase by lazy { ArchiveDatabase.build(context) }
    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
}

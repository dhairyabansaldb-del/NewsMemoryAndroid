package com.dhairya.newsmemory

import android.content.Context
import com.dhairya.newsmemory.data.NotificationRepository
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.llm.GroqClient
import com.dhairya.newsmemory.llm.GroqClusterEngine
import com.dhairya.newsmemory.pipeline.ClusterResult
import com.dhairya.newsmemory.pipeline.Deduper
import com.dhairya.newsmemory.pipeline.DigestNotifier
import com.dhairya.newsmemory.pipeline.DigestPipeline
import com.dhairya.newsmemory.pipeline.HeuristicClusterer

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

    // Groq is wired only when a key is configured in local.properties (BuildConfig). With no
    // key the pipeline stays fully heuristic — the app builds and runs without it.
    private val groqClient: GroqClient? by lazy {
        BuildConfig.GROQ_API_KEY.takeIf { it.isNotBlank() }?.let { GroqClient(it) }
    }

    /** LLM clustering with built-in heuristic fallback, or pure heuristic when no key. */
    private val clusterEngine: suspend (List<Deduper.MergedStory>) -> ClusterResult by lazy {
        groqClient?.let { GroqClusterEngine(it)::cluster } ?: { HeuristicClusterer.cluster(it) }
    }

    val digestPipeline: DigestPipeline by lazy {
        DigestPipeline(database, settingsStore, clusterEngine = clusterEngine, notifier = digestNotifier)
    }
}

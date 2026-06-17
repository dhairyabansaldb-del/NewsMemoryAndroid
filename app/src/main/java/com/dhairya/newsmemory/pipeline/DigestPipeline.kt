package com.dhairya.newsmemory.pipeline

import androidx.room.withTransaction
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.ItemEntityCrossRef
import com.dhairya.newsmemory.data.db.ItemSource
import com.dhairya.newsmemory.util.Normalizer
import java.time.ZoneId

/**
 * The digest pipeline (EDD §5.2). Idempotent per window_id: a digest row already
 * present means the whole run is a no-op (insertDigest uses IGNORE).
 *
 *   load raw rows → near-dup merge → cluster (LLM in Phase 5 / heuristic now)
 *   → assemble in one transaction → one push (unless empty)
 */
class DigestPipeline(
    private val db: ArchiveDatabase,
    private val settingsStore: SettingsStore,
    private val clusterEngine: suspend (List<Deduper.MergedStory>) -> ClusterResult =
        { HeuristicClusterer.cluster(it) },
    private val notifier: DigestNotifier? = null,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    sealed interface Outcome {
        data class Created(val digest: Digest) : Outcome
        data object AlreadyExists : Outcome
    }

    suspend fun runForWindow(windowId: String): Outcome {
        val times = settingsStore.digestTimesSnapshot()
        val (start, end) = WindowCalculator.windowBounds(windowId, times, zone())
        val (_, slot) = WindowCalculator.parse(windowId)

        if (db.digestDao().digest(windowId) != null) return Outcome.AlreadyExists

        val rows = db.rawNotificationDao().inWindow(start, end)

        // Stage 2: near-dup merge (exact dupes are already gone at insert time)
        val stories = Deduper.merge(rows)

        // Stage 3: clustering — LLM with heuristic fallback (Phase 5), heuristic-only today
        val result = if (stories.isEmpty()) {
            ClusterResult(emptyList(), ClusterResult.MODE_HEURISTIC)
        } else {
            clusterEngine(stories)
        }

        // Stage 4: assembly, single transaction. Items ordered by source_count desc —
        // multi-source stories are the strongest signal headlines can carry.
        val now = System.currentTimeMillis()
        val orderedClusters = result.clusters.sortedByDescending { c -> c.stories.sumOf { it.sourceCount } }
        val digest = Digest(
            id = windowId,
            slot = slot.name,
            windowStart = start,
            windowEnd = end,
            createdAt = now,
            itemCount = orderedClusters.size,
            sourceCount = rows.map { it.publisher ?: it.packageName }.distinct().size,
            pipelineMode = result.mode
        )

        val inserted = db.withTransaction {
            if (db.digestDao().insertDigest(digest) == -1L) return@withTransaction false
            db.rawNotificationDao().assignWindow(windowId, rows.map { it.id })
            orderedClusters.forEachIndexed { position, cluster ->
                val representative = cluster.stories.first().representative
                val itemId = db.digestDao().insertItem(
                    DigestItem(
                        digestId = windowId,
                        topicLabel = cluster.topicLabel,
                        headline = representative.title ?: representative.body ?: "",
                        sourceCount = cluster.stories.flatMap { it.members }
                            .map { it.publisher ?: it.packageName }.distinct().size,
                        position = position
                    )
                )
                db.digestDao().insertItemSources(
                    cluster.stories.flatMap { it.members }.map { ItemSource(itemId, it.id) }
                )
                if (cluster.entities.isNotEmpty()) {
                    val refs = cluster.entities.map { name ->
                        val entityId = db.entityDao().upsert(name, Normalizer.normalize(name), now)
                        ItemEntityCrossRef(itemId, entityId)
                    }
                    db.entityDao().link(refs)
                }
            }
            true
        }
        if (!inserted) return Outcome.AlreadyExists

        // Stage 5: one push — never for an empty window (EDD §5.2 empty/low handling)
        if (digest.itemCount > 0) notifier?.postDigestReady(digest)

        return Outcome.Created(digest)
    }
}

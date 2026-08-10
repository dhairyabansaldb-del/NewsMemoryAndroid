package com.dhairya.newsmemory.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The archive — the system's only source of truth (SAD §4.2).
 * All six tables ship in v0 so no migration sits between v0 and v1 (EDD §3).
 */
@Database(
    entities = [
        RawNotification::class,
        Digest::class,
        DigestItem::class,
        ItemSource::class,
        TrackedEntity::class,
        ItemEntityCrossRef::class
    ],
    version = 2,
    exportSchema = true
)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun rawNotificationDao(): RawNotificationDao
    abstract fun digestDao(): DigestDao
    abstract fun entityDao(): EntityDao

    companion object {
        fun build(context: Context): ArchiveDatabase =
            Room.databaseBuilder(context, ArchiveDatabase::class.java, "archive.db")
                // No destructive fallback. It was acceptable pre-accumulation, but the archive
                // now holds the history the v1 recurrence gate counts over — a silent wipe on a
                // schema bump would destroy the thing being measured. v1 adds no columns, so
                // nothing needs migrating; any future bump must ship a real Migration and will
                // fail loudly here until it does.
                .build()
    }
}

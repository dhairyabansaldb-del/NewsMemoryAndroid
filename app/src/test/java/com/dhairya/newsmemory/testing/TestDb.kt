package com.dhairya.newsmemory.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhairya.newsmemory.data.db.ArchiveDatabase

/**
 * One in-memory archive for tests. Extracted because the same four lines were inlined in
 * every DB test's @Before — with parallel tracks each adding their own, they would have
 * drifted. Requires Robolectric (@RunWith(RobolectricTestRunner::class)) for the context.
 */
fun inMemoryArchive(): ArchiveDatabase =
    Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        ArchiveDatabase::class.java
    )
        .allowMainThreadQueries()
        .build()

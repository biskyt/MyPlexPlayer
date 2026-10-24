package com.biskyt.myplexplayer

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.biskyt.myplexplayer.core.xtream.XtreamCategory
import com.biskyt.myplexplayer.data.CategoryStore
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_LIVE
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's own SQLite: before Android 12 (SQLite 3.32) a query takes at most 999 values, which the JVM tests' newer
 * SQLite doesn't show.
 */
@RunWith(AndroidJUnit4::class)
class SqlLimitsTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java
    ).build()

    @After
    fun tearDown() = db.close()

    @Test
    fun a_provider_with_more_than_999_categories_syncs() = runBlocking {
        val store = CategoryStore(db) { Locale.UK }
        val many = (1..1_200).map { XtreamCategory("$it", "UK | Category $it") }
        store.store(1, CAT_LIVE, many)
        // And again with some gone, as a later sync does.
        store.store(1, CAT_LIVE, many.drop(100))
        assertEquals(1_100, db.categories().all(1, CAT_LIVE).size)
    }
}

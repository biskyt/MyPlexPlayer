package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.CAT_MOVIE
import com.biskyt.myplexplayer.data.db.MovieEntity
import com.biskyt.myplexplayer.testutil.PlainApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The grids' Title order: a leading "The", "A" or "An" is passed over, as Plex sorts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class TitleSortTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    @Test
    fun `titles sort without their article`() = runBlocking {
        val titles =
            listOf("The Mentalist", "Anatomy", "A Bug's Life", "the goldbergs", "An Officer", "Theatre", "Zoo", "Thing", "The  Thing")
        db.vod().insertMovies(
            titles.mapIndexed { i, t ->
                MovieEntity(1, i + 1L, t, null, "1", null, null, 0, "mkv", position = i, syncStamp = 0)
            }
        )
        val sorted = db.query(gridQuery(CAT_MOVIE, 1, VodView(sort = VodSort.TITLE))).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("title")) else null }.toList()
        }
        // "Theatre" and "Anatomy" keep their first letters: only a whole word is passed over. "Thing" and "The  Thing"
        // sort the same (spaces trimmed), so they keep the server's order.
        assertEquals(
            listOf("Anatomy", "A Bug's Life", "the goldbergs", "The Mentalist", "An Officer", "Theatre", "Thing", "The  Thing", "Zoo"),
            sorted
        )
    }
}

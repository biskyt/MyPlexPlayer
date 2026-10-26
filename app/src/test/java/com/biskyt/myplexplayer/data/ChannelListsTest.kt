package com.biskyt.myplexplayer.data

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.data.db.ChannelEntity
import com.biskyt.myplexplayer.data.db.FavouriteEntity
import com.biskyt.myplexplayer.data.db.RecentEntity
import com.biskyt.myplexplayer.testutil.PlainApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every channel list filters the same way: by name, or by the start of the channel's number. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class ChannelListsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    private fun channel(id: Long, number: Int, name: String) = ChannelEntity(1, id, number, name, "1", null, null)

    private suspend fun names(source: PagingSource<Int, ChannelEntity>) =
        (TestPager(PagingConfig(50), source).refresh() as PagingSource.LoadResult.Page).data.map { it.name }

    @Test
    fun `favourites and recent filter as the other lists do`() = runBlocking {
        val channels = db.channels()
        // Channel 10, and one the provider gave no number (stored as 0); "5_" with an underscore in its name.
        channels.upsert(listOf(channel(1, 10, "News 10"), channel(2, 0, "No Number"), channel(3, 7, "5_Live")))
        listOf(1L, 2L, 3L).forEach {
            db.favourites().add(FavouriteEntity(1, it, it))
            db.favourites().touchRecent(RecentEntity(1, it, it))
        }
        for (q in listOf("0", "1", "5_")) {
            val all = names(channels.pageAll(1, likeArg(q)))
            assertEquals("favourites, '$q'", all, names(channels.pageFavourites(1, likeArg(q))))
            assertEquals("recent, '$q'", all.toSet(), names(channels.pageRecent(1, likeArg(q))).toSet())
        }
        assertEquals(listOf("News 10"), names(channels.pageAll(1, likeArg("0")))) // by its name; no channel is number 0
    }
}

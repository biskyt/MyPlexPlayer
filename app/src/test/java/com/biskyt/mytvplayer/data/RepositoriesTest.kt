package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.xtream.XtreamException
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.testutil.FakeProvider
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepositoriesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var epg: EpgRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var channels: ChannelRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        settings = SettingsRepository(context)
        epg = EpgRepository(db, http, context.cacheDir.resolve("test-epg"), scope)
        playlists = PlaylistRepository(db, http, settings, epg) { Locale.UK }
        channels = ChannelRepository(db)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        provider.close()
    }

    private suspend fun addPlaylist(): Long = playlists.add("Home", provider.url, "me", "secret")

    @Test
    fun `wrong password is rejected and nothing is saved`() = runBlocking {
        try {
            playlists.add("Home", provider.url, "me", "wrong")
            fail("expected login failure")
        } catch (_: XtreamException) {
        }
        assertTrue(playlists.allIds().isEmpty())
    }

    @Test
    fun `new playlist includes the TV's own language groups and becomes active`() = runBlocking {
        val id = addPlaylist()
        val cats = db.categories().all(id).associateBy { it.categoryId }
        assertTrue(cats.getValue("1").included)
        assertTrue(cats.getValue("2").included)
        assertFalse("French category should be excluded for a UK TV", cats.getValue("3").included)
        assertFalse(cats.getValue("4").included)
        assertEquals("FR", cats.getValue("3").lang)
        assertEquals(id, settings.current().activePlaylistId)
    }

    @Test
    fun `a TV whose country the provider lacks falls back to its language`() = runBlocking {
        val us = PlaylistRepository(db, OkHttpClient(), settings, epg) { Locale.US }
        val id = us.add("Home", provider.url, "me", "secret")
        val included = db.categories().all(id).filter { it.included }.map { it.categoryId }
        assertEquals(listOf("1", "2"), included)
    }

    @Test
    fun `sync only downloads included categories and keeps user choices`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        assertEquals(listOf(101L, 102L, 103L), channels.ids(id, ChannelFilter.All))
        // Few categories included out of many → fetched one category at a time.
        assertTrue(provider.requests.any { it.endsWith("get_live_streams") })

        channels.setHidden(id, 103, true)
        channels.setFavourite(id, 101, true)
        channels.move(id, ChannelFilter.All, 102, -1)
        assertEquals(listOf(102L, 101L), channels.ids(id, ChannelFilter.All))

        // Provider renames a channel and drops another; user choices survive.
        provider.streams = provider.streams.filter { it.id != 103L }.map { if (it.id == 101L) it.copy(name = "UK: CHANNEL 1") else it }
        playlists.syncChannels(id)
        assertEquals(listOf(102L, 101L), channels.ids(id, ChannelFilter.All))
        assertEquals("UK: CHANNEL 1", channels.get(id, 101)!!.name)
        assertNull(channels.get(id, 103))
        assertEquals(listOf(101L), channels.ids(id, ChannelFilter.Favourites))
    }

    @Test
    fun `excluding a category removes its channels on the next sync`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        db.categories().setIncluded(id, listOf("2"), false)
        db.categories().setIncluded(id, listOf("3"), true)
        playlists.syncChannels(id)
        assertEquals(setOf(101L, 103L, 201L), channels.ids(id, ChannelFilter.All).toSet())
        assertEquals(listOf(201L), channels.ids(id, ChannelFilter.Category("3")))
    }

    @Test
    fun `guide import matches channels and stores programmes only for used guide channels`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        epg.ensureProviderSource(playlists.get(id)!!, playlists.client(playlists.get(id)!!).xmltvUrl())
        epg.importAll(listOf(id))

        val now = System.currentTimeMillis()
        val programmes = epg.programmes(id, listOf(101, 102, 103), now, now + 3 * 3_600_000)
        // 101 matches automatically via its provider epg id.
        assertEquals("Now on one.uk", programmes.getValue(101).first().title)
        assertEquals("Next on one.uk", programmes.getValue(101)[1].title)
        // Misspelt names aren't matched automatically (too risky) but are the top suggestion for the user to confirm.
        assertNull(programmes[102])
        assertEquals("arena1.uk", epg.suggestions("UK: ARENA SPRTS 1 FHD", null).first().candidate.xmlId)
        assertEquals("kite.uk", epg.suggestions("UK: KITE KIDZ", null).first().candidate.xmlId)

        // cine.fr is in the guide but no included channel uses it, so its programmes aren't stored.
        val stored = db.query("SELECT COUNT(*) FROM programme WHERE xmlId = 'cine.fr'", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(0, stored)
    }

    @Test
    fun `manual guide choice wins over automatic matching and survives re-import`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        epg.importAll(listOf(id))
        val source = db.epg().enabledSources().first().id

        epg.setManual(id, 103, source, "cine.fr", offsetMinutes = 60)
        delay(2_500) // debounced programme import
        val now = System.currentTimeMillis()
        val list = epg.programmes(id, listOf(103), now - 4 * 3_600_000, now + 4 * 3_600_000).getValue(103)
        assertEquals("Now on cine.fr", list.first().title)
        // The guide has it starting 30 min ago; the +1 h time shift moves it to 30 min from now.
        assertTrue(kotlin.math.abs(list.first().startMs - (now + 30 * 60_000)) < 120_000)

        epg.importAll(listOf(id))
        assertEquals("cine.fr", db.epg().mapping(id, 103)!!.xmlId)

        epg.setNoGuide(id, 102)
        epg.importAll(listOf(id))
        assertNull(epg.programmes(id, listOf(102), now, now + 3_600_000)[102])
    }

    @Test
    fun `deleting a playlist removes its data and guide`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        playlists.delete(id)
        assertTrue(channels.ids(id, ChannelFilter.All).isEmpty())
        assertTrue(db.epg().enabledSources().isEmpty())
        assertEquals(0L, settings.current().activePlaylistId)
    }
}

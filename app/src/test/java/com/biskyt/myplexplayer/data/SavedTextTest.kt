package com.biskyt.myplexplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.myplexplayer.AppText
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.testutil.FakePlex
import com.biskyt.myplexplayer.testutil.PlainApp
import com.biskyt.myplexplayer.testutil.signInHome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only the app's own text is translated, and never saved: saved names stay as they are whatever the language, and the
 * app's words about saved things (a guide's last error) follow the language when shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class SavedTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fake = FakePlex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val epg = EpgRepository(db)
    private val plex = PlexSources(db, OkHttpClient(), SettingsRepository(context)).apply { plexTv = fake.url }
    private val playlists = PlaylistRepository(db, SettingsRepository(context), epg, CategoryStore(db), plex)

    @After
    fun tearDown() {
        AppText.setLanguage(context, "")
        scope.cancel()
        db.close()
        fake.close()
    }

    private suspend fun names() = db.playlists().all().map { it.name } + db.epg().observeSources().first().map { it.name }

    @Test
    fun `saved source and guide names stay as they are when the language changes`() = runBlocking {
        val id = plex.signInHome(db)
        playlists.syncChannels(id)
        assertEquals(listOf("Home Plex", "Home Plex"), names())

        AppText.setLanguage(context, "es")
        playlists.syncChannels(id)
        assertEquals(listOf("Home Plex", "Home Plex"), names())
    }

    @Test
    fun `a guide's last error is saved as what failed, and read in the language shown`() = runBlocking {
        fake.guideFails = true
        val id = plex.signInHome(db)
        playlists.syncChannels(id)
        val saved = db.epg().observeSources().first().single().lastError!!
        assertFalse(saved.contains("error"))
        assertEquals("Server returned error 500", GuideError.text(saved))
        AppText.setLanguage(context, "es")
        assertEquals("El servidor devolvió el error 500", GuideError.text(saved))
        // Anything else (a server's or a library's words) is kept and shown as it came.
        assertEquals("Connection reset", GuideError.text("Connection reset"))
    }

    @Test
    fun `nameless things are named by the app when shown, in its language`() {
        AppText.setLanguage(context, "fr")
        assertEquals("Sans titre", programmeTitle(""))
        assertEquals("Line of Duty", programmeTitle("Line of Duty"))
        assertEquals("Sans catégorie", categoryLabel(""))
        assertEquals("News", categoryLabel("News"))
        assertEquals(" ", categoryLabel(" ")) // a playlist's own group of spaces: not the one with none
    }
}

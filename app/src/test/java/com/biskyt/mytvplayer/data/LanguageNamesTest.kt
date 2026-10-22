package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Names the app gave follow its language; names people gave stay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class LanguageNamesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val epg = EpgRepository(db, OkHttpClient(), context.cacheDir.resolve("test-epg"), scope)
    private val playlists = PlaylistRepository(
        db,
        OkHttpClient(),
        SettingsRepository(context),
        epg,
        context.cacheDir.resolve("test-playlists"),
        CategoryStore(db) { Locale.UK }
    )

    @After
    fun tearDown() {
        AppText.setLanguage(context, "")
        scope.cancel()
        db.close()
        provider.close()
    }

    private suspend fun names() = db.playlists().all().map { it.name }

    private suspend fun guides() = db.epg().enabledSources().map { it.name }

    @Test
    fun `the app's names change language with it, people's names stay`() = runBlocking {
        val unnamed = playlists.add("", provider.url, "me", "secret")
        val named = playlists.add("Home", provider.url, "me", "secret")
        listOf(unnamed, named).forEach { epg.ensureProviderSource(playlists.get(it)!!, "${provider.url}/xmltv.php") }
        assertEquals(listOf("My TV", "Home"), names())
        assertEquals(listOf("My TV guide", "Home guide"), guides())

        AppText.setLanguage(context, "es")
        playlists.followLanguage()
        assertEquals(listOf("Mi TV", "Home"), names())
        assertEquals(listOf("Guía de Mi TV", "Guía de Home"), guides())

        // Renamed by hand: its guide follows.
        playlists.update(playlists.get(named)!!.copy(name = "Casa"))
        assertEquals(listOf("Guía de Mi TV", "Guía de Casa"), guides())
    }
}

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

/** Only the app's own text is translated: saved names stay as they are, whatever the language, through updates too. */
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

    private suspend fun names() = db.playlists().all().map { it.name } + db.epg().enabledSources().map { it.name }

    @Test
    fun `saved playlist and guide names stay as they are when the language changes`() = runBlocking {
        val id = playlists.add("", provider.url, "me", "secret")
        playlists.syncChannels(id)
        assertEquals(listOf("My TV", "My TV guide"), names())

        AppText.setLanguage(context, "es")
        playlists.syncChannels(id)
        epg.importAll(listOf(id))
        assertEquals(listOf("My TV", "My TV guide"), names())
    }
}

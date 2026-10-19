package com.biskyt.mytvplayer.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.biskyt.mytvplayer.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrading from an earlier release keeps the user's playlists and channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun `version 1 to 2 keeps data and marks old playlists as Xtream`() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL(
                "INSERT INTO playlist (id, name, server, username, password, lastSyncAt) VALUES (1, 'Home', 'http://x', 'u', 'p', 0)"
            )
            db.execSQL(
                "INSERT INTO channel (playlistId, streamId, number, name, categoryId, archiveDays, hidden, position, syncStamp) " +
                    "VALUES (1, 101, 1, 'One', '1', 0, 0, 1, 0)",
            )
        }
        helper.runMigrationsAndValidate("migration-test", 2, true).use { db ->
            db.query("SELECT kind FROM playlist WHERE id = 1").use {
                it.moveToFirst()
                assertEquals("xtream", it.getString(0))
            }
            db.query("SELECT name, streamUrl FROM channel WHERE streamId = 101").use {
                it.moveToFirst()
                assertEquals("One", it.getString(0))
                assertEquals(true, it.isNull(1))
            }
        }
    }

    @Test
    fun `version 2 to 3 keeps categories as live ones and adds the movie tables`() {
        helper.createDatabase("migration-test-3", 2).use { db ->
            db.execSQL(
                "INSERT INTO category (playlistId, categoryId, name, lang, included, sortOrder, channelCount) VALUES (1, '5', 'UK | NEWS', 'UK', 1, 0, 3)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-3", 3, true).use { db ->
            db.query("SELECT type, included FROM category WHERE categoryId = '5'").use {
                it.moveToFirst()
                assertEquals("live", it.getString(0))
                assertEquals(1, it.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM movie").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun `version 3 to 4 keeps programmes, of unknown kind until the next guide update`() {
        helper.createDatabase("migration-test-4", 3).use { db ->
            db.execSQL(
                "INSERT INTO programme (sourceId, xmlId, startMs, stopMs, title, subtitle, description, stamp) " +
                    "VALUES (1, 'one.uk', 1000, 2000, 'News', NULL, NULL, 0)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-4", 4, true).use { db ->
            db.query("SELECT title, kind, season FROM programme").use {
                it.moveToFirst()
                assertEquals("News", it.getString(0))
                assertEquals(0, it.getInt(1))
                assertEquals(true, it.isNull(2))
            }
        }
    }
}

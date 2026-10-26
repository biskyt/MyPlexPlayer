package com.biskyt.myplexplayer.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.biskyt.myplexplayer.data.db.AppDatabase
import com.biskyt.myplexplayer.testutil.PlainApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrading from an earlier release keeps the user's playlists and channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
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

    @Test
    fun `version 4 to 5 keeps channels and adds the catch-up index`() {
        helper.createDatabase("migration-test-5", 4).use { db ->
            db.execSQL(
                "INSERT INTO channel (playlistId, streamId, number, name, categoryId, archiveDays, hidden, position) " +
                    "VALUES (1, 101, 1, 'One', '1', 3, 0, 1)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-5", 5, true).use { db ->
            db.query("SELECT archiveDays FROM channel").use {
                it.moveToFirst()
                assertEquals(3, it.getInt(0))
            }
        }
    }

    @Test
    fun `version 5 to 6 keeps playlists and movies, and adds M3U movie and series playlists`() {
        helper.createDatabase("migration-test-6", 5).use { db ->
            db.execSQL(
                "INSERT INTO playlist (id, name, kind, server, username, password, lastSyncAt) VALUES (1, 'Home', 'm3u', 'http://a', '', '', 0)"
            )
            db.execSQL(
                "INSERT INTO movie (playlistId, streamId, title, categoryId, added, extension, position, syncStamp) " +
                    "VALUES (1, 501, 'Dune', '10', 0, 'mkv', 0, 0)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-6", 6, true).use { db ->
            db.query("SELECT moviesUrl, seriesUrl, vodFeedHash FROM playlist").use {
                it.moveToFirst()
                assertTrue(it.isNull(0) && it.isNull(1) && it.isNull(2))
            }
            db.query("SELECT title, streamUrl FROM movie").use {
                it.moveToFirst()
                assertEquals("Dune", it.getString(0))
                assertTrue(it.isNull(1))
            }
            db.query("SELECT COUNT(*) FROM episode").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun `version 6 to 7 keeps favourites, in their old order until moved`() {
        helper.createDatabase("migration-test-7", 6).use { db ->
            db.execSQL("INSERT INTO favourite (playlistId, streamId, addedAt) VALUES (1, 101, 5)")
            db.execSQL("INSERT INTO vod_favourite (playlistId, kind, itemId, addedAt) VALUES (1, 'movie', 501, 6)")
        }
        helper.runMigrationsAndValidate("migration-test-7", 7, true).use { db ->
            db.query("SELECT streamId, addedAt, position FROM favourite").use {
                it.moveToFirst()
                assertEquals(listOf(101L, 5L, 0L), listOf(it.getLong(0), it.getLong(1), it.getLong(2)))
            }
            db.query("SELECT itemId, position FROM vod_favourite").use {
                it.moveToFirst()
                assertEquals(listOf(501L, 0L), listOf(it.getLong(0), it.getLong(1)))
            }
        }
    }

    @Test
    fun `version 8 to 9 removes M3U playlists with all they had, and keeps the rest`() {
        helper.createDatabase("migration-test-9", 8).use { db ->
            listOf(1 to "xtream", 2 to "m3u", 3 to "plex").forEach { (id, kind) ->
                db.execSQL(
                    "INSERT INTO playlist (id, name, kind, server, username, password, lastSyncAt) VALUES ($id, 'P$id', '$kind', 'x', '', '', 0)"
                )
                db.execSQL(
                    "INSERT INTO channel (playlistId, streamId, number, name, categoryId, archiveDays, hidden, position, syncStamp) " +
                        "VALUES ($id, 101, 1, 'One', '1', 0, 0, 1, 0)"
                )
                db.execSQL("INSERT INTO favourite (playlistId, streamId, addedAt, position) VALUES ($id, 101, 0, 0)")
                db.execSQL(
                    "INSERT INTO epg_source (id, name, url, enabled, playlistId, lastImportAt, channelCount) VALUES ($id, 'G', 'u$id', 1, $id, 0, 0)"
                )
                db.execSQL(
                    "INSERT INTO programme (sourceId, xmlId, startMs, stopMs, title, stamp, kind) VALUES ($id, 'one', 0, 1, 'News', 0, 0)"
                )
            }
        }
        helper.runMigrationsAndValidate("migration-test-9", 9, true).use { db ->
            fun ids(sql: String) = db.query(sql).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
            assertEquals(listOf(1L, 3L), ids("SELECT id FROM playlist ORDER BY id"))
            assertEquals(listOf(1L, 3L), ids("SELECT playlistId FROM channel ORDER BY playlistId"))
            assertEquals(listOf(1L, 3L), ids("SELECT playlistId FROM favourite ORDER BY playlistId"))
            assertEquals(listOf(1L, 3L), ids("SELECT id FROM epg_source ORDER BY id"))
            assertEquals(listOf(1L, 3L), ids("SELECT sourceId FROM programme ORDER BY sourceId"))
        }
    }

    @Test
    fun `version 9 to 10 keeps progress on carry on watching in its order, and series' badges unknown`() {
        helper.createDatabase("migration-test-10", 9).use { db ->
            db.execSQL(
                "INSERT INTO watch_progress (playlistId, kind, itemId, positionMs, durationMs, updatedAt, title, hasNext) " +
                    "VALUES (1, 'movie', 501, 1, 2, 3, 'Film', 0)"
            )
            db.execSQL(
                "INSERT INTO series (playlistId, seriesId, title, categoryId, lastModified, position, syncStamp) VALUES (1, 77, 'S', '1', 0, 0, 0)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-10", 10, true).use { db ->
            db.query("SELECT carryOnAt, pending FROM watch_progress").use {
                it.moveToFirst()
                assertEquals("on Carry on watching, in the order last played", 3L, it.getLong(0))
                assertEquals(0, it.getInt(1))
            }
            db.query("SELECT unwatched FROM series").use {
                it.moveToFirst()
                assertTrue(it.isNull(0))
            }
        }
    }

    @Test
    fun `version 10 to 11 keeps series, none among the newest until the next sync`() {
        helper.createDatabase("migration-test-11", 10).use { db ->
            db.execSQL(
                "INSERT INTO series (playlistId, seriesId, title, categoryId, lastModified, position, syncStamp) VALUES (1, 77, 'S', '1', 0, 0, 0)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-11", 11, true).use { db ->
            db.query("SELECT title, newest FROM series").use {
                it.moveToFirst()
                assertEquals("S", it.getString(0))
                assertTrue(it.isNull(1))
            }
        }
    }

    @Test
    fun `version 11 to 12 removes Xtream accounts and added guides with all they had, and keeps Plex servers'`() {
        helper.createDatabase("migration-test-12", 11).use { db ->
            listOf(1 to "xtream", 3 to "plex").forEach { (id, kind) ->
                db.execSQL(
                    "INSERT INTO playlist (id, name, kind, server, username, password, lastSyncAt, machineId) " +
                        "VALUES ($id, 'P$id', '$kind', 'x', 'u', 't', 5, 'm$id')"
                )
                db.execSQL(
                    "INSERT INTO channel (playlistId, streamId, number, name, categoryId, archiveDays, hidden, position, syncStamp) " +
                        "VALUES ($id, 101, 1, 'One', '1', 0, 0, 1, 0)"
                )
                db.execSQL("INSERT INTO favourite (playlistId, streamId, addedAt, position) VALUES ($id, 101, 0, 0)")
                // Each source's own guide, its programme and the channel's mapping to it.
                db.execSQL(
                    "INSERT INTO epg_source (id, name, url, enabled, playlistId, lastImportAt, channelCount) VALUES ($id, 'G', 'u$id', 1, $id, 0, 0)"
                )
                db.execSQL(
                    "INSERT INTO programme (sourceId, xmlId, startMs, stopMs, title, stamp, kind) VALUES ($id, 'one', 0, 1, 'News', 0, 0)"
                )
                // Shifted an hour by hand.
                db.execSQL(
                    "INSERT INTO channel_epg_mapping (playlistId, streamId, sourceId, xmlId, manual, score, offsetMinutes) " +
                        "VALUES ($id, 101, $id, 'one', 1, 1.0, 60)"
                )
            }
            // A guide added by hand, matched to the Plex channel (in another's place, as a second mapping can't be).
            db.execSQL(
                "INSERT INTO epg_source (id, name, url, enabled, lastImportAt, channelCount) VALUES (9, 'Added', 'http://g', 1, 0, 0)"
            )
            db.execSQL("INSERT INTO epg_channel (sourceId, xmlId, displayName, nameNorm) VALUES (9, 'two', 'Two', 'two')")
            db.execSQL("INSERT INTO programme (sourceId, xmlId, startMs, stopMs, title, stamp, kind) VALUES (9, 'two', 0, 1, 'Film', 0, 0)")
            db.execSQL(
                "INSERT INTO channel_epg_mapping (playlistId, streamId, sourceId, xmlId, manual, score, offsetMinutes) " +
                    "VALUES (3, 102, 9, 'two', 1, 1.0, 60)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-12", 12, true).use { db ->
            fun ids(sql: String) = db.query(sql).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
            assertEquals(listOf(3L), ids("SELECT id FROM playlist"))
            assertEquals(listOf(3L), ids("SELECT playlistId FROM channel"))
            assertEquals(listOf(3L), ids("SELECT playlistId FROM favourite"))
            assertEquals(listOf(3L), ids("SELECT id FROM epg_source"))
            assertEquals(listOf(3L), ids("SELECT sourceId FROM programme"))
            assertEquals(listOf(3L), ids("SELECT sourceId FROM channel_epg_mapping"))
            // Nothing can shift a guide any more.
            assertEquals(listOf(0L), ids("SELECT manual + offsetMinutes FROM channel_epg_mapping"))
            db.query("SELECT name, server, password, lastSyncAt, machineId FROM playlist").use {
                it.moveToFirst()
                assertEquals(listOf("P3", "x", "t", "5", "m3"), (0..4).map(it::getString))
            }
        }
    }

    @Test
    fun `version 12 to 13 keeps favourites, with none yet seen in Plex`() {
        helper.createDatabase("migration-test-13", 12).use { db ->
            db.execSQL(
                "INSERT INTO playlist (id, name, kind, server, password, lastSyncAt, machineId) VALUES (3, 'P', 'plex', 'x', 't', 5, 'm')"
            )
            db.execSQL("INSERT INTO favourite (playlistId, streamId, addedAt, position) VALUES (3, 101, 0, 1)")
        }
        helper.runMigrationsAndValidate("migration-test-13", 13, true).use { db ->
            fun count(sql: String) = db.query(sql).use { c ->
                c.moveToFirst()
                c.getInt(0)
            }
            assertEquals(1, count("SELECT COUNT(*) FROM favourite"))
            assertEquals(0, count("SELECT COUNT(*) FROM plex_favourite_seen"))
        }
    }

    @Test
    fun `version 13 to 14 keeps favourites and drops what was seen in Plex`() {
        helper.createDatabase("migration-test-14", 13).use { db ->
            db.execSQL(
                "INSERT INTO playlist (id, name, kind, server, password, lastSyncAt, machineId) VALUES (3, 'P', 'plex', 'x', 't', 5, 'm')"
            )
            db.execSQL("INSERT INTO favourite (playlistId, streamId, addedAt, position) VALUES (3, 101, 0, 1)")
            db.execSQL("INSERT INTO plex_favourite_seen (playlistId, channelKey) VALUES (3, 'ch-1')")
        }
        helper.runMigrationsAndValidate("migration-test-14", 14, true).use { db ->
            db.query("SELECT COUNT(*) FROM favourite").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT name FROM sqlite_master WHERE name = 'plex_favourite_seen'").use { assertEquals(0, it.count) }
        }
    }

    @Test
    fun `version 14 to 15 keeps films and series, running time and episodes unknown until a sync`() {
        helper.createDatabase("migration-test-15", 14).use { db ->
            db.execSQL(
                "INSERT INTO movie (playlistId, streamId, title, year, categoryId, posterUrl, rating, added, extension, " +
                    "position, syncStamp) " +
                    "VALUES (3, 11, 'Film', 2008, '1', NULL, NULL, 0, 'mp4', 0, 1)"
            )
            db.execSQL(
                "INSERT INTO series (playlistId, seriesId, title, year, categoryId, coverUrl, rating, lastModified, position, syncStamp) " +
                    "VALUES (3, 21, 'Show', 2020, '2', NULL, NULL, 0, 0, 1)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-15", 15, true).use { db ->
            db.query("SELECT durationSec FROM movie WHERE streamId = 11").use { c ->
                c.moveToFirst()
                assertTrue(c.isNull(0))
            }
            db.query("SELECT episodes FROM series WHERE seriesId = 21").use { c ->
                c.moveToFirst()
                assertTrue(c.isNull(0))
            }
        }
    }
}

package com.biskyt.myplexplayer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A title's art is kept once, whichever page asked for it (Home with its saved address, or its own page). */
class BackdropCacheKeyTest {
    private fun art(host: String, token: String, path: String = "/library/metadata/11/art/1") =
        "http://$host:32400/photo/:/transcode?url=${path.replace("/", "%2F")}&width=1280&height=720&minSize=1&upscale=1&X-Plex-Token=$token"

    @Test
    fun `the same picture with another token is one cache entry`() {
        assertEquals(backdropCacheKey(art("192.0.2.1", "a")), backdropCacheKey(art("192.0.2.1", "b")))
    }

    @Test
    fun `the same path on another server is another picture`() {
        assertNotEquals(backdropCacheKey(art("192.0.2.1", "a")), backdropCacheKey(art("198.51.100.7", "a")))
    }

    @Test
    fun `another title's picture, or a newer one of the same title, is another`() {
        assertNotEquals(backdropCacheKey(art("192.0.2.1", "a")), backdropCacheKey(art("192.0.2.1", "a", "/library/metadata/12/art/1")))
        assertNotEquals(backdropCacheKey(art("192.0.2.1", "a")), backdropCacheKey(art("192.0.2.1", "a", "/library/metadata/11/art/2")))
    }
}

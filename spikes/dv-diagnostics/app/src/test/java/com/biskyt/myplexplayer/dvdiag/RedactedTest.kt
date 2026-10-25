package com.biskyt.myplexplayer.dvdiag

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactedTest {
    @Test
    fun `takes out where the server is`() {
        assertEquals(
            "failed to connect to <address>; <server> (<ip>, port 32400)",
            redacted(
                "failed to connect to http://192.168.1.20:32400/library/parts/1?X-Plex-Token=abc; " +
                    "192-168-1-20.0123abcd.plex.direct (/192.168.1.20, port 32400)"
            ).replace("/<ip>", "<ip>")
        )
    }
}

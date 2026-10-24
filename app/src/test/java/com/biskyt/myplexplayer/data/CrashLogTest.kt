package com.biskyt.myplexplayer.data

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test
    fun `logins are taken out of addresses in a report`() {
        val text = "java.io.IOException: failed http://me:secret@tv.example/get.php?username=me&password=secret&type=m3u\n" +
            "  at http://tv.example/live/me/secret/101.ts and http://tv.example/movie/me/secret/7.mkv"
        val out = CrashLog.redact(text)
        assertFalse(out, "secret" in out)
        assertFalse(out, "me:" in out)
        assertTrue(out, "username=…&password=…&type=m3u" in out)
        assertTrue(out, "/live/…/…/101.ts" in out)
        assertTrue(out, "/movie/…/…/7.mkv" in out)
        assertEquals("at http://tv.example:8080/…/…/101.ts", CrashLog.redact("at http://tv.example:8080/me/secret/101.ts"))
    }

    @Test
    fun `the issue link carries the error, short enough for a QR code`() {
        val report = "MyPlexPlayer 0.9.6 · Android 9 (API 28) · Acme Box\nThread: main\n\n" +
            "java.lang.IllegalStateException: boom\n" + "\tat com.example.Foo.bar(Foo.kt:1)\n".repeat(200)
        val url = CrashLog.issueUrl(report)
        assertTrue(url.startsWith("https://github.com/biskyt/MyPlexPlayer/issues/new?title="))
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded, "title=Crash: java.lang.IllegalStateException: boom" in decoded)
        assertTrue(url.length <= 1_800)
        assertEquals(1, Regex("```\n").findAll(decoded).count())
    }

    @Test
    fun `a report in another script still fits a QR code`() {
        val report = "MyPlexPlayer 0.9.6 · Android 9 (API 28) · Acme Box\nThread: main\n\n" +
            "java.io.IOException: Не удалось открыть канал Первый · ".repeat(80)
        val url = CrashLog.issueUrl(report)
        assertTrue(url.length <= 1_800)
        com.biskyt.myplexplayer.ui.common.encode(url) // doesn't throw
    }
}

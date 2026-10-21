package com.biskyt.mytvplayer.data

import android.os.Build
import java.io.File
import java.net.URLEncoder

/**
 * The last crash, kept in a file so the next start can show it and the user can choose to send it (nothing leaves the
 * TV otherwise). Logins are taken out first: provider addresses carry them.
 */
class CrashLog(dir: File, private val version: String) {
    private val file = File(dir, "last-crash.txt")

    /** Records any crash, then lets it end the app as before. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file.writeText(report(thread.name, e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear() {
        file.delete()
    }

    private fun report(thread: String, e: Throwable): String = redact(
        "MyTVPlayer $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}\nThread: $thread\n\n" + e.stackTraceToString()
    ).take(MAX_CHARS)

    companion object {
        private const val MAX_CHARS = 20_000

        /** Comfortably within what a QR code holds (about 2,300 bytes at the level QrCode uses). */
        private const val MAX_URL = 1_800
        const val ISSUES = "https://github.com/biskyt/MyTVPlayer/issues/new"
        private const val FENCE = "```"

        private val loginParams = Regex("""(?i)\b(username|password|user|pass|pwd|token|key|auth|mac)=[^&\s"']+""")
        private val loginPath = Regex("""/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/""")

        // Xtream's other stream address: http://host:port/USER/PASS/ID(.ext).
        private val bareLoginPath = Regex("""(?i)(https?://[^/\s]+)/[^/\s]+/[^/\s]+/(\d+(?:\.\w+)?)\b""")
        private val urlCredentials = Regex("""(?i)(https?://)[^/\s@]+@""")

        /** [text] with logins removed from any addresses in it. */
        fun redact(text: String): String = text
            .replace(loginParams) { "${it.groupValues[1]}=…" }
            .replace(loginPath) { "/${it.groupValues[1]}/…/…/" }
            .replace(bareLoginPath) { "${it.groupValues[1]}/…/…/${it.groupValues[2]}" }
            .replace(urlCredentials) { "${it.groupValues[1]}…@" }

        /**
         * A "new issue" page with as much of [report] filled in as a QR code can carry (its start: the error and where
         * it happened).
         */
        fun issueUrl(report: String): String {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            val error = report.substringAfter("\n\n").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            val title = enc("Crash: " + error.take(100))
            // A code fence in the report would end the issue's own; a cut never splits a character.
            val text = report.replace(FENCE, "'''")
            var chars = text.length
            while (true) {
                val cut = if (chars in 1 until text.length && text[chars - 1].isHighSurrogate()) chars - 1 else chars
                val url = "$ISSUES?title=$title&body=${enc(FENCE + "\n" + text.take(cut) + "\n" + FENCE)}"
                if (url.length <= MAX_URL || chars <= 0) return url
                chars = maxOf(0, chars - maxOf(1, (url.length - MAX_URL) / 3))
            }
        }
    }
}

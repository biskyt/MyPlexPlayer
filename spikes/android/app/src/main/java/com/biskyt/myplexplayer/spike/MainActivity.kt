package com.biskyt.myplexplayer.spike

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executors

/**
 * One run of spikes/plex_spike.py's "all" step, kept outside the screen: a screen rebuilt mid-run (dark mode, a
 * trip to the browser) picks it up again rather than losing it or starting a second one.
 */
object SpikeRun {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    val printed = StringBuilder()
    var running = false
        private set
    var code: String? = null
        private set
    var zip: File? = null
        private set

    /** The screen showing the run, told on the main thread of each change. */
    var screen: MainActivity? = null

    /** Runs the spike's [step]: "all", "save-favourites", "cast", "test-dvr" or "test-recordings". */
    fun start(files: File, step: String = "all") {
        if (running) return
        running = true
        printed.clear()
        code = null
        zip = null
        worker.execute {
            val result = try {
                Python.getInstance().getModule("runner").callAttr("run", files.absolutePath, Printer(), step).toString()
            } catch (e: Throwable) { // shown, rather than closing the app
                main.post { add("\nStopped with an error: $e\n") }
                ""
            }
            main.post {
                running = false
                zip = result.takeIf { it.isNotEmpty() }?.let(::File)
                screen?.update()
            }
        }
    }

    fun forget(files: File) {
        if (running) return
        Python.getInstance().getModule("runner").callAttr("forget", files.absolutePath)
        File(files, "share").deleteRecursively()
        printed.clear()
        code = null
        zip = null
    }

    /** Called from Python with each piece of printed text. */
    class Printer {
        fun emit(text: String) = main.post { add(text) }
    }

    private fun add(text: String) {
        val from = maxOf(0, printed.length - CODE_LOOKBACK)
        printed.append(text)
        // Only the newest output: the whole log grows to thousands of lines.
        CODE.find(printed, from)?.let { code = it.groupValues[1] }
        screen?.update()
    }

    private val CODE = Regex("plex\\.tv/link and enter: ([A-Z0-9]+)")
    private const val CODE_LOOKBACK = 80
}

/** Run the tests, show the plex.tv/link code, share the zip; the script's printed lines are shown as they come. */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var run: Button
    private lateinit var saveFavourites: Button
    private lateinit var cast: Button
    private lateinit var seriesDvr: Button
    private lateinit var recordings: Button
    private lateinit var codePanel: LinearLayout
    private lateinit var code: TextView
    private lateinit var share: Button

    /** How much of [SpikeRun.printed] the log shows. */
    private var shown = 0
    private var hinted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        column.addView(text("MyPlexPlayer spike", 24f, bold = true))
        column.addView(
            text(
                "Tests how MyPlexPlayer will talk to your Plex server: sign-in, your servers, libraries, playback " +
                    "(never transcoding), Live TV, DVR, favourite channels, skip-intro markers and cast. Keep this phone " +
                    "on your home Wi-Fi until it says Done (about 10 minutes).",
                15f
            )
        )
        run = button("Run the tests") { SpikeRun.start(filesDir) }
        column.addView(run)
        column.addView(
            text(
                "Test saving favourite channels: saves your Plex favourite channels back unchanged (a backup " +
                    "first, put back at once if anything differs), then reads them again (about a minute).",
                15f
            )
        )
        saveFavourites = button("Test saving favourite channels") { SpikeRun.start(filesDir, "save-favourites") }
        column.addView(saveFavourites)
        column.addView(
            text(
                "Test cast and similar titles: reads a show's cast, a cast member's biography and other films and " +
                    "shows, and similar titles, from your server and from Plex (about a minute; only reads).",
                15f
            )
        )
        cast = button("Test cast and similar titles") { SpikeRun.start(filesDir, "cast") }
        column.addView(cast)
        column.addView(
            text(
                "Test series recording: sets a show with no recording yet to record (new airings only), changes and " +
                    "restores one of its settings, cancels one airing, then removes the series recording (about a minute).",
                15f
            )
        )
        seriesDvr = button("Test series recording") { SpikeRun.start(filesDir, "test-dvr") }
        column.addView(seriesDvr)
        column.addView(
            text(
                "Test recordings: while a recording is in progress, finds it and recent recordings in your " +
                    "library, checks that their files can be read, and briefly tunes the channel being recorded " +
                    "to test watching from the start (about a minute).",
                15f
            )
        )
        recordings = button("Test recordings") { SpikeRun.start(filesDir, "test-recordings") }
        column.addView(recordings)

        code = text("", 40f, bold = true).apply { typeface = Typeface.MONOSPACE }
        codePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Sign in: open plex.tv/link (signed in to your Plex account) and enter", 15f))
            addView(code)
            addView(button("Open plex.tv/link") { openLink() })
        }
        column.addView(codePanel)

        share = button("Share the results") { shareResults() }
        column.addView(share)

        log = text("", 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(log) }
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(button("Start again (forget the sign-in)") { forget() })
        setContentView(column)
        SpikeRun.screen = this
        update()
    }

    override fun onDestroy() {
        if (SpikeRun.screen === this) SpikeRun.screen = null
        super.onDestroy()
    }

    /** Shows the run as it stands. */
    fun update() {
        val running = SpikeRun.running
        run.isEnabled = !running
        saveFavourites.isEnabled = !running
        cast.isEnabled = !running
        seriesDvr.isEnabled = !running
        recordings.isEnabled = !running
        run.text = if (SpikeRun.printed.isEmpty() || running) "Run the tests" else "Run the tests again"
        val shownCode = SpikeRun.code.takeIf { running }
        codePanel.visibility = if (shownCode != null) View.VISIBLE else View.GONE
        code.text = shownCode.orEmpty()
        share.visibility = if (!running && SpikeRun.zip != null) View.VISIBLE else View.GONE
        if (running) window.addFlags(KEEP_ON) else window.clearFlags(KEEP_ON)
        val printed = SpikeRun.printed
        if (printed.length < shown) { // forgotten, or a new run
            log.text = ""
            shown = 0
        }
        if (printed.length > shown) { // only what's new: the log grows to thousands of lines
            log.append(printed.substring(shown))
            shown = printed.length
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        if (running) hinted = false
        if (!running && SpikeRun.zip != null && !hinted) {
            log.append(SHARE_HINT)
            hinted = true
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun openLink() = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://plex.tv/link")))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No browser here: open plex.tv/link on another device", Toast.LENGTH_LONG).show()
    }

    private fun shareResults() {
        val zip = SpikeRun.zip ?: return
        // Shared from a folder of its own, so the share link can never reach the sign-in file beside it.
        val shared = File(filesDir, "share/spike-results.zip").also { zip.copyTo(it, overwrite = true) }
        val uri = FileProvider.getUriForFile(this, "com.biskyt.myplexplayer.spike.files", shared)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "MyPlexPlayer spike results")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share the results"))
    }

    private fun forget() {
        if (SpikeRun.running) return
        SpikeRun.forget(filesDir)
        update()
        shown = 0
        log.text = "Sign-in and results deleted. Also remove \"MyPlexPlayer spike\" in plex.tv › Settings › " +
            "Authorized Devices.\n"
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private companion object {
        const val KEEP_ON = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        const val SHARE_HINT = "\nTap \"Share the results\" and send the zip to the conversation with Claude.\n"
    }
}

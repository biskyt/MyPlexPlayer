package com.biskyt.myplexplayer.dvdiag

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One screen at a time, built from plain views so the remote's D-pad works without extra code: the device report,
 * Plex sign-in, choosing a title, the test plays, and the report to send.
 */
class MainActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var plex: Plex
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var surface: SurfaceView
    private lateinit var hint: TextView

    /** Where Back goes from the current screen; null leaves the app. */
    private var back: (() -> Unit)? = null
    private var job: Job? = null

    private var deviceReport: String? = null
    private var chosenTitle: String? = null
    private var fileChoice: String? = null
    private val results = mutableListOf<PlaybackResult>()

    /** What Plex says about the file: its container and video stream (never its name or where it is). */
    private var plexDescription = ""

    /** The home screen's line on the device report, while home shows. */
    private var homeStatus: TextView? = null
    private var server: ReportServer? = null

    private var playing: PlaybackTest? = null
    private var afterPlay: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        plex = Plex(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        scroll = ScrollView(this).apply { addView(content) }
        surface = SurfaceView(this).apply { visibility = View.GONE }
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            visibility = View.GONE
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(18, 18, 18))
                addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { gravity = Gravity.TOP })
            }
        )
        scope.launch {
            deviceReport = withContext(Dispatchers.Default) {
                runCatching { DeviceReport.collect(applicationContext) }.getOrElse { "Device report failed: $it" }
            }
            homeStatus?.text = READY
        }
        home()
    }

    override fun onDestroy() {
        stopPlaying()
        server?.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val stops = event.keyCode in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        if (playing != null && stops) {
            if (event.action == KeyEvent.ACTION_UP) finishPlay()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && back != null) {
            if (event.action == KeyEvent.ACTION_UP) back?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Screens

    private fun home(): Unit = screen(null) {
        heading("Dolby Vision diagnostics")
        text(
            "This collects what your TV's decoders and display say they support, and can test play a Dolby Vision title " +
                "from your Plex server in three ways, to find out why MyPlexPlayer shows it as HDR10."
        )
        homeStatus = text(if (deviceReport == null) "Collecting the device report…" else READY)
        button("Test play a title from Plex") { startPlex() }
        button("Show and send the report") { report() }
    }

    private fun startPlex() {
        val token = plex.accountToken
        if (token == null) signIn() else servers(token)
    }

    private fun signIn(): Unit = screen(::home) {
        heading("Sign in to Plex")
        val status = text("Getting a code…")
        launch {
            val pin = runCatching { plex.createPin() }.getOrElse {
                status.text = "Couldn't reach plex.tv: $it"
                return@launch
            }
            status.text = "On your phone or computer, go to plex.tv/link and enter this code:"
            text(pin.code).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
                setTextColor(Color.rgb(0xE5, 0xA0, 0x0D))
                typeface = Typeface.DEFAULT_BOLD
            }
            qr("https://plex.tv/link")
            repeat(450) { // 15 minutes, the code's lifetime
                delay(2000)
                val token = runCatching { plex.pinToken(pin) }.getOrNull()
                if (token != null) {
                    plex.accountToken = token
                    servers(token)
                    return@launch
                }
            }
            status.text = "The code has run out. Press Back and try again."
        }
    }

    private fun servers(token: String): Unit = screen(::home) {
        heading("Your Plex servers")
        val status = text("Finding your servers…")
        launch {
            val list = runCatching { plex.servers(token) }.getOrElse {
                status.text = "Couldn't list your servers: $it"
                button("Sign in again") { signIn() }
                return@launch
            }
            status.text = if (list.isEmpty()) "Your account has no servers." else "Choose the server with the title:"
            list.forEach { s -> button(s.name) { connect(s) } }
            button("Sign in again") { signIn() }
        }
    }

    private fun connect(s: Plex.Server): Unit = screen({ startPlex() }) {
        heading(s.name)
        val status = text("Connecting…")
        launch {
            val connected = plex.connect(s)
            if (connected == null) {
                status.text = "None of the server's addresses answered."
            } else {
                pick(connected)
            }
        }
    }

    private fun pick(s: Plex.Connected, query: String = ""): Unit = screen({ startPlex() }) {
        heading("Choose the Dolby Vision title")
        text("Search by name, or pick from On Deck below.")
        val field = EditText(this@MainActivity).apply {
            setText(query)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ ->
                pick(s, text.toString())
                true
            }
        }
        content.addView(field)
        button("Search") { pick(s, field.text.toString()) }
        val status = text(if (query.isEmpty()) "Loading On Deck…" else "Searching…")
        launch {
            val items = runCatching { if (query.isEmpty()) plex.onDeck(s) else plex.search(s, query) }.getOrElse {
                status.text = "Failed: $it"
                return@launch
            }
            status.text = if (items.isEmpty()) "Nothing found." else ""
            items.forEach { item ->
                button(item.label) { if (item.type == "show") episodes(s, item, query) else chosen(s, item) }
            }
        }
    }

    private fun episodes(s: Plex.Connected, show: Plex.Item, query: String): Unit = screen({ pick(s, query) }) {
        heading(show.label.substringBefore(" (series"))
        val status = text("Loading episodes…")
        launch {
            val items = runCatching { plex.episodes(s, show) }.getOrElse {
                status.text = "Failed: $it"
                return@launch
            }
            status.text = ""
            items.take(300).forEach { item -> button(item.label) { chosen(s, item) } }
        }
    }

    private fun chosen(s: Plex.Connected, item: Plex.Item): Unit = screen({ pick(s) }) {
        heading(item.label)
        val status = text("Reading the file's details…")
        launch {
            val metadata = runCatching { plex.metadata(s, item) }.getOrElse {
                status.text = "Failed: $it"
                return@launch
            }
            val media = metadata.optJSONArray("Media")?.optJSONObject(0)
            val part = media?.optJSONArray("Part")?.optJSONObject(0)
            if (part == null) {
                status.text = "Plex lists no file for this title."
                return@launch
            }
            if (chosenTitle != item.label) {
                chosenTitle = item.label
                results.clear()
                fileChoice = null
            }
            plexDescription = describe(media, part)
            status.text = plexDescription
            val duration = metadata.optLong("duration")
            val start = if (duration > START_MS * 2) START_MS else 0L
            val url = plex.playUrl(s, part.getString("key"))
            text("Each test plays from ${start / 60_000} minutes in, for up to 40 seconds (Back or OK stops it sooner).")
            button("Run all three tests") { playAll(url, start, Way.entries, s, item) }
            Way.entries.forEach { way -> button(way.label) { playAll(url, start, listOf(way), s, item) } }
            button("Show and send the report") { report() }
        }
    }

    private fun describe(media: JSONObject, part: JSONObject): String = buildString {
        appendLine("Container ${media.optString("container")}, ${media.optInt("width")}x${media.optInt("height")}, ")
        append("video ${media.optString("videoCodec")} ${media.optString("videoProfile")}, ")
        appendLine("${media.optInt("bitrate")} kbps")
        val video = part.optJSONArray("Stream").objects().firstOrNull { it.optInt("streamType") == 1 }
        // Not its ids, nor any title (a stream can be named after the film).
        video?.keys()?.asSequence()?.sorted()?.filter { it !in setOf("id", "key", "index") && !it.contains("title", ignoreCase = true) }?.forEach { key ->
            appendLine("  $key: ${video.get(key)}")
        }
    }

    private fun playAll(url: String, start: Long, ways: List<Way>, s: Plex.Connected, item: Plex.Item) {
        val way = ways.firstOrNull() ?: return chosen(s, item)
        play(url, start, way) { ask(way) { playAll(url, start, ways.drop(1), s, item) } }
    }

    private fun play(url: String, start: Long, way: Way, then: () -> Unit) {
        job?.cancel()
        back = null
        scroll.visibility = View.GONE
        surface.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
        hint.text = "${way.label}: watch for the TV's Dolby Vision logo or picture mode. Back or OK stops."
        val result = PlaybackResult(way).also { results.removeAll { r -> r.way == way }; results += it }
        playing = PlaybackTest(this, url, start, surface, result)
        afterPlay = then
        handler.postDelayed({ hint.visibility = View.GONE }, 6000)
        handler.postDelayed({ finishPlay() }, PLAY_MS)
    }

    private fun finishPlay() {
        val test = playing ?: return
        val format = test.result.inputFormat
        if (fileChoice == null && format != null) {
            scope.launch { fileChoice = withContext(Dispatchers.Default) { DeviceReport.describeChoice(applicationContext, format) } }
        }
        stopPlaying()
        afterPlay?.invoke()
    }

    private fun stopPlaying() {
        handler.removeCallbacksAndMessages(null)
        playing?.release()
        playing = null
        surface.visibility = View.GONE
        hint.visibility = View.GONE
        scroll.visibility = View.VISIBLE
    }

    // Back does nothing here: leaving would lose every result so far.
    private fun ask(way: Way, next: () -> Unit): Unit = screen({}) {
        heading("What did the TV show in ${way.label}?")
        val result = results.last { it.way == way }
        listOf(
            "Dolby Vision (its logo, or Dolby Vision in the picture settings)",
            "HDR10 or HDR (but not Dolby Vision)",
            "No HDR at all",
            "A black screen or no picture",
            "Not sure"
        ).forEach { answer ->
            button(answer) {
                result.seen = answer
                next()
            }
        }
        if (result.errors.isNotEmpty()) text("The player reported: ${result.errors.first()}")
    }

    private fun report(): Unit = screen(::home) {
        heading("The report")
        val server = server ?: runCatching { ReportServer(::fullReport) }.getOrNull()?.also { server = it }
        val address = server?.address
        if (address != null) {
            text("On a phone on the same Wi-Fi, scan this or open $address, then copy the text into the chat:")
            qr(address)
        }
        button("Share") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fullReport()), "Send the report"))
        }
        button("Save to Downloads") { it.text = save() }
        text(fullReport()).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
    }

    private fun fullReport(): String = buildString {
        appendLine("MyPlexPlayer Dolby Vision diagnostics, ${getString(R.string.app_name)}")
        appendLine()
        appendLine(deviceReport ?: "(still collecting)")
        if (plexDescription.isNotEmpty()) {
            appendLine()
            appendLine("== The title's file, as Plex describes it")
            appendLine(plexDescription)
        }
        fileChoice?.let {
            appendLine("== Media3's choice for this file's video")
            appendLine(it)
        }
        if (results.isNotEmpty()) appendLine("== Test plays")
        results.forEach { appendLine(it.describe()) }
    }

    private fun save(): String = runCatching {
        val name = "dv-diagnostics-${packageName.substringAfterLast('.')}.txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(fullReport().toByteArray()) }
            "Saved to Downloads as $name"
        } else {
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
            file.writeText(fullReport())
            "Saved to ${file.path}"
        }
    }.getOrElse { "Couldn't save: $it" }

    // Building blocks

    private fun screen(back: (() -> Unit)?, build: () -> Unit) {
        job?.cancel()
        content.removeAllViews()
        homeStatus = null
        this.back = back
        build()
        scroll.scrollTo(0, 0)
        content.post { (0 until content.childCount).map { content.getChildAt(it) }.firstOrNull { it is Button }?.requestFocus() }
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit) {
        job = scope.launch(block = block)
    }

    private fun heading(value: String) = text(value).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, dp(8), 0, dp(8))
        content.addView(this)
    }

    private fun button(label: String, onClick: (Button) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick(this) }
        content.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** As the app draws its QR codes: medium error correction, with ZXing's own 4-module quiet border. */
    private fun qr(value: String) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val pixels = IntArray(matrix.width * matrix.height) { if (matrix[it % matrix.width, it / matrix.width]) Color.BLACK else Color.WHITE }
        val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565)
        // One pixel a module, scaled up without smoothing.
        val image = ImageView(this).apply {
            setImageDrawable(BitmapDrawable(resources, bitmap).apply { setFilterBitmap(false) })
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(image, LinearLayout.LayoutParams(dp(220), dp(220)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Where the tests start: past any dark opening titles. */
        const val START_MS = 5 * 60_000L
        const val PLAY_MS = 40_000L
        const val READY = "The device report is ready."
    }
}

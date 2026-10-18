package com.biskyt.mytvplayer.core.epg

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class XmltvChannel(val id: String, val names: List<String>, val iconUrl: String?)

data class XmltvProgramme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
)

/**
 * Streaming XMLTV reader. Every `<channel>` is reported (they are small and needed for matching), but
 * `<programme>` elements are only built when [wantProgrammes] accepts the channel id and the programme
 * overlaps [windowStartMs]..[windowEndMs]; everything else is skipped without allocating.
 *
 * The caller supplies the [XmlPullParser] so Android can use the framework parser and tests can use kxml2.
 */
class XmltvParser(
    private val newParser: () -> XmlPullParser,
    private val windowStartMs: Long = Long.MIN_VALUE,
    private val windowEndMs: Long = Long.MAX_VALUE,
) {
    /** Blocking read; call from an IO dispatcher. Callbacks may suspend (e.g. to write a batch to the database). */
    suspend fun parse(
        input: InputStream,
        wantProgrammes: (channelId: String) -> Boolean,
        onChannel: suspend (XmltvChannel) -> Unit,
        onProgramme: suspend (XmltvProgramme) -> Unit,
    ) {
        val p = newParser()
        p.setInput(input, null)
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> readChannel(p)?.let { onChannel(it) }
                    "programme" -> {
                        val id = p.getAttributeValue(null, "channel")
                        val start = parseTime(p.getAttributeValue(null, "start"))
                        val stop = parseTime(p.getAttributeValue(null, "stop"))
                        if (id == null || start == null || !wantProgrammes(id)) {
                            p.skipElement()
                        } else {
                            val end = stop ?: (start + DEFAULT_LENGTH_MS)
                            if (end <= windowStartMs || start >= windowEndMs) p.skipElement() else onProgramme(readProgramme(p, id, start, end))
                        }
                    }
                }
            }
        }
    }

    private fun readChannel(p: XmlPullParser): XmltvChannel? {
        val id = p.getAttributeValue(null, "id")
        val names = ArrayList<String>(2)
        var icon: String? = null
        forEachChild(p) { tag ->
            when (tag) {
                "display-name" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let(names::add)
                "icon" -> { icon = p.getAttributeValue(null, "src"); p.skipElement() }
                else -> p.skipElement()
            }
        }
        return id?.let { XmltvChannel(it, names, icon) }
    }

    private fun readProgramme(p: XmlPullParser, channelId: String, start: Long, stop: Long): XmltvProgramme {
        var title: String? = null
        var sub: String? = null
        var desc: String? = null
        var category: String? = null
        forEachChild(p) { tag ->
            when (tag) {
                "title" -> p.nextText().trim().let { if (title == null) title = it }
                "sub-title" -> p.nextText().trim().let { if (sub == null) sub = it }
                "desc" -> p.nextText().trim().let { if (desc == null) desc = it }
                "category" -> p.nextText().trim().let { if (category == null) category = it }
                else -> p.skipElement()
            }
        }
        return XmltvProgramme(channelId, start, stop, title?.ifEmpty { null } ?: "No title", sub?.ifEmpty { null }, desc?.ifEmpty { null }, category)
    }

    /** Calls [onChild] for each direct child start tag; [onChild] must consume the child element. */
    private inline fun forEachChild(p: XmlPullParser, onChild: (String) -> Unit) {
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) return
            if (e == XmlPullParser.START_TAG) onChild(p.name)
        }
    }

    private fun XmlPullParser.skipElement() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        private const val DEFAULT_LENGTH_MS = 30 * 60_000L
        private val BASIC = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** Parses XMLTV times such as `20261018180000 +0100`; a missing offset means UTC. */
        fun parseTime(value: String?): Long? {
            val v = value?.trim() ?: return null
            if (v.length < 14) return null
            return try {
                val local = LocalDateTime.parse(v.substring(0, 14), BASIC)
                val offsetText = v.substring(14).trim()
                val offset = if (offsetText.length == 5 && (offsetText[0] == '+' || offsetText[0] == '-')) {
                    ZoneOffset.of(offsetText.substring(0, 3) + ":" + offsetText.substring(3))
                } else {
                    ZoneOffset.UTC
                }
                local.toInstant(offset).toEpochMilli()
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}

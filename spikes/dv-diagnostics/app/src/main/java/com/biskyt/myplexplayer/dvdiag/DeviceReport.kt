package com.biskyt.myplexplayer.dvdiag

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/** What the device, its display and its decoders say they support, and what Media3 makes of it. */
object DeviceReport {
    fun collect(context: Context): String = buildString {
        appendLine("== Device")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, board ${Build.BOARD}, hardware ${Build.HARDWARE})")
        if (Build.VERSION.SDK_INT >= 31) appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
        appendLine("Fingerprint: ${Build.FINGERPRINT}")
        appendLine("Media3 ${MediaLibraryInfo.VERSION}")
        appendLine()
        display(context)
        appendLine()
        properties()
        appendLine()
        decoders()
        appendLine()
        media3(context)
    }

    /**
     * Media3's own view of a format: the decoders its video renderer would try, in its order, and whether each would
     * do. The list comes from the renderer's own (private) method, which for Dolby Vision also takes in the plain
     * HEVC decoders, first when the display doesn't report Dolby Vision; so it is read by reflection, falling back to
     * a look-alike when the method isn't there.
     */
    fun describeChoice(context: Context, format: Format): String = buildString {
        val support = runCatching { MediaCodecVideoRenderer.supportsFormat(context, MediaCodecSelector.DEFAULT, format) }
        appendLine("  Renderer: ${support.map { formatSupport(it) }.getOrElse { "error: $it" }}")
        appendLine("  Alternative type: ${MediaCodecUtil.getAlternativeCodecMimeType(format)}")
        runCatching {
            val (infos, source) = rendererDecoders(context, format)
            appendLine("  Decoders, in the renderer's order ($source):")
            sortedByFormatSupport(context, infos, format).forEachIndexed { i, info ->
                appendLine(
                    "  ${i + 1}. ${info.name} (as ${info.codecMimeType}): supported=${info.supports(context, format)}, " +
                        "hardware=${info.hardwareAccelerated}, vendor=${info.vendor}, secure=${info.secure}, " +
                        "tunneling=${info.tunneling}"
                )
            }
        }.onFailure { appendLine("  Decoders: error: $it") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rendererDecoders(context: Context, format: Format): Pair<List<MediaCodecInfo>, String> = runCatching {
        val method = MediaCodecVideoRenderer::class.java.getDeclaredMethod(
            "getDecoderInfos", Context::class.java, MediaCodecSelector::class.java, Format::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        method.invoke(null, context, MediaCodecSelector.DEFAULT, format, false, false) as List<MediaCodecInfo> to "Media3's own list"
    }.getOrElse {
        val exact = MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false)
        val alternatives = MediaCodecUtil.getAlternativeDecoderInfos(MediaCodecSelector.DEFAULT, format, false, false)
        exact + alternatives to "look-alike: its own list wasn't readable ($it)"
    }

    /** A renderer's or track's support for a format, from its capabilities or a C.FORMAT_* value. */
    fun formatSupport(capabilities: Int): String = when (RendererCapabilities.getFormatSupport(capabilities)) {
        C.FORMAT_HANDLED -> "handled"
        C.FORMAT_EXCEEDS_CAPABILITIES -> "exceeds capabilities"
        C.FORMAT_UNSUPPORTED_DRM -> "unsupported DRM"
        C.FORMAT_UNSUPPORTED_SUBTYPE -> "unsupported subtype"
        C.FORMAT_UNSUPPORTED_TYPE -> "unsupported type"
        else -> "unknown"
    }

    /** A format's video details (not Format.toLogString, which can carry a track's name). */
    fun describe(format: Format): String = "${format.sampleMimeType}, codecs=${format.codecs}, " +
        "${format.width}x${format.height} @ ${format.frameRate} fps, colour=${format.colorInfo}, " +
        "container=${format.containerMimeType}"

    private fun StringBuilder.display(context: Context) {
        appendLine("== Display")
        val displays = context.getSystemService(DisplayManager::class.java)
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        appendLine("Mode: ${mode.physicalWidth}x${mode.physicalHeight} @ ${mode.refreshRate} Hz (id ${mode.modeId})")
        appendLine("Modes: " + display.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" })
        val hdr = display.hdrCapabilities
        appendLine(
            "HDR types: ${hdr?.supportedHdrTypes?.joinToString { hdrType(it) } ?: "none reported"}; luminance max " +
                "${hdr?.desiredMaxLuminance}, average ${hdr?.desiredMaxAverageLuminance}, min ${hdr?.desiredMinLuminance}"
        )
        appendLine("isHdr=${display.isHdr}, isWideColorGamut=${display.isWideColorGamut}")
        if (Build.VERSION.SDK_INT >= 34) {
            appendLine("Mode's HDR types: ${mode.supportedHdrTypes.joinToString { hdrType(it) }}")
            appendLine("HDR output conversion: ${displays.hdrConversionMode}")
        }
    }

    private fun hdrType(type: Int) = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "type $type"
    }

    /** System properties that name the chip or Dolby Vision, where the system lets an app read them. */
    private fun StringBuilder.properties() {
        appendLine("== System properties (Dolby, HDR, chip)")
        val wanted = Regex("dolby|dovi|\\.dv\\.|hdr|soc|chip|platform|mediatek|mtk|amlogic|realtek|tvos", RegexOption.IGNORE_CASE)
        runCatching {
            val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.filter { wanted.containsMatchIn(it.substringBefore("]:")) }.take(80).forEach { appendLine(it) }
            }
        }.onFailure { appendLine("Not readable: $it") }
    }

    /** Every video decoder, with the profiles it claims for the HDR-capable types. */
    private fun StringBuilder.decoders() {
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val regular = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.map { it.name }.toSet()
        appendLine("== Video decoders (${all.size} codecs in all)")
        all.filter { !it.isEncoder && it.supportedTypes.any { t -> t.startsWith("video/") } }.forEach { info ->
            val kind = if (Build.VERSION.SDK_INT >= 29) {
                "hardware=${info.isHardwareAccelerated}, softwareOnly=${info.isSoftwareOnly}, vendor=${info.isVendor}, " +
                    "canonical=${info.canonicalName}"
            } else {
                ""
            }
            appendLine("${info.name}${if (info.name in regular) "" else " (not in REGULAR_CODECS)"}: $kind")
            info.supportedTypes.filter { it.startsWith("video/") }.forEach { type ->
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@forEach
                appendLine("  $type")
                if (type in DETAILED) {
                    appendLine("    profiles: " + caps.profileLevels.joinToString { "${profileName(type, it.profile)}/${levelName(type, it.level)}" })
                    val video = caps.videoCapabilities
                    appendLine(
                        "    up to ${video.supportedWidths.upper}x${video.supportedHeights.upper}; " +
                            "3840x2160@24 ${video.areSizeAndRateSupported(3840, 2160, 24.0)}, " +
                            "3840x2160@60 ${video.areSizeAndRateSupported(3840, 2160, 60.0)}"
                    )
                    val features = listOf("adaptive-playback", "secure-playback", "tunneled-playback", "low-latency")
                        .filter { runCatching { caps.isFeatureSupported(it) }.getOrDefault(false) }
                    appendLine("    features: $features")
                }
            }
        }
    }

    /** How Media3 would choose a decoder for typical Dolby Vision and HDR10 files. */
    private fun StringBuilder.media3(context: Context) {
        // Made-up formats, for comparing devices: the test plays show Media3's choice for the real file.
        appendLine("== Media3's choice for sample formats")
        val hdr10 = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        val samples = listOf(
            Triple("Dolby Vision 8.1 (HDR10 base layer)", "dvhe.08.06", MimeTypes.VIDEO_DOLBY_VISION),
            Triple("Dolby Vision 5", "dvhe.05.06", MimeTypes.VIDEO_DOLBY_VISION),
            Triple("Dolby Vision 7", "dvhe.07.06", MimeTypes.VIDEO_DOLBY_VISION),
            Triple("HEVC Main 10 HDR10", "hvc1.2.4.L153.B0", MimeTypes.VIDEO_H265)
        )
        samples.forEach { (name, codecs, mimeType) ->
            val format = Format.Builder().setSampleMimeType(mimeType).setCodecs(codecs)
                .setWidth(3840).setHeight(2160).setFrameRate(23.976f).setColorInfo(hdr10).build()
            appendLine("$name ($codecs):")
            append(describeChoice(context, format))
        }
    }

    private val DETAILED = setOf(MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_VP9)

    private fun profileName(type: String, value: Int): String {
        val name = CODEC_NAMES[profilePrefix(type)]?.get(value) ?: return hex(value)
        val dvNumber = DV_PROFILES.indexOf(name)
        return if (type == MimeTypes.VIDEO_DOLBY_VISION && dvNumber >= 0) "$name ($dvNumber)" else name
    }

    private fun levelName(type: String, value: Int): String = CODEC_NAMES[levelPrefix(type)]?.get(value) ?: hex(value)

    private fun hex(value: Int) = "0x${value.toString(16)}"

    private fun profilePrefix(type: String) = when (type) {
        MimeTypes.VIDEO_DOLBY_VISION -> "DolbyVisionProfile"
        MimeTypes.VIDEO_H265 -> "HEVCProfile"
        MimeTypes.VIDEO_AV1 -> "AV1Profile"
        else -> "VP9Profile"
    }

    private fun levelPrefix(type: String) = when (type) {
        MimeTypes.VIDEO_DOLBY_VISION -> "DolbyVisionLevel"
        MimeTypes.VIDEO_H265 -> "HEVC" // HEVCMainTierLevel41, HEVCHighTierLevel51…
        MimeTypes.VIDEO_AV1 -> "AV1Level"
        else -> "VP9Level"
    }

    /** CodecProfileLevel's constants by prefix, then value: e.g. DolbyVisionProfile, 0x100 → DvheSt. Read once. */
    private val CODEC_NAMES: Map<String, Map<Int, String>> by lazy {
        val prefixes = DETAILED.flatMap { listOf(profilePrefix(it), levelPrefix(it)) }
        val fields = CodecProfileLevel::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }
        prefixes.associateWith { prefix ->
            fields.filter { it.name.startsWith(prefix) && (prefix != "HEVC" || "Level" in it.name) }
                .associate { it.getInt(null) to it.name.removePrefix(prefix) }
        }
    }

    /** Dolby Vision's profile names in profile-number order (DvheSt is profile 8). */
    private val DV_PROFILES = listOf(
        "DvavPer", "DvavPen", "DvheDer", "DvheDen", "DvheDtr", "DvheStn", "DvheDth", "DvheDtb", "DvheSt", "DvavSe", "Dvav110"
    )
}

package com.biskyt.myplexplayer.dvdiag

import java.nio.ByteBuffer

/**
 * Counts the Dolby Vision and HDR10+ metadata in HEVC samples (Annex B: Media3's Matroska and MP4 readers hand the
 * decoder start codes), and can switch the HDR10+ messages off. Some chipsets show HDR10 or a black picture when a
 * Dolby Vision decoder also gets HDR10+; Plezy cuts those messages out of every sample for that reason.
 *
 * Done the light way, in place and without copying: an SEI NAL holding only HDR10+ gets an unspecified NAL type (48),
 * which the HEVC spec says decoders ignore, so nothing moves. And when only switching off (not counting), the scan
 * stops at the first slice: HDR10+ travels in prefix SEI, ahead of the picture data, so a sample costs a few hundred
 * bytes of reading, however large it is.
 */
class NalScanner {
    /** Dolby Vision RPUs seen (NAL type 62), one per frame in a Dolby Vision stream. */
    var rpus = 0L
        private set

    /** Dolby Vision enhancement-layer NALs seen (type 63, profile 7). */
    var enhancementLayers = 0L
        private set

    /** HDR10+ (SMPTE ST 2094-40) messages seen while counting. */
    var hdr10Plus = 0L
        private set

    /** SEI NALs switched off (each held only HDR10+). */
    var switchedOff = 0L
        private set

    /** HDR10+ messages left on, as they share an SEI NAL with other messages. */
    var leftOn = 0L
        private set

    /** Samples counted in full. */
    var samples = 0L
        private set

    /**
     * Reads [data] from its position to its limit, leaving both as they were. With [count], it reads the whole sample
     * and counts what it holds; with [switchOffHdr10Plus], it switches off each SEI NAL that holds only HDR10+.
     */
    fun scan(data: ByteBuffer, count: Boolean, switchOffHdr10Plus: Boolean) {
        if (!count && !switchOffHdr10Plus) return
        if (count) samples++
        val end = data.limit()
        var header = nextNal(data, data.position(), end)
        while (header >= 0 && header + 1 < end) {
            val type = (data.get(header).toInt() and 0x7E) shr 1
            if (type < FIRST_NON_SLICE && !count) return // the picture data: no HDR10+ after it
            val next = nextNal(data, header + 2, end)
            when (type) {
                RPU -> rpus++
                ENHANCEMENT_LAYER -> enhancementLayers++
                PREFIX_SEI, SUFFIX_SEI -> sei(data, header, if (next < 0) end else next - 3, count, switchOffHdr10Plus)
            }
            header = next
        }
    }

    private fun sei(data: ByteBuffer, header: Int, end: Int, count: Boolean, switchOff: Boolean) {
        val (found, others) = Rbsp(data, header + 2, end).hdr10PlusMessages()
        if (found == 0) return
        if (count) hdr10Plus += found
        if (!switchOff) return
        if (others == 0) {
            // Keep the forbidden bit and the layer id's top bit; only the type changes.
            data.put(header, ((UNSPECIFIED shl 1) or (data.get(header).toInt() and 0x81)).toByte())
            switchedOff++
        } else {
            leftOn += found
        }
    }

    /** An SEI payload read past its emulation prevention bytes (00 00 03). */
    private class Rbsp(private val data: ByteBuffer, private var pos: Int, private val end: Int) {
        private var zeros = 0

        fun more() = pos < end && peek() != TRAILING_BITS

        /** The next byte, or 0 past the NAL's end (a cut-short message then just doesn't match). */
        fun read(): Int {
            if (zeros >= 2 && pos < end && data.get(pos).toInt() == 3) {
                pos++
                zeros = 0
            }
            if (pos >= end) return 0
            val b = data.get(pos++).toInt() and 0xFF
            zeros = if (b == 0) zeros + 1 else 0
            return b
        }

        private fun peek() = data.get(pos).toInt() and 0xFF

        /** A value sent as 0xFF bytes plus a last byte, as SEI types and sizes are. */
        fun extended(): Int {
            var value = 0
            while (pos < end) {
                val b = read()
                value += b
                if (b != 0xFF) break
            }
            return value
        }

        /** How many of the NAL's messages are HDR10+, and how many are something else. */
        fun hdr10PlusMessages(): Pair<Int, Int> {
            var found = 0
            var others = 0
            while (more()) {
                val type = extended()
                val size = extended()
                var read = 0
                val id = IntArray(HDR10_PLUS_ID.size)
                while (read < size && pos < end) {
                    val b = read()
                    if (read < id.size) id[read] = b
                    read++
                }
                val hdr10Plus = type == USER_DATA_REGISTERED && size >= id.size &&
                    HDR10_PLUS_ID.indices.all { it == id.lastIndex && id[it] <= 1 || id[it] == HDR10_PLUS_ID[it] }
                if (hdr10Plus) found++ else others++
            }
            return found to others
        }
    }

    private companion object {
        const val FIRST_NON_SLICE = 32
        const val PREFIX_SEI = 39
        const val SUFFIX_SEI = 40
        const val UNSPECIFIED = 48
        const val RPU = 62
        const val ENHANCEMENT_LAYER = 63
        const val USER_DATA_REGISTERED = 4
        const val TRAILING_BITS = 0x80

        /** HDR10+'s registered user data: the US (0xB5), provider 0x003C, oriented code 0x0001, application 4, version 0 or 1. */
        val HDR10_PLUS_ID = intArrayOf(0xB5, 0x00, 0x3C, 0x00, 0x01, 4, 0)

        /**
         * The offset of the next NAL header (just after a 00 00 01 start code; a four-byte one ends the same way) at
         * or after [from], or -1. Skips three bytes at a time where no start code can end, as decoders do.
         */
        fun nextNal(data: ByteBuffer, from: Int, end: Int): Int {
            var i = from
            while (i + 2 < end) {
                val third = data.get(i + 2).toInt() and 0xFF
                when {
                    third > 1 -> i += 3
                    third == 1 && data.get(i + 1).toInt() == 0 && data.get(i).toInt() == 0 -> return i + 3
                    else -> i++
                }
            }
            return -1
        }
    }
}

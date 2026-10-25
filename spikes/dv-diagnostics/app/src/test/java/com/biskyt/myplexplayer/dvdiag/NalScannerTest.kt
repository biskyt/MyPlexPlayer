package com.biskyt.myplexplayer.dvdiag

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NalScannerTest {
    private val aud = nal(35, 0x50)
    private val slice = nal(1, 0x11, 0x22, 0x33)
    private val rpu = nal(62, 0x08, 0x09)
    private val hdr10PlusMessage = intArrayOf(4, 9, 0xB5, 0x00, 0x3C, 0x00, 0x01, 4, 1, 0x55, 0x66)
    private val otherMessage = intArrayOf(4, 9, 0xB5, 0x00, 0x31, 0x00, 0x01, 4, 1, 0x55, 0x66) // another provider
    private val hdr10Plus = nal(39, *hdr10PlusMessage, 0x80)
    private val otherSei = nal(39, *otherMessage, 0x80)
    private val sample = aud + hdr10Plus + otherSei + slice + rpu

    @Test
    fun `counts each kind of metadata and leaves the sample as it was`() {
        val scanner = NalScanner()
        val data = buffer(sample)
        scanner.scan(data, count = true, switchOffHdr10Plus = false)
        assertEquals(1, scanner.rpus)
        assertEquals(1, scanner.hdr10Plus)
        assertEquals(0, scanner.switchedOff)
        assertArrayEquals(sample, data.bytes())
    }

    @Test
    fun `switches off only the HDR10+ NAL, in place`() {
        val scanner = NalScanner()
        val data = buffer(sample)
        scanner.scan(data, count = false, switchOffHdr10Plus = true)
        assertEquals(1, scanner.switchedOff)
        assertArrayEquals(aud + retyped(hdr10Plus) + otherSei + slice + rpu, data.bytes())
    }

    @Test
    fun `finds HDR10+ after another message, and leaves a NAL with both on`() {
        val both = nal(39, *otherMessage, *hdr10PlusMessage, 0x80)
        val scanner = NalScanner()
        val data = buffer(both + slice)
        scanner.scan(data, count = true, switchOffHdr10Plus = true)
        assertEquals(1, scanner.hdr10Plus)
        assertEquals(1, scanner.leftOn)
        assertArrayEquals(both + slice, data.bytes())
    }

    @Test
    fun `reads a message past its emulation prevention bytes`() {
        // A first message whose payload holds 00 00 03 00: sent as 6 bytes, read as 5.
        val escaped = nal(39, 5, 5, 0x00, 0x00, 0x03, 0x00, 0x07, 0x08, *hdr10PlusMessage, 0x80)
        val scanner = NalScanner()
        scanner.scan(buffer(escaped), count = true, switchOffHdr10Plus = false)
        assertEquals(1, scanner.hdr10Plus)
    }

    @Test
    fun `without counting, stops at the first slice`() {
        val after = buffer(slice + hdr10Plus) // not a real stream: HDR10+ never follows the picture
        val scanner = NalScanner()
        scanner.scan(after, count = false, switchOffHdr10Plus = true)
        assertEquals(0, scanner.switchedOff)
    }

    @Test
    fun `works from a buffer's position, with three-byte start codes and leading bytes`() {
        val short = byteArrayOf(0, 0, 1) + hdr10Plus.copyOfRange(4, hdr10Plus.size)
        val data = ByteBuffer.allocateDirect(3 + 2 + short.size + slice.size)
        data.put(byteArrayOf(7, 7, 7, 9, 9)).put(short).put(slice).flip()
        data.position(3)
        NalScanner().scan(data, count = false, switchOffHdr10Plus = true)
        assertEquals(3, data.position())
        assertArrayEquals(byteArrayOf(9, 9) + retyped(short, at = 3) + slice, data.bytes())
    }

    private fun nal(type: Int, vararg body: Int) = byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1) + body.map { it.toByte() }

    /** [nal] as the scanner leaves it switched off: NAL type 48. */
    private fun retyped(nal: ByteArray, at: Int = 4) = nal.copyOf().also { it[at] = (48 shl 1).toByte() }

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes).flip() }

    private fun ByteBuffer.bytes() = ByteArray(remaining()).also { duplicate().get(it) }
}

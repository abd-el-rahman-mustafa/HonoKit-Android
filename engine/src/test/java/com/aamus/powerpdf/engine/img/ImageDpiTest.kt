package com.aamus.powerpdf.engine.img

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.CRC32

class ImageDpiTest {
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int) = (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    /** A JPEG's JFIF units and densities, as an encoder writes them before any image data. */
    private fun jfif(units: Int, x: Int, y: Int) = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1,
        units.toByte(), (x shr 8).toByte(), x.toByte(), (y shr 8).toByte(), y.toByte(), 0, 0, 0xFF.toByte(), 0xD9.toByte(),
    )

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value
        val n = data.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + t + data +
            byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val png = signature + chunk("IHDR", ByteArray(13).also { it[3] = 1; it[7] = 1; it[8] = 8; it[9] = 2 }) +
        chunk("IDAT", byteArrayOf(1, 2, 3)) + chunk("IEND", ByteArray(0))

    @Test
    fun aJpegSaysTheResolutionItWasDrawnAt() {
        // Android writes units 0 (aspect ratio only), 1:1.
        val out = ImageDpi.withDpi(jfif(0, 1, 1), 300)
        assertEquals(1, u8(out, 13))
        assertEquals(300, u16(out, 14))
        assertEquals(300, u16(out, 16))
    }

    @Test
    fun aJpegWithoutJfifGetsOne() {
        val bare = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val out = ImageDpi.withDpi(bare, 150)
        assertEquals(0xE0, u8(out, 3))
        assertEquals(1, u8(out, 13))
        assertEquals(150, u16(out, 14))
        assertEquals(0xD9, u8(out, out.size - 1))
    }

    @Test
    fun aPngGetsAPhysChunkAfterItsHeader() {
        val out = ImageDpi.withDpi(png, 300)
        assertEquals("pHYs", String(out, 33 + 4, 4, Charsets.ISO_8859_1))
        val ppm = u32(out, 33 + 8)
        assertEquals(300.0, ppm * 0.0254, 0.01)
        assertEquals(1, u8(out, 33 + 16))
        val crc = CRC32().apply { update(out, 33 + 4, 13) }.value
        assertEquals(crc, u32(out, 33 + 17))
        // Everything else is kept, in order; doing it again does not add a second chunk.
        assertArrayEquals(png.copyOfRange(33, png.size), out.copyOfRange(33 + 21, out.size))
        assertEquals(out.size, ImageDpi.withDpi(out, 300).size)
    }
}

package com.aamus.powerpdf.engine.img

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.roundToInt

/**
 * Records the resolution a page picture was drawn at in its JPEG (JFIF density, dots per inch) or
 * PNG (pHYs, pixels per metre). Android's encoders write none, so other programs showed and
 * printed a 300-dpi page at their default, several times too big (desktop §97).
 */
object ImageDpi {
    /** [bytes] with [dpi] recorded; unchanged when they are neither a JPEG nor a PNG. */
    fun withDpi(bytes: ByteArray, dpi: Int): ByteArray {
        val d = dpi.coerceIn(1, 65535)
        return when {
            isJpeg(bytes) -> jpeg(bytes, d)
            isPng(bytes) -> png(bytes, d)
            else -> bytes
        }
    }

    private fun isJpeg(b: ByteArray) = b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun isPng(b: ByteArray) = b.size > 33 && PNG_SIGNATURE.indices.all { b[it] == PNG_SIGNATURE[it] }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun jpeg(b: ByteArray, dpi: Int): ByteArray {
        // A JFIF APP0 right after SOI: its units and densities are set in place.
        if (b.size > 18 && u8(b, 2) == 0xFF && u8(b, 3) == 0xE0 && String(b, 6, 5, Charsets.ISO_8859_1) == "JFIF\u0000") {
            val out = b.copyOf()
            out[13] = 1
            out[14] = (dpi shr 8).toByte(); out[15] = dpi.toByte()
            out[16] = (dpi shr 8).toByte(); out[17] = dpi.toByte()
            return out
        }
        // Otherwise a JFIF APP0 is put in front of the other segments.
        val app0 = byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            1, 1, 1, (dpi shr 8).toByte(), dpi.toByte(), (dpi shr 8).toByte(), dpi.toByte(), 0, 0,
        )
        return ByteArrayOutputStream(b.size + app0.size).apply {
            write(b, 0, 2)
            write(app0)
            write(b, 2, b.size - 2)
        }.toByteArray()
    }

    private fun png(b: ByteArray, dpi: Int): ByteArray {
        val ppm = (dpi / 0.0254).roundToInt()
        val out = ByteArrayOutputStream(b.size + 21)
        // The signature and IHDR (always first: 4 length + 4 type + 13 data + 4 CRC).
        val ihdrEnd = 8 + 25
        out.write(b, 0, ihdrEnd)
        val data = ByteArray(13)
        for (k in 0 until 2) {
            data[k * 4] = (ppm ushr 24).toByte(); data[k * 4 + 1] = (ppm ushr 16).toByte()
            data[k * 4 + 2] = (ppm ushr 8).toByte(); data[k * 4 + 3] = ppm.toByte()
        }
        data[8] = 1
        val type = "pHYs".toByteArray(Charsets.ISO_8859_1)
        out.write(byteArrayOf(0, 0, 0, 9))
        out.write(type)
        out.write(data, 0, 9)
        val crc = CRC32().apply { update(type); update(data, 0, 9) }.value
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        // Every other chunk, an earlier pHYs left out.
        var i = ihdrEnd
        while (i + 12 <= b.size) {
            val len = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
            val end = i + 12 + len
            if (len < 0 || end > b.size) {
                out.write(b, i, b.size - i)
                return out.toByteArray()
            }
            if (String(b, i + 4, 4, Charsets.ISO_8859_1) != "pHYs") out.write(b, i, end - i)
            i = end
        }
        if (i < b.size) out.write(b, i, b.size - i)
        return out.toByteArray()
    }
}

package com.aamus.powerpdf.engine.img

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Files are recognised by their first bytes, not their names (decisions §34). */
class SniffTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun jpegWhateverItsName() {
        val jpeg = bytes(0xff, 0xd8, 0xff, 0xe0) + ascii("JFIF")
        assertEquals(ImgFormat.JPG, ImgFormat.sniff(jpeg, "photo.jfif"))
        assertEquals(ImgFormat.JPG, ImgFormat.sniff(jpeg, "photo.png"))
    }

    @Test fun pngWebpGifBmp() {
        assertEquals(ImgFormat.PNG, ImgFormat.sniff(bytes(0x89) + ascii("PNG\r\n")))
        assertEquals(ImgFormat.WEBP, ImgFormat.sniff(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBPVP8 ")))
        assertEquals(ImgFormat.GIF, ImgFormat.sniff(ascii("GIF89a")))
        assertEquals(ImgFormat.BMP, ImgFormat.sniff(ascii("BM") + bytes(0, 0, 0, 0)))
    }

    @Test fun heicAndAvifByBrand() {
        val heic = bytes(0, 0, 0, 0x18) + ascii("ftypheic") + bytes(0, 0, 0, 0) + ascii("mif1heic")
        val avif = bytes(0, 0, 0, 0x18) + ascii("ftypavif") + bytes(0, 0, 0, 0) + ascii("mif1avif")
        val generic = bytes(0, 0, 0, 0x18) + ascii("ftypmif1") + bytes(0, 0, 0, 0) + ascii("mif1avif")
        assertEquals(ImgFormat.HEIC, ImgFormat.sniff(heic, "IMG_0001.JPG"))
        assertEquals(ImgFormat.AVIF, ImgFormat.sniff(avif))
        assertEquals(ImgFormat.AVIF, ImgFormat.sniff(generic))
    }

    @Test fun dngOnlyByNameAmongTiffs() {
        val tiff = bytes(0x49, 0x49, 0x2a, 0)
        assertEquals(ImgFormat.DNG, ImgFormat.sniff(tiff, "raw.DNG"))
        assertNull(ImgFormat.sniff(tiff, "scan.tif"))
    }
}

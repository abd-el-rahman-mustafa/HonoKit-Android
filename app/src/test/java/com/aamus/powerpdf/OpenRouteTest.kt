package com.aamus.powerpdf

import com.aamus.powerpdf.OpenRoute.Kind
import com.aamus.powerpdf.OpenRoute.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Open with" for PDFs and pictures, viewing and editing (decisions §42). */
class OpenRouteTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private val jpeg = bytes(0xff, 0xd8, 0xff, 0xe0) + ascii("JFIF")
    private val pdf = ascii("%PDF-1.7\n")
    private val heic = bytes(0, 0, 0, 0x18) + ascii("ftypheic") + bytes(0, 0, 0, 0) + ascii("mif1heic")

    @Test fun firstBytesWin() {
        // A PDF another app calls a picture, and a photo it calls a PDF.
        assertEquals(Kind.Pdf, OpenRoute.kindOf("image/jpeg", "scan.jpg", pdf))
        assertEquals(Kind.Image, OpenRoute.kindOf("application/pdf", "doc.pdf", jpeg))
        assertEquals(Kind.Image, OpenRoute.kindOf("application/octet-stream", "IMG_0001.JPG", heic))
    }

    @Test fun declaredTypeWhenBytesSayNothing() {
        assertEquals(Kind.Pdf, OpenRoute.kindOf("application/pdf", null, null))
        assertEquals(Kind.Pdf, OpenRoute.kindOf("Application/PDF; charset=binary", null, ByteArray(0)))
        assertEquals(Kind.Image, OpenRoute.kindOf("image/webp", null, null))
        assertEquals(Kind.Image, OpenRoute.kindOf("image/heic", "x", bytes(1, 2, 3)))
    }

    @Test fun nameWhenTheTypeIsVague() {
        assertEquals(Kind.Pdf, OpenRoute.kindOf("application/octet-stream", "Report.PDF", null))
        assertEquals(Kind.Image, OpenRoute.kindOf(null, "photo.heif", null))
        assertEquals(Kind.Image, OpenRoute.kindOf("*/*", "pic.jfif", null))
        assertEquals(Kind.Image, OpenRoute.kindOf("image/*", "noext", null))
        assertEquals(Kind.Unknown, OpenRoute.kindOf(null, "notes.txt", null))
        assertEquals(Kind.Unknown, OpenRoute.kindOf(null, null, null))
    }

    @Test fun routes() {
        assertEquals(Route.ViewPdf, OpenRoute.of(edit = false, Kind.Pdf))
        assertEquals(Route.EditPdf, OpenRoute.of(edit = true, Kind.Pdf))
        assertEquals(Route.ViewImage, OpenRoute.of(edit = false, Kind.Image))
        assertEquals(Route.EditImage, OpenRoute.of(edit = true, Kind.Image))
        // Unknown files are tried as PDFs, as before; the engine says when they are not.
        assertEquals(Route.ViewPdf, OpenRoute.of(edit = false, Kind.Unknown))
        assertEquals(Route.EditPdf, OpenRoute.of(edit = true, Kind.Unknown))
    }
}

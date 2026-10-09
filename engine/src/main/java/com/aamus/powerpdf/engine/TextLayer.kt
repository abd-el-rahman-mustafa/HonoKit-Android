package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.text.Bidi

/** A line read from a picture of the page: its text and where it sits, in displayed points (y down). */
data class RecognisedLine(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * The invisible text layer of "Recognize text" (docs/research-ocr-android.md §3.4, route B; the
 * way Tesseract writes its PDFs). Each line is written over the picture with text render mode 3
 * (not drawn) in a font without glyphs: a Type0 font whose CIDs are the text's own UTF-16 code
 * units, a ToUnicode map that says so, and every CID mapped to glyph 0 of a bundled font. Readers
 * get the exact Unicode back, nothing is shaped, and no subset of a real font is carried. Each
 * character is one em wide, so a line is squeezed to its box with Tz.
 *
 * Right-to-left lines are written in visual order (java.text.Bidi), as every PDF holds its glyphs;
 * readers turn that back into reading order themselves.
 */
object TextLayer {

    /** The resource name of the layer's font on each page. */
    const val FONT = "HonoPDFOcr"

    /** Tesseract's identity map: every CID is the Unicode code unit it stands for. */
    fun toUnicodeText(): String = TO_UNICODE

    private const val TO_UNICODE = """/CIDInit /ProcSet findresource begin
12 dict begin
begincmap
/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
/CMapName /Adobe-Identity-UCS def
/CMapType 2 def
1 begincodespacerange
<0000> <FFFF>
endcodespacerange
1 beginbfrange
<0000> <FFFF> <0000>
endbfrange
endcmap
CMapName currentdict /CMap defineresource pop
end
end
"""

    /** The text as it is laid out on the page: right-to-left runs reversed (visual order). */
    fun visualOrder(text: String): String {
        if (!StampText.isRtl(text) && !text.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC || Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT }) return text
        val bidi = Bidi(text, if (StampText.isRtl(text)) Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT else Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        val n = bidi.runCount
        val runs = Array(n) { i -> text.substring(bidi.getRunStart(i), bidi.getRunLimit(i)).let { if (bidi.getRunLevel(i) % 2 == 1) it.reversed() else it } }
        val levels = ByteArray(n) { bidi.getRunLevel(it).toByte() }
        val order = Array<Any>(n) { it }
        Bidi.reorderVisually(levels, 0, order, 0, n)
        return order.joinToString("") { runs[it as Int] }
    }

    /**
     * A combining mark (shadda, tanween, an accent): written with no width, so it sits on its
     * letter and readers keep it there instead of taking the gap for a space.
     */
    fun isMark(c: Char): Boolean = Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    /** The CID ranges (first, last) that have no width: the combining marks of Latin and Arabic. */
    val ZERO_WIDTH = listOf(0x0300 to 0x036F, 0x0610 to 0x061A, 0x064B to 0x065F, 0x0670 to 0x0670, 0x06D6 to 0x06DC, 0x06DF to 0x06E4, 0x06E7 to 0x06E8, 0x06EA to 0x06ED)

    /** The content of one page's layer, in overlay space (points, y up, the displayed frame). */
    fun content(lines: List<RecognisedLine>, frameHeight: Float): String {
        val sb = StringBuilder("BT 3 Tr\n")
        for (line in lines) {
            val text = line.text.trim()
            if (text.isEmpty() || line.w <= 0f || line.h <= 0f) continue
            val size = line.h * 0.72f
            val units = text.count { !isMark(it) }.coerceAtLeast(1)
            // A space before and after the line, outside its box: readers that join boxes on one
            // baseline into one line keep a word break between them ("تغيثالكونغو" on the phone).
            // Each CID advances one em: squeezed (or stretched) so the line's own letters fill its width.
            val k = line.w / (units * size)
            val tz = 100f * k
            val pad = size * k
            val baseline = frameHeight - (line.y + line.h * 0.78f)
            val hex = "0020" + visualOrder(text).toCharArray().joinToString("") { "%04X".format(it.code) } + "0020"
            sb.append("/%s %.3f Tf %.2f Tz 1 0 0 1 %.3f %.3f Tm <%s> Tj\n".format(java.util.Locale.US, FONT, size, tz, line.x - pad, baseline, hex))
        }
        sb.append("ET\n")
        return sb.toString()
    }
}

/**
 * Adds the invisible text layer of [pages] (page index → its lines) to this document. The layer
 * is built as an overlay document, one page per target page, and placed like a stamp (Stamp.kt):
 * the graft map carries the font's objects across. Written straight into the document, the font's
 * references came out wrong after the final save's compaction (seen on the emulator, 2026-10-01).
 */
fun PdfDoc.addTextLayer(pages: Map<Int, List<RecognisedLine>>) {
    requireEdit()
    // Recognising a page again replaces the layer written last time (desktop ocr/index.ts): Find and
    // copy would otherwise see every word twice. The page's own text is never touched.
    for (index in pages.keys) removeOcrLayers(index)
    val targets = pages.filterValues { it.isNotEmpty() }.toSortedMap()
    if (targets.isEmpty()) return
    val overlay = PDFDocument()
    try {
        val font = overlay.glyphlessFont()
        val frames = ArrayList<Frame>()
        for ((index, lines) in targets) {
            val size = pageSize(index)
            val resources = overlay.newDictionary()
            val fonts = overlay.newDictionary()
            fonts.put(TextLayer.FONT, font)
            resources.put("Font", fonts)
            val page = overlay.addPage(com.artifex.mupdf.fitz.Rect(0f, 0f, size.width, size.height), 0, resources, TextLayer.content(lines, size.height))
            overlay.insertPage(-1, page)
            frames.add(Frame(index, size.width, size.height))
        }
        placeOverlay(overlay, frames, 1f, tag = OCR_TAG)
    } finally {
        PdfEngine.dispose(overlay)
    }
}

/** On our text layer's form and on the stream that draws it. */
internal const val OCR_TAG = "HonoOcr"

/** A text layer written before the tag existed: a form of ours whose content begins with invisible text. */
private fun isLegacyOcrForm(form: PDFObject): Boolean = runCatching {
    form.isStream && String(form.readStream(), Charsets.ISO_8859_1).trimStart().startsWith("BT 3 Tr")
}.getOrDefault(false)

/**
 * Takes the text layers this app wrote off page [index]: a tagged stream goes from the page's
 * content with its form; a form from before the tag is swapped for an empty one in page-local
 * resources (its stream also held the page's closing Q). True when one was found.
 */
internal fun PdfDoc.removeOcrLayers(index: Int): Boolean {
    val page = pdf.findPage(index)
    val resources = page.getInheritable("Resources")
    val xobjects = resources.get("XObject")
    if (!xobjects.isDictionary) return false
    val ours = HashSet<String>()
    for (key in xobjects) {
        val name = key.asName()
        val form = xobjects.get(key)
        if (name.startsWith("HonoPDFStamp") && form.isStream && (form.get(OCR_TAG).isBoolean || isLegacyOcrForm(form))) ours.add(name)
    }
    if (ours.isEmpty()) return false
    val contents = page.get("Contents")
    val tagged = HashSet<String>()
    if (contents.isArray) {
        val kept = pdf.newArray()
        for (i in 0 until contents.size()) {
            val s = contents.get(i)
            val drawn = if (s.get(OCR_TAG).isBoolean) Regex("/(\\S+)\\s+Do\\b").find(String(s.readStream(), Charsets.ISO_8859_1))?.groupValues?.get(1) else null
            if (drawn != null && drawn in ours) tagged.add(drawn) else kept.append(s)
        }
        if (tagged.isNotEmpty()) page.put("Contents", kept)
    }
    // Page-local copies: resources are often shared by every page.
    val ownXObjects = pdf.newDictionary()
    for (key in xobjects) {
        val name = key.asName()
        when {
            name in tagged -> {}
            name in ours -> ownXObjects.put(name, pdf.addStream("", pdf.newDictionary().also { d ->
                d.put("Type", pdf.newName("XObject"))
                d.put("Subtype", pdf.newName("Form"))
                d.put("BBox", com.artifex.mupdf.fitz.Rect(0f, 0f, 0f, 0f))
            }))
            else -> ownXObjects.put(key, xobjects.get(key))
        }
    }
    val ownResources = pdf.newDictionary()
    for (key in resources) ownResources.put(key, resources.get(key))
    ownResources.put("XObject", ownXObjects)
    page.put("Resources", ownResources)
    invalidate(listOf(index))
    return true
}

/** The glyphless Type0 font, in this (overlay) document. */
private fun PDFDocument.glyphlessFont(): PDFObject {
    val pdf = this
    fun stream(bytes: ByteArray, dict: PDFObject): PDFObject {
        dict.put("Filter", pdf.newName("FlateDecode"))
        val b = Buffer()
        try {
            b.writeBytes(Bilevel.deflate(bytes))
            return pdf.addRawStream(b, dict)
        } finally {
            b.destroy()
        }
    }
    // A real font file keeps strict readers happy; its glyphs are never used (every CID → glyph 0).
    val file = pdf.newDictionary()
    val fontBytes = StampFonts.bytesOf("noto-r.ttf")
    file.put("Length1", fontBytes.size)
    val fontFile = stream(fontBytes, file)

    val descriptor = pdf.newDictionary()
    descriptor.put("Type", pdf.newName("FontDescriptor"))
    descriptor.put("FontName", pdf.newName("GlyphLessFont"))
    descriptor.put("Flags", 4)
    descriptor.put("FontBBox", com.artifex.mupdf.fitz.Rect(0f, -200f, 1000f, 800f))
    descriptor.put("ItalicAngle", 0)
    descriptor.put("Ascent", 800)
    descriptor.put("Descent", -200)
    descriptor.put("CapHeight", 700)
    descriptor.put("StemV", 80)
    descriptor.put("FontFile2", fontFile)

    val system = pdf.newDictionary()
    system.put("Registry", pdf.newString("Adobe"))
    system.put("Ordering", pdf.newString("Identity"))
    system.put("Supplement", 0)

    val cid = pdf.newDictionary()
    cid.put("Type", pdf.newName("Font"))
    cid.put("Subtype", pdf.newName("CIDFontType2"))
    cid.put("BaseFont", pdf.newName("GlyphLessFont"))
    cid.put("CIDSystemInfo", system)
    cid.put("FontDescriptor", pdf.addObject(descriptor))
    cid.put("DW", 1000)
    // Combining marks have no width (TextLayer.isMark): "c_first c_last 0" entries.
    val widths = pdf.newArray()
    for ((first, last) in TextLayer.ZERO_WIDTH) {
        widths.push(first)
        widths.push(last)
        widths.push(0)
    }
    cid.put("W", widths)
    cid.put("CIDToGIDMap", stream(ByteArray(65536 * 2), pdf.newDictionary()))

    val descendants = pdf.newArray()
    descendants.append(pdf.addObject(cid))

    val type0 = pdf.newDictionary()
    type0.put("Type", pdf.newName("Font"))
    type0.put("Subtype", pdf.newName("Type0"))
    type0.put("BaseFont", pdf.newName("GlyphLessFont"))
    type0.put("Encoding", pdf.newName("Identity-H"))
    type0.put("DescendantFonts", descendants)
    type0.put("ToUnicode", stream(TextLayer.TO_UNICODE_BYTES, pdf.newDictionary()))
    return pdf.addObject(type0)
}

private val TextLayer.TO_UNICODE_BYTES: ByteArray
    get() = toUnicodeText().toByteArray(Charsets.US_ASCII)

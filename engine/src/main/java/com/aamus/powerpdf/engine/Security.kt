package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Pixmap
import java.io.File
import java.security.SecureRandom

/** Protect, unlock and compress — ported from the desktop's security.ts and compress.ts. */

data class ProtectOptions(
    /** Needed to open the file; empty = the file opens for anyone (then [ownerPassword] is required). */
    val userPassword: String,
    val ownerPassword: String? = null,
    val allowPrint: Boolean = true,
    val allowCopy: Boolean = true,
    val allowModify: Boolean = true,
    val allowAnnotate: Boolean = true,
)

object PdfSecurity {
    // ISO 32000-1 table 22. Bits 7-8 and 13-32 are reserved and must be set.
    private const val PRINT = 1 shl 2
    private const val MODIFY = 1 shl 3
    private const val COPY = 1 shl 4
    private const val ANNOTATE = 1 shl 5
    private const val FILL = 1 shl 8
    private const val ACCESSIBILITY = 1 shl 9
    private const val ASSEMBLE = 1 shl 10
    private const val PRINT_HQ = 1 shl 11

    fun permissionsValue(o: ProtectOptions): Int {
        var v = 0xfffff0c0.toInt() or ACCESSIBILITY
        if (o.allowPrint) v = v or PRINT or PRINT_HQ
        if (o.allowCopy) v = v or COPY
        if (o.allowModify) v = v or MODIFY or ASSEMBLE
        if (o.allowAnnotate) v = v or ANNOTATE or FILL
        return v
    }

    /**
     * MuPDF takes write options as one comma-separated string with no escaping, so a comma in a
     * password would split it (desktop §21). Returns a reason, or null if usable.
     */
    fun passwordProblem(password: String): String? = when {
        Passwords.prepared(password).contains(',') -> "comma"
        // Readers key the file on the first 127 bytes (UTF-8) only: a longer open password could
        // equal the permissions password where it counts, and opening with it would lift every
        // restriction.
        Passwords.byteLength(password) > 127 -> "long"
        Passwords.prepared(password).isEmpty() -> "empty"
        else -> null
    }

    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..32).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }

    /**
     * The MuPDF write options that encrypt with AES-256. Without a permissions password a random
     * one is made: using the open password for both would make the switches meaningless (desktop §21).
     */
    fun encryptOptions(o: ProtectOptions): String {
        if (o.userPassword.isEmpty() && o.ownerPassword.isNullOrEmpty()) throw PdfException(PdfException.Code.BadArguments, "A password is required.")
        listOfNotNull(o.userPassword.takeIf { it.isNotEmpty() }, o.ownerPassword?.takeIf { it.isNotEmpty() }).forEach {
            when (passwordProblem(it)) {
                "comma" -> throw PdfException(PdfException.Code.BadArguments, "A password cannot contain a comma.")
                "long" -> throw PdfException(PdfException.Code.BadArguments, "The password is too long.")
                "empty" -> throw PdfException(PdfException.Code.BadArguments, "The password has no characters a reader would use.")
            }
        }
        // The same password for both would make whoever opens the file its owner (with every right).
        if (o.userPassword.isNotEmpty() && !o.ownerPassword.isNullOrEmpty() && Passwords.prepared(o.userPassword) == Passwords.prepared(o.ownerPassword)) {
            throw PdfException(PdfException.Code.BadArguments, "The permissions password must be different from the open password.")
        }
        // Written as Acrobat keys it (SASLprep), so Acrobat opens it with the same text; we try both.
        val user = Passwords.prepared(o.userPassword)
        val owner = o.ownerPassword?.takeIf { it.isNotEmpty() }?.let { Passwords.prepared(it) } ?: randomPassword()
        return "encrypt=aes-256,user-password=$user,owner-password=$owner,permissions=${permissionsValue(o)}"
    }

    /**
     * A signed file is not given a new password or freed of its own (as Acrobat): the whole file is
     * written again, and every signature in it would no longer hold.
     */
    private fun refuseSigned(doc: PdfDoc) {
        if (doc.isSigned()) throw PdfException(PdfException.Code.Signed, "This file is digitally signed; changing its password would break the signatures.")
    }

    /** Writes an encrypted copy. */
    fun protect(doc: PdfDoc, target: File, o: ProtectOptions) {
        refuseSigned(doc)
        val r = doc.rights()
        if (r.encrypted && !r.full) throw PdfException(PdfException.Code.Restricted, "The author's permissions password is needed to change the protection.")
        doc.save(target, final = true, extra = encryptOptions(o))
    }

    /** Writes a copy without encryption. Needs full rights (the permissions password, or none set). */
    fun unlock(doc: PdfDoc, target: File) {
        refuseSigned(doc)
        val r = doc.rights()
        if (r.encrypted && !r.full) throw PdfException(PdfException.Code.Restricted, "The author's permissions password is needed to remove the restrictions.")
        doc.save(target, final = true, extra = "encrypt=none")
    }
}

/**
 * Whether the file carries a digital signature: /AcroForm /SigFlags bit 1 (SignaturesExist), or a
 * signature field with a value, for files that forget the flag.
 */
fun PdfDoc.isSigned(): Boolean = runCatching {
    val acro = pdf.trailer.get("Root").get("AcroForm")
    if (!acro.isDictionary) return@runCatching false
    val flags = acro.get("SigFlags")
    if (flags.isNumber && (flags.asInteger() and 1) == 1) return@runCatching true
    val fields = acro.get("Fields")
    fun signed(f: PDFObject, depth: Int): Boolean {
        if (!f.isDictionary || depth > 32) return false
        val ft = f.getInheritable("FT")
        if (ft.isName && ft.asName() == "Sig" && f.get("V").isDictionary) return true
        val kids = f.get("Kids")
        return kids.isArray && (0 until kids.size()).any { signed(kids.get(it), depth + 1) }
    }
    fields.isArray && (0 until fields.size()).any { signed(fields.get(it), 0) }
}.getOrDefault(false)

enum class CompressLevel(val maxSide: Int, val quality: Int) {
    Low(3200, 85),
    Medium(1800, 72),
    High(1200, 55),
}

data class CompressResult(val before: Long, val after: Long, val imagesRecompressed: Int, val changed: Boolean)

object PdfCompress {
    /** Images with fewer pixels than this are left alone. */
    private const val MIN_PIXELS = 40_000

    /**
     * Recompresses large images (longest side capped, JPEG quality by level), then lets MuPDF drop
     * unused objects and compress every stream. Never returns something bigger than the input:
     * then [CompressResult.changed] is false and [target] is a plain copy of [source].
     */
    fun compress(doc: PdfDoc, source: File, target: File, level: CompressLevel, progress: (Float) -> Unit = {}): CompressResult {
        val before = source.length()
        discardUnused(doc)
        val replaced = recompressImages(doc, level, progress)
        // No garbage collection here: after loadImage() on object references, MuPDF 1.28's
        // "garbage" save breaks some images ("object is not a stream"; JPX and soft-masked ones,
        // found on a phone, reproduced with mupdf.js). [source] is already a cleaned snapshot.
        doc.save(target, final = false, extra = "objstms,compress-fonts,compress-images")
        val after = target.length()
        if (after >= before) {
            source.copyTo(target, overwrite = true)
            return CompressResult(before, before, 0, false)
        }
        return CompressResult(before, after, replaced, true)
    }

    private fun isImage(o: PDFObject) = o.isDictionary && o.get("Subtype").let { it.isName && it.asName() == "Image" }

    /** The filter names of a stream: /Filter may be one name or an array of them. */
    private fun filterNames(o: PDFObject): List<String> {
        val f = o.get("Filter")
        if (f.isName) return listOf(f.asName())
        return if (f.isArray) (0 until f.size()).map { f.get(it) }.filter { it.isName }.map { it.asName() } else emptyList()
    }

    /** The family of an image's /ColorSpace: "DeviceRGB", "ICCBased", "Indexed", … ("" when absent). */
    private fun colourFamily(o: PDFObject): String {
        val cs = o.get("ColorSpace")
        if (cs.isName) return cs.asName()
        if (cs.isArray && cs.size() > 0 && cs.get(0).isName) return cs.get(0).asName()
        return ""
    }

    /**
     * Objects never re-encoded on their own (desktop compress.ts): every soft mask (a JPEG'd alpha
     * channel blurs and rings at each edge), and both halves of a pair whose soft mask has /Matte
     * (the colours are pre-blended with the mask; resampling either one gives fringes).
     */
    private fun protectedObjects(doc: PdfDoc): Set<Int> {
        val pdf = doc.pdf
        val keep = HashSet<Int>()
        for (n in 1 until pdf.countObjects()) {
            runCatching {
                val obj = pdf.newIndirect(n, 0).resolve()
                if (!isImage(obj)) return@runCatching
                val smask = obj.get("SMask")
                if (!smask.isIndirect) return@runCatching
                keep.add(smask.asIndirect())
                if (!smask.resolve().get("Matte").isNull) keep.add(n)
            }
        }
        return keep
    }

    /**
     * Drops what only the authoring program or an old viewer uses (Acrobat's "Discard objects"
     * defaults): embedded page thumbnails (every viewer draws its own) and /PieceInfo, the private
     * data Illustrator, Photoshop and others keep for re-editing — sometimes a full copy of the artwork.
     */
    private fun discardUnused(doc: PdfDoc) {
        val pdf = doc.pdf
        runCatching { pdf.trailer.get("Root").delete("PieceInfo") }
        for (i in 0 until pdf.countPages()) runCatching {
            val page = pdf.findPage(i)
            page.delete("Thumb")
            page.delete("PieceInfo")
        }
    }

    private fun recompressImages(doc: PdfDoc, level: CompressLevel, progress: (Float) -> Unit): Int {
        val pdf = doc.pdf
        val total = pdf.countObjects()
        val keep = protectedObjects(doc)
        var replaced = 0
        for (n in 1 until total) {
            if (n % 50 == 0) progress(n.toFloat() / total)
            if (n in keep) continue
            val ref: PDFObject
            val obj: PDFObject
            try {
                ref = pdf.newIndirect(n, 0)
                obj = ref.resolve()
                if (!isImage(obj)) continue
            } catch (_: Throwable) {
                continue
            }
            try {
                if (obj.get("ImageMask").let { it.isBoolean && it.asBoolean() }) continue
                val mask = obj.get("Mask")
                if (mask.isArray || mask.isIndirect) continue
                // JPEG 2000 is compact already; JBIG2 and CCITT are 1-bit scans JPEG would enlarge.
                // (A filter array, [/FlateDecode /JBIG2Decode] say, is checked name by name.)
                if (filterNames(obj).any { it in setOf("JPXDecode", "JBIG2Decode", "CCITTFaxDecode") }) continue
                // A palette image is flat colour (charts, logos, screenshots): JPEG would smear every
                // edge and usually come out bigger than the palette data anyway.
                val family = colourFamily(obj)
                if (family == "Indexed") continue
                // An ICC-based image keeps its profile: the decoded samples are still in that space.
                val keepColourSpace = family == "ICCBased"
                val w0 = obj.get("Width").asInteger()
                val h0 = obj.get("Height").asInteger()
                if (w0.toLong() * h0 < MIN_PIXELS) continue

                val image = pdf.loadImage(ref)
                var pix: Pixmap = image.toPixmap()
                val cs = pix.colorSpace
                // Only plain grey and RGB are re-encoded; CMYK/indexed/separations keep their fidelity.
                if (cs == null || (!cs.isGray && !cs.isRGB) || pix.alpha) {
                    pix.destroy()
                    image.destroy()
                    continue
                }
                val scale = minOf(1f, level.maxSide.toFloat() / maxOf(w0, h0))
                if (scale < 1f) {
                    val w = maxOf(1, Math.round(w0 * scale))
                    val h = maxOf(1, Math.round(h0 * scale))
                    val target = Pixmap(if (keepColourSpace) cs else if (cs.isGray) ColorSpace.DeviceGray else ColorSpace.DeviceRGB, 0, 0, w, h, false)
                    target.clear(255)
                    val dev = DrawDevice(target)
                    dev.fillImage(image, Matrix(w.toFloat(), 0f, 0f, h.toFloat(), 0f, 0f), 1f, 0)
                    dev.close()
                    dev.destroy()
                    pix.destroy()
                    pix = target
                }
                val jpeg = pix.asJPEG(level.quality, false)
                val w = pix.width
                val h = pix.height
                val gray = pix.colorSpace.isGray
                val components = pix.numberOfComponents
                pix.destroy()
                image.destroy()
                val rawLength = obj.get("Length").let { if (it.isNumber) it.asInteger() else Int.MAX_VALUE }
                // A profile whose number of channels is not the picture's (a mismatched ICC) is left alone.
                val iccMatches = !keepColourSpace || obj.get("ColorSpace").get(1).resolve().get("N").let { it.isNumber && it.asInteger() == components }
                if (jpeg.length >= rawLength * 0.95 || !iccMatches) {
                    jpeg.destroy()
                    continue
                }
                obj.put("Width", w)
                obj.put("Height", h)
                if (!keepColourSpace) obj.put("ColorSpace", pdf.newName(if (gray) "DeviceGray" else "DeviceRGB"))
                obj.put("BitsPerComponent", 8)
                obj.put("Filter", pdf.newName("DCTDecode"))
                obj.delete("DecodeParms")
                obj.delete("Decode")
                ref.writeRawStream(jpeg)
                jpeg.destroy()
                replaced++
            } catch (_: Throwable) {
                // An image that cannot be decoded is left exactly as it was.
            }
        }
        doc.invalidate()
        return replaced
    }
}

package com.aamus.powerpdf.engine

import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject

/**
 * What a document declares about its print standard, carried into documents made from its pages
 * (desktop src/engine/pdfx.ts, decisions §82 there, §52 here): the catalogue's
 * /OutputIntents (with their ICC profiles), the document information's GTS_PDFXVersion,
 * GTS_PDFXConformance and Trapped, and the XMP identification (pdfxid). A RIP or a preflight reads
 * these; without them a PDF/X file stops being one and the intended printing condition is lost.
 *
 * Extract, split and delete keep the whole catalogue already (PageCopy.kt `restoreCatalog`): the
 * XMP there is kept too, except a claim the result can no longer honestly make ([withdrawClaims]).
 * Combine starts a new document: it gets the first file's print standard only when every file
 * declares the same one, and a fresh XMP packet that claims PDF/X only, never PDF/A or PDF/UA,
 * whose structure and other conditions a combination of other files' pages does not keep.
 */
internal class PrintStandard(
    /** The catalogue's /OutputIntents, or null (an object of the source document, which stays open). */
    val intents: PDFObject?,
    /** Compared across files: each intent's /S, condition, registry and decoded profile. */
    val signature: String,
    val version: String,
    val conformance: String,
    val trapped: String,
    /** The source had an XMP packet (a PDF/X-1a:2001 file has none; it does not gain one). */
    val xmp: Boolean,
    val title: String,
) {
    fun sameAs(o: PrintStandard?): Boolean =
        o != null && o.signature == signature && o.version == version && o.conformance == conformance && o.trapped == trapped
}

/** XMP packets read and changed as text (no MuPDF; JVM-tested). */
object Xmp {
    const val PDFX_NS = "http://www.npes.org/pdfx/ns/id/"

    /** A property of the packet, written as an attribute or as an element; "" when absent. */
    fun value(xmp: String, name: String): String {
        val n = Regex.escape(name)
        Regex("$n\\s*=\\s*\"([^\"]{0,200})\"").find(xmp)?.let { return it.groupValues[1].trim() }
        Regex("$n\\s*=\\s*'([^']{0,200})'").find(xmp)?.let { return it.groupValues[1].trim() }
        return Regex("<$n>([^<]{0,200})</$n>").find(xmp)?.groupValues?.get(1)?.trim() ?: ""
    }

    /** The packet without one property, in attribute or element form. */
    fun drop(xmp: String, name: String): String {
        val n = Regex.escape(name)
        return xmp
            .replace(Regex("\\s$n\\s*=\\s*(\"[^\"]*\"|'[^']*')"), "")
            .replace(Regex("\\s*<$n>[^<]*</$n>"), "")
            .replace(Regex("\\s*<$n\\s*/>"), "")
    }

    /**
     * The claims a document without tagged content can no longer make: PDF/UA, and PDF/A level A
     * ("accessible"), need the structure tree. PDF/X, PDF/A-b/u and the rest stay.
     */
    fun withdrawn(xmp: String): String {
        if (!xmp.contains("pdfuaid") && !xmp.contains("pdfaid")) return xmp
        var next = drop(drop(xmp, "pdfuaid:part"), "pdfuaid:rev")
        if (value(next, "pdfaid:conformance").equals("a", ignoreCase = true)) {
            for (p in listOf("pdfaid:part", "pdfaid:conformance", "pdfaid:amd", "pdfaid:rev")) next = drop(next, p)
        }
        return next
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A new XMP packet that claims PDF/X (and nothing else) for a combined document. */
    fun pdfxPacket(version: String, conformance: String, trapped: String, title: String): String {
        val props = ArrayList<String>()
        props.add("<pdfxid:GTS_PDFXVersion>${escape(version)}</pdfxid:GTS_PDFXVersion>")
        if (conformance.isNotEmpty()) props.add("<pdfxid:GTS_PDFXConformance>${escape(conformance)}</pdfxid:GTS_PDFXConformance>")
        if (trapped.isNotEmpty()) props.add("<pdf:Trapped>${escape(trapped)}</pdf:Trapped>")
        if (title.isNotEmpty()) props.add("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${escape(title)}</rdf:li></rdf:Alt></dc:title>")
        return listOf(
            "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>",
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">",
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">",
            "<rdf:Description rdf:about=\"\" xmlns:pdfxid=\"$PDFX_NS\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">",
        ).plus(props).plus(listOf("</rdf:Description>", "</rdf:RDF>", "</x:xmpmeta>", "<?xpacket end=\"w\"?>")).joinToString("\n")
    }
}

private fun textOf(v: PDFObject): String = if (v.isString) v.asString() else if (v.isName) v.asName() else ""

private fun clean(s: String) = s.replace(Regex("[\\u0000<>&]"), "").trim()

/** The catalogue's XMP packet as text, "" when there is none. */
internal fun PDFDocument.metadataXml(): String = runCatching {
    val meta = trailer.get("Root").get("Metadata")
    if (!meta.isStream) "" else String(meta.readStream(), Charsets.UTF_8)
}.getOrDefault("")

/** FNV-1a over the decoded profile, with its length: equal profiles, equal signatures. */
private fun profileHash(profile: PDFObject): String {
    if (!profile.isStream) return ""
    return runCatching {
        val bytes = profile.readStream()
        var h = 0x811c9dc5.toInt()
        for (b in bytes) h = (h xor (b.toInt() and 0xFF)) * 0x01000193
        "${bytes.size}:${Integer.toHexString(h)}"
    }.getOrDefault("?")
}

/** What a document declares; null when it declares no print standard at all. */
internal fun PDFDocument.printStandard(): PrintStandard? = runCatching {
    val root = trailer.get("Root")
    val info = trailer.get("Info")
    val xmp = metadataXml()
    fun fromInfo(key: String) = if (info.isDictionary) clean(textOf(info.get(key))) else ""
    val pdfx = xmp.contains(Xmp.PDFX_NS)
    val version = fromInfo("GTS_PDFXVersion").ifEmpty { if (pdfx) clean(Xmp.value(xmp, "pdfxid:GTS_PDFXVersion")) else "" }
    val conformance = fromInfo("GTS_PDFXConformance").ifEmpty { if (pdfx) clean(Xmp.value(xmp, "pdfxid:GTS_PDFXConformance")) else "" }
    val trapped = if (info.isDictionary && info.get("Trapped").isName) info.get("Trapped").asName() else ""
    val list = root.get("OutputIntents")
    val intents = if (list.isArray && list.size() > 0) list else null
    val parts = ArrayList<String>()
    if (intents != null) for (i in 0 until intents.size()) {
        val it = intents.get(i)
        if (!it.isDictionary) {
            parts.add("?")
            continue
        }
        parts.add(listOf(textOf(it.get("S")), textOf(it.get("OutputConditionIdentifier")), textOf(it.get("RegistryName")), profileHash(it.get("DestOutputProfile"))).joinToString("|"))
    }
    if (intents == null && version.isEmpty()) null
    else PrintStandard(intents, parts.joinToString("\n"), version, conformance, trapped, xmp.isNotEmpty(), fromInfo("Title"))
}.getOrNull()

/**
 * Combine: the print standard of the files, when they all declare the same one (version, trapping
 * and output intents). When they differ, or one declares none, the result claims nothing (as
 * before): one press condition must not silently apply to another file's pages.
 */
internal fun PDFDocument.carryPrintStandard(sources: List<PrintStandard?>) {
    val first = sources.firstOrNull() ?: return
    if (!sources.all { first.sameAs(it) }) return
    val root = trailer.get("Root")
    first.intents?.let { root.put("OutputIntents", graftObject(it)) }
    if (first.version.isEmpty()) return
    var info = trailer.get("Info")
    if (!info.isDictionary) {
        info = createObject().also { it.writeObject(newDictionary()) }
        trailer.put("Info", info)
    }
    info.put("GTS_PDFXVersion", newString(first.version))
    if (first.conformance.isNotEmpty()) info.put("GTS_PDFXConformance", newString(first.conformance))
    if (first.trapped.isNotEmpty()) info.put("Trapped", newName(first.trapped))
    if (first.xmp) {
        val dict = newDictionary()
        dict.put("Type", newName("Metadata"))
        dict.put("Subtype", newName("XML"))
        root.put("Metadata", addStream(Xmp.pdfxPacket(first.version, first.conformance, first.trapped, first.title), dict))
    }
}

/**
 * Extract, split, delete: when no tagged content is left, a PDF/UA or PDF/A level A claim in the
 * kept XMP is taken out (PDF/X and the rest stay). [wasTagged]: the document had a structure tree
 * before the pages went.
 */
internal fun PDFDocument.withdrawClaims(wasTagged: Boolean) {
    if (!wasTagged) return
    val root = trailer.get("Root")
    if (!root.get("StructTreeRoot").isNull) return
    val meta = root.get("Metadata")
    if (!meta.isStream) return
    val xmp = metadataXml()
    val next = Xmp.withdrawn(xmp)
    if (next == xmp) return
    val dict = newDictionary()
    for (key in meta.iterator().asSequence().toList()) {
        val k = key.asName()
        if (k != "Length" && k != "Filter" && k != "DecodeParms") dict.put(k, meta.get(key))
    }
    root.put("Metadata", addStream(next, dict))
}

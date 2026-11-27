package com.aamus.powerpdf.engine

/** Largest XFA packet read when looking for the configuration (a form's config is a few KB). */
internal const val MAX_XFA_CONFIG = 4 shl 20

/**
 * True when an XFA configuration (the `config` packet, or a whole XDP holding one) says
 * `<dynamicRender>required</dynamicRender>` under config/acrobat/acrobat7: what Designer writes for
 * a dynamic form (`forbidden` for a static one). Acrobat lays such a form out from its XFA even
 * when it was saved with its pages drawn and only its signature boxes as fields. The same element
 * in the template or data, or elsewhere in the configuration, is not the setting; a malformed
 * configuration counts as not required (desktop forms.ts requiresDynamicRender).
 */
internal fun requiresDynamicRender(xml: String): Boolean {
    val config = xfaElement(xml, "config") ?: return false
    val acrobat = xfaElement(config, "acrobat") ?: return false
    val acrobat7 = xfaElement(acrobat, "acrobat7") ?: return false
    return xfaElement(acrobat7, "dynamicRender")?.trim() == "required"
}

/** The inner text of the first `<name …>…</name>` element (any namespace prefix), or null. */
internal fun xfaElement(xml: String, name: String): String? {
    val open = Regex("<(?:[A-Za-z_][\\w.-]*:)?$name(?=[\\s/>])[^>]*>").find(xml) ?: return null
    if (open.value.endsWith("/>")) return ""
    val start = open.range.last + 1
    val close = Regex("</(?:[A-Za-z_][\\w.-]*:)?$name\\s*>").find(xml, start) ?: return null
    return xml.substring(start, close.range.first)
}

package com.aamus.powerpdf

import android.content.Context
import android.util.Log
import com.aamus.powerpdf.engine.PdfEngine
import com.aamus.powerpdf.engine.TextRepair
import com.aamus.powerpdf.engine.paragraphs
import com.aamus.powerpdf.engine.replaceParagraph
import com.aamus.powerpdf.engine.eraseArea
import com.aamus.powerpdf.engine.addText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only (docs/testing.md, "roundtrip"): proves that editing one word of a paragraph
 * leaves every other word as it was. For each paragraph of the chosen pages, on a fresh copy of
 * the document: the edit box's text with one word replaced is retyped exactly as Done does, the
 * page is read back, and the paragraph must hold the expected text word for word; every other
 * paragraph of the page must still be there unchanged. Nothing is saved.
 *
 * value: "<pdf path>|<pages: all, 3, 1-5, 2,7>|<fix or nofix>|<most paragraphs per page>|<step>|<gc>"
 * step (to find what breaks): edit (default: retype as Done does), read (open and read only),
 * erase (erase the first line only), add (add a text overlay only). gc: collect garbage and run
 * finalizers after every paragraph, so a fault shows right after the step that caused it; raw:
 * skip the settle() the app does after each change (to reproduce the crash of decisions §23).
 * The report goes to files/roundtrip/<name>-<fix|nofix>.txt; the summary to the log.
 */
object RoundTrip {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context, value: String) {
        val parts = value.split('|')
        val file = File(parts[0])
        val fix = parts.getOrNull(2) != "nofix"
        val most = parts.getOrNull(3)?.toIntOrNull() ?: 30
        val step = parts.getOrNull(4)?.ifBlank { null } ?: "edit"
        val gc = parts.getOrNull(5) == "gc"
        val dir = File(context.filesDir, "roundtrip").apply { mkdirs() }
        val report = File(dir, "${file.nameWithoutExtension}-${if (fix) "fix" else "nofix"}.txt")
        scope.launch {
            report.writeText("Round trip ${file.name} pages=${parts.getOrNull(1)} fix=$fix\n")
            val started = System.currentTimeMillis()
            var tested = 0
            var passed = 0
            var failed = 0
            var othersChanged = 0
            var errors = 0
            var unreadable = 0
            var grewCount = 0
            var hyphenOnly = 0
            PdfEngine.run {
                TextRepair.ligatures = fix
                // 7th field: steps of an edit to leave out (redact, layout, stamp, reopen, subset, place).
                com.aamus.powerpdf.engine.EngineDebug.skip = parts.getOrNull(6)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            }
            try {
                val count = PdfEngine.run { PdfEngine.open(file).use { it.pageCount } }
                for (p in pages(parts.getOrNull(1) ?: "all", count)) {
                    val t0 = System.currentTimeMillis()
                    val box = PdfEngine.run { PdfEngine.open(file).use { it.paragraphs(p) } }
                    report.appendText("\n=== page ${p + 1}: ${box.size} paragraphs (read in ${System.currentTimeMillis() - t0} ms)\n")
                    box.forEachIndexed { i, para -> report.appendText("BOX p${p + 1}#$i u=${para.unreadable} g=${para.guessed}: ${para.text}\n") }
                    for ((i, para) in box.withIndex()) {
                        if (i >= most) break
                        val words = para.text.split(' ').filter { it.isNotEmpty() }
                        if (words.size < 3) continue
                        if (para.unreadable > 0) {
                            unreadable++
                            continue
                        }
                        // A word near the middle made of letters only is the one changed.
                        val k = words.indices.sortedBy { kotlin.math.abs(it - words.size / 2) }
                            .firstOrNull { words[it].length >= 3 && words[it].all { c -> c.isLetter() } } ?: continue
                        val expected = words.toMutableList().also { it[k] = if (para.rtl) "زرافة" else "Zebra" }.joinToString(" ")
                        val first = para.lines.first()
                        val x = (first.x0 + first.x1) / 2
                        val y = (first.y0 + first.y1) / 2
                        tested++
                        var grew = false
                        val outcome = runCatching {
                            PdfEngine.run {
                                PdfEngine.open(file).use { doc ->
                                    val before = doc.paragraphs(p)
                                    when (step) {
                                        "read" -> {}
                                        "erase" -> doc.eraseArea(p, com.aamus.powerpdf.engine.Box(first.x0, first.y0, first.x1, first.y1))
                                        "add" -> doc.addText(p, first.x0, first.y1 + 2f, 200f, "Zebra test", com.aamus.powerpdf.engine.TextStyle(9f, com.aamus.powerpdf.engine.Rgb.of(0xFF000000.toInt())))
                                        else -> grew = doc.replaceParagraph(p, x, y, expected).grew
                                    }
                                    before to doc.paragraphs(p)
                                }.also { if (!gc && parts.getOrNull(5) != "raw") PdfEngine.settle() }
                            }
                        }
                        val pair = outcome.getOrNull()
                        if (pair == null) {
                            errors++
                            report.appendText("ERROR p${p + 1}#$i: ${outcome.exceptionOrNull()?.message}\n")
                            continue
                        }
                        val (before, after) = pair
                        if (gc) {
                            Runtime.getRuntime().gc()
                            System.runFinalization()
                            Log.i("PowerPDF", "roundtrip step $step p${p + 1}#$i survived gc")
                        }
                        if (step != "edit") {
                            passed++
                            continue
                        }
                        // The words are judged by place, not by how the page regroups into paragraphs.
                        val got = region(after, para.box, para.pitch * 4)
                        val ok = norm(got).contains(norm(expected))
                        // Every letter there, only a line-end hyphen joined differently (a word
                        // carried into the next column): counted apart, not as a failure.
                        val joinedOnly = !ok && squash(got).contains(squash(expected))
                        if (grew) grewCount++
                        if (joinedOnly) {
                            passed++
                            hyphenOnly++
                            report.appendText("HYPHEN p${p + 1}#$i\n  expected: $expected\n  got:      $got\n")
                        } else if (ok) passed++ else {
                            failed++
                            report.appendText("FAIL p${p + 1}#$i${if (grew) " (grew)" else ""}\n  expected: $expected\n  got:      $got\n  diff: ${diff(norm(expected), norm(got))}\n")
                            report.appendText("  box ${para.box}\n")
                            after.filter { a -> a.lines.any { it.x1 > para.box.x0 + 1 && it.x0 < para.box.x1 - 1 && it.y1 > para.box.y0 - 1 && it.y0 < para.box.y1 + para.pitch * 4 } }
                                .forEach { a -> report.appendText("  near ${a.box} lines=${a.lines.size}: ${a.text.take(80)}\n") }
                        }
                        // Another paragraph may regroup around the edit («uni-» / «versity» no longer joined): its
                        // letters must all be there, in order; hyphens and spaces are not judged.
                        val lost = before.filter { b -> b.text != para.text && !squash(region(after, b.box, 0f)).contains(squash(b.text)) && after.none { squash(it.text).contains(squash(b.text)) } }
                        if (lost.isNotEmpty()) {
                            othersChanged++
                            lost.forEach { b ->
                                val near = after.minByOrNull { a -> kotlin.math.abs(a.box.y0 - b.box.y0) + kotlin.math.abs(a.box.x0 - b.box.x0) }
                                report.appendText("OTHER p${p + 1}#$i${if (grew) " (grew)" else ""} changed another paragraph:\n  was: ${b.text}\n  now: ${near?.text}\n  diff: ${diff(norm(b.text), norm(near?.text ?: ""))}\n")
                            }
                        }
                    }
                    Log.i("PowerPDF", "roundtrip ${file.name} page ${p + 1}: tested $tested passed $passed failed $failed others $othersChanged errors $errors")
                }
            } catch (e: Throwable) {
                report.appendText("STOPPED: $e\n")
                Log.w("PowerPDF", "roundtrip stopped", e)
            } finally {
                PdfEngine.run {
                    TextRepair.ligatures = true
                    com.aamus.powerpdf.engine.EngineDebug.skip = emptySet()
                }
            }
            val summary = "roundtrip ${file.name} fix=$fix DONE: tested $tested, passed $passed, failed $failed, other paragraphs changed $othersChanged, errors $errors, unreadable skipped $unreadable, grew $grewCount, hyphen-only $hyphenOnly, ${(System.currentTimeMillis() - started) / 1000} s"
            report.appendText("\n$summary\n")
            Log.i("PowerPDF", summary)
        }
    }

    /** The text of the paragraphs on [box]'s part of the page (and [below] it), top to bottom. */
    private fun region(paras: List<com.aamus.powerpdf.engine.ParagraphInfo>, box: com.aamus.powerpdf.engine.Box, below: Float): String =
        paras.filter { p -> p.lines.any { it.x1 > box.x0 + 1 && it.x0 < box.x1 - 1 && it.y1 > box.y0 - 1 && it.y0 < box.y1 + below } }
            .sortedWith(compareBy({ it.box.y0 }, { if (it.rtl) -it.box.x1 else it.box.x0 }))
            .joinToString(" ") { it.text }

    private fun pages(spec: String, count: Int): List<Int> = when {
        spec == "all" -> (0 until count).toList()
        else -> spec.split(',').flatMap { part ->
            val r = part.split('-').mapNotNull { it.trim().toIntOrNull() }
            if (r.size == 2) (r[0]..r[1]).toList() else r
        }.map { it - 1 }.filter { it in 0 until count }
    }

    private val spaces = Regex("\\s+")

    private fun norm(s: String) = s.replace(spaces, " ").trim()

    private val joints = Regex("[\\s\\-­‐]")

    private fun squash(s: String) = s.replace(joints, "")

    /** The words that differ, as «-old +new» runs (a small LCS; paragraphs are short). */
    private fun diff(a: String, b: String): String {
        val x = a.split(' ')
        val y = b.split(' ')
        if (x.size * y.size > 400_000) return "(too long)"
        val l = Array(x.size + 1) { IntArray(y.size + 1) }
        for (i in x.indices.reversed()) for (j in y.indices.reversed()) {
            l[i][j] = if (x[i] == y[j]) l[i + 1][j + 1] + 1 else maxOf(l[i + 1][j], l[i][j + 1])
        }
        val out = StringBuilder()
        var i = 0
        var j = 0
        while (i < x.size || j < y.size) {
            when {
                i < x.size && j < y.size && x[i] == y[j] -> { i++; j++ }
                j < y.size && (i == x.size || l[i][j + 1] >= l[i + 1][j]) -> out.append(" +").append(y[j++])
                else -> out.append(" -").append(x[i++])
            }
        }
        return out.toString().trim().ifEmpty { "(none)" }
    }
}

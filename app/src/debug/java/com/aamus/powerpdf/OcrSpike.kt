package com.aamus.powerpdf

import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import com.aamus.powerpdf.engine.PaperSize
import com.aamus.powerpdf.engine.PdfEngine
import com.aamus.powerpdf.engine.PdfImages
import com.aamus.powerpdf.engine.PictureInput
import com.aamus.powerpdf.engine.RecognisedLine
import com.aamus.powerpdf.engine.addTextLayer
import com.aamus.powerpdf.ocr.OcrEngine
import com.aamus.powerpdf.ocr.OcrImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only (docs/testing.md, "ocr"): the OCR spike (docs/research-ocr-android.md S0).
 * Renders a page of a PDF at a given dpi, finds the lines and reads them, and logs the timings,
 * native memory and the text; the report goes to files/ocr/<name>-p<page>.txt.
 * Value: "<pdf>|<page>|<dpi>|<threads>".
 */
object OcrSpike {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun start(context: Context, value: String) {
        val parts = value.split('|')
        val file = File(parts[0])
        val page = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val dpi = parts.getOrNull(2)?.toIntOrNull() ?: 200
        val threads = parts.getOrNull(3)?.toIntOrNull() ?: 4
        // "layer": the page also becomes an image-only PDF with the invisible text layer, read back.
        val layer = parts.getOrNull(4) == "layer"
        scope.launch {
            val dir = File(context.filesDir, "ocr").apply { mkdirs() }
            val report = File(dir, "${file.nameWithoutExtension}-p${page + 1}.txt")
            try {
                val t0 = System.currentTimeMillis()
                val bitmap = PdfEngine.run {
                    PdfEngine.open(file).use { doc ->
                        val widthPt = doc.pageSize(page).width
                        doc.renderWidth(page, (widthPt * dpi / 72f).toInt().coerceAtMost(2600), Bitmap.Config.ARGB_8888)
                    }
                }
                val tRender = System.currentTimeMillis() - t0
                val px = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val image = OcrImage(px, bitmap.width, bitmap.height)
                val jpeg = if (layer) java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() else null
                bitmap.recycle()
                val scale = image.width / PdfEngine.run { PdfEngine.open(file).use { it.pageSize(page).width } }
                val recognised = ArrayList<RecognisedLine>()
                val nativeBefore = Debug.getNativeHeapAllocatedSize() / 1048576
                val t1 = System.currentTimeMillis()
                val sb = StringBuilder("OCR spike ${file.name} page ${page + 1} dpi $dpi threads $threads: ${image.width}x${image.height}, rendered in $tRender ms\n")
                OcrEngine.open(context, threads).use { ocr ->
                    val lines = ocr.detectLines(image)
                    val tDetect = System.currentTimeMillis() - t1
                    val nativeDetect = Debug.getNativeHeapAllocatedSize() / 1048576
                    sb.append("detect: ${lines.size} lines in $tDetect ms (model ${ocr.detectMs} ms); native ${nativeBefore} → $nativeDetect MB\n")
                    val t2 = System.currentTimeMillis()
                    var read = 0
                    var peak = nativeDetect
                    for (box in lines) {
                        if (box.h < 8 || box.w < 12) continue
                        val r = ocr.read(image.crop(box.x, box.y, box.w, box.h))
                        read++
                        if (r.confidence >= 0.5f && r.text.isNotBlank()) recognised.add(RecognisedLine(squash(r.text), box.x / scale, box.y / scale, box.w / scale, box.h / scale))
                        peak = maxOf(peak, Debug.getNativeHeapAllocatedSize() / 1048576)
                        sb.append("  [%.2f] (%.0f,%.0f %.0fx%.0f) %s\n".format(r.confidence, box.x, box.y, box.w, box.h, r.text))
                    }
                    val tRead = System.currentTimeMillis() - t2
                    sb.append("read: $read lines in $tRead ms (model ${ocr.readMs} ms, ${if (read > 0) tRead / read else 0} ms a line); native peak $peak MB; total ${System.currentTimeMillis() - t0} ms\n")
                }
                if (jpeg != null) {
                    val out = File(dir, "${file.nameWithoutExtension}-p${page + 1}-layer.pdf")
                    val t3 = System.currentTimeMillis()
                    PdfEngine.run {
                        val plain = File(dir, "plain.pdf")
                        PdfImages.imagesToPdf(listOf(PictureInput(jpeg, image.width, image.height, PaperSize.A4)), PaperSize.A4, 0f, plain)
                        PdfEngine.open(plain).use { doc ->
                            // The picture fills an A4 page: the lines scale from the rendered page to it.
                            val k = doc.pageSize(0).width / (image.width / scale)
                            doc.addTextLayer(mapOf(0 to recognised.map { RecognisedLine(it.text, it.x * k, it.y * k, it.w * k, it.h * k) }))
                            doc.save(out)
                        }
                        plain.delete()
                        PdfEngine.open(out).use { doc ->
                            val back = doc.lines(0).map { squash(it.text) }
                            sb.append("layer: ${recognised.size} lines written in ${System.currentTimeMillis() - t3} ms, ${out.length()} bytes; read back ${back.size} lines\n")
                            var same = 0
                            for (l in recognised) if (l.text in back) same++ else sb.append("  DIFF wrote '${l.text}'\n")
                            for (b in back) if (recognised.none { it.text == b }) sb.append("       read '$b'\n")
                            sb.append("layer read-back: $same of ${recognised.size} lines identical\n")
                            // Readers join boxes on one baseline into one line, so words are the fairer measure.
                            val backWords = back.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().toMutableMap()
                            var found = 0
                            var total = 0
                            val missing = ArrayList<String>()
                            for (w in recognised.flatMap { it.text.split(' ') }.filter { it.isNotEmpty() }) {
                                total++
                                val n = backWords[w] ?: 0
                                if (n > 0) {
                                    found++
                                    backWords[w] = n - 1
                                } else missing.add(w)
                            }
                            sb.append("layer read-back words: $found of $total; missing: ${missing.take(40).joinToString(" | ")}\n")
                        }
                    }
                }
                report.writeText(sb.toString())
                Log.i("PowerPDF", sb.lines().first())
                Log.i("PowerPDF", sb.lines().last { it.isNotBlank() })
                Log.i("PowerPDF", "ocr spike DONE: ${report.absolutePath}")
            } catch (e: Throwable) {
                report.writeText("STOPPED: $e\n${e.stackTraceToString()}")
                Log.w("PowerPDF", "ocr spike stopped", e)
            }
        }
    }
}

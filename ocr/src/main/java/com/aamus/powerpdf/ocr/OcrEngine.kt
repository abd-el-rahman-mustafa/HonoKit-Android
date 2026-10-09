package com.aamus.powerpdf.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.security.MessageDigest

/** What a recogniser read from one line. */
class Reading(val text: String, val confidence: Float)

/**
 * Text recognition on the phone, offline: the desktop app's PaddleOCR models (detection v4,
 * Arabic recognition v5, which also reads Latin and digits) run by onnxruntime on the CPU
 * (docs/research-ocr-android.md §2). The detector runs without onnxruntime's memory arena, as
 * the desktop found necessary (desktop decisions §19). Open on a worker thread; [close] when the
 * job ends so the weights are released.
 */
class OcrEngine private constructor(private val env: OrtEnvironment, private val threads: Int, private val assets: android.content.res.AssetManager) : AutoCloseable {

    private var detector: OrtSession? = null
    private var arabic: OrtSession? = null
    private val arabicAlphabet: List<String> by lazy { OcrLogic.alphabetFrom(String(bytes(Models.ARABIC_DICT), Charsets.UTF_8)) }

    @Volatile
    var cancelled = false

    /** Milliseconds spent in the models, for the log. */
    var detectMs = 0L
    var readMs = 0L

    private fun bytes(m: Models.File): ByteArray {
        val b = assets.open("ocr/${m.name}").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        // A damaged or swapped model fails here, never guesses (desktop decisions §6).
        if (digest != m.sha256) throw OcrException("The text recognition models are damaged (${m.name}).")
        return b
    }

    private fun session(m: Models.File, arena: Boolean): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setCPUArenaAllocator(arena)
            setMemoryPatternOptimization(arena)
        }
        return env.createSession(bytes(m), options)
    }

    private fun detectorSession() = detector ?: session(Models.DETECTOR, arena = false).also { detector = it }
    private fun arabicSession() = arabic ?: session(Models.ARABIC, arena = true).also { arabic = it }

    private class Output(val data: FloatArray, val shape: LongArray)

    private fun run(model: OrtSession, data: FloatArray, dims: LongArray): Output {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), dims)
        try {
            model.run(mapOf(model.inputNames.first() to input)).use { result ->
                val out = result[0] as OnnxTensor
                val buf = out.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                return Output(arr, out.info.shape)
            }
        } finally {
            input.close()
        }
    }

    /**
     * The lines of text in [image], top to bottom. The picture is read in bands of [band] rows
     * (overlapping by [overlap]) so a page does not need the whole map in memory at once; a line
     * cut by a band edge is found twice and the larger copy kept.
     */
    fun detectLines(image: OcrImage, band: Int = DETECT_BAND, overlap: Int = DETECT_OVERLAP): List<TextBox> {
        val model = detectorSession()
        val found = ArrayList<TextBox>()
        var top = 0
        while (top < image.height) {
            if (cancelled) throw OcrException.Cancelled()
            val bandHeight = minOf(band, image.height - top)
            val rows = OcrImage(image.px.copyOfRange(top * image.width, (top + bandHeight) * image.width), image.width, bandHeight)
            val width = (image.width + 31) / 32 * 32
            val height = (bandHeight + 31) / 32 * 32
            val t0 = System.currentTimeMillis()
            val planes = OcrLogic.toPlanes(rows, width, height, image.width, bandHeight, DET_MEAN, DET_STD)
            val out = run(model, planes, longArrayOf(1, 3, height.toLong(), width.toLong()))
            detectMs += System.currentTimeMillis() - t0
            for (box in OcrLogic.boxesFromProbability(out.data, width, height, image.width, bandHeight)) {
                val placed = box.copy(y = box.y + top)
                val twin = found.indexOfFirst { OcrLogic.overlapOfSmaller(it, placed) > 0.5f }
                if (twin < 0) found.add(placed)
                else if (placed.w * placed.h > found[twin].w * found[twin].h) found[twin] = placed
            }
            if (top + bandHeight >= image.height) break
            top += band - overlap
        }
        return found.sortedWith(compareBy<TextBox> { it.y }.thenByDescending { it.x })
    }

    /** Reads one line (a crop of the page) with the Arabic recogniser; Arabic comes back in reading order. */
    fun read(line: OcrImage): Reading {
        if (cancelled) throw OcrException.Cancelled()
        val width = OcrLogic.readWidth(line.width, line.height)
        val model = arabicSession()
        val t0 = System.currentTimeMillis()
        val planes = OcrLogic.toPlanes(line, width, OcrLogic.LINE_HEIGHT, width, OcrLogic.LINE_HEIGHT, REC_MEAN, REC_STD)
        val out = run(model, planes, longArrayOf(1, 3, OcrLogic.LINE_HEIGHT.toLong(), width.toLong()))
        readMs += System.currentTimeMillis() - t0
        val steps = out.shape[1].toInt()
        val classes = out.shape[2].toInt()
        val decoded = OcrLogic.decodeCtc(out.data, steps, classes, arabicAlphabet)
        val arabic = OcrLogic.ARABIC.containsMatchIn(decoded.text)
        // This recogniser reads spaces itself; gaps are guessed only for a line that came back
        // without any (the gap rule split wide letters: "ju m ps", "ipsu m" on the phone).
        val text = when {
            arabic -> OcrLogic.visualToLogical(decoded.text)
            decoded.text.contains(' ') -> decoded.text
            else -> OcrLogic.withSpaces(decoded.chars)
        }
        return Reading(text, decoded.confidence)
    }

    override fun close() {
        detector?.close()
        arabic?.close()
        detector = null
        arabic = null
    }

    companion object {
        const val DETECT_BAND = 1280
        const val DETECT_OVERLAP = 96
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

        /**
         * Opens the engine; [threads] for each model (the phone's big cores, 2–4). onnxruntime
         * ships for 64-bit phones only (owner, 2026-10-01; decisions §29): on a 32-bit phone the
         * library is missing and this says so.
         */
        fun open(context: Context, threads: Int = 4): OcrEngine {
            val env = try {
                OrtEnvironment.getEnvironment()
            } catch (e: UnsatisfiedLinkError) {
                throw OcrException.Unsupported()
            }
            return OcrEngine(env, threads, context.applicationContext.assets)
        }
    }
}

/** The bundled model files and their checksums (the desktop's resources/ocr, 2026-09-30). */
object Models {
    class File(val name: String, val sha256: String)

    val DETECTOR = File("ch_PP-OCRv4_det_infer.onnx", "30a86f5731181461d08021402766601e4302a9b9b9666be8aff402696339cdff")
    val ARABIC = File("arabic_PP-OCRv5_mobile_rec.onnx", "4e2f4ae42104e1b272463966c56ddafa3c6ad98ce9d8c7ed765ce66666ea13e1")
    val ARABIC_DICT = File("arabic_PP-OCRv5_mobile_rec_dict.txt", "7f92f7dbb9b75a4787a83bfb4f6d14a8ab515525130c9d40a9036f61cf6999e9")
}

open class OcrException(message: String) : RuntimeException(message) {
    class Cancelled : OcrException("Text recognition was cancelled.")
    class Unsupported : OcrException("Text recognition needs a 64-bit phone.")
}

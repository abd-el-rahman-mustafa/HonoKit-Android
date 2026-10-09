package com.aamus.powerpdf.ui.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aamus.powerpdf.R
import com.aamus.powerpdf.engine.PageQuad
import com.aamus.powerpdf.ocr.PageFinder
import com.aamus.powerpdf.engine.ScanTracker
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.Sym
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Whether the app may use the camera itself (the scanner's live view). */
fun hasCamera(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * The scanner's own camera (docs/research-scan.md M5): the page outline is drawn live over the
 * picture (PageQuad on every analysed frame; decisions §30), and when the phone is held still over a page the
 * picture is taken by itself (ScanTracker), page after page. The shutter always works; auto
 * capture can be turned off. [single]: one picture and back (Retake). Without the camera
 * permission, [onFallback] uses the phone's camera app instead. A strip under the picture chooses
 * what is scanned ([kind], [onKind]; docs/research-camscanner.md part 2); [cardBack]: the next ID
 * card picture is the back. Tap the picture to focus there; pinch to zoom. [onGallery]: pictures
 * from the phone instead (a button beside the shutter, and the way out when no camera can be
 * opened: another app holds it, a device policy, an emulator without one; UX review finding 1).
 */
@Composable
fun ScanCamera(
    pages: Int,
    last: Uri?,
    single: Boolean,
    onPhoto: (Uri) -> Unit,
    onDone: () -> Unit,
    onFallback: () -> Unit,
    kind: ScanKind = ScanKind.Document,
    onKind: ((ScanKind) -> Unit)? = null,
    cardBack: Boolean = false,
    onGallery: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(hasCamera(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) allowed = true else onFallback()
    }
    /** No camera could be opened (CameraX found none, or binding failed): say so and offer photos. */
    var failed by remember { mutableStateOf(!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) }
    LaunchedEffect(Unit) { if (!allowed && !failed) ask.launch(Manifest.permission.CAMERA) }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (failed) CameraUnavailable(onGallery, onDone)
            else if (allowed) CameraView(pages, last, single, onPhoto, onDone, kind, onKind, cardBack, onGallery) { failed = true }
        }
    }
}

/** The camera could not be opened: one plain sentence, photos from the phone instead, Close. */
@Composable
private fun CameraUnavailable(onGallery: (() -> Unit)?, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(R.drawable.ic_close, stringResource(R.string.close), onClose, tint = Color.White)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Sym(R.drawable.ic_photo_camera, null, size = 48.dp, tint = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.cam_unavailable), color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.cam_unavailable_note), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            if (onGallery != null) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF1473E6))
                        .clickable(onClick = onGallery)
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym(R.drawable.ic_photo_library, null, size = 20.dp, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cam_choose_photos), color = Color.White, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** What the live view shows: the outline (fractions of the upright frame) and the frame's shape. */
private class Seen(
    val outline: FloatArray?,
    val state: ScanTracker.State,
    val progress: Float,
    val frameW: Int,
    val frameH: Int,
    /** A page is outlined but not sure enough to be taken by itself (too close: two sides off the frame). */
    val tapOnly: Boolean = false,
)

@Composable
private fun CameraView(
    pages: Int,
    last: Uri?,
    single: Boolean,
    onPhoto: (Uri) -> Unit,
    onDone: () -> Unit,
    kind: ScanKind,
    onKind: ((ScanKind) -> Unit)?,
    cardBack: Boolean,
    onGallery: (() -> Unit)?,
    onFail: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var seen by remember { mutableStateOf<Seen?>(null) }
    var auto by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var taken by remember { mutableStateOf(pages) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    /** Where the picture was tapped to focus (shown as a ring for a moment). */
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var zoomText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusAt) {
        if (focusAt != null) {
            kotlinx.coroutines.delay(1200)
            focusAt = null
        }
    }
    LaunchedEffect(zoomText) {
        if (zoomText != null) {
            kotlinx.coroutines.delay(1000)
            zoomText = null
        }
    }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .build()
    }
    val tracker = remember { ScanTracker() }
    /** The page model the analysis thread opened, so it can be closed there. */
    var finderHolder: () -> PageFinder? = { null }
    val analysis = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    val autoNow by androidx.compose.runtime.rememberUpdatedState(auto)

    fun shoot() {
        if (busy) return
        busy = true
        flash = true
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "${UUID.randomUUID()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            main,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    busy = false
                    flash = false
                    synchronized(tracker) { tracker.tookPicture() }
                    taken++
                    onPhoto(Uri.fromFile(f))
                    if (single) onDone()
                }

                override fun onError(e: ImageCaptureException) {
                    busy = false
                    flash = false
                    android.util.Log.w("PowerPDF", "scan camera: picture failed", e)
                }
            },
        )
    }
    val shootNow by androidx.compose.runtime.rememberUpdatedState(::shoot)

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            // CameraX throws here when no camera can be used ("Available cameras: 0"); that took
            // the whole app down (UX review finding 1).
            val provider = try {
                providerFuture.get()
            } catch (e: Throwable) {
                android.util.Log.w("PowerPDF", "scan camera: no camera provider", e)
                onFail()
                return@addListener
            }
            val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val preview = Preview.Builder().setResolutionSelector(ratio).build()
            val analyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build(),
                )
                .build()
            var frames = 0
            var spent = 0L
            // The page model, opened on the analysis thread the first time it is needed.
            var finder: PageFinder? = null
            var finderTried = false
            var modelEvery = 1
            var sinceModel = 1
            var modelMs = 0L
            var lastModel: FloatArray? = null
            finderHolder = { finder }
            analyzer.setAnalyzer(analysis) { image ->
                try {
                    val t0 = System.currentTimeMillis()
                    val (px, w, h) = upright(image, 320)
                    ScanDiag.light = px.let { a -> var s = 0L; var i = 0; while (i < a.size) { val p = a[i]; s += (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)) / 3; i += 37 }; (s / (a.size / 37 + 1)).toInt() }
                    // A sheet found by its edges and straight sides (decisions §30): no outline and no
                    // auto capture for a wall, the floor or a lamp, and a page on a light desk or
                    // filling the frame is found (owner, 2026-10-02).
                    // The analysis stream came through black on the owner's phone while the preview
                    // was fine: then the preview is read instead (below), and these frames are ignored.
                    if (ScanDiag.usePreview) return@setAnalyzer
                    val found = PageQuad.detect(px, w, h)
                    // The outline is shown for a page (confident); the picture is only taken by
                    // itself when at least three of its edges are in view (autoCapture).
                    var outline = found?.takeIf { it.autoCapture }?.corners
                    // When the edges are not enough (a sheet lying on other paper), the page model
                    // (decisions §31): all four corners sure and a sheet's shape, or nothing.
                    // It paces itself to the phone (the owner: "can work on different phones, not
                    // requiring a high phone"): every frame when quick, every 2nd or 3rd on slower
                    // phones (the last outline stands in between), off when too slow.
                    var model: PageFinder.Corners? = null
                    if (outline == null && ScanDiag.light > 15) {
                        if (finder == null && !finderTried) {
                            finderTried = true
                            finder = PageFinder.open(context)
                        }
                        val f = finder
                        if (f != null && modelEvery > 0) {
                            sinceModel++
                            if (sinceModel >= modelEvery) {
                                sinceModel = 0
                                val m0 = System.currentTimeMillis()
                                model = f.find(px, w, h)
                                val ms = System.currentTimeMillis() - m0
                                modelMs = if (modelMs == 0L) ms else (modelMs * 3 + ms) / 4
                                modelEvery = when {
                                    modelMs <= 80 -> 1
                                    modelMs <= 160 -> 2
                                    modelMs <= 300 -> 3
                                    else -> 0
                                }
                                if (modelEvery == 0) android.util.Log.w("PowerPDF", "scan camera: page model too slow here ($modelMs ms): edges only")
                                lastModel = model?.takeIf { it.confidence >= MODEL_SURE && PageQuad.plausible(it.corners) }?.corners
                            }
                            outline = lastModel
                        }
                    } else {
                        lastModel = null
                        sinceModel = modelEvery
                    }
                    val shown = outline
                    val r = synchronized(tracker) { tracker.next(outline, System.currentTimeMillis()) }
                    spent += System.currentTimeMillis() - t0
                    if (++frames % 15 == 0) android.util.Log.i("PowerPDF", "scan camera: ${found ?: "nothing light"}${model?.let { " | model %.2f%s, %d ms, every %d".format(java.util.Locale.US, it.confidence, if (outline === it.corners) " → page" else "", modelMs, modelEvery) } ?: ""}")
                    // Dark frames (the phone lying face down) are not worth keeping: they used up the
                    // recording before the owner had picked the phone up (2026-10-02).
                    if (ScanDiag.light > 15) FrameRecorder.offer(context, frames, px, w, h, found?.toString() ?: "nothing light")
                    if (frames % 30 == 0) {
                        android.util.Log.i("PowerPDF", "scan camera: ${spent / 30} ms a frame (${w}x$h)")
                        spent = 0
                    }
                    main.execute {
                        seen = Seen(r.outline ?: shown, r.state, r.progress, w, h, tapOnly = r.outline == null && shown != null)
                        if (r.state == ScanTracker.State.Capture && autoNow) shootNow()
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("PowerPDF", "scan camera: frame", e)
                } finally {
                    image.close()
                }
            }
            val view = PreviewHolder.view
            if (view != null) preview.surfaceProvider = view.surfaceProvider
            runCatching {
                provider.unbindAll()
                // A phone (or emulator) with only a front camera still scans with that one.
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> error("no camera")
                }
                val cam = provider.bindToLifecycle(owner, selector, preview, analyzer, capture)
                camera = cam
                // Disabled by a device policy, or broken: CameraX gives up (a camera held by
                // another app is a recoverable error and opens by itself once it is free).
                cam.cameraInfo.cameraState.observe(owner) { st ->
                    if (st.error?.type == androidx.camera.core.CameraState.ErrorType.CRITICAL) {
                        android.util.Log.w("PowerPDF", "scan camera: camera error ${st.error?.code}")
                        onFail()
                    }
                }
            }.onFailure {
                android.util.Log.w("PowerPDF", "scan camera: bind", it)
                onFail()
            }
            PreviewHolder.preview = preview
            PreviewHolder.analysis = analyzer
        }, main)
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            // The model's weights are released on its own thread, after the last frame.
            val holder = finderHolder
            analysis.execute { runCatching { holder()?.close() } }
            analysis.shutdown()
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    // A safeguard (2026-10-02): on the owner's phone the analysis frames once came through black
    // for a whole session while the preview and the photos were fine, so the detector was blind.
    // Once a second the preview's light is compared with the analysis frames'; when the preview is
    // lit and the analysis is dark twice running, the page is looked for in the preview itself
    // (about five times a second) until the analysis stream is lit again.
    LaunchedEffect(Unit) {
        ScanDiag.usePreview = false
        var dark = 0
        var lit = 0
        while (true) {
            kotlinx.coroutines.delay(1000)
            val bmp = PreviewHolder.view?.bitmap ?: continue
            val preview = meanLight(bmp)
            bmp.recycle()
            val analysis = ScanDiag.light
            if (preview > 60 && analysis in 0..20) { dark++; lit = 0 } else { lit++; dark = 0 }
            if (!ScanDiag.usePreview && dark >= 2) {
                ScanDiag.usePreview = true
                synchronized(tracker) { tracker.reset() }
                android.util.Log.w("PowerPDF", "scan camera: analysis frames dark ($analysis) while the preview is lit ($preview): reading the preview")
            } else if (ScanDiag.usePreview && lit >= 3) {
                ScanDiag.usePreview = false
                synchronized(tracker) { tracker.reset() }
                android.util.Log.i("PowerPDF", "scan camera: analysis frames lit again ($analysis): back to them")
            }
            if (lit % 10 == 1) android.util.Log.i("PowerPDF", "scan light: preview $preview, analysis $analysis${if (ScanDiag.usePreview) " (reading the preview)" else ""}")
        }
    }
    // The preview as the detector's picture, only while the analysis stream is dark.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(200)
            if (!ScanDiag.usePreview) continue
            val bmp = PreviewHolder.view?.bitmap ?: continue
            val sw = 240
            val sh = maxOf(1, bmp.height * sw / bmp.width)
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
            bmp.recycle()
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            small.recycle()
            val found = withContext(Dispatchers.Default) { PageQuad.detect(px, sw, sh) }
            val shown = found?.takeIf { it.autoCapture }?.corners
            val r = synchronized(tracker) { tracker.next(found?.takeIf { it.autoCapture }?.corners, System.currentTimeMillis()) }
            // The preview bitmap is the view itself: its outline maps one to one onto the screen.
            seen = Seen(r.outline ?: shown, r.state, r.progress, sw, sh, tapOnly = r.outline == null && shown != null)
            if (r.state == ScanTracker.State.Capture && autoNow) shootNow()
        }
    }

    // The activity keeps itself across a turn of the phone (configChanges), so CameraX is told the
    // new rotation here: the preview, the outline and the saved photo stay upright in landscape.
    val hostView = androidx.compose.ui.platform.LocalView.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(config.orientation, camera) {
        val r = hostView.display?.rotation ?: return@LaunchedEffect
        capture.targetRotation = r
        PreviewHolder.preview?.targetRotation = r
        PreviewHolder.analysis?.targetRotation = r
        synchronized(tracker) { tracker.reset() }
    }

    @Composable
    fun CloseButton() = IconAction(R.drawable.ic_close, stringResource(R.string.close), onDone, tint = Color.White)

    @Composable
    fun TorchButton() {
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            IconAction(if (torch) R.drawable.ic_flash_on else R.drawable.ic_flash_off, stringResource(R.string.torch), { torch = !torch }, tint = Color.White, selected = torch)
        }
    }

    @Composable
    fun AutoChip() {
        Row(
            Modifier
                .clip(CircleShape)
                .background(if (auto) Color(0xFF1473E6) else Color(0x33FFFFFF))
                .clickable { auto = !auto }
                .heightIn(min = 40.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Sym(R.drawable.ic_auto_fix_high, null, size = 18.dp, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (auto) R.string.cam_auto else R.string.cam_manual), color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }

    // The picture, with the page outline over it. Tap: focus and light metering there
    // (blurred pictures are the top complaint: docs/research-scan.md 4); pinch: zoom.
    @Composable
    fun Picture(modifier: Modifier) {
        BoxWithConstraints(
            modifier
                .pointerInput(camera) {
                    detectTapGestures { pos ->
                        val view = PreviewHolder.view ?: return@detectTapGestures
                        val cam = camera ?: return@detectTapGestures
                        val point = view.meteringPointFactory.createPoint(pos.x, pos.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        runCatching { cam.cameraControl.startFocusAndMetering(action) }
                        focusAt = pos
                    }
                }
                .pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val ratio = (state.zoomRatio * zoom).coerceIn(state.minZoomRatio, minOf(state.maxZoomRatio, 4f))
                        cam.cameraControl.setZoomRatio(ratio)
                        zoomText = String.format(java.util.Locale.US, "%.1f×", ratio)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        PreviewHolder.view = this
                        PreviewHolder.preview?.surfaceProvider = surfaceProvider
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            val s = seen
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                val q = s?.outline ?: return@Canvas
                // The frame is shown whole, centred (FIT_CENTER): the same fit maps the outline.
                val k = minOf(boxW / s.frameW, boxH / s.frameH)
                val ox = (boxW - s.frameW * k) / 2
                val oy = (boxH - s.frameH * k) / 2
                val pts = (0 until 4).map { Offset(ox + q[it * 2] * s.frameW * k, oy + q[it * 2 + 1] * s.frameH * k) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                val steady = s.state == ScanTracker.State.Steady || s.state == ScanTracker.State.Capture
                val colour = when {
                    s.state == ScanTracker.State.Taken -> Color(0xFF2D9D78)
                    steady -> Color(0xFF4B9CF5)
                    else -> Color.White
                }
                drawPath(path, colour.copy(alpha = 0.18f))
                drawPath(path, colour, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            focusAt?.let { at ->
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, 34.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                }
            }
            zoomText?.let { z ->
                Text(
                    z,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC111111)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // A white blink when the picture is taken.
            val blink by animateFloatAsState(if (flash) 0.55f else 0f, tween(if (flash) 60 else 260), label = "blink")
            if (blink > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = blink)))
            // What to do now.
            val state = s?.state ?: ScanTracker.State.Searching
            var lost by remember { mutableStateOf(false) }
            LaunchedEffect(state == ScanTracker.State.Searching) {
                lost = false
                if (state == ScanTracker.State.Searching) {
                    kotlinx.coroutines.delay(4000)
                    lost = true
                }
            }
            val pill = when {
                busy -> stringResource(R.string.cam_taking)
                state == ScanTracker.State.Taken -> stringResource(R.string.cam_taken, taken)
                // What to hold up, by kind: the card's side, the open book, a page.
                state == ScanTracker.State.Searching || state == ScanTracker.State.Moving -> when (kind) {
                    ScanKind.IdCard -> stringResource(if (cardBack) R.string.cam_id_back else R.string.cam_id_front)
                    ScanKind.Book -> stringResource(R.string.cam_book)
                    else -> when {
                        // After a few seconds without a page, say the shutter works anyway.
                        s?.tapOnly == true -> stringResource(R.string.cam_tap)
                        state == ScanTracker.State.Searching && lost -> stringResource(R.string.cam_no_page)
                        state == ScanTracker.State.Searching -> stringResource(R.string.cam_point)
                        !auto -> stringResource(R.string.cam_tap)
                        else -> stringResource(R.string.cam_hold)
                    }
                }
                !auto -> stringResource(R.string.cam_tap)
                else -> stringResource(R.string.cam_hold)
            }
            Column(Modifier.align(Alignment.TopCenter).padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    pill,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC111111)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (auto && state == ScanTracker.State.Steady) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(120.dp).height(4.dp).clip(CircleShape).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxWidth(s?.progress ?: 0f).height(4.dp).background(Color(0xFF4B9CF5)))
                    }
                }
            }
        }
    }

    // What is scanned: a page, an ID card, a book (not while retaking one picture).
    @Composable
    fun KindItems() {
        for ((k, label) in listOf(ScanKind.Document to R.string.kind_document, ScanKind.IdCard to R.string.kind_id, ScanKind.Book to R.string.kind_book)) {
            val on = k == kind
            Text(
                stringResource(label),
                color = if (on) Color(0xFF8CC4FF) else Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(CircleShape)
                    .background(if (on) Color(0x33FFFFFF) else Color.Transparent)
                    .clickable(onClickLabel = stringResource(R.string.cam_kind)) { onKind?.invoke(k) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // Pictures already on the phone, beside the shutter as in Adobe Scan and Lens.
    @Composable
    fun GalleryButton() {
        if (onGallery == null || single) return
        val label = stringResource(R.string.from_gallery)
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF))
                .clickable(onClick = onGallery)
                .semantics { contentDescription = label; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { Sym(R.drawable.ic_photo_library, null, size = 24.dp, tint = Color.White) }
    }

    // The last page taken (tap: done), with the number taken so far.
    @Composable
    fun LastTaken(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
        val thumb by produceState<ImageBitmap?>(null, last) {
            value = last?.let { u -> withContext(Dispatchers.Default) { runCatching { decodeUpright(context, u, 0, 160)?.asImageBitmap() }.getOrNull() } }
        }
        val pagesLabel = stringResource(R.string.cam_pages_taken, taken)
        thumb?.let { t ->
            Box(Modifier.clickable(onClick = onDone).semantics(mergeDescendants = true) { contentDescription = pagesLabel; role = Role.Button }) {
                Image(t, null, Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).border(1.5.dp, Color.White, RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Text(
                    "$taken",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).clip(CircleShape).background(Color(0xFF1473E6)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }

    // The shutter: a white ring, filled while a picture is being taken.
    @Composable
    fun Shutter() {
        val shutterLabel = stringResource(R.string.cam_shutter)
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .padding(7.dp)
                .clip(CircleShape)
                .background(if (busy) Color(0xFF4B9CF5) else Color.White)
                .clickable { shoot() }
                .semantics { contentDescription = shutterLabel; role = Role.Button },
        )
    }

    @Composable
    fun DoneButton() {
        if (taken > 0) {
            Text(
                stringResource(R.string.done),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(Color(0xFF1473E6)).clickable(onClick = onDone).heightIn(min = 40.dp).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }

    if (landscape) {
        // Sideways: the picture takes the whole height and the controls stand at the end, as in
        // the phone's own camera (the picture was a quarter of the screen; decisions §43).
        Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Picture(Modifier.weight(1f).fillMaxHeight())
            Column(
                Modifier.width(208.dp).fillMaxHeight().padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TorchButton()
                    CloseButton()
                }
                AutoChip()
                if (onKind != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) { KindItems() }
                Shutter()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GalleryButton()
                    LastTaken(40.dp, 52.dp)
                    DoneButton()
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Close, torch, auto capture.
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CloseButton()
                Spacer(Modifier.weight(1f))
                TorchButton()
                Box(Modifier.padding(end = 8.dp)) { AutoChip() }
            }
            Picture(Modifier.weight(1f).fillMaxWidth())
            if (onKind != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { KindItems() }
            }
            // The last page taken, the shutter, Done.
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GalleryButton()
                    LastTaken(52.dp, 68.dp)
                }
                Shutter()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { DoneButton() }
            }
        }
    }
}

/**
 * Debug only, with the owner's consent (2026-10-02): saves some of the frames the page detector
 * sees (320 px, every 4th frame, at most 200) to files/frames, with what it found, so detection
 * can be tuned on real scenes on the PC. Off unless the debug command "recframes on" turns it on;
 * never in release builds (nothing turns it on there). The frames are never committed.
 */
object FrameRecorder {
    @Volatile
    var on = false
    private var saved = 0

    fun offer(context: Context, frame: Int, px: IntArray, w: Int, h: Int, found: String) {
        if (!on || frame % 4 != 0 || saved >= 200) return
        runCatching {
            val dir = File(context.filesDir, "frames").apply { mkdirs() }
            val n = ++saved
            val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            File(dir, "f%03d.png".format(n)).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            File(dir, "found.txt").appendText("f%03d %d %s\n".format(n, System.currentTimeMillis(), found))
        }
    }

    fun start(context: Context) {
        File(context.filesDir, "frames").deleteRecursively()
        saved = 0
        on = true
    }
}

/** The page model's least corner confidence to count (pages on the owner's frames: 0.88–0.94; none: under 0.05). */
private const val MODEL_SURE = 0.6f

/** The analysis frames' mean light (0..255), and whether the preview is read instead of them. */
private object ScanDiag {
    @Volatile
    var light = -1

    @Volatile
    var usePreview = false
}

/** The mean light (0..255) of [bmp], from about 40 × 60 points. */
private fun meanLight(bmp: android.graphics.Bitmap): Int {
    val step = maxOf(1, bmp.width / 40)
    var sum = 0L
    var n = 0
    var y = 0
    while (y < bmp.height) {
        var x = 0
        while (x < bmp.width) {
            val p = bmp.getPixel(x, y)
            sum += (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)) / 3
            n++
            x += step
        }
        y += step
    }
    return (sum / maxOf(1, n)).toInt()
}

/** The preview view and its use case, which are created in either order. */
private object PreviewHolder {
    var view: PreviewView? = null
    var preview: Preview? = null
    var analysis: ImageAnalysis? = null
}

/**
 * A frame as upright ARGB pixels at most [side] pixels long (the analysed frame comes in the
 * sensor's orientation, turned by its rotation).
 */
private fun upright(image: ImageProxy, side: Int): Triple<IntArray, Int, Int> {
    val plane = image.planes[0]
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val pxStride = plane.pixelStride
    val iw = image.width
    val ih = image.height
    val step = maxOf(1, (maxOf(iw, ih) + side - 1) / side)
    val sw = iw / step
    val sh = ih / step
    val small = IntArray(sw * sh)
    for (y in 0 until sh) {
        val row = y * step * rowStride
        for (x in 0 until sw) {
            val o = row + x * step * pxStride
            val r = buf.get(o).toInt() and 255
            val g = buf.get(o + 1).toInt() and 255
            val b = buf.get(o + 2).toInt() and 255
            small[y * sw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return when ((image.imageInfo.rotationDegrees % 360 + 360) % 360) {
        90 -> Triple(IntArray(sw * sh) { i -> val x = i % sh; val y = i / sh; small[(sh - 1 - x) * sw + y] }, sh, sw)
        180 -> Triple(IntArray(sw * sh) { i -> small[sw * sh - 1 - i] }, sw, sh)
        270 -> Triple(IntArray(sw * sh) { i -> val x = i % sh; val y = i / sh; small[x * sw + (sw - 1 - y)] }, sh, sw)
        else -> Triple(small, sw, sh)
    }
}

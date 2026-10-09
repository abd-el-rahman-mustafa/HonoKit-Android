package com.aamus.powerpdf.ui.image

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.aamus.powerpdf.MainActivity
import com.aamus.powerpdf.R
import com.aamus.powerpdf.Screen
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.engine.img.ImageFacts
import com.aamus.powerpdf.engine.img.ImageKit
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.LocalLauncher
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.SheetTitle
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.ToolCatalog
import com.aamus.powerpdf.ui.ToolRow
import com.aamus.powerpdf.ui.Zoomable
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * The image viewer (decisions §42): the picture on black, pinch and double-tap to zoom, swipe
 * between pictures; Back, the name, Share and ⋮ (Details, Open in another app) at the top;
 * Edit (the photo editor) and Tools (the image tools, with this picture already in) at the
 * bottom. A tap shows or hides the bars.
 */
@Composable
fun ImageViewerScreen(screen: Screen.ImageViewer) {
    val model = LocalModel.current
    val context = LocalContext.current
    val launch = LocalLauncher.current
    val uris = screen.uris
    if (uris.isEmpty()) return
    val pager = rememberPagerState(initialPage = screen.index.coerceIn(0, uris.lastIndex)) { uris.size }
    var chrome by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    val current = uris[pager.currentPage.coerceIn(0, uris.lastIndex)]
    val name by produceState("", current) { value = withContext(Dispatchers.IO) { Files.displayName(context, current) } }

    // Light status and navigation bar icons over the black screen, as long as it shows.
    // On leaving, the icons follow the app's theme as it is then (not as it was on arrival: the
    // theme may have changed meanwhile).
    val view = LocalView.current
    val lightTheme by androidx.compose.runtime.rememberUpdatedState(!Power.colors.isDark)
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            bars?.isAppearanceLightStatusBars = lightTheme
            bars?.isAppearanceLightNavigationBars = lightTheme
        }
    }

    // Decoded a little larger than the screen, so a zoom stays sharp a while (and memory stays modest).
    val config = LocalConfiguration.current
    val side = with(LocalDensity.current) { (max(config.screenWidthDp, config.screenHeightDp).dp.toPx() * 1.5f).toInt().coerceIn(1600, 2560) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { i -> "$i:${uris[i]}" }) { i ->
            ImagePage(uris[i], side, onTap = { chrome = !chrome }, resetKey = pager.currentPage == i)
        }

        // Top: Back, the name (and "2 of 5"), Share, ⋮.
        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            // A scrim that stays dark under the whole bar and fades out below it: the gradient used
            // to fade within the bar, and "2 of 4" sat grey on a tall light picture, unreadable
            // (phone test, 2026-10-04).
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.62f)).statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) { model.pop() }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(bidi(name), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        if (uris.size > 1) {
                            Text(stringResource(R.string.n_of_m, pager.currentPage + 1, uris.size), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                        }
                    }
                    BarIcon(R.drawable.ic_share, stringResource(R.string.share)) {
                        model.shareSaved(listOf(current), mimeOf(context, current), launch)
                    }
                    Box {
                        BarIcon(R.drawable.ic_more_vert, stringResource(R.string.more)) { menuOpen = true }
                        val c = Power.colors
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = c.surface, shape = RoundedCornerShape(12.dp)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.img_details), style = MaterialTheme.typography.bodyLarge) },
                                leadingIcon = { Sym(R.drawable.ic_info, null, tint = c.muted) },
                                onClick = { menuOpen = false; detailsOpen = true },
                                modifier = Modifier.widthIn(min = 220.dp),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_in_other_app), style = MaterialTheme.typography.bodyLarge) },
                                leadingIcon = { Sym(R.drawable.ic_open_in_new, null, tint = c.muted) },
                                onClick = {
                                    menuOpen = false
                                    openElsewhere(context, current, name, launch) { model.say(model.str(R.string.error_no_app)) }
                                },
                                modifier = Modifier.widthIn(min = 220.dp),
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent))))
            }
        }

        // Bottom: Edit and Tools.
        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // As the top: dark under the buttons, fading out above them ("Tools" was white on white).
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)))))
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.62f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    BottomAction(R.drawable.ic_edit, stringResource(R.string.edit), filled = true, Modifier.weight(1f, fill = false).widthIn(min = 140.dp)) {
                        model.push(Screen.Tool(ToolId.ImgEditor, inputs = listOf(current)))
                    }
                    BottomAction(R.drawable.ic_apps, stringResource(R.string.tools), filled = false, Modifier.weight(1f, fill = false).widthIn(min = 140.dp)) {
                        toolsOpen = true
                    }
                }
            }
        }
    }

    if (toolsOpen) {
        ImageToolsSheet(onDismiss = { toolsOpen = false }) { tool ->
            toolsOpen = false
            model.push(Screen.Tool(tool, inputs = listOf(current)))
        }
    }
    if (detailsOpen) DetailsDialog(current, name, onDismiss = { detailsOpen = false })
}

private sealed interface Picture {
    data object Loading : Picture
    data class Shown(val image: ImageBitmap) : Picture
    data object Failed : Picture
}

/** One picture, decoded upright by the image tools' own decoder (HEIC, WebP, AVIF… as they read them). */
@Composable
private fun ImagePage(uri: Uri, side: Int, onTap: () -> Unit, resetKey: Any) {
    val context = LocalContext.current
    val picture by produceState<Picture>(Picture.Loading, uri, side) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageKit.decode(context.contentResolver, uri, side).asImageBitmap() }
                .onFailure { android.util.Log.w("PowerPDF", "image viewer: $uri", it) }
                .getOrNull()
        }?.let { Picture.Shown(it) } ?: Picture.Failed
    }
    Zoomable(onTap = onTap, tapResets = false, maxZoom = 6f, resetKey = resetKey) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val p = picture) {
                Picture.Loading -> CircularProgressIndicator(Modifier.size(32.dp), color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.5.dp)
                is Picture.Shown -> Image(p.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Picture.Failed -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Sym(R.drawable.ic_error, null, tint = Color.White.copy(alpha = 0.7f), size = 40.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.img_cannot_read), style = MaterialTheme.typography.bodyLarge, color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** An icon button on the dark bars: white, with its name for TalkBack. */
@Composable
private fun BarIcon(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Sym(icon, label, tint = Color.White) }
}

/** Edit (filled, the accent) and Tools (outlined) at the bottom: an icon and a label that fits. */
@Composable
private fun BottomAction(icon: Int, label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Power.colors
    val shape = CircleShape
    Row(
        modifier
            .clip(shape)
            .then(if (filled) Modifier.background(c.accent) else Modifier.background(Color.White.copy(alpha = 0.12f)).border(1.dp, Color.White.copy(alpha = 0.45f), shape))
            .clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
            .heightIn(min = 52.dp)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val ink = if (filled) c.accentInk else Color.White
        Sym(icon, null, tint = ink, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The image tools, each opening with this picture in (Photo editor is the Edit button). Also the
 * image tools' "switch tool" sheet (decisions §48), with [current] marked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToolsSheet(onDismiss: () -> Unit, current: ToolId? = null, title: String? = null, onTool: (ToolId) -> Unit) {
    val c = Power.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        SheetTitle(title ?: stringResource(R.string.group_images))
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(ToolCatalog.imageTools.filter { it.id != ToolId.ImgEditor }, key = { it.id.name }) { t -> ToolRow(t, selected = t.id == current) { if (t.id == current) onDismiss() else onTool(t.id) } }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = c.line)
                Text(
                    stringResource(R.string.img_make_pdf_text),
                    style = MaterialTheme.typography.labelLarge,
                    color = c.muted,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            items(listOf(ToolCatalog.images, ToolCatalog.imageToText), key = { it.id.name }) { t -> ToolRow(t) { onTool(t.id) } }
        }
    }
}

/** Name, kind, size in pixels and on disk. */
@Composable
private fun DetailsDialog(uri: Uri, name: String, onDismiss: () -> Unit) {
    val c = Power.colors
    val context = LocalContext.current
    val arabic = context.resources.configuration.locales[0].language == "ar"
    val facts by produceState<ImageFacts?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { ImageKit.facts(context.contentResolver, uri, Files.size(context, uri)) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.img_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                @Composable
                fun row(label: String, value: String) {
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
                row(stringResource(R.string.info_name), bidi(name))
                facts?.let { f ->
                    row(stringResource(R.string.info_type), f.format.label)
                    row(stringResource(R.string.info_dimensions), bidi("${f.width} × ${f.height} px"))
                    if (f.bytes > 0) row(stringResource(R.string.info_file_size), Files.formatSize(f.bytes, arabic))
                }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.close), onDismiss) },
        containerColor = c.surface,
    )
}

private fun mimeOf(context: Context, uri: Uri): String =
    runCatching { context.contentResolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("image/") }
        ?: runCatching { context.contentResolver.openInputStream(uri)?.use { s -> ByteArray(64).let { b -> val n = s.read(b); if (n > 0) b.copyOf(n) else null } } }.getOrNull()
            ?.let { com.aamus.powerpdf.engine.img.ImgFormat.sniff(it)?.mime }
        ?: "image/*"

/** Hands a named copy of the picture to another app (this one left out of the list). */
private fun openElsewhere(context: Context, uri: Uri, name: String, launch: (Intent) -> Unit, failed: () -> Unit) {
    runCatching {
        val dir = File(Files.shareDir(context), UUID.randomUUID().toString()).apply { mkdirs() }
        val copy = File(dir, name.ifBlank { "picture" })
        context.contentResolver.openInputStream(uri)?.use { i -> copy.outputStream().use { i.copyTo(it) } }
        val shared = Files.shareUri(context, copy)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(shared, mimeOf(context, uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf<android.os.Parcelable>(ComponentName(context, MainActivity::class.java)))
        launch(chooser)
    }.onFailure { failed() }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

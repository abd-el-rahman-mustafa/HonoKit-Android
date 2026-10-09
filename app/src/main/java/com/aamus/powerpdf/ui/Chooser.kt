package com.aamus.powerpdf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.ChooserRequest
import com.aamus.powerpdf.Picked
import com.aamus.powerpdf.R
import com.aamus.powerpdf.data.Choosing
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.data.RecentFile
import com.aamus.powerpdf.ui.home.Thumb
import com.aamus.powerpdf.ui.home.subtitle
import com.aamus.powerpdf.ui.home.takeAccess
import com.aamus.powerpdf.ui.theme.Power

/**
 * One way into every tool that needs a file (decisions §45; UX review findings 8–9): a sheet
 * with HonoKit's own recent files of the right kind at the top (PDFs for PDF tools, pictures for
 * image tools), and Android's pickers below ("Browse phone…", and Photos for pictures). With no
 * recent file of that kind, Android's picker opens at once (nothing to choose from here); Back
 * from it leaves the person where they were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooserHost() {
    val model = LocalModel.current
    val context = LocalContext.current
    val request = model.chooser
    val all by model.recents.flow.collectAsStateWithLifecycle()
    // True while Android's picker was opened straight away (no sheet behind it to come back to).
    var direct by remember { mutableStateOf(false) }

    fun finish(picked: List<Picked>) {
        val r = model.chooser ?: return
        model.chooser = null
        direct = false
        if (picked.isNotEmpty()) r.done(picked)
    }

    fun systemResult(uris: List<android.net.Uri>, persist: Boolean) {
        if (uris.isEmpty()) {
            // Cancelled: back to the sheet, or (no sheet) back to where the tool was chosen.
            if (direct) finish(emptyList())
            return
        }
        if (persist) uris.forEach { takeAccess(context, it) }
        finish(uris.map { Picked.System(it) })
    }

    val openOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> systemResult(listOfNotNull(u), true) }
    val openMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { u -> systemResult(u, true) }
    val photoOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> systemResult(listOfNotNull(u), true) }
    val photoMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { u -> systemResult(u, true) }

    fun browse(r: ChooserRequest) {
        val types = if (r.kind == Choosing.Kind.Pdf) arrayOf(Files.PDF) else arrayOf("image/*")
        if (r.multiple) openMany.launch(types) else openOne.launch(types)
    }

    fun photos(r: ChooserRequest) {
        val ask = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (r.multiple) photoMany.launch(ask) else photoOne.launch(ask)
    }

    request ?: return
    val recents = remember(all, request.kind) { Choosing.recentsFor(request.kind, all) }

    // Nothing of this kind in Recent: the sheet would only hold the Browse buttons.
    LaunchedEffect(request) {
        if (recents.isEmpty()) {
            direct = true
            if (request.kind == Choosing.Kind.Image) photos(request) else browse(request)
        }
    }
    if (recents.isEmpty()) return

    var chosen by remember(request) { mutableStateOf(emptyList<String>()) }
    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            // "choose 0": the first recent file in the sheet (several: ticks it).
            "choose" -> recents.getOrNull(c.value.toIntOrNull() ?: 0)?.let { f ->
                if (request.multiple) chosen = Choosing.toggle(chosen, f.id) else finish(listOf(Picked.Recent(f)))
            }
            "choosego" -> finish(chosen.mapNotNull { id -> recents.firstOrNull { it.id == id } }.map { Picked.Recent(it) })
            "choosebrowse" -> browse(request)
            "chooseclose" -> finish(emptyList())
        }
    }

    val c = Power.colors
    // Opened in full: with several to tick, the Continue button must be on screen (it sat below a half-open sheet).
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val arabic = context.resources.configuration.locales[0].language == "ar"
    ModalBottomSheet(onDismissRequest = { finish(emptyList()) }, sheetState = sheet, containerColor = c.surface) {
        // Clear of the navigation bar: the last rows ran under the buttons.
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // What is being chosen, and for which tool.
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                request.tool?.let { t ->
                    ToolIcon(ToolCatalog.of(t), 36)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when {
                                request.kind == Choosing.Kind.Pdf && request.multiple -> R.string.choose_pdfs
                                request.kind == Choosing.Kind.Pdf -> R.string.choose_pdf
                                request.multiple -> R.string.choose_pictures
                                else -> R.string.choose_picture
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium, color = c.ink,
                    )
                    request.tool?.let { t -> Text(stringResource(ToolCatalog.of(t).title), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
                // Android's pickers first: one tap from anywhere on the phone (or the cloud apps).
                item {
                    if (request.kind == Choosing.Kind.Image) {
                        ListRow(stringResource(R.string.choose_photos), icon = R.drawable.ic_photo_library, iconTint = c.accent, onClick = { direct = false; photos(request) })
                        ListRow(stringResource(R.string.choose_browse_files), icon = R.drawable.ic_folder_open, iconTint = c.accent, onClick = { direct = false; browse(request) })
                    } else {
                        ListRow(stringResource(R.string.choose_browse), icon = R.drawable.ic_folder_open, iconTint = c.accent, onClick = { direct = false; browse(request) })
                    }
                    HorizontalDivider(Modifier.padding(top = 4.dp), color = c.line)
                    Text(
                        stringResource(R.string.choose_recent),
                        style = MaterialTheme.typography.titleSmall, color = c.muted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(recents, key = { it.id }) { r ->
                    ChooserRow(r, arabic, multiple = request.multiple, on = r.id in chosen) {
                        if (request.multiple) chosen = Choosing.toggle(chosen, r.id) else finish(listOf(Picked.Recent(r)))
                    }
                }
            }
            if (request.multiple) {
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                    PrimaryButton(
                        if (chosen.isEmpty()) stringResource(R.string.choose_none) else stringResource(R.string.choose_use, chosen.size),
                        { finish(chosen.mapNotNull { id -> recents.firstOrNull { it.id == id } }.map { Picked.Recent(it) }) },
                        Modifier.fillMaxWidth(),
                        enabled = chosen.isNotEmpty(),
                    )
                }
            }
        }
    }
}

/** A recent file in the chooser: its thumbnail, name, date and size; ticked when several are chosen. */
@Composable
private fun ChooserRow(r: RecentFile, arabic: Boolean, multiple: Boolean, on: Boolean, onClick: () -> Unit) {
    val c = Power.colors
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (multiple) Modifier.toggleable(value = on, role = Role.Checkbox, onValueChange = { onClick() }) else Modifier.clickable(onClick = onClick))
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(r, 36, 48)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(bidi(r.name), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.starred) {
                    Sym(R.drawable.ic_star, null, tint = c.warn, size = 14.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(subtitle(context, r, arabic), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
            }
        }
        if (multiple) {
            Spacer(Modifier.width(8.dp))
            Sym(if (on) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked, null, tint = if (on) c.accent else c.faint)
        }
    }
}

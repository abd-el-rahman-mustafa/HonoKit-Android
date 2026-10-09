package com.aamus.powerpdf.ui.home

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.PowerPdfApp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.Screen
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.data.Pins
import com.aamus.powerpdf.data.RecentFile
import com.aamus.powerpdf.data.SavedList
import com.aamus.powerpdf.ui.Adaptive
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.ListRow
import com.aamus.powerpdf.ui.LocalLauncher
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.SecondaryButton
import com.aamus.powerpdf.ui.Segmented
import com.aamus.powerpdf.ui.SheetTitle
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.ToolCatalog
import com.aamus.powerpdf.ui.ToolIcon
import com.aamus.powerpdf.ui.ToolInfo
import com.aamus.powerpdf.ui.ToolRow
import com.aamus.powerpdf.ui.ToolTile
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.isCompactHeight
import com.aamus.powerpdf.ui.isLargeFont
import com.aamus.powerpdf.ui.rememberToolCellMin
import com.aamus.powerpdf.ui.rememberWidestWord
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Home's tabs (decisions §42): Home, the two hubs (PDF tools, Image tools), Files. */
private enum class Tab { Home, Pdf, Images, Files }

/** Takes lasting read (and write, when granted) access to a file picked in the system picker. */
fun takeAccess(context: android.content.Context, uri: Uri) {
    val cr = context.contentResolver
    runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        .onFailure { runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
}

/** What happens when a tool is chosen: the chooser first when it needs a file (decisions §45). */
@Composable
fun rememberToolStarter(): (ToolId) -> Unit {
    val model = LocalModel.current
    return remember(model) { { tool: ToolId -> model.startTool(tool) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val model = LocalModel.current
    val context = LocalContext.current
    val tab = Tab.entries[model.homeTab.coerceIn(0, Tab.entries.lastIndex)]
    val startTool = rememberToolStarter()
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            takeAccess(context, uri)
            model.openUri(uri, persistable = true)
        }
    }
    // The Files tab's one Open: a PDF or a picture, each where it belongs (decisions §46).
    val openAny = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            takeAccess(context, uri)
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
            if (type.startsWith("image/")) model.openImages(listOf(uri)) else model.openUri(uri, persistable = true)
        }
    }
    // Pictures open in the image viewer (decisions §42), several side by side.
    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { takeAccess(context, it) }
            model.openImages(uris)
        }
    }
    val pickImages = { openImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val c = Power.colors
    // Home's search: every tool, PDF and image alike, with the keyboard up.
    var searching by rememberSaveable { mutableStateOf(false) }
    val searchingNow = searching && tab == Tab.Home
    BackHandler(enabled = searchingNow) { searching = false }
    // Back from another tab goes to Home first (the usual bottom-bar behaviour), then leaves.
    BackHandler(enabled = tab != Tab.Home) { model.homeTab = Tab.Home.ordinal }

    // A phone held sideways (compact height) or a wide window gets a navigation rail at the start
    // instead of the bottom bar, and a lower top bar (decisions §43; UX review finding 3).
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val rail = Adaptive.useRail(config.screenWidthDp, config.screenHeightDp)
    val compact = Adaptive.compactHeight(config.screenHeightDp)
    val tabs = listOf(
        Triple(Tab.Home, R.drawable.ic_home, R.string.home),
        Triple(Tab.Pdf, R.drawable.ic_picture_as_pdf, R.string.tab_pdf),
        Triple(Tab.Images, R.drawable.ic_image, R.string.tab_images),
        Triple(Tab.Files, R.drawable.ic_folder_open, R.string.tab_files),
    )
    fun select(t: Tab) {
        searching = false
        model.homeTab = t.ordinal
    }
    // The rail's own edge (the navigation buttons or a cut-out on that side) is its business.
    val railEdge = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)

    Row(Modifier.fillMaxSize().background(c.bg)) {
        if (rail) {
            NavigationRail(
                containerColor = c.surface,
                windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                modifier = Modifier.border(androidx.compose.foundation.BorderStroke(1.dp, c.line), androidx.compose.ui.graphics.RectangleShape),
            ) {
                val itemColors = NavigationRailItemDefaults.colors(
                    selectedIconColor = c.accent,
                    selectedTextColor = c.accent,
                    indicatorColor = c.accentSoft,
                    unselectedIconColor = c.muted,
                    unselectedTextColor = c.muted,
                )
                Spacer(Modifier.weight(1f))
                tabs.forEach { (t, icon, label) ->
                    NavigationRailItem(
                        tab == t,
                        { select(t) },
                        { Sym(icon, null) },
                        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = itemColors,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Scaffold(
            modifier = if (rail) Modifier.weight(1f).consumeWindowInsets(railEdge) else Modifier.weight(1f),
            containerColor = c.bg,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (searchingNow) IconAction(R.drawable.ic_arrow_back, stringResource(R.string.back), { searching = false })
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!searchingNow) {
                                Image(painterResource(R.drawable.logo), null, Modifier.size(if (compact) 24.dp else 30.dp))
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                when {
                                    searchingNow -> stringResource(R.string.all_tools)
                                    tab == Tab.Home -> stringResource(R.string.app_name)
                                    tab == Tab.Pdf -> stringResource(R.string.pdf_tools)
                                    tab == Tab.Images -> stringResource(R.string.group_images)
                                    else -> stringResource(R.string.tab_files)
                                },
                                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (tab == Tab.Home && !searchingNow) IconAction(R.drawable.ic_search, stringResource(R.string.search_tools), { searching = true })
                        IconAction(R.drawable.ic_settings, stringResource(R.string.settings), { model.push(Screen.Settings) })
                    },
                    // Sideways, the bar is a slim 48 dp so the content keeps the height.
                    expandedHeight = if (compact) 48.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg, scrolledContainerColor = c.surface),
                )
            },
            bottomBar = {
                if (!rail) NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
                    val itemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accent,
                        selectedTextColor = c.accent,
                        indicatorColor = c.accentSoft,
                        unselectedIconColor = c.muted,
                        unselectedTextColor = c.muted,
                    )
                    tabs.forEach { (t, icon, label) ->
                        NavigationBarItem(
                            tab == t,
                            { select(t) },
                            { Sym(icon, null) },
                            label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = itemColors,
                        )
                    }
                }
            },
            floatingActionButton = {
                // The Files tab's one Open (it had a big button and this one; UX review finding 7).
                if (tab == Tab.Files) {
                    // Named on the button itself: an extended FAB's text did not reach uiautomator (NAF), as the reader's Edit (§43).
                    val openLabel = stringResource(R.string.open)
                    ExtendedFloatingActionButton(
                        modifier = Modifier.semantics { contentDescription = openLabel },
                        onClick = { openAny.launch(arrayOf(Files.PDF, "image/*")) },
                        icon = { Sym(R.drawable.ic_file_open, null) },
                        text = { Text(stringResource(R.string.open)) },
                        containerColor = c.accent,
                        contentColor = c.accentInk,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                when {
                    searchingNow -> ToolsTab(startTool, ToolCatalog.groups.indices.toList(), R.string.search_tools, focusSearch = true)
                    tab == Tab.Home -> RecentTab(
                        startTool,
                        onOpen = { openPdf.launch(arrayOf(Files.PDF)) },
                        onOpenImage = pickImages,
                    )
                    tab == Tab.Pdf -> ToolsTab(startTool, ToolCatalog.pdfGroupIndices, R.string.search_pdf_tools)
                    tab == Tab.Images -> ToolsTab(startTool, ToolCatalog.imageGroupIndices, R.string.search_image_tools, onOpenImage = pickImages)
                    else -> FilesTab(onOpen = { openAny.launch(arrayOf(Files.PDF, "image/*")) }, onScan = { startTool(ToolId.Scan) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentTab(startTool: (ToolId) -> Unit, onOpen: () -> Unit, onOpenImage: () -> Unit) {
    val model = LocalModel.current
    val stored by model.recents.flow.collectAsStateWithLifecycle()
    // Each file once, the same rule as the chooser (decisions §48).
    val recents = remember(stored) { com.aamus.powerpdf.data.RecentList.distinct(stored) }
    val pinsStore = PowerPdfApp.instance.pins
    val pins by pinsStore.flow.collectAsStateWithLifecycle()
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    // Recent is split into PDFs and Images (owner, 2026-10-09); the last tab used is kept.
    val homePrefs = LocalContext.current.getSharedPreferences("home", android.content.Context.MODE_PRIVATE)
    var imagesTab by rememberSaveable { mutableStateOf(homePrefs.getBoolean("recentImages", false)) }
    val chooseTab: (Boolean) -> Unit = { imagesTab = it; homePrefs.edit().putBoolean("recentImages", it).apply() }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val compactHeight = isCompactHeight()
    var menuFor by remember { mutableStateOf<RecentFile?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var editPins by rememberSaveable { mutableStateOf(false) }
    val c = Power.colors
    val arabic = LocalContext.current.resources.configuration.locales[0].language == "ar"

    com.aamus.powerpdf.OnDebug { cmd ->
        when (cmd.cmd) {
            // "pins Sign,Merge": the pinned row; "editpins on": the sheet that changes it.
            "pins" -> pinsStore.set(cmd.value.split(',').map { it.trim() })
            "editpins" -> editPins = cmd.value != "off"
            // "pdfs" / "images": Recent's tab; "starred on|off".
            "recenttab" -> chooseTab(cmd.value == "images")
            "starred" -> starredOnly = cmd.value != "off"
        }
    }

    val shown = recents
        .filter { it.isImage == imagesTab }
        .filter { !starredOnly || it.starred }
        .filter { query.isBlank() || com.aamus.powerpdf.engine.TextLogic.foldString(it.name).contains(com.aamus.powerpdf.engine.TextLogic.foldString(query.trim())) }

    // Sideways or with large text, the big doors drop their second line (UX review findings 3, 5).
    val slimDoors = isCompactHeight() || isLargeFont()
    val pinned = pins.mapNotNull { id -> ToolId.entries.firstOrNull { it.name == id }?.let { ToolCatalog.of(it) } }

    /**
     * The doors and the pinned tools (decisions §44). With recent files on a phone held upright,
     * the doors are one compact row so Recent is on screen at once; the first time (nothing in
     * Recent) or beside Recent (a wide window), the four big doors with what each does.
     */
    fun LazyListScope.actions(wide: Boolean) {
        item {
            if (recents.isNotEmpty() && !wide) {
                DoorRow(startTool, onOpen, onOpenImage, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            } else {
                // Four doors (owner, 2026-10-04: a viewer as well as an editor, PDFs and pictures):
                // scan paper, open a PDF, open a picture, edit a photo.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionCard(ToolCatalog.scan, stringResource(R.string.tool_scan), stringResource(R.string.home_scan_sub).takeUnless { slimDoors }, { startTool(ToolId.Scan) }, Modifier.weight(1f).fillMaxHeight())
                        ActionCard(OPEN_PDF, stringResource(R.string.open_pdf), stringResource(R.string.home_open_sub).takeUnless { slimDoors }, onOpen, Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionCard(OPEN_IMAGE, stringResource(R.string.open_image), stringResource(R.string.home_image_sub).takeUnless { slimDoors }, onOpenImage, Modifier.weight(1f).fillMaxHeight())
                        ActionCard(ToolCatalog.imgEditor, stringResource(R.string.home_photo), stringResource(R.string.home_photo_sub).takeUnless { slimDoors }, { startTool(ToolId.ImgEditor) }, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        item {
            PinnedRow(pinned, startTool, onEdit = { editPins = true }, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        }
    }

    /** Recent files: the heading, All/Starred, the search when the list is long, the rows. */
    fun LazyListScope.recentFiles() {
        if (recents.isEmpty()) {
            item { EmptyRecent() }
            return
        }
        if (compactHeight) {
            // Sideways the height is short: the heading, Starred, search and clear share one line
            // (search opens in its place), so three or more rows show (phone test 2026-10-04: one did).
            item {
                CompactRecentHeader(
                    imagesTab = imagesTab, onTab = chooseTab,
                    starredOnly = starredOnly, onStarred = { starredOnly = it },
                    searchOpen = searchOpen, onSearchOpen = { searchOpen = it; if (!it) query = "" },
                    query = query, onQuery = { query = it },
                    onClear = { confirmClear = true },
                )
            }
        } else {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                StarredChip(starredOnly, { starredOnly = it })
                IconAction(R.drawable.ic_delete, stringResource(R.string.clear_recent), { confirmClear = true })
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Segmented(
                    listOf(false to stringResource(R.string.recent_tab_pdfs), true to stringResource(R.string.recent_tab_images)),
                    imagesTab,
                    chooseTab,
                )
                if (recents.size > 6) {
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.search_recent)) },
                        leadingIcon = { Sym(R.drawable.ic_search, null, tint = c.muted) },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = c.sunken,
                            unfocusedContainerColor = c.sunken,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            cursorColor = c.accent,
                        ),
                    )
                }
            }
        }
        }
        if (shown.isEmpty()) {
            item {
                Hint(
                    when {
                        query.isNotBlank() -> stringResource(R.string.search_none)
                        starredOnly -> stringResource(R.string.starred_empty)
                        imagesTab -> stringResource(R.string.recent_no_images)
                        else -> stringResource(R.string.recent_no_pdfs)
                    },
                    Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        items(shown, key = { it.id }) { r ->
            RecentRow(r, arabic, onClick = { model.openRecent(r) }, onMenu = { menuFor = r }, compact = compactHeight)
        }
    }

    // Wide enough (a phone sideways, a tablet): the doors and pinned tools on one side, Recent
    // files on the other, so the recent files are on screen at once (in landscape they were not at all).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= Adaptive.MEDIUM_WIDTH.dp) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) { actions(wide = true) }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 32.dp)) { recentFiles() }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                actions(wide = false)
                recentFiles()
            }
        }
    }

    if (editPins) EditPinsSheet(onDismiss = { editPins = false })

    menuFor?.let { r ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val launch = LocalLauncher.current
        ModalBottomSheet(onDismissRequest = { menuFor = null }, sheetState = sheet, containerColor = c.surface) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(r, 36, 48)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = c.ink)
                    Text(subtitle(LocalContext.current, r, arabic), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = c.line)
            ListRow(stringResource(R.string.open), icon = R.drawable.ic_open_in_new, onClick = { menuFor = null; model.openRecent(r) })
            ListRow(stringResource(R.string.share), icon = R.drawable.ic_share, onClick = {
                menuFor = null
                val uri = Uri.parse(r.uri)
                val cached = r.cached?.let { File(it) }
                if (r.isImage) {
                    // The private copy keeps the picture's real name; the original may no longer be readable.
                    val from = if (cached != null && cached.isFile) Uri.fromFile(cached) else uri
                    model.shareSaved(listOf(from), "image/*", launch)
                } else if (cached != null && cached.isFile) {
                    val dir = File(Files.shareDir(model.getApplication()), r.id).apply { mkdirs() }
                    val copy = File(dir, Files.ensurePdfName(r.name))
                    cached.copyTo(copy, overwrite = true)
                    model.shareFile(copy, launch)
                } else model.shareUris(listOf(uri), Files.PDF, launch)
            })
            ListRow(
                stringResource(if (r.starred) R.string.unstar else R.string.star),
                icon = R.drawable.ic_star,
                iconTint = if (r.starred) c.warn else androidx.compose.ui.graphics.Color.Unspecified,
                onClick = { menuFor = null; model.recents.updateFile(r.id) { it.copy(starred = !r.starred) } },
            )
            ListRow(stringResource(R.string.remove_recent), icon = R.drawable.ic_close, onClick = { menuFor = null; model.recents.removeFile(r.id) })
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_recent)) },
            text = { Text(stringResource(R.string.clear_recent_confirm)) },
            confirmButton = { DialogButton(stringResource(R.string.clear), { confirmClear = false; model.recents.clear() }, danger = true) },
            dismissButton = { DialogButton(stringResource(R.string.cancel), { confirmClear = false }) },
            containerColor = c.surface,
        )
    }
}

private val PDF_TINT = androidx.compose.ui.graphics.Color(0xFFE34850)
private val IMAGE_TINT = androidx.compose.ui.graphics.Color(0xFF1473E6)

/** "Open PDF" and "Open image" drawn like tools (their own icon and colour), for Home's big actions. */
private val OPEN_PDF = ToolInfo(ToolId.Images, R.drawable.ic_file_open, R.string.open_pdf, R.string.home_open_sub, PDF_TINT)
private val OPEN_IMAGE = ToolInfo(ToolId.Images, R.drawable.ic_photo_library, R.string.open_image, R.string.home_image_sub, IMAGE_TINT)
private val EDIT_PHOTO get() = ToolCatalog.imgEditor.copy(title = R.string.home_photo)

/** One of Home's big actions: the tool's icon beside a name and what it does. */
@Composable
private fun ActionCard(tool: ToolInfo, title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Power.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .clip(shape)
            .background(tool.tint.copy(alpha = if (c.isDark) 0.16f else 0.08f))
            .border(1.dp, tool.tint.copy(alpha = if (c.isDark) 0.32f else 0.22f), shape)
            .clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
            .heightIn(min = if (subtitle == null) 56.dp else 76.dp)
            .padding(horizontal = 12.dp, vertical = if (subtitle == null) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIcon(tool, if (subtitle == null) 36 else 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The four doors in one compact row (decisions §44): Scan, Open PDF, Open image, Edit photo, each
 * an icon over its name on a soft tile of its colour. Two rows of two only with large text.
 */
@Composable
private fun DoorRow(startTool: (ToolId) -> Unit, onOpen: () -> Unit, onOpenImage: () -> Unit, modifier: Modifier = Modifier) {
    val doors = listOf(
        ToolCatalog.scan to { startTool(ToolId.Scan) },
        OPEN_PDF to onOpen,
        OPEN_IMAGE to onOpenImage,
        EDIT_PHOTO to { startTool(ToolId.ImgEditor) },
    )
    val widest = rememberWidestWord(doors.map { it.first.title })
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = Adaptive.rowColumns(maxWidth.value, widest.value + 8f, doors.size)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            doors.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (tool, go) -> DoorTile(tool, go, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun DoorTile(tool: ToolInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Power.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(tool.tint.copy(alpha = if (c.isDark) 0.16f else 0.08f))
            .border(1.dp, tool.tint.copy(alpha = if (c.isDark) 0.32f else 0.22f), shape)
            .clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ToolIcon(tool, 40)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(tool.title),
            style = MaterialTheme.typography.labelMedium,
            color = c.ink,
            textAlign = TextAlign.Center,
            maxLines = if (isLargeFont()) 3 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Home's one row of pinned tools (decisions §44; UX review finding 6): the person's own, up to
 * four, changed by Edit or a long press (and from the hubs: long-press a tool to pin it). It
 * replaces the PDF tools and Image tools cards (eight tools and two "See all" links).
 */
@Composable
private fun PinnedRow(tools: List<ToolInfo>, startTool: (ToolId) -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val c = Power.colors
    val shape = RoundedCornerShape(18.dp)
    val editLabel = stringResource(R.string.pinned_edit)
    Column(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Sym(R.drawable.ic_push_pin, null, tint = c.muted, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.pinned_tools), style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) { Text(stringResource(R.string.edit), style = MaterialTheme.typography.labelLarge, color = c.accent, maxLines = 1) }
        }
        val add = ToolInfo(ToolId.Images, R.drawable.ic_add, R.string.pinned_add, R.string.pinned_add, c.muted)
        val slots: List<ToolInfo?> = tools + if (tools.size < Pins.MAX) listOf(null) else emptyList()
        val widest = rememberWidestWord(slots.map { it?.title ?: R.string.pinned_add })
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
            // Four across at normal text on a 360–375 dp phone; two rows of two with large text.
            val cols = Adaptive.rowColumns(maxWidth.value, widest.value, Pins.MAX)
            Column {
                slots.chunked(cols).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { t ->
                            if (t != null) ToolTile(t, { startTool(t.id) }, Modifier.weight(1f), onLongClick = onEdit, longClickLabel = editLabel, small = true)
                            else ToolTile(add, onEdit, Modifier.weight(1f), small = true)
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * Changes Home's pinned tools: the ones on Home (move, remove), then every other tool to add
 * while there is room. Kept on this phone only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPinsSheet(onDismiss: () -> Unit) {
    val model = LocalModel.current
    val store = PowerPdfApp.instance.pins
    val pins by store.flow.collectAsStateWithLifecycle()
    val c = Power.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val full = pins.size >= Pins.MAX
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        SheetTitle(stringResource(R.string.pinned_title))
        Hint(stringResource(R.string.pinned_hint), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { PinsHeading(stringResource(R.string.pinned_on_home)) }
            if (pins.isEmpty()) item { Hint(stringResource(R.string.pinned_none), Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            items(pins, key = { "on-$it" }) { id ->
                val t = ToolCatalog.of(ToolId.valueOf(id))
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(t, 36)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(t.title), style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val i = pins.indexOf(id)
                    IconAction(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.move_up), { store.set(Pins.move(pins, id, -1)) }, enabled = i > 0)
                    IconAction(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.move_down), { store.set(Pins.move(pins, id, 1)) }, enabled = i < pins.lastIndex)
                    IconAction(R.drawable.ic_close, stringResource(R.string.unpin), { store.set(Pins.toggle(pins, id)) })
                }
            }
            val groups = listOf(R.string.pdf_tools to ToolCatalog.pdfTools, R.string.group_images to ToolCatalog.imageTools)
            groups.forEach { (title, tools) ->
                item(key = "h-$title") {
                    PinsHeading(stringResource(title))
                    if (full) Hint(stringResource(R.string.pinned_full), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
                }
                items(tools.filter { it.id.name !in pins }, key = { "add-${it.id}" }) { t ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !full) { store.set(Pins.toggle(pins, t.id.name)) }
                            .heightIn(min = 56.dp).padding(start = 20.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolIcon(t, 36)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(t.title), style = MaterialTheme.typography.bodyLarge, color = if (full) c.faint else c.ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Sym(R.drawable.ic_add, stringResource(R.string.pin), tint = if (full) c.faint else c.accent)
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    SecondaryButton(stringResource(R.string.pinned_reset), { store.set(Pins.DEFAULT) }, enabled = pins != Pins.DEFAULT)
                }
            }
        }
    }
}

@Composable
private fun PinsHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Power.colors.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp))
}

/** No recent files yet: the big actions above already say what to do, so only a short note. */
@Composable
private fun EmptyRecent() {
    val c = Power.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sym(R.drawable.ic_history, null, tint = c.faint, size = 40.dp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.recent_empty_title), style = MaterialTheme.typography.titleMedium, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.recent_empty_short), style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodySmall, color = c.faint, textAlign = TextAlign.Center)
    }
}

@Composable
fun Thumb(r: RecentFile, w: Int, h: Int) {
    val model = LocalModel.current
    val c = Power.colors
    val image by produceState<ImageBitmap?>(null, r.id, r.openedAt) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(model.recents.thumbFile(r.id).absolutePath)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(w.dp, h.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (r.isImage) c.sunken else androidx.compose.ui.graphics.Color.White)
            .border(1.dp, c.line, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        // A page from its top; a picture from its middle.
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = if (r.isImage) Alignment.Center else Alignment.TopCenter)
        else if (r.isImage) Sym(R.drawable.ic_image, null, tint = IMAGE_TINT.copy(alpha = 0.7f), size = 20.dp)
        else Sym(R.drawable.ic_picture_as_pdf, null, tint = c.danger.copy(alpha = 0.7f), size = 20.dp)
    }
}

fun subtitle(context: android.content.Context, r: RecentFile, arabic: Boolean): String {
    val parts = ArrayList<String>()
    // Western digits in both languages, as the rest of the interface (Gulf usage).
    val locale = java.util.Locale.Builder().setLocale(context.resources.configuration.locales[0]).setUnicodeLocaleKeyword("nu", "latn").build()
    parts.add(
        when {
            DateUtils.isToday(r.openedAt) -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(java.util.Date(r.openedAt))
            DateUtils.isToday(r.openedAt + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.yesterday)
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date(r.openedAt))
        }
    )
    if (r.size > 0) parts.add(Files.formatSize(r.size, arabic))
    return parts.joinToString(" · ")
}

@Composable
private fun RecentRow(r: RecentFile, arabic: Boolean, onClick: () -> Unit, onMenu: () -> Unit, compact: Boolean = false) {
    val c = Power.colors
    Row(
        Modifier
            .fillMaxWidth()
            // A long press opens the same menu as the three dots (common file-list behaviour).
            .combinedClickable(onClick = onClick, onLongClick = onMenu, onLongClickLabel = stringResource(R.string.more))
            // Sideways: slimmer rows, so more of them fit the short height.
            .heightIn(min = if (compact) 60.dp else 72.dp)
            .padding(start = 20.dp, end = 4.dp, top = if (compact) 5.dp else 8.dp, bottom = if (compact) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact) Thumb(r, 36, 46) else Thumb(r, 40, 52)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // What it is, at a glance: a PDF or a picture (decisions §42).
                Sym(
                    if (r.isImage) R.drawable.ic_image else R.drawable.ic_picture_as_pdf,
                    stringResource(if (r.isImage) R.string.kind_image else R.string.kind_pdf),
                    tint = if (r.isImage) IMAGE_TINT else PDF_TINT,
                    size = 14.dp,
                )
                Spacer(Modifier.width(4.dp))
                if (r.starred) {
                    Sym(R.drawable.ic_star, null, tint = c.warn, size = 14.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(subtitle(LocalContext.current, r, arabic), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
            }
        }
        IconAction(R.drawable.ic_more_vert, stringResource(R.string.more), onMenu)
    }
}

/** Starred as a chip that stays on: Recent shows only starred files of the chosen tab. */
@Composable
private fun StarredChip(starredOnly: Boolean, onStarred: (Boolean) -> Unit) {
    val c = Power.colors
    val on = starredOnly
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (on) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (on) c.accent else c.line, CircleShape)
            .toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onStarred)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // With large text the word would squeeze the heading to "Re…": the star alone, still named.
        if (isLargeFont()) Sym(R.drawable.ic_star, stringResource(R.string.starred), tint = if (on) c.warn else c.muted, size = 20.dp)
        else {
            Sym(R.drawable.ic_star, null, tint = if (on) c.warn else c.muted, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.starred), style = MaterialTheme.typography.labelLarge, color = if (on) c.accent else c.ink, maxLines = 1)
        }
    }
}

/**
 * Recent files' heading when the phone is sideways (decisions §48): the PDFs | Images tabs, Starred,
 * search and clear on one line; search opens in the title's place with a close button.
 */
@Composable
private fun CompactRecentHeader(
    imagesTab: Boolean,
    onTab: (Boolean) -> Unit,
    starredOnly: Boolean,
    onStarred: (Boolean) -> Unit,
    searchOpen: Boolean,
    onSearchOpen: (Boolean) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
) {
    val c = Power.colors
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchOpen) {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.search_recent), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Sym(R.drawable.ic_search, null, tint = c.muted) },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.weight(1f).focusRequester(focus),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.sunken,
                    unfocusedContainerColor = c.sunken,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = c.accent,
                ),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            IconAction(R.drawable.ic_close, stringResource(R.string.clear_search), { onSearchOpen(false) })
            return@Row
        }
        // Sideways the PDFs | Images tabs take the heading's place: they say what the list is.
        Segmented(
            listOf(false to stringResource(R.string.recent_tab_pdfs), true to stringResource(R.string.recent_tab_images)),
            imagesTab,
            onTab,
            Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        StarredChip(starredOnly, onStarred)
        IconAction(R.drawable.ic_search, stringResource(R.string.search_recent), { onSearchOpen(true) })
        IconAction(R.drawable.ic_delete, stringResource(R.string.clear_recent), onClear)
    }
}

/**
 * A hub of tools (decisions §42): a search over the names and what each does (Arabic folded like
 * the recent files' search), chips that jump to a group when there are several, and the grid
 * by group. [groupIndices] picks the groups: PDF, images, or all of them for Home's search.
 * A long press on a tool pins it to Home or takes it off (decisions §44).
 */
@Composable
private fun ToolsTab(
    startTool: (ToolId) -> Unit,
    groupIndices: List<Int>,
    searchHint: Int,
    focusSearch: Boolean = false,
    onOpenImage: (() -> Unit)? = null,
) {
    val c = Power.colors
    val context = LocalContext.current
    val model = LocalModel.current
    val store = PowerPdfApp.instance.pins
    val pins by store.flow.collectAsStateWithLifecycle()
    // Each hub keeps its own search text.
    var query by rememberSaveable(groupIndices) { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusSearch) { if (focusSearch) focus.requestFocus() }
    val groups = groupIndices.map { ToolCatalog.groups[it].second }
    val titles = groupIndices.map { ToolCatalog.groupTitles[it] }
    val fold = com.aamus.powerpdf.engine.TextLogic::foldString
    // Scan ID card and Scan book are no tiles of their own, but a search still finds them.
    val extras = if (groupIndices.any { ToolCatalog.groupTitles[it] == R.string.group_create }) ToolCatalog.searchExtras else emptyList()
    val found = remember(query, groupIndices) {
        val q = fold(query.trim())
        if (q.isEmpty()) emptyList()
        else (groups.flatten() + extras).filter { fold(context.getString(it.title)).contains(q) || fold(context.getString(it.desc)).contains(q) }
            // Names first, then the ones that matched by what they do.
            .sortedBy { if (fold(context.getString(it.title)).contains(q)) 0 else 1 }
    }
    // Sideways (compact height) the search and the group chips share one line, so the grid keeps
    // room for several rows (UX review finding 3).
    val compact = isCompactHeight()
    // Where each group's heading sits in the grid, for the chips (one item before them when
    // the Open image row is there).
    val lead = if (onOpenImage != null) 1 else 0
    val starts = groups.runningFold(lead) { at, g -> at + 1 + g.size }
    val pinLabel = stringResource(R.string.pin)
    val unpinLabel = stringResource(R.string.unpin)

    fun togglePin(t: ToolInfo) {
        val id = t.id.name
        val before = pins
        if (id !in before && !Pins.canAdd(before, id)) {
            model.say(model.str(R.string.pinned_full), action = model.str(R.string.edit)) { model.homeTab = 0 }
            return
        }
        store.set(Pins.toggle(before, id))
        model.say(model.str(if (id in before) R.string.pinned_removed else R.string.pinned_added, model.str(t.title)), action = model.str(R.string.undo)) { store.set(before) }
    }

    @Composable
    fun Search(modifier: Modifier) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(searchHint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Sym(R.drawable.ic_search, null, tint = c.muted) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconAction(R.drawable.ic_close, stringResource(R.string.clear_search), { query = "" }) }
            } else null,
            singleLine = true,
            shape = CircleShape,
            modifier = modifier.focusRequester(focus),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.sunken,
                unfocusedContainerColor = c.sunken,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                cursorColor = c.accent,
            ),
        )
    }

    @Composable
    fun Chips(modifier: Modifier) {
        Row(
            modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            titles.forEachIndexed { gi, title ->
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.labelLarge,
                    color = c.ink,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, c.line, CircleShape)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { scope.launch { grid.animateScrollToItem(starts[gi]) } }
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Search(Modifier.weight(1f).widthIn(max = 320.dp).padding(start = 16.dp, end = if (groups.size > 1) 0.dp else 16.dp, top = 2.dp, bottom = 2.dp))
                if (groups.size > 1 && query.isBlank()) Chips(Modifier.weight(1.6f))
            }
        } else {
            Search(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (query.isNotBlank()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
                if (found.isEmpty()) item { Hint(stringResource(R.string.search_tools_none, query.trim()), Modifier.fillMaxWidth().padding(32.dp)) }
                items(found, key = { it.id.name }) { t -> ToolRow(t) { startTool(t.id) } }
            }
            return@Column
        }
        if (groups.size > 1 && !compact) Chips(Modifier.fillMaxWidth())
        LazyVerticalGrid(
            // Cells as wide as the longest word of any tool name at this font size (200 %:
            // fewer, wider columns instead of "Comme / nt"; UX review finding 5), and four
            // across on a 360–375 dp phone at normal text (phone test, sw375).
            columns = GridCells.Adaptive(minSize = rememberToolCellMin(ToolCatalog.all.map { it.title })),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
        ) {
            if (onOpenImage != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // The Images hub opens pictures too, not only tools (decisions §42).
                    ListRow(stringResource(R.string.open_image), description = stringResource(R.string.home_image_sub), icon = R.drawable.ic_photo_library, iconTint = IMAGE_TINT, onClick = onOpenImage)
                }
            }
            groups.forEachIndexed { gi, tools ->
                // One group (Images): the bar already names it.
                if (groups.size > 1) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(titles[gi]),
                        style = MaterialTheme.typography.titleSmall,
                        color = c.muted,
                        modifier = Modifier.padding(start = 8.dp, top = if (gi == 0) 8.dp else 20.dp, bottom = 4.dp),
                    )
                }
                items(tools, key = { it.id.name }) { t ->
                    val on = t.id.name in pins
                    ToolTile(t, { startTool(t.id) }, onLongClick = { togglePin(t) }, longClickLabel = if (on) unpinLabel else pinLabel, pinned = on)
                }
            }
        }
    }
}

/**
 * The Files tab (decisions §46; UX review finding 11): everything HonoKit made — PDFs in
 * Downloads › HonoKit and pictures in Pictures › HonoKit — newest first, with All / PDFs /
 * Pictures. One Open (the button at the bottom); when nothing is saved yet, Open and Scan.
 */
@Composable
private fun FilesTab(onOpen: () -> Unit, onScan: () -> Unit) {
    val model = LocalModel.current
    val context = LocalContext.current
    val launch = LocalLauncher.current
    val c = Power.colors
    val arabic = context.resources.configuration.locales[0].language == "ar"
    var show by rememberSaveable { mutableStateOf(SavedList.Show.All) }
    com.aamus.powerpdf.OnDebug { cmd -> if (cmd.cmd == "files") show = SavedList.Show.entries.firstOrNull { it.name.equals(cmd.value, true) } ?: SavedList.Show.All }
    // Read again after every save (and rename), so a file just made is listed.
    val saved by produceState<List<SavedFile>?>(null, model.savedVersion) {
        value = withContext(Dispatchers.IO) { SavedFiles.list(context) }
    }
    val all = saved ?: emptyList()
    val shown = SavedList.arrange(all, show, { it.kind }, { it.modified })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Segmented(
                listOf(SavedList.Show.All to stringResource(R.string.all), SavedList.Show.Pdfs to stringResource(R.string.files_pdfs), SavedList.Show.Pictures to stringResource(R.string.files_pictures)),
                show, { show = it },
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (saved != null && shown.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Sym(R.drawable.ic_folder_open, null, tint = c.faint, size = 40.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.files_empty_title), style = MaterialTheme.typography.titleMedium, color = c.ink, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.files_saved_empty), style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(stringResource(R.string.open), onOpen, icon = R.drawable.ic_file_open)
                        PrimaryButton(stringResource(R.string.tool_scan), onScan, icon = R.drawable.ic_photo_camera)
                    }
                }
            }
        }
        items(shown, key = { it.uri.toString() }) { f ->
            val picture = f.kind == SavedList.Kind.Picture
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (picture) {
                            // The pictures shown, side by side in the viewer, this one first.
                            val pics = shown.filter { it.kind == SavedList.Kind.Picture }.map { it.uri }
                            model.openImages(pics, pics.indexOf(f.uri).coerceAtLeast(0), record = false)
                        } else model.openUri(f.uri, persistable = true)
                    }
                    .heightIn(min = 68.dp)
                    .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SavedThumb(f)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(bidi(f.name), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Sym(
                            if (picture) R.drawable.ic_image else R.drawable.ic_picture_as_pdf,
                            stringResource(if (picture) R.string.kind_image else R.string.kind_pdf),
                            tint = if (picture) IMAGE_TINT else PDF_TINT,
                            size = 14.dp,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(savedSubtitle(context, f, arabic), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
                    }
                }
                IconAction(R.drawable.ic_share, stringResource(R.string.share), { model.shareSaved(listOf(f.uri), f.mime, launch) })
            }
        }
        // Kept clear of the Open button when the list is short.
        if (all.isNotEmpty()) item { Hint(stringResource(R.string.files_cloud_hint), Modifier.padding(start = 20.dp, end = 180.dp, top = 16.dp, bottom = 16.dp)) }
    }
}

/** A saved picture's own small picture; a PDF's icon (its pages are a tap away). */
@Composable
private fun SavedThumb(f: SavedFile) {
    val c = Power.colors
    val context = LocalContext.current
    val picture = f.kind == SavedList.Kind.Picture
    val image by produceState<ImageBitmap?>(null, f.uri) {
        if (picture) value = withContext(Dispatchers.IO) {
            runCatching { com.aamus.powerpdf.engine.img.ImageKit.decode(context.contentResolver, f.uri, 160).asImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier.size(40.dp, 48.dp).clip(RoundedCornerShape(4.dp)).background(if (picture) c.sunken else androidx.compose.ui.graphics.Color.White).border(1.dp, c.line, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        when {
            img != null -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            picture -> Sym(R.drawable.ic_image, null, tint = IMAGE_TINT.copy(alpha = 0.7f), size = 20.dp)
            else -> Sym(R.drawable.ic_picture_as_pdf, null, tint = c.danger.copy(alpha = 0.7f), size = 20.dp)
        }
    }
}

/** A file the app saved: where it is, its name, when it was last changed, its size and kind. */
data class SavedFile(val uri: Uri, val name: String, val modified: Long, val size: Long, val kind: SavedList.Kind, val mime: String)

private fun savedSubtitle(context: android.content.Context, f: SavedFile, arabic: Boolean): String {
    val locale = java.util.Locale.Builder().setLocale(context.resources.configuration.locales[0]).setUnicodeLocaleKeyword("nu", "latn").build()
    val parts = ArrayList<String>()
    if (f.modified > 0) parts.add(
        if (DateUtils.isToday(f.modified)) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(java.util.Date(f.modified))
        else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date(f.modified))
    )
    if (f.size > 0) parts.add(Files.formatSize(f.size, arabic))
    return parts.joinToString(" · ")
}

/**
 * What the app saved (it may read its own files without a permission): PDFs in Downloads ›
 * HonoKit (or › HonoPDF before the rename) and pictures in Pictures › HonoKit.
 */
object SavedFiles {
    private fun folders() = listOf(Files.FOLDER) + Files.OLD_FOLDERS

    fun list(context: android.content.Context): List<SavedFile> {
        val out = ArrayList<SavedFile>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val where = folders().joinToString(" OR ") { "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
            val args = folders().map { "%/$it/%" }.toTypedArray()
            query(context, android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, where, args, out)
            // Pictures › HonoKit › Compressed, Edited… (and PDF pages as pictures, in their own folder).
            val picArgs = folders().map { "${android.os.Environment.DIRECTORY_PICTURES}/$it/%" }.toTypedArray()
            query(context, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, where, picArgs, out)
        } else {
            @Suppress("DEPRECATION")
            val roots = listOf(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            )
            roots.flatMap { root -> folders().map { File(root, it) } }.forEach { dir ->
                dir.walkTopDown().maxDepth(3).filter { it.isFile }.forEach { f ->
                    val kind = SavedList.kindOf(f.name, null) ?: return@forEach
                    out.add(SavedFile(Uri.fromFile(f), f.name, f.lastModified(), f.length(), kind, if (kind == SavedList.Kind.Pdf) Files.PDF else "image/*"))
                }
            }
        }
        // One file listed once, newest first.
        return out.distinctBy { it.uri }.sortedByDescending { it.modified }
    }

    private fun query(context: android.content.Context, coll: Uri, where: String, args: Array<String>, out: MutableList<SavedFile>) {
        runCatching {
            context.contentResolver.query(
                coll,
                arrayOf(
                    android.provider.MediaStore.MediaColumns._ID,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                    android.provider.MediaStore.MediaColumns.SIZE,
                    android.provider.MediaStore.MediaColumns.MIME_TYPE,
                ),
                where, args,
                "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(1) ?: continue
                    val mime = cur.getString(4)
                    // Text and Markdown exports live there too; this list is for PDFs and pictures.
                    val kind = SavedList.kindOf(name, mime) ?: continue
                    out.add(SavedFile(android.content.ContentUris.withAppendedId(coll, cur.getLong(0)), name, cur.getLong(2) * 1000, cur.getLong(3), kind, mime ?: if (kind == SavedList.Kind.Pdf) Files.PDF else "image/*"))
                }
            }
        }.onFailure { android.util.Log.w("PowerPDF", "saved files", it) }
    }
}

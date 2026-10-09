package com.aamus.powerpdf.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.ui.theme.Power

/** Every tool, in the groups the Tools tab and the viewer's "All tools" sheet show. */
data class ToolInfo(
    val id: ToolId,
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val desc: Int,
    /** Acrobat gives each tool its own colour in lists (research-acrobat-mobile.md). */
    val tint: Color,
)

object ToolCatalog {
    val comment = ToolInfo(ToolId.Comment, R.drawable.ic_ink_highlighter, R.string.tool_comment, R.string.tool_comment_desc, Color(0xFFE68619))
    val sign = ToolInfo(ToolId.Sign, R.drawable.ic_signature, R.string.tool_sign, R.string.tool_sign_desc, Color(0xFF7E57E6))
    val addText = ToolInfo(ToolId.AddText, R.drawable.ic_text_fields, R.string.tool_add_text, R.string.tool_add_text_desc, Color(0xFF1473E6))
    val editText = ToolInfo(ToolId.EditText, R.drawable.ic_edit, R.string.tool_edit_text, R.string.tool_edit_text_desc, Color(0xFF0D66D0))
    val erase = ToolInfo(ToolId.Erase, R.drawable.ic_scan_delete, R.string.tool_erase, R.string.tool_erase_desc, Color(0xFFE34850))
    val forms = ToolInfo(ToolId.Forms, R.drawable.ic_form, R.string.tool_forms, R.string.tool_forms_desc, Color(0xFF7E57E6))
    val redact = ToolInfo(ToolId.Redact, R.drawable.ic_redact, R.string.tool_redact, R.string.tool_redact_desc, Color(0xFF2C2C2C))
    val organize = ToolInfo(ToolId.Organize, R.drawable.ic_grid_view, R.string.tool_organize, R.string.tool_organize_desc, Color(0xFF2D9D78))
    val crop = ToolInfo(ToolId.Crop, R.drawable.ic_crop, R.string.tool_crop, R.string.tool_crop_desc, Color(0xFF2D9D78))
    val merge = ToolInfo(ToolId.Merge, R.drawable.ic_merge, R.string.tool_merge, R.string.tool_merge_desc, Color(0xFFD83790))
    val split = ToolInfo(ToolId.Split, R.drawable.ic_content_cut, R.string.tool_split, R.string.tool_split_desc, Color(0xFFE34850))
    val images = ToolInfo(ToolId.Images, R.drawable.ic_add_photo_alternate, R.string.tool_images, R.string.tool_images_desc, Color(0xFF0D66D0))
    val scan = ToolInfo(ToolId.Scan, R.drawable.ic_photo_camera, R.string.tool_scan, R.string.tool_scan_desc, Color(0xFF0FA5A0))
    val scanId = ToolInfo(ToolId.ScanId, R.drawable.ic_id_card, R.string.tool_scan_id, R.string.tool_scan_id_desc, Color(0xFF0D8FB3))
    val scanBook = ToolInfo(ToolId.ScanBook, R.drawable.ic_menu_book, R.string.tool_scan_book, R.string.tool_scan_book_desc, Color(0xFFE34850))
    val blank = ToolInfo(ToolId.Blank, R.drawable.ic_note_add, R.string.tool_blank, R.string.tool_blank_desc, Color(0xFF5A5FF0))
    val compress = ToolInfo(ToolId.Compress, R.drawable.ic_compress, R.string.tool_compress, R.string.tool_compress_desc, Color(0xFFCB6F10))
    val protect = ToolInfo(ToolId.Protect, R.drawable.ic_lock, R.string.tool_protect, R.string.tool_protect_desc, Color(0xFF5A5FF0))
    val unlock = ToolInfo(ToolId.Unlock, R.drawable.ic_lock_open, R.string.tool_unlock, R.string.tool_unlock_desc, Color(0xFF6D7380))
    val toImages = ToolInfo(ToolId.ToImages, R.drawable.ic_image, R.string.tool_to_images, R.string.tool_to_images_desc, Color(0xFF0FA5A0))
    val toText = ToolInfo(ToolId.ToText, R.drawable.ic_description, R.string.tool_to_text, R.string.tool_to_text_desc, Color(0xFF6D7380))
    val compare = ToolInfo(ToolId.Compare, R.drawable.ic_compare, R.string.tool_compare, R.string.tool_compare_desc, Color(0xFFCB6F10))
    val recognize = ToolInfo(ToolId.Recognize, R.drawable.ic_ocr, R.string.tool_recognize, R.string.tool_recognize_desc, Color(0xFF2D9D78))
    val imageToText = ToolInfo(ToolId.ImageToText, R.drawable.ic_text_fields, R.string.tool_image_text, R.string.tool_image_text_desc, Color(0xFF0D8FB3))
    val watermark = ToolInfo(ToolId.Watermark, R.drawable.ic_branding_watermark, R.string.tool_watermark, R.string.tool_watermark_desc, Color(0xFF9256D9))
    // Image tools (decisions §34)
    val imgCompress = ToolInfo(ToolId.ImgCompress, R.drawable.ic_compress, R.string.tool_img_compress, R.string.tool_img_compress_desc, Color(0xFF2D9D78))
    val imgResize = ToolInfo(ToolId.ImgResize, R.drawable.ic_open_with, R.string.tool_img_resize, R.string.tool_img_resize_desc, Color(0xFF0D8FB3))
    val imgCrop = ToolInfo(ToolId.ImgCrop, R.drawable.ic_crop, R.string.tool_img_crop, R.string.tool_img_crop_desc, Color(0xFF9256D9))
    val imgConvert = ToolInfo(ToolId.ImgConvert, R.drawable.ic_swap_vert, R.string.tool_img_convert, R.string.tool_img_convert_desc, Color(0xFF1473E6))
    val imgRotate = ToolInfo(ToolId.ImgRotate, R.drawable.ic_rotate_right, R.string.tool_img_rotate, R.string.tool_img_rotate_desc, Color(0xFF0FA5A0))
    val imgWatermark = ToolInfo(ToolId.ImgWatermark, R.drawable.ic_stamp, R.string.tool_img_watermark, R.string.tool_img_watermark_desc, Color(0xFFD83790))
    val imgInfo = ToolInfo(ToolId.ImgInfo, R.drawable.ic_info, R.string.tool_img_info, R.string.tool_img_info_desc, Color(0xFF5A5FF0))
    val imgUpscale = ToolInfo(ToolId.ImgUpscale, R.drawable.ic_zoom_in, R.string.tool_img_upscale, R.string.tool_img_upscale_desc, Color(0xFF2D9D78))
    val imgRemoveBg = ToolInfo(ToolId.ImgRemoveBg, R.drawable.ic_auto_fix_high, R.string.tool_img_removebg, R.string.tool_img_removebg_desc, Color(0xFF6E9A1E))
    val imgEditor = ToolInfo(ToolId.ImgEditor, R.drawable.ic_tune, R.string.tool_img_editor, R.string.tool_img_editor_desc, Color(0xFF7E57E6))
    val imgBlur = ToolInfo(ToolId.ImgBlur, R.drawable.ic_visibility_off, R.string.tool_img_blur, R.string.tool_img_blur_desc, Color(0xFF0D66D0))
    val pageNumbers = ToolInfo(ToolId.PageNumbers, R.drawable.ic_format_list_numbered, R.string.tool_page_numbers, R.string.tool_page_numbers_desc, Color(0xFF2680EB))

    val groups: List<Pair<Int, List<ToolInfo>>> = listOf(
        R.string.tool_comment to listOf(comment, sign, forms, editText, addText, erase),
        R.string.tool_organize to listOf(organize, crop, merge, split),
        R.string.tool_images to listOf(images, scan, blank),
        R.string.tool_protect to listOf(compress, protect, unlock, redact),
        R.string.tool_watermark to listOf(watermark, pageNumbers),
        R.string.group_convert to listOf(recognize, imageToText, toText, toImages, compare),
        R.string.group_images to listOf(imgEditor, imgCompress, imgResize, imgCrop, imgConvert, imgRotate, imgWatermark, imgUpscale, imgRemoveBg, imgBlur, imgInfo),
    )

    /** Group headings are their own strings (a tool's name would read oddly as a heading). */
    val groupTitles = listOf(R.string.group_comment_sign, R.string.group_organize, R.string.group_create, R.string.group_protect, R.string.group_stamp, R.string.group_convert, R.string.group_images)

    /**
     * Not tiles of their own any more (decisions §44; UX review finding 7): the scanner's own
     * Document / ID card / Book switch covers them. A search for "ID" or "book" still finds them.
     */
    val searchExtras: List<ToolInfo> = listOf(scanId, scanBook)

    val all: List<ToolInfo> = groups.flatMap { it.second } + searchExtras

    /**
     * The two hubs (decisions §42): the PDF tab has every group but the image tools (Scan, Images
     * to PDF and Image to text stay here: they make PDFs or read them); the Images tab has those.
     */
    val pdfGroupIndices: List<Int> = groups.indices.filter { groupTitles[it] != R.string.group_images }
    val imageGroupIndices: List<Int> = groups.indices.filter { groupTitles[it] == R.string.group_images }
    val pdfTools: List<ToolInfo> = pdfGroupIndices.flatMap { groups[it].second }
    val imageTools: List<ToolInfo> = imageGroupIndices.flatMap { groups[it].second }

    fun of(id: ToolId): ToolInfo = all.first { it.id == id }
}

/** A tool's coloured icon on a soft tile of its own colour. */
@Composable
fun ToolIcon(tool: ToolInfo, size: Int = 44) {
    val dark = Power.colors.isDark
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3.4f).dp))
            .background(tool.tint.copy(alpha = if (dark) 0.22f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Sym(tool.icon, null, tint = if (dark) lighten(tool.tint) else tool.tint, size = (size * 0.55f).dp)
    }
}

private fun lighten(c: Color): Color = Color(
    red = c.red + (1 - c.red) * 0.35f,
    green = c.green + (1 - c.green) * 0.35f,
    blue = c.blue + (1 - c.blue) * 0.35f,
)

/**
 * A tile in the tools grid: icon above a label of up to two lines (three with large text). The
 * grid makes the tile at least as wide as the longest word (rememberToolCellMin), so a word is
 * never broken in the middle.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ToolTile(
    tool: ToolInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A long press (pin to Home in the hubs, change the pins on Home; decisions §44). */
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    /** Shows a small pin: this tool is on Home. */
    pinned: Boolean = false,
    /** Smaller icon and padding (Home's one row). */
    small: Boolean = false,
) {
    // Sideways, smaller tiles so the grid shows more than one row (decisions §43).
    val compact = isCompactHeight() || small
    val large = isLargeFont()
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
                else Modifier.clickable(onClick = onClick)
            )
            .padding(vertical = if (compact) 6.dp else 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ToolIcon(tool, if (compact) 44 else 52)
            if (pinned) {
                val pinLabel = stringResource(R.string.pinned_mark)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp)
                        .size(18.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Power.colors.surface)
                        .semantics { contentDescription = pinLabel },
                    contentAlignment = Alignment.Center,
                ) { Sym(R.drawable.ic_push_pin, null, tint = Power.colors.accent, size = 12.dp) }
            }
        }
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        Text(
            stringResource(tool.title),
            style = MaterialTheme.typography.labelMedium,
            color = Power.colors.ink,
            textAlign = TextAlign.Center,
            maxLines = if (large) 3 else 2,
            overflow = TextOverflow.Ellipsis,
            // Two lines kept for every label so the rows line up; with large text the cells are
            // tall anyway, and an empty second line only pushed the next row away.
            minLines = if (large) 1 else 2,
        )
    }
}

/** A row in the "All tools" sheet: icon, name, one-line hint. */
@Composable
fun ToolRow(tool: ToolInfo, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Power.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        ToolIcon(tool, 40)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(tool.title), style = MaterialTheme.typography.bodyLarge, color = Power.colors.ink)
            Text(stringResource(tool.desc), style = MaterialTheme.typography.bodySmall, color = Power.colors.muted)
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Sym(R.drawable.ic_check, null, tint = Power.colors.accent)
        }
    }
}

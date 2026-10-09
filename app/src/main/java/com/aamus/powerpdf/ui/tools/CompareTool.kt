package com.aamus.powerpdf.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.Change
import com.aamus.powerpdf.engine.PdfEngine
import com.aamus.powerpdf.engine.TextDiff
import com.aamus.powerpdf.engine.words
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.bidi
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.launch

/**
 * Compare two PDFs (desktop features, §45 there): the open document's words against another's,
 * folded for Arabic; each change shown with a little context, removed words struck through in red
 * and added words in green.
 */
@Composable
fun CompareTool(session: DocumentSession) {
    val model = LocalModel.current
    val scope = rememberCoroutineScope()
    val name by session.name.collectAsStateWithLifecycle()
    var otherName by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<List<Change>?>(null) }
    var tooDifferent by remember { mutableStateOf(false) }

    fun compare(uri: Uri) {
        scope.launch {
            val opened = model.openInput(uri) ?: return@launch
            val (other, file) = opened
            try {
                otherName = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Files.displayName(model.getApplication(), uri) }
                model.busy = model.str(R.string.comparing)
                val a = session.engine { it.words() }
                val b = PdfEngine.run { other.words() }
                val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { TextDiff.changes(a, b) }
                tooDifferent = found == null
                result = found ?: emptyList()
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                model.busy = null
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { PdfEngine.run { other.close() } }
                file.delete()
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) compare(uri) }

    /** A change card opens this document at its page: back to the viewer under this tool, or a new one. */
    fun showPage(p: Int) {
        model.rememberPage(session, p)
        val below = model.stack.getOrNull(model.stack.size - 2)
        if (below is com.aamus.powerpdf.Screen.Viewer && below.sessionId == session.id) model.pop()
        else model.push(com.aamus.powerpdf.Screen.Viewer(session.id))
    }

    com.aamus.powerpdf.OnDebug { c -> if (c.cmd == "compare") compare(Uri.fromFile(java.io.File(c.value))) }

    ToolFrame(ToolId.Compare, subtitle = name, bottom = {
        PrimaryButton(stringResource(if (result == null) R.string.compare_choose else R.string.compare_other), { pick.launch(arrayOf(Files.PDF)) }, Modifier.fillMaxWidth())
    }) {
        val c = Power.colors
        val list = result
        if (list == null) {
            Column(Modifier.fillMaxSize().padding(20.dp)) { Hint(stringResource(R.string.compare_hint)) }
            return@ToolFrame
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    bidi(stringResource(R.string.compare_with, otherName.orEmpty())),
                    style = MaterialTheme.typography.labelLarge,
                    color = c.muted,
                )
                Text(
                    if (tooDifferent) stringResource(R.string.compare_too_different) else if (list.isEmpty()) stringResource(R.string.compare_same) else pluralStringResource(R.plurals.compare_changes, list.size, list.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(list) { ch ->
                val removedStyle = SpanStyle(color = Color(0xFFC62828), background = Color(0x22E34850), textDecoration = TextDecoration.LineThrough)
                val addedStyle = SpanStyle(color = Color(0xFF1B7A55), background = Color(0x222D9D78))
                val text = buildAnnotatedString {
                    if (ch.before.isNotBlank()) {
                        withStyle(SpanStyle(color = c.muted)) { append("…" + ch.before + " ") }
                    }
                    if (ch.removed.isNotBlank()) withStyle(removedStyle) { append(ch.removed) }
                    if (ch.removed.isNotBlank() && ch.added.isNotBlank()) append(' ')
                    if (ch.added.isNotBlank()) withStyle(addedStyle) { append(ch.added) }
                    if (ch.after.isNotBlank()) {
                        withStyle(SpanStyle(color = c.muted)) { append(" " + ch.after + "…") }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = stringResource(R.string.open)) { showPage(ch.oldPage) }
                        .padding(14.dp),
                ) {
                    Text(
                        if (ch.oldPage == ch.newPage) stringResource(R.string.page_n, ch.oldPage + 1) else stringResource(R.string.compare_pages, ch.oldPage + 1, ch.newPage + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = c.muted,
                    )
                    Text(text, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), color = c.ink, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the privacy policy from the app's assets, where the build copies
 * PRIVACY.md from the repository root (see app/build.gradle.kts). Google
 * Play's User Data policy wants the policy reachable from inside the app,
 * as a link or as text; text keeps the app offline.
 */
@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val document by produceState<PolicyDocument?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadPolicy(context) }
    }
    // A plain Dialog with its own layout: AlertDialog sizes its text slot
    // to the content and fights a fixed height, and its default width
    // shows four lines in landscape. The text scrolls between a fixed
    // title and a fixed button row.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.9f),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    document?.title ?: "Privacy Policy",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    for (block in document?.blocks.orEmpty()) {
                        when (block) {
                            is PolicyBlock.Heading -> Text(
                                block.text,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                            is PolicyBlock.Paragraph -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            is PolicyBlock.Bullet -> Row(Modifier.padding(bottom = 6.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Text(block.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

class PolicyDocument(val title: String, val blocks: List<PolicyBlock>)

sealed class PolicyBlock {
    class Heading(val text: String) : PolicyBlock()
    class Paragraph(val text: AnnotatedString) : PolicyBlock()
    class Bullet(val text: AnnotatedString) : PolicyBlock()
}

private fun loadPolicy(context: Context): PolicyDocument {
    val markdown = context.assets.open("PRIVACY.md").bufferedReader().use { it.readText() }
    return parsePolicy(markdown)
}

/**
 * The subset of Markdown the policy file uses: "#" and "##" headings,
 * paragraphs, "-" bullets with indented continuation lines, and inline
 * `code`, **bold** and *italic*. The policy deliberately uses no tables or
 * link syntax so that this stays small.
 */
fun parsePolicy(markdown: String): PolicyDocument {
    var title = "Privacy Policy"
    val blocks = mutableListOf<PolicyBlock>()
    val paragraph = StringBuilder()
    var bullet: StringBuilder? = null

    fun flush() {
        bullet?.let { blocks += PolicyBlock.Bullet(inline(it.toString())) }
        bullet = null
        if (paragraph.isNotEmpty()) {
            blocks += PolicyBlock.Paragraph(inline(paragraph.toString()))
            paragraph.clear()
        }
    }

    for (raw in markdown.lines()) {
        val line = raw.trimEnd()
        when {
            line.isBlank() -> flush()
            line.startsWith("# ") -> { flush(); title = line.removePrefix("# ").trim() }
            line.startsWith("## ") -> { flush(); blocks += PolicyBlock.Heading(line.removePrefix("## ").trim()) }
            line.startsWith("- ") -> { flush(); bullet = StringBuilder(line.removePrefix("- ").trim()) }
            bullet != null && raw.startsWith("  ") -> bullet!!.append(' ').append(line.trim())
            else -> {
                if (bullet != null) flush()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
    }
    flush()
    return PolicyDocument(title, blocks)
}

private val inlineMarkup = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*")

private fun inline(text: String): AnnotatedString = buildAnnotatedString { appendInline(text) }

/** Styled spans may nest (code inside italic), so the inner text is parsed again. */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var last = 0
    for (match in inlineMarkup.findAll(text)) {
        append(text.substring(last, match.range.first))
        val (code, bold, italic) = match.destructured
        when {
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(bold) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(italic) }
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

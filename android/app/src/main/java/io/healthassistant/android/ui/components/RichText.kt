package io.healthassistant.android.ui.components

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.viewinterop.AndroidView
import io.healthassistant.shared.richtext.TextFormat
import io.healthassistant.shared.richtext.detectTextFormat
import io.healthassistant.shared.richtext.htmlToMarkdown
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Renders the platform's mixed rich-text content (HTML from the Quill editor,
 * Markdown from LLM extraction, or plain text) with a single safe pipeline:
 * HTML is converted to Markdown by the shared converter (script/style blocks
 * structurally removed, attributes stripped, href schemes allowlisted), then
 * Markwon renders it into a TextView. Server content never touches a WebView;
 * links are resolved by the default Markwon link resolver (system intent).
 */
@Composable
fun RichText(
    text: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    if (text.isNullOrBlank()) return
    val context = LocalContext.current
    val content = remember(text) { normalize(text) }
    val textColor = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val fontSize = if (style.fontSize.isSpecified && style.fontSize.isSp) style.fontSize.value else 14f
    val lineHeightFactor =
        if (style.lineHeight != null && style.lineHeight.isSpecified && style.lineHeight.isSp && fontSize > 0f) {
            (style.lineHeight.value / fontSize).coerceAtLeast(1f)
        } else {
            1.3f
        }
    val markwon =
        remember(context) {
            Markwon
                .builder(context)
                .usePlugin(TablePlugin.create(context))
                .usePlugin(LinkifyPlugin.create())
                .build()
        }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod()
                setTextIsSelectable(true)
            }
        },
        update = { tv ->
            tv.textSize = fontSize
            tv.setTextColor(textColor.toArgb())
            tv.setLinkTextColor(linkColor.toArgb())
            tv.setLineSpacing(0f, lineHeightFactor)
            markwon.setMarkdown(tv, content)
        },
    )
}

private fun normalize(text: String): String =
    when (detectTextFormat(text)) {
        TextFormat.HTML -> htmlToMarkdown(text)
        else -> text
    }

package io.healthassistant.android.ui.components

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.healthassistant.shared.richtext.TextFormat
import io.healthassistant.shared.richtext.detectTextFormat
import io.healthassistant.shared.richtext.htmlToMarkdown
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R1 gate (Robolectric): the RichText pipeline end-to-end — detect →
 * htmlToMarkdown → Markwon spans on a real TextView. The composable wraps
 * this exact pipeline in an AndroidView; interop-view text is not part of the
 * Compose semantics tree, so we assert on the TextView directly (the shared
 * golden tests already pin detection + conversion).
 */
@RunWith(RobolectricTestRunner::class)
class RichTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val markwon =
        Markwon
            .builder(context)
            .usePlugin(TablePlugin.create(context))
            .build()

    private fun render(raw: String): String {
        val md =
            when (detectTextFormat(raw)) {
                TextFormat.HTML -> htmlToMarkdown(raw)
                else -> raw
            }
        val tv = TextView(context)
        markwon.setMarkdown(tv, md)
        return tv.text.toString()
    }

    @Test
    fun markdownBoldRendersWithoutMarkers() {
        val out = render("Report: **critical** finding")
        assertFalse(out.contains("**"))
        assertTrue(out.contains("critical"))
    }

    @Test
    fun markdownHeadingAndListRender() {
        val out = render("## Summary\n\n- item one\n- item two")
        assertFalse(out.contains("##"))
        assertTrue(out.contains("Summary"))
        assertTrue(out.contains("item one"))
        assertTrue(out.contains("item two"))
    }

    @Test
    fun htmlNotesRenderAsRichText() {
        val out = render("<p>Fasting panel with <strong>Minor</strong> fatigue</p>")
        assertFalse(out.contains("<"))
        assertTrue(out.contains("Fasting panel with Minor fatigue"))
    }

    @Test
    fun htmlListConvertsToMarkdownList() {
        val out = render("<ul><li>avoid dairy</li><li>rest 2 days</li></ul>")
        assertFalse(out.contains("<"))
        assertTrue(out.contains("avoid dairy"))
        assertTrue(out.contains("rest 2 days"))
    }

    @Test
    fun scriptContentNeverRenders() {
        val out = render("<p>safe</p><script>alert('x')</script>")
        assertFalse(out.contains("<"))
        assertFalse(out.contains("alert"))
        assertEquals("safe", out.trim())
    }

    @Test
    fun htmlTableNeverLeaksRawTags() {
        // Markwon renders GFM tables into a child view (tv.text is a
        // placeholder), so assert tag-stripping here; the HTML→GFM
        // conversion itself is golden-tested in shared HtmlToMarkdownTest.
        val out = render("<table><tr><th>Biomarker</th><th>Value</th></tr><tr><td>LDL</td><td>3.9</td></tr></table>")
        assertFalse(out.contains("<"))
        assertFalse(out.contains("table"))
    }

    @Test
    fun plainTextPassesThrough() {
        assertEquals("Plain fasting panel note", render("Plain fasting panel note"))
    }
}

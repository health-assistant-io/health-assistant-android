package io.healthassistant.shared.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class TextFormatTest {
    @Test
    fun `null and blank detect as plain`() {
        assertEquals(TextFormat.PLAIN, detectTextFormat(null))
        assertEquals(TextFormat.PLAIN, detectTextFormat(""))
        assertEquals(TextFormat.PLAIN, detectTextFormat("   \n  "))
    }

    @Test
    fun `html wins over markdown`() {
        val quill = "<p>Some <strong>bold</strong> text</p>"
        assertEquals(TextFormat.HTML, detectTextFormat(quill))
        val htmlWithMd = "<p>**not markdown** when inside html</p>"
        assertEquals(TextFormat.HTML, detectTextFormat(htmlWithMd))
    }

    @Test
    fun `markdown constructs detected`() {
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("# Heading"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("intro\n\n- bullet one"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("1. first\n2. second"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("> quoted wisdom"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("code:\n```\nx = 1\n```"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("before\n---\nafter"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("| a | b |\n|---|---|"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("with **bold** inside"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("with __bold__ inside"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("run `npm test` now"))
        assertEquals(TextFormat.MARKDOWN, detectTextFormat("see [docs](https://example.com)"))
    }

    @Test
    fun `plain text stays plain`() {
        assertEquals(TextFormat.PLAIN, detectTextFormat("Fasting panel, minor fatigue."))
        assertEquals(TextFormat.PLAIN, detectTextFormat("A1C 5.4 % — within range"))
        assertEquals(TextFormat.PLAIN, detectTextFormat("value_type and snake_case stay plain"))
    }

    @Test
    fun `toPlainText collapses quill html`() {
        val html = "<p>Fasting panel</p><p><strong>Minor</strong> fatigue &amp; headache</p>"
        assertEquals("Fasting panel Minor fatigue & headache", toPlainText(html))
    }

    @Test
    fun `toPlainText decodes entities inside html`() {
        assertEquals("a < b and c > d", toPlainText("<p>a &lt; b and c &gt; d</p>"))
        assertEquals("“quoted”", toPlainText("<p>&ldquo;quoted&rdquo;</p>"))
        assertEquals("café", toPlainText("<p>caf&#233;</p>"))
        assertEquals("hex é", toPlainText("<p>hex &#xE9;</p>"))
        assertEquals("keep &unknownentity; as-is", toPlainText("<p>keep &unknownentity; as-is</p>"))
    }

    @Test
    fun `toPlainText strips tags and comments from html`() {
        val hostile = "<p>ok</p><script>alert('x')</script><!-- hidden -->"
        val plain = toPlainText(hostile)
        assertFalse(plain.contains("<"))
        assertFalse(plain.contains("hidden"))
        assertEquals("ok alert('x')", plain)
    }

    @Test
    fun `toPlainText collapses markdown`() {
        val md = "# Heading\n\nsome **bold** and *italic* and `code`\n\n- item one\n- item two\n\n[link text](https://x.y)"
        assertEquals(
            "Heading some bold and italic and code item one item two link text",
            toPlainText(md),
        )
    }

    @Test
    fun `toPlainText handles markdown table`() {
        val md = "| Biomarker | Value |\n|---|---|\n| Glucose | 5.2 |"
        assertEquals("Biomarker Value Glucose 5.2", toPlainText(md))
    }

    @Test
    fun `toPlainText keeps fenced code content`() {
        val md = "snippet:\n```python\nprint('hi')\n```\ndone"
        assertEquals("snippet: print('hi') done", toPlainText(md))
    }

    @Test
    fun `toPlainText plain passes through with whitespace collapse`() {
        assertEquals("one two three", toPlainText("one\ntwo   three"))
        assertEquals("", toPlainText(null))
        assertEquals("", toPlainText("  \n "))
    }

    @Test
    fun `impressions style markdown collapses`() {
        val impressions = """
            ## Summary
            Cumulative extraction notes with a **critical** finding.

            | Test | Result |
            |------|--------|
            | LDL  | 3.9 mmol/L |
        """.trimIndent()
        assertEquals(
            "Summary Cumulative extraction notes with a critical finding. Test Result LDL 3.9 mmol/L",
            toPlainText(impressions),
        )
    }

    @Test
    fun `toSnippet truncates with ellipsis`() {
        val long = "word ".repeat(50).trim()
        val snippet = toSnippet(long, 40)
        assertEquals(40, snippet!!.length)
        assertEquals('…', snippet.last())
        assertEquals("short", toSnippet("short", 120))
        assertNull(toSnippet(null))
        assertNull(toSnippet("   "))
        assertEquals("html stripped", toSnippet("<p><b>html</b> stripped"))
    }

    @Test
    fun `hasTextContent`() {
        assertEquals(true, hasTextContent(" x "))
        assertEquals(false, hasTextContent(null))
        assertEquals(false, hasTextContent(" \n "))
    }
}

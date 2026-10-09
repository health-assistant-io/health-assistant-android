package io.healthassistant.shared.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlToMarkdownTest {
    @Test
    fun `quill paragraphs and inline formatting`() {
        val html = "<p>Fasting panel</p><p><strong>Minor</strong> <em>fatigue</em> &amp; <u>headache</u></p>"
        assertEquals(
            "Fasting panel\n\n**Minor** *fatigue* & headache",
            htmlToMarkdown(html),
        )
    }

    @Test
    fun `unauthorized list converts`() {
        val html = "<ul><li>item one</li><li>item <strong>two</strong></li></ul>"
        assertEquals(
            "- item one\n- item **two**",
            htmlToMarkdown(html),
        )
    }

    @Test
    fun `ordered list converts`() {
        val html = "<ol><li>first</li><li>second</li><li>third</li></ol>"
        assertEquals(
            "1. first\n2. second\n3. third",
            htmlToMarkdown(html),
        )
    }

    @Test
    fun `link converts with allowed scheme`() {
        val html = "<p>see <a href=\"https://example.com/docs\">the docs</a></p>"
        assertEquals(
            "see [the docs](https://example.com/docs)",
            htmlToMarkdown(html),
        )
    }

    @Test
    fun `javascript href is dropped but text kept`() {
        val html = "<p><a href=\"javascript:alert(1)\">click me</a></p>"
        assertEquals(
            "click me",
            htmlToMarkdown(html),
        )
    }

    @Test
    fun `onclick attributes are stripped`() {
        val html = "<p onclick=\"steal()\" style=\"color:red\">safe text</p>"
        assertEquals(
            "safe text",
            htmlToMarkdown(html),
        )
    }

    @Test
    fun `script and style blocks removed entirely`() {
        val html = "<p>keep</p><script>alert('x')</script><style>body{}</style><p>also keep</p>"
        val md = htmlToMarkdown(html)
        assertFalse(md.contains("alert"))
        assertFalse(md.contains("body{}"))
        assertEquals("keep\n\nalso keep", md)
    }

    @Test
    fun `unclosed script is removed to end`() {
        val html = "<p>keep</p><script>alert(1)"
        val md = htmlToMarkdown(html)
        assertFalse(md.contains("alert"))
        assertEquals("keep", md)
    }

    @Test
    fun `heading converts`() {
        assertEquals("## Follow up", htmlToMarkdown("<h2>Follow up</h2>"))
    }

    @Test
    fun `blockquote converts`() {
        val html = "<blockquote><p>quoted advice</p></blockquote>"
        assertEquals("> quoted advice", htmlToMarkdown(html))
    }

    @Test
    fun `pre code block converts to fence`() {
        val html = "<pre><code>line1\nline2</code></pre>"
        assertEquals(
            "```\nline1\nline2\n```",
            htmlToMarkdown(html),
        )
    }

    @Test
    fun `inline code converts`() {
        assertEquals(
            "run `npm test` now",
            htmlToMarkdown("<p>run <code>npm test</code> now</p>"),
        )
    }

    @Test
    fun `basic table converts to gfm`() {
        val html = "<table><thead><tr><th>Biomarker</th><th>Value</th></tr></thead><tbody><tr><td>LDL</td><td>3.9</td></tr></tbody></table>"
        val md = htmlToMarkdown(html)
        assertEquals(
            "| Biomarker | Value |\n| --- | --- |\n| LDL | 3.9 |",
            md,
        )
    }

    @Test
    fun `entities decode`() {
        assertEquals("a < b", htmlToMarkdown("<p>a &lt; b</p>"))
        assertEquals("café", htmlToMarkdown("<p>caf&#233;</p>"))
    }

    @Test
    fun `unknown tags drop but keep text`() {
        assertEquals(
            "span text",
            htmlToMarkdown("<p><span style=\"x\">span text</span></p>"),
        )
    }

    @Test
    fun `nested list degrades to flat list`() {
        val html = "<ul><li>outer</li><ul><li>inner</li></ul></ul>"
        val md = htmlToMarkdown(html)
        assertTrue(md.contains("- outer"))
        assertTrue(md.contains("- inner"))
    }

    @Test
    fun `non html passes through unchanged`() {
        assertEquals("**bold** md", htmlToMarkdown("**bold** md"))
        assertEquals("plain text", htmlToMarkdown("plain text"))
    }

    @Test
    fun `head block removed`() {
        val html = "<html><head><title>t</title><meta charset=\"utf-8\"></head><body><p>body only</p></body></html>"
        assertEquals("body only", htmlToMarkdown(html))
    }

    @Test
    fun `img tag is dropped`() {
        val html = "<p>before</p><img src=\"https://x/y.png\" onerror=\"steal()\"><p>after</p>"
        val md = htmlToMarkdown(html)
        assertFalse(md.contains("<"))
        assertFalse(md.contains("steal"))
        assertFalse(md.contains("y.png"))
        assertEquals("before\n\nafter", md)
    }
}

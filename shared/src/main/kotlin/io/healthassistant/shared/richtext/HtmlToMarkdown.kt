package io.healthassistant.shared.richtext

/**
 * Convert the platform's stored HTML (Quill editor output) to Markdown so the
 * Android app renders a single rich-text pipeline (Markwon). Handles the tag
 * subset Quill emits (p, br, strong, em, u, s, lists, headings, blockquote,
 * code, pre, a, basic tables) and structurally removes dangerous content:
 * `<script>`/`<style>` blocks, comments, all attributes except allowlisted
 * `href` schemes. Unknown tags are dropped, their text kept.
 */

private val SCRIPT_BLOCK_RE = Regex("(?is)<script[^>]*>.*?(</script>|$)")
private val STYLE_BLOCK_RE = Regex("(?is)<style[^>]*>.*?(</style>|$)")
private val COMMENT_RE = Regex("(?s)<!--.*?-->")
private val HEAD_RE = Regex("(?is)<head[^>]*>.*?</head>")

private val KNOWN_TAGS = setOf("p", "br", "hr", "strong", "b", "em", "i", "u", "s", "del", "ol", "ul", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "code", "a", "table", "thead", "tbody", "tr", "th", "td")
private val ANY_KNOWN_TAG_RE = Regex("(?i)</?([a-z][a-z0-9]*)[^>]*>")
private val ALLOWED_HREF_SCHEMES = setOf("http", "https", "mailto", "tel")

private const val MAX_PASSES = 24

/** Convert stored HTML to Markdown. Non-HTML input is returned unchanged. */
fun htmlToMarkdown(input: String): String {
    if (detectTextFormat(input) != TextFormat.HTML) return input
    var t = HEAD_RE.replace(input, "")
    t = SCRIPT_BLOCK_RE.replace(t, "")
    t = STYLE_BLOCK_RE.replace(t, "")
    t = COMMENT_RE.replace(t, "")

    // Drop unknown tags (keep inner text), keep known tags but strip their
    // attributes — except `a href`, validated below.
    t =
        ANY_KNOWN_TAG_RE.replace(t) { m ->
            val tag = m.groupValues[1].lowercase()
            if (tag !in KNOWN_TAGS) return@replace ""
            val closing = m.value.startsWith("</")
            val selfClosing = m.value.endsWith("/>")
            when {
                closing -> "</$tag>"
                tag == "a" && !selfClosing -> {
                    val href = Regex("(?i)\\shref\\s*=\\s*[\"']([^\"']*)[\"']").find(m.value)?.groupValues?.get(1) ?: ""
                    "<a href=\"$href\">"
                }
                else -> "<$tag>"
            }
        }

    // Tables → GFM rows (before other block handling). Cells must be plain.
    repeat(MAX_PASSES) {
        val next = convertTablesOnce(t)
        if (next == t) return@repeat
        t = next
    }

    // Headings, innermost-first.
    repeat(MAX_PASSES) {
        val next = Regex("(?is)<h([1-6])>((?:(?!</?h[1-6]>)[^<])*?)</h\\1>").replace(t) { m ->
            "\n\n" + "#".repeat(m.groupValues[1].toInt()) + " " + m.groupValues[2].trim() + "\n\n"
        }.toString()
        if (next == t) return@repeat
        t = next
    }

    // Fenced pre blocks (content without nested pre).
    repeat(MAX_PASSES) {
        val next = Regex("(?is)<pre>([^<]*(?:<(?!/pre>)[^<]*)*?)</pre>").replace(t) { m ->
            val body = m.groupValues[1].replace(Regex("(?i)</?code[^>]*>"), "").trim('\n')
            "\n\n```\n$body\n```\n\n"
        }.toString()
        if (next == t) return@repeat
        t = next
    }

    // Ordered + unordered lists, innermost li first.
    repeat(MAX_PASSES) {
        val next = convertListsOnce(t)
        if (next == t) return@repeat
        t = next
    }

    // Blockquote: strip inner paragraph wrappers, prefix each line.
    repeat(MAX_PASSES) {
        val next = Regex("(?is)<blockquote>((?:(?!</?blockquote>)[\\s\\S])*?)</blockquote>").replace(t) { m ->
            val inner =
                stripParaTags(m.groupValues[1]).trim().lines()
                    .joinToString("\n") { l -> "> $l".trimEnd() }
            "\n\n$inner\n\n"
        }.toString()
        if (next == t) return@repeat
        t = next
    }

    // Inline code (before bold/italic so ** inside code survives as literal).
    t = Regex("(?is)<code>((?:(?!</?code>)[^<])*?)</code>").replace(t) { "`" + it.groupValues[1] + "`" }

    // Links — validated schemes only.
    t =
        Regex("(?is)<a href=\"([^\"]*)\">((?:(?!</?a[ >])[^<])*?)</a>").replace(t) { m ->
            val href = m.groupValues[1]
            val text = m.groupValues[2].ifBlank { href }
            if (href.isBlank() || href.startsWith("#")) {
                text
            } else {
                val scheme = href.substringBefore("://", missingDelimiterValue = "").lowercase()
                val plainScheme = href.substringBefore(':').lowercase()
                if (scheme in ALLOWED_HREF_SCHEMES || plainScheme in ALLOWED_HREF_SCHEMES) {
                    "[$text]($href)"
                } else {
                    text
                }
            }
        }

    // Bold / italic / strikethrough / underline (underline has no MD glyph).
    t = Regex("(?is)<(strong|b)>((?:(?!</?(?:strong|b)>)[^<])*?)</\\1>").replace(t) { "**" + it.groupValues[2] + "**" }
    t = Regex("(?is)<(em|i)>((?:(?!</?(?:em|i)>)[^<])*?)</\\1>").replace(t) { "*" + it.groupValues[2] + "*" }
    t = Regex("(?is)<(s|del)>((?:(?!</?(?:s|del)>)[^<])*?)</\\1>").replace(t) { "~~" + it.groupValues[2] + "~~" }
    t = Regex("(?is)<u>((?:(?!</?u>)[^<])*?)</u>").replace(t) { it.groupValues[1] }

    // Paragraphs + breaks, innermost-first.
    repeat(MAX_PASSES) {
        val next = Regex("(?is)<p>((?:(?!</?p>)[^<])*?)</p>").replace(t) { m ->
            "\n\n" + m.groupValues[1].trim() + "\n\n"
        }.toString()
        if (next == t) return@repeat
        t = next
    }
    t = Regex("(?i)<br\\s*/?>").replace(t, "\n")
    t = Regex("(?i)<hr\\s*/?>").replace(t, "\n\n---\n\n")

    // Anything left (stray tags) goes away.
    t = Regex("(?i)</?[a-z][^>]*>").replace(t, "")

    t = decodeHtmlEntities(t)

    // Tidy: trailing spaces before newlines, 3+ newlines → one blank line.
    t = Regex("[ \t]+\n").replace(t, "\n")
    t = Regex("\n{3,}").replace(t, "\n\n")
    return t.trim('\n', ' ')
}

/** Drop redundant `<p>`/`</p>` wrappers inside li / blockquote / cells. */
private fun stripParaTags(s: String): String = Regex("(?i)</?p[^>]*>").replace(s, " ")

/** Convert the innermost `<table>…</table>` block into one GFM table. */
private fun convertTablesOnce(t: String): String {
    val tableRe = Regex("(?is)<table>((?:(?!<table>)[\\s\\S])*?)</table>")
    val tm = tableRe.find(t) ?: return t
    val rowRe = Regex("(?is)<tr>((?:(?!</?tr>)[\\s\\S])*?)</tr>")
    val cellRe = Regex("(?is)<(th|td)>((?:(?!</?(?:th|td)>)[\\s\\S])*?)</\\1>")
    val rows = rowRe.findAll(tm.groupValues[1]).toList()
    if (rows.isEmpty()) return t.replaceFirst(tm.value, "")
    val sb = StringBuilder("\n\n")
    var first = true
    for (row in rows) {
        val cells = cellRe.findAll(row.groupValues[1]).map { stripParaTags(it.groupValues[2]).trim() }.toList()
        if (cells.isEmpty()) continue
        sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
        if (first) {
            sb.append("| ").append(cells.joinToString(" | ") { "---" }).append(" |\n")
            first = false
        }
    }
    sb.append("\n")
    return t.replaceFirst(tm.value, sb.toString())
}

/** Convert the innermost `<ol|ul>…</ol|ul>` block: bullets + kept inner text. */
private fun convertListsOnce(t: String): String {
    val listRe = Regex("(?is)<(ol|ul)>((?:(?!</?(?:ol|ul)>)[\\s\\S])*?)</\\1>")
    val m = listRe.find(t) ?: return t
    val ordered = m.groupValues[1].equals("ol", ignoreCase = true)
    val liRe = Regex("(?is)<li>((?:(?!</?li>)[\\s\\S])*?)</li>")
    var idx = 0
    val converted =
        liRe.replace(m.groupValues[2]) { li ->
            idx++
            val bullet = if (ordered) "$idx. " else "- "
            val body =
                stripParaTags(li.groupValues[1]).trim()
                    .replace(Regex("\n{2,}"), "\n").trimEnd()
            bullet + body.replace("\n", "\n   ") + "\n"
        }
    return t.replaceFirst(m.value, "\n\n" + converted.trim('\n') + "\n\n")
}

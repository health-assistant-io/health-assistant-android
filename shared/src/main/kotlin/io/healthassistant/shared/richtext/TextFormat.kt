package io.healthassistant.shared.richtext

/**
 * Format detection + plain-text collapse for rich-text fields (Kotlin port of
 * the core frontend's `textFormat.ts`).
 *
 * The platform stores long-text clinical fields (examination `notes`,
 * `patient_notes`, `impressions`, biomarker `info`, …) as either HTML
 * (produced by the Quill editor) or Markdown (produced by LLMs / AI
 * magic-fill). HTML detection wins over Markdown — same precedence as the
 * web client — so both clients render the same format for the same value.
 */
enum class TextFormat { HTML, MARKDOWN, PLAIN }

private val HTML_TAG_RE = Regex("</?[a-z][\\s\\S]*?>", RegexOption.IGNORE_CASE)

private val MARKDOWN_RE =
    Regex(
        joinAlternation(
            "(^|\\n)\\s*#{1,6}\\s",
            "(^|\\n)\\s*[-*+]\\s",
            "(^|\\n)\\s*\\d+\\.\\s",
            "(^|\\n)\\s*>\\s",
            "(^|\\n)```",
            "(^|\\n)---",
            "(^|\\n)\\|",
            "\\*\\*[^*]+\\*\\*",
            "__[^_]+__",
            "`[^`]+`",
            "\\[[^\\]]+\\]\\([^)]+\\)",
        ),
    )

private fun joinAlternation(vararg parts: String): String = parts.joinToString("|")

/** Matches an HTML comment (removed before tag stripping). */
private val HTML_COMMENT_RE = Regex("<!--[\\s\\S]*?-->")

/** Block-level tags whose close boundary becomes a space when flattened. */
private val HTML_BLOCK_TAG_RE =
    Regex(
        "</?(p|div|br|li|ul|ol|dl|dd|dt|h[1-6]|blockquote|pre|table|tr|section|article|header|footer)[^>]*>",
        RegexOption.IGNORE_CASE,
    )

private val ANY_TAG_RE = Regex("</?[a-z][^>]*>", RegexOption.IGNORE_CASE)

private val NUMERIC_ENTITY_RE = Regex("&#(x?[0-9a-fA-F]+);")

private val NAMED_ENTITIES =
    mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
        "bull" to "•",
        "middot" to "·",
        "deg" to "°",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "times" to "×",
        "divide" to "÷",
        "plusmn" to "±",
        "micro" to "µ",
        "para" to "¶",
        "sect" to "§",
        "laquo" to "«",
        "raquo" to "»",
    )

private val NAMED_ENTITY_RE = Regex("&([a-zA-Z]+);")

/** Decode numeric + the common named HTML entities. Unknown entities pass through. */
internal fun decodeHtmlEntities(s: String): String {
    var t = NUMERIC_ENTITY_RE.replace(s) { m ->
        val raw = m.groupValues[1]
        val code =
            if (raw.startsWith("x") || raw.startsWith("X")) {
                raw.substring(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull(10)
            }
        code?.toChar()?.toString() ?: m.value
    }
    t =
        NAMED_ENTITY_RE.replace(t) { m ->
            NAMED_ENTITIES[m.groupValues[1].lowercase()] ?: m.value
        }
    return t
}

/**
 * Detect whether a string is HTML, Markdown, or plain text. HTML wins over
 * Markdown (a Markdown document won't contain `<tag>` patterns unless it is
 * actually HTML-with-inline-Markdown, which is rare for stored fields).
 */
fun detectTextFormat(text: String?): TextFormat {
    if (text.isNullOrBlank()) return TextFormat.PLAIN
    if (HTML_TAG_RE.containsMatchIn(text)) return TextFormat.HTML
    if (MARKDOWN_RE.containsMatchIn(text)) return TextFormat.MARKDOWN
    return TextFormat.PLAIN
}

/** True if the value contains any non-whitespace content. */
fun hasTextContent(text: String?): Boolean = !text.isNullOrBlank()

/** Collapse Markdown syntax to its inner text (headings, lists, links,
 *  emphasis, code fences, table pipes). */
private fun markdownToPlainText(s: String): String {
    var t = s
    t = Regex("```[a-zA-Z0-9_-]*\\n?[\\s\\S]*?```").replace(t) { m ->
        m.value.dropWhile { it != '\n' }.removeSuffix("```")
    }
    t = Regex("!\\[([^\\]]*)\\]\\([^)]+\\)").replace(t) { m -> m.groupValues[1] }
    t = Regex("\\[([^\\]]+)\\]\\([^)]+\\)").replace(t) { m -> m.groupValues[1] }
    t = Regex("(^|\\n)\\s*#{1,6}\\s+").replace(t) { m -> m.groupValues[1] }
    t = Regex("(^|\\n)\\s*>\\s?").replace(t) { m -> m.groupValues[1] }
    t = Regex("(^|\\n)\\s*[-*+]\\s+").replace(t) { m -> m.groupValues[1] }
    t = Regex("(^|\\n)\\s*\\d+\\.\\s+").replace(t) { m -> m.groupValues[1] }
    t = Regex("(^|\\n)\\s*(?:---+|\\*\\*\\*+|___+)\\s*(?=\\n|$)").replace(t) { m -> m.groupValues[1] }
    t = Regex("(^|\\n)\\s*\\|?[\\s:|-]*\\|\\s*(?=\\n|$)").replace(t) { m -> m.groupValues[1] }
    t = t.replace("|", " ")
    t = Regex("\\*\\*([^*]+)\\*\\*").replace(t) { m -> m.groupValues[1] }
    t = Regex("__([^_]+)__").replace(t) { m -> m.groupValues[1] }
    t = Regex("\\*([^*\\n]+)\\*").replace(t) { m -> m.groupValues[1] }
    t = Regex("(?<!\\w)_([^_\\n]+)_(?!\\w)").replace(t) { m -> m.groupValues[1] }
    t = Regex("`([^`\\n]+)`").replace(t) { m -> m.groupValues[1] }
    return t
}

/** Strip HTML tags + decode entities; block boundaries become spaces. */
private fun htmlToPlainText(s: String): String {
    var t = HTML_COMMENT_RE.replace(s, " ")
    t = HTML_BLOCK_TAG_RE.replace(t, " ")
    t = ANY_TAG_RE.replace(t, "")
    return decodeHtmlEntities(t)
}

/**
 * Collapse a rich-text value (HTML / Markdown / plain) to a single plain-text
 * line with all whitespace (incl. block boundaries) collapsed to single
 * spaces. Use for compact one-line displays (list rows, subtitles); for full
 * rich rendering use the Android `RichText` composable.
 */
fun toPlainText(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val s = text.trim()
    return when (detectTextFormat(s)) {
        TextFormat.HTML -> htmlToPlainText(s)
        TextFormat.MARKDOWN -> markdownToPlainText(s)
        TextFormat.PLAIN -> s
    }.replace(Regex("\\s+"), " ").trim()
}

/**
 * A plain-text snippet (via [toPlainText]) truncated to [maxLen] chars with an
 * ellipsis, or null when there is no content. For short secondary display
 * fields derived from rich-text sources.
 */
fun toSnippet(
    text: String?,
    maxLen: Int = 120,
): String? {
    val plain = toPlainText(text)
    if (plain.isEmpty()) return null
    if (plain.length <= maxLen) return plain
    return plain.take(maxLen).trimEnd() + "…"
}

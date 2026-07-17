package com.agenthub.app.ui.chat

/**
 * Full Markdown parser for AgentHub.
 * Returns a list of [MarkdownBlock]s that can be rendered by Compose.
 *
 * Supported syntax:
 * - **bold**, *italic*, ***bold+italic***
 * - `inline code`, ```code blocks``` (with optional language tag)
 * - [link](url) — clickable blue underlined text
 * - > blockquote — left bar + grey background
 * - Unordered lists (- or *), Ordered lists (1. 2. 3.)
 * - Headings (# h1 – #### h4)
 * - Tables (simple pipe-delimited)
 * - Horizontal rules (---)
 * - Images (![alt](url)) — rendered as placeholder
 */
object MarkdownParser {

    fun parse(text: String): List<MarkdownBlock> {
        if (text.isBlank()) return listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan.Text(""))))

        val blocks = mutableListOf<MarkdownBlock>()
        val lines = text.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // ── Blank line → skip ──
            if (line.isBlank()) { i++; continue }

            // ── Fenced code block ──
            if (line.trimStart().startsWith("```")) {
                val lang = line.trimStart().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip closing ```
                blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
                continue
            }

            // ── Horizontal rule ──
            if (line.trim().matches(Regex("^[-*_]{3,}$"))) {
                blocks.add(MarkdownBlock.Divider(line.trim()))
                i++
                continue
            }

            // ── Heading ──
            val headingMatch = Regex("^(#{1,4})\\s+(.+)$").matchEntire(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                blocks.add(MarkdownBlock.Heading(level, headingMatch.groupValues[2]))
                i++
                continue
            }

            // ── Table ──
            if (line.contains("|") && i + 1 < lines.size && lines[i + 1].matches(Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$"))) {
                val headerCells = parseTableRow(line)
                i += 2 // skip header + separator
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) {
                    rows.add(parseTableRow(lines[i]))
                    i++
                }
                blocks.add(MarkdownBlock.Table(headerCells, rows))
                continue
            }

            // ── Blockquote ──
            if (line.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].startsWith(">")) {
                    quoteLines.add(lines[i].removePrefix(">").trimStart())
                    i++
                }
                val quoteText = quoteLines.joinToString("\n")
                blocks.add(MarkdownBlock.BlockQuote(parseInline(quoteText)))
                continue
            }

            // ── Unordered list ──
            if (line.trimStart().matches(Regex("^[-*]\\s+.*"))) {
                val items = mutableListOf<List<MarkdownSpan>>()
                while (i < lines.size && lines[i].trimStart().matches(Regex("^[-*]\\s+.*"))) {
                    val itemText = lines[i].trimStart().replaceFirst(Regex("^[-*]\\s+"), "")
                    items.add(parseInline(itemText))
                    i++
                }
                blocks.add(MarkdownBlock.UnorderedList(items))
                continue
            }

            // ── Ordered list ──
            if (line.trimStart().matches(Regex("^\\d+\\.\\s+.*"))) {
                val items = mutableListOf<List<MarkdownSpan>>()
                while (i < lines.size && lines[i].trimStart().matches(Regex("^\\d+\\.\\s+.*"))) {
                    val itemText = lines[i].trimStart().replaceFirst(Regex("^\\d+\\.\\s+"), "")
                    items.add(parseInline(itemText))
                    i++
                }
                blocks.add(MarkdownBlock.OrderedList(items))
                continue
            }

            // ── Paragraph (default) ──
            val paraLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trimStart().startsWith("```") &&
                !lines[i].trimStart().startsWith("#") &&
                !lines[i].startsWith(">") &&
                !lines[i].trimStart().matches(Regex("^[-*]\\s+.*")) &&
                !lines[i].trimStart().matches(Regex("^\\d+\\.\\s+.*")) &&
                !lines[i].trim().matches(Regex("^[-*_]{3,}$"))
            ) {
                paraLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.Paragraph(parseInline(paraLines.joinToString("\n"))))
        }

        return blocks
    }

    /** Parse inline spans: bold, italic, bold+italic, code, links, images */
    fun parseInline(text: String): List<MarkdownSpan> {
        if (text.isBlank()) return listOf(MarkdownSpan.Text(""))

        val spans = mutableListOf<MarkdownSpan>()
        // Pattern: ***bold+italic***, **bold**, *italic*, `code`, ![img](url), [link](url), plain text
        val regex = Regex(
            """\*\*\*(.+?)\*\*\*|""" +
            """\*\*(.+?)\*\*|""" +
            """\*(.+?)\*|""" +
            """`([^`]+)`|""" +
            """!\[([^\]]*)\]\(([^)]+)\)|""" +
            """\[([^\]]+)\]\(([^)]+)\)|""" +
            """([^*`\[]+)"""
        )

        for (match in regex.findAll(text)) {
            when {
                // ***bold+italic***
                match.groupValues[1].isNotEmpty() -> {
                    spans.add(MarkdownSpan.Text(match.groupValues[1], bold = true, italic = true))
                }
                // **bold**
                match.groupValues[2].isNotEmpty() -> {
                    spans.add(MarkdownSpan.Text(match.groupValues[2], bold = true))
                }
                // *italic*
                match.groupValues[3].isNotEmpty() -> {
                    spans.add(MarkdownSpan.Text(match.groupValues[3], italic = true))
                }
                // `code`
                match.groupValues[4].isNotEmpty() -> {
                    spans.add(MarkdownSpan.Code(match.groupValues[4]))
                }
                // ![alt](url) — image placeholder
                match.groupValues[5].isNotEmpty() || match.groupValues[6].isNotEmpty() -> {
                    val alt = match.groupValues[5].ifEmpty { "image" }
                    spans.add(MarkdownSpan.Text("[🖼 $alt]", italic = true))
                }
                // [link](url)
                match.groupValues[7].isNotEmpty() -> {
                    spans.add(MarkdownSpan.Link(match.groupValues[7], match.groupValues[8]))
                }
                // plain text
                match.groupValues[9].isNotEmpty() -> {
                    spans.add(MarkdownSpan.Text(match.groupValues[9]))
                }
            }
        }
        if (spans.isEmpty()) spans.add(MarkdownSpan.Text(text))
        return spans
    }

    private fun parseTableRow(line: String): List<String> {
        return line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }
    }
}

// ── Data models ──

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class BlockQuote(val spans: List<MarkdownSpan>) : MarkdownBlock()
    data class UnorderedList(val items: List<List<MarkdownSpan>>) : MarkdownBlock()
    data class OrderedList(val items: List<List<MarkdownSpan>>) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class Divider(val text: String) : MarkdownBlock()
}

sealed class MarkdownSpan {
    data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false) : MarkdownSpan()
    data class Code(val text: String) : MarkdownSpan()
    data class Link(val text: String, val url: String) : MarkdownSpan()
}

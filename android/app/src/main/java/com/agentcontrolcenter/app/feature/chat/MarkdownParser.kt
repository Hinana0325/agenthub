package com.agentcontrolcenter.app.feature.chat

/**
 * Full Markdown parser for Agent Control Center.
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

    // Phase 2.3: 缓存所有 Regex 实例到 object 级别常量。
    // 此前每次 parse/parseInline 调用都新建 Regex（涉及 pattern 编译，开销大），
    // 流式响应期间每个 delta 都触发重新 parse，是 CPU 热点。

    /** 水平分割线：---、***、___（至少 3 个） */
    private val HR_REGEX = Regex("^[-*_]{3,}$")
    /** 标题：# ~ #### 后跟文本 */
    private val HEADING_REGEX = Regex("^(#{1,4})\\s+(.+)$")
    /** 表格分隔行：|---|:---:|---| 等 */
    private val TABLE_SEPARATOR_REGEX = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    /** 无序列表项：- 或 * 后跟空格 */
    private val UNORDERED_LIST_REGEX = Regex("^[-*]\\s+.*")
    /** 无序列表项前缀（用于剥离） */
    private val UNORDERED_LIST_PREFIX_REGEX = Regex("^[-*]\\s+")
    /** 有序列表项：数字. 后跟空格 */
    private val ORDERED_LIST_REGEX = Regex("^\\d+\\.\\s+.*")
    /** 有序列表项前缀（用于剥离） */
    private val ORDERED_LIST_PREFIX_REGEX = Regex("^\\d+\\.\\s+")

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
            if (line.trim().matches(HR_REGEX)) {
                blocks.add(MarkdownBlock.Divider(line.trim()))
                i++
                continue
            }

            // ── Heading ──
            val headingMatch = HEADING_REGEX.matchEntire(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                blocks.add(MarkdownBlock.Heading(level, headingMatch.groupValues[2]))
                i++
                continue
            }

            // ── Table ──
            if (line.contains("|") && i + 1 < lines.size && lines[i + 1].matches(TABLE_SEPARATOR_REGEX)) {
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
            if (line.trimStart().matches(UNORDERED_LIST_REGEX)) {
                val items = mutableListOf<List<MarkdownSpan>>()
                while (i < lines.size && lines[i].trimStart().matches(UNORDERED_LIST_REGEX)) {
                    val itemText = lines[i].trimStart().replaceFirst(UNORDERED_LIST_PREFIX_REGEX, "")
                    items.add(parseInline(itemText))
                    i++
                }
                blocks.add(MarkdownBlock.UnorderedList(items))
                continue
            }

            // ── Ordered list ──
            if (line.trimStart().matches(ORDERED_LIST_REGEX)) {
                val items = mutableListOf<List<MarkdownSpan>>()
                while (i < lines.size && lines[i].trimStart().matches(ORDERED_LIST_REGEX)) {
                    val itemText = lines[i].trimStart().replaceFirst(ORDERED_LIST_PREFIX_REGEX, "")
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
                !lines[i].trimStart().matches(UNORDERED_LIST_REGEX) &&
                !lines[i].trimStart().matches(ORDERED_LIST_REGEX) &&
                !lines[i].trim().matches(HR_REGEX)
            ) {
                paraLines.add(lines[i])
                i++
            }
            if (paraLines.isEmpty()) {
                // 当前行不匹配任何块类型且无法归入段落（如 "#NoSpace"），
                // 作为单行段落处理并推进 i，避免外层 while 死循环导致 OOM。
                blocks.add(MarkdownBlock.Paragraph(parseInline(line)))
                i++
            } else {
                blocks.add(MarkdownBlock.Paragraph(parseInline(paraLines.joinToString("\n"))))
            }
        }

        return blocks
    }

    /**
     * Phase 2.3: 缓存 inline span 的复合正则。
     * 匹配 ***bold+italic***、**bold**、*italic*、`code`、![img](url)、[link](url)、plain text
     */
    private val INLINE_SPAN_REGEX = Regex(
        """\*\*\*(.+?)\*\*\*|""" +
        """\*\*(.+?)\*\*|""" +
        """\*(.+?)\*|""" +
        """`([^`]+)`|""" +
        """!\[([^\]]*)\]\(([^)]+)\)|""" +
        """\[([^\]]+)\]\(([^)]+)\)|""" +
        """([^*`\[]+)"""
    )

    /** Parse inline spans: bold, italic, bold+italic, code, links, images */
    fun parseInline(text: String): List<MarkdownSpan> {
        if (text.isBlank()) return listOf(MarkdownSpan.Text(""))

        val spans = mutableListOf<MarkdownSpan>()
        val regex = INLINE_SPAN_REGEX

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

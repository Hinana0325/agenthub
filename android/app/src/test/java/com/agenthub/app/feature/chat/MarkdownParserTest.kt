package com.agenthub.app.feature.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * MarkdownParser 单元测试。
 * 验证各类 Markdown 元素的解析正确性和边界情况。
 */
class MarkdownParserTest {

    // ── 空白/边界 ──

    @Test
    fun `parse empty string returns single empty paragraph`() {
        val blocks = MarkdownParser.parse("")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        val spans = (blocks[0] as MarkdownBlock.Paragraph).spans
        assertEquals(1, spans.size)
        assertEquals("", (spans[0] as MarkdownSpan.Text).text)
    }

    @Test
    fun `parse blank string returns single empty paragraph`() {
        val blocks = MarkdownParser.parse("   ")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    // ── 标题 ──

    @Test
    fun `parse h1 heading`() {
        val blocks = MarkdownParser.parse("# Hello")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        val h = blocks[0] as MarkdownBlock.Heading
        assertEquals(1, h.level)
        assertEquals("Hello", h.text)
    }

    @Test
    fun `parse h1-h4 headings`() {
        val text = "# H1\n## H2\n### H3\n#### H4"
        val blocks = MarkdownParser.parse(text)
        assertEquals(4, blocks.size)
        assertEquals(1, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals(2, (blocks[1] as MarkdownBlock.Heading).level)
        assertEquals(3, (blocks[2] as MarkdownBlock.Heading).level)
        assertEquals(4, (blocks[3] as MarkdownBlock.Heading).level)
    }

    @Test
    fun `heading without space is not a heading`() {
        val blocks = MarkdownParser.parse("#NoSpace")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    // ── 代码块 ──

    @Test
    fun `parse fenced code block with language`() {
        val text = "```kotlin\nfun main() {}\n```"
        val blocks = MarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.CodeBlock)
        val cb = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", cb.language)
        assertEquals("fun main() {}", cb.code)
    }

    @Test
    fun `parse fenced code block without language`() {
        val text = "```\ncode here\n```"
        val blocks = MarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        val cb = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("", cb.language)
        assertEquals("code here", cb.code)
    }

    @Test
    fun `parse multiline code block`() {
        val text = "```\nline1\nline2\nline3\n```"
        val blocks = MarkdownParser.parse(text)
        val cb = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("line1\nline2\nline3", cb.code)
    }

    // ── 行内代码 ──

    @Test
    fun `parseInline inline code`() {
        val spans = MarkdownParser.parseInline("use `println` here")
        assertEquals(3, spans.size)
        assertTrue(spans[0] is MarkdownSpan.Text)
        assertTrue(spans[1] is MarkdownSpan.Code)
        assertEquals("println", (spans[1] as MarkdownSpan.Code).text)
        assertTrue(spans[2] is MarkdownSpan.Text)
    }

    // ── 粗体/斜体 ──

    @Test
    fun `parseInline bold`() {
        val spans = MarkdownParser.parseInline("**bold**")
        assertEquals(1, spans.size)
        val t = spans[0] as MarkdownSpan.Text
        assertEquals("bold", t.text)
        assertTrue(t.bold)
        assertFalse(t.italic)
    }

    @Test
    fun `parseInline italic`() {
        val spans = MarkdownParser.parseInline("*italic*")
        assertEquals(1, spans.size)
        val t = spans[0] as MarkdownSpan.Text
        assertEquals("italic", t.text)
        assertTrue(t.italic)
        assertFalse(t.bold)
    }

    @Test
    fun `parseInline bold+italic`() {
        val spans = MarkdownParser.parseInline("***both***")
        assertEquals(1, spans.size)
        val t = spans[0] as MarkdownSpan.Text
        assertEquals("both", t.text)
        assertTrue(t.bold)
        assertTrue(t.italic)
    }

    // ── 链接 ──

    @Test
    fun `parseInline link`() {
        val spans = MarkdownParser.parseInline("[Google](https://google.com)")
        assertEquals(1, spans.size)
        assertTrue(spans[0] is MarkdownSpan.Link)
        val link = spans[0] as MarkdownSpan.Link
        assertEquals("Google", link.text)
        assertEquals("https://google.com", link.url)
    }

    @Test
    fun `parseInline image becomes italic placeholder`() {
        val spans = MarkdownParser.parseInline("![alt](url)")
        assertEquals(1, spans.size)
        val t = spans[0] as MarkdownSpan.Text
        assertTrue(t.italic)
        assertTrue(t.text.contains("alt"))
    }

    // ── 引用块 ──

    @Test
    fun `parse blockquote`() {
        val text = "> quoted text"
        val blocks = MarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.BlockQuote)
    }

    @Test
    fun `parse multiline blockquote`() {
        val text = "> line1\n> line2"
        val blocks = MarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.BlockQuote)
    }

    // ── 列表 ──

    @Test
    fun `parse unordered list`() {
        val text = "- item1\n- item2\n- item3"
        val blocks = MarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.UnorderedList)
        val list = blocks[0] as MarkdownBlock.UnorderedList
        assertEquals(3, list.items.size)
    }

    @Test
    fun `parse unordered list with asterisk`() {
        val text = "* item1\n* item2"
        val blocks = MarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.UnorderedList)
    }

    @Test
    fun `parse ordered list`() {
        val text = "1. first\n2. second\n3. third"
        val blocks = MarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.OrderedList)
        val list = blocks[0] as MarkdownBlock.OrderedList
        assertEquals(3, list.items.size)
    }

    // ── 分割线 ──

    @Test
    fun `parse horizontal rule with dashes`() {
        val blocks = MarkdownParser.parse("---")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Divider)
    }

    @Test
    fun `parse horizontal rule with asterisks`() {
        val blocks = MarkdownParser.parse("***")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Divider)
    }

    // ── 表格 ──

    @Test
    fun `parse simple table`() {
        val text = "| A | B |\n|---|---|\n| 1 | 2 |"
        val blocks = MarkdownParser.parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Table)
        val table = blocks[0] as MarkdownBlock.Table
        assertEquals(2, table.headers.size)
        assertEquals("A", table.headers[0])
        assertEquals("B", table.headers[1])
        assertEquals(1, table.rows.size)
        assertEquals("1", table.rows[0][0])
        assertEquals("2", table.rows[0][1])
    }

    // ── 段落 ──

    @Test
    fun `parse plain paragraph`() {
        val blocks = MarkdownParser.parse("Hello world")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `parse multiline paragraph`() {
        val blocks = MarkdownParser.parse("line1\nline2")
        assertEquals(1, blocks.size)
        val p = blocks[0] as MarkdownBlock.Paragraph
        val text = p.spans.filterIsInstance<MarkdownSpan.Text>().joinToString("") { it.text }
        assertTrue(text.contains("line1"))
        assertTrue(text.contains("line2"))
    }

    // ── 混合内容 ──

    @Test
    fun `parse mixed content heading paragraph code`() {
        val text = "# Title\n\nSome text\n\n```kotlin\ncode\n```"
        val blocks = MarkdownParser.parse(text)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        assertTrue(blocks[2] is MarkdownBlock.CodeBlock)
    }

    // ── parseInline 边界 ──

    @Test
    fun `parseInline empty returns empty text span`() {
        val spans = MarkdownParser.parseInline("")
        assertEquals(1, spans.size)
        assertEquals("", (spans[0] as MarkdownSpan.Text).text)
    }

    @Test
    fun `parseInline plain text`() {
        val spans = MarkdownParser.parseInline("hello world")
        assertEquals(1, spans.size)
        assertEquals("hello world", (spans[0] as MarkdownSpan.Text).text)
    }

    @Test
    fun `parseInline mixed bold code link`() {
        val spans = MarkdownParser.parseInline("**bold** and `code` and [link](url)")
        assertEquals(5, spans.size) // bold + " and " + code + " and " + link
        assertTrue(spans[0] is MarkdownSpan.Text && (spans[0] as MarkdownSpan.Text).bold)
        assertTrue(spans[2] is MarkdownSpan.Code)
        assertTrue(spans[4] is MarkdownSpan.Link)
    }
}

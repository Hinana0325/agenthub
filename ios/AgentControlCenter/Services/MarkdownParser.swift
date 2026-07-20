import SwiftUI

/// Markdown 解析与渲染
/// 对应 Android MarkdownParser + MarkdownText
/// 简化实现：用 AttributedString + 正则替换处理常见 Markdown 语法
struct MarkdownText: View {
    let markdown: String
    let isUser: Bool

    init(_ markdown: String, isUser: Bool = false) {
        self.markdown = markdown
        self.isUser = isUser
    }

    var body: some View {
        // 尝试解析为 AttributedString，失败则回退纯文本
        Text(attributedContent)
            .textSelection(.enabled)
    }

    private var attributedContent: AttributedString {
        // 1. 代码块 (```)
        var result = markdown
        result = replaceCodeBlocks(in: result)

        // 2. 行内代码 (`)
        result = replaceInlineCode(in: result)

        // 3. 粗体 (**text**)
        result = replaceBold(in: result)

        // 4. 斜体 (*text*)
        result = replaceItalic(in: result)

        // 5. 标题 (## text)
        result = replaceHeaders(in: result)

        // 6. 列表项 (- text)
        result = replaceListItems(in: result)

        do {
            var attr = try AttributedString(markdown: result, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))
            // 整体字体
            attr.font = .body
            if !isUser {
                attr.foregroundColor = .primary
            }
            return attr
        } catch {
            return AttributedString(markdown)
        }
    }

    // MARK: - 替换规则

    /// 代码块 → 移除 ``` 包裹，保留内容
    private func replaceCodeBlocks(in text: String) -> String {
        text.replacingOccurrences(
            of: "```[\\w]*\\n([\\s\\S]*?)```",
            with: "$1",
            options: .regularExpression
        )
    }

    /// 行内代码 → 移除 ` 包裹
    private func replaceInlineCode(in text: String) -> String {
        text.replacingOccurrences(
            of: "`([^`]+)`",
            with: "$1",
            options: .regularExpression
        )
    }

    /// 粗体 **text** → text (AttributedString 会处理)
    private func replaceBold(in text: String) -> String { text }

    /// 斜体 *text* → text
    private func replaceItalic(in text: String) -> String { text }

    /// 标题 ## text → text (去掉 ## 前缀)
    private func replaceHeaders(in text: String) -> String {
        text.replacingOccurrences(
            of: "^#{1,6}\\s+",
            with: "",
            options: .regularExpression
        )
    }

    /// 列表项 - text → • text
    private func replaceListItems(in text: String) -> String {
        text.replacingOccurrences(
            of: "^\\s*[-*]\\s+",
            with: "• ",
            options: .regularExpression
        )
    }
}

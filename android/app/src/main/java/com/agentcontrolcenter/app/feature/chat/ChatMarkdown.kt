package com.agentcontrolcenter.app.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeS8
import com.agentcontrolcenter.app.ui.theme.ShapeXs2
import com.agentcontrolcenter.app.ui.theme.ShapeXs4
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full Markdown renderer using Compose Column + AnnotatedString.
 * Parses the text into [MarkdownBlock]s and renders each one.
 */
@Composable
fun MarkdownText(
    text: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            MarkdownBlockView(block, style, color, uriHandler)
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    baseStyle: androidx.compose.ui.text.TextStyle,
    baseColor: androidx.compose.ui.graphics.Color,
    uriHandler: androidx.compose.ui.platform.UriHandler
) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val headingStyle = when (block.level) {
                1 -> MaterialTheme.typography.headlineLarge
                2 -> MaterialTheme.typography.headlineMedium
                3 -> MaterialTheme.typography.headlineSmall
                else -> MaterialTheme.typography.titleLarge
            }
            Text(
                text = block.text,
                style = headingStyle,
                fontWeight = FontWeight.Bold,
                color = baseColor
            )
        }

        is MarkdownBlock.Paragraph -> {
            val linkColor = MaterialTheme.colorScheme.primary
            val annotated = remember(block.spans, baseColor, linkColor) {
                buildSpanAnnotatedString(block.spans, baseColor, linkColor)
            }
            ClickableAnnotatedText(
                text = annotated,
                style = baseStyle,
                color = baseColor,
                uriHandler = uriHandler
            )
        }

        is MarkdownBlock.CodeBlock -> {
            Surface(
                shape = ShapeS8,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    if (block.language.isNotEmpty()) {
                        Text(
                            text = block.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = baseColor.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        text = block.code,
                        style = baseStyle.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseStyle.fontSize.value * 0.9).sp
                        ),
                        color = baseColor
                    )
                }
            }
        }

        is MarkdownBlock.BlockQuote -> {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                Surface(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    shape = ShapeXs2
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                val linkColor2 = MaterialTheme.colorScheme.primary
                val annotated = remember(block.spans, baseColor, linkColor2) {
                    buildSpanAnnotatedString(block.spans, baseColor, linkColor2)
                }
                Surface(
                    shape = ShapeXs4,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        ClickableAnnotatedText(
                            text = annotated,
                            style = baseStyle,
                            color = baseColor.copy(alpha = 0.8f),
                            uriHandler = uriHandler
                        )
                    }
                }
            }
        }

        is MarkdownBlock.UnorderedList -> {
            Column(modifier = Modifier.padding(start = 8.dp)) {
                block.items.forEach { spans ->
                    Row {
                        Text(text = "\u2022  ", style = baseStyle, color = baseColor)
                        val annotated = remember(spans, baseColor) {
                            val lc = androidx.compose.ui.graphics.Color(0xFF1976D2)
                            buildSpanAnnotatedString(spans, baseColor, lc)
                        }
                        ClickableAnnotatedText(
                            text = annotated,
                            style = baseStyle,
                            color = baseColor,
                            uriHandler = uriHandler
                        )
                    }
                }
            }
        }

        is MarkdownBlock.OrderedList -> {
            Column(modifier = Modifier.padding(start = 8.dp)) {
                block.items.forEachIndexed { idx, spans ->
                    Row {
                        Text(text = "${idx + 1}.  ", style = baseStyle, color = baseColor)
                        val annotated = remember(spans, baseColor) {
                            buildSpanAnnotatedString(spans, baseColor)
                        }
                        ClickableAnnotatedText(
                            text = annotated,
                            style = baseStyle,
                            color = baseColor,
                            uriHandler = uriHandler
                        )
                    }
                }
            }
        }

        is MarkdownBlock.Table -> {
            Surface(
                shape = ShapeS8,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        block.headers.forEach { header ->
                            Text(
                                text = header,
                                style = baseStyle.copy(fontWeight = FontWeight.Bold),
                                color = baseColor,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    block.rows.forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            row.forEach { cell ->
                                Text(
                                    text = cell,
                                    style = baseStyle,
                                    color = baseColor,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        is MarkdownBlock.Divider -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = baseColor.copy(alpha = 0.2f)
            )
        }
    }
}

/** Build an AnnotatedString from a list of MarkdownSpan with proper styling. */
private fun buildSpanAnnotatedString(
    spans: List<MarkdownSpan>,
    baseColor: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF1976D2)
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when (span) {
            is MarkdownSpan.Text -> {
                withStyle(SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null
                )) {
                    append(span.text)
                }
            }
            is MarkdownSpan.Code -> {
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = baseColor.copy(alpha = 0.1f),
                    fontSize = 13.sp
                )) {
                    append(" ${span.text} ")
                }
            }
            is MarkdownSpan.Link -> {
                val tag = "link_${span.url.hashCode()}"
                pushStringAnnotation(tag = tag, annotation = span.url)
                withStyle(SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline
                )) {
                    append(span.text)
                }
                pop()
            }
        }
    }
}

/**
 * Renders an AnnotatedString and handles link click annotations via [uriHandler].
 */
@Composable
private fun ClickableAnnotatedText(
    text: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    uriHandler: androidx.compose.ui.platform.UriHandler
) {
    val layoutResult = remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val uriHandlerRef = rememberUpdatedState(uriHandler)

    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layoutResult.value = it },
        modifier = Modifier.clickable {
            layoutResult.value?.let {
                text.getStringAnnotations(start = 0, end = text.length)
                    .firstOrNull()
                    ?.let { annotation ->
                        try { uriHandlerRef.value.openUri(annotation.item) } catch (_: Exception) {}
                    }
            }
        }
    )
}

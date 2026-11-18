package app.notify.android.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun MessageMarkdown(source: String) {
    val blocks = remember(source) { parseMessageMarkdown(source) }
    SelectionContainer {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(block.language.ifBlank { "Code" }, style = MaterialTheme.typography.labelMedium)
                        Text(block.source, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        DisableSelection { CopyButton(block.source, "Copy code") }
                    }
                }
                is MarkdownBlock.Prose -> {
                    val content = remember(block) {
                        if (block.literal) AnnotatedString(block.source) else markdownInline(block.prefix + block.source)
                    }
                    val style = when (block.heading) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        in 4..6 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    }
                    Text(content, style = style, modifier = if (block.heading > 0)
                        Modifier.padding(top = 12.dp).semantics { heading() } else Modifier)
                }
                }
            }
        }
    }
}

internal sealed interface MarkdownBlock {
    data class Prose(val source: String, val heading: Int = 0, val prefix: String = "", val literal: Boolean = false) : MarkdownBlock
    data class Code(val source: String, val language: String) : MarkdownBlock
}
private val openingFence = Regex("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$")
private val heading = Regex("^ {0,3}(#{1,6})[ \\t]+(.*)$")
private val listItem = Regex("^ {0,12}([-+*]|[0-9]{1,9}[.)])[ \\t]+(.*)$")

internal fun parseMessageMarkdown(source: String): List<MarkdownBlock> {
    fun literal() = listOf(MarkdownBlock.Prose(source, literal = true))
    if (source.length > 65_536) return literal()
    val result = mutableListOf<MarkdownBlock>()
    var offset = 0
    while (offset < source.length) {
        if (result.size >= 256) return literal()
        val end = source.indexOf('\n', offset).let { if (it == -1) source.length else it + 1 }
        val line = source.substring(offset, end).removeSuffix("\n").removeSuffix("\r")
        val fence = openingFence.matchEntire(line)?.takeUnless {
            it.groupValues[1][0] == '`' && '`' in it.groupValues[2]
        }
        if (fence != null) {
            val marker = fence.groupValues[1]
            val start = end
            var cursor = end
            var codeEnd = source.length
            var next = source.length
            while (cursor < source.length) {
                val nextLine = source.indexOf('\n', cursor).let { if (it == -1) source.length else it + 1 }
                val candidate = source.substring(cursor, nextLine).removeSuffix("\n").removeSuffix("\r")
                val trimmed = candidate.trimStart(' ')
                val count = trimmed.takeWhile { it == marker[0] }.length
                if (candidate.length - trimmed.length <= 3 && count >= marker.length &&
                    trimmed.substring(count).all { it == ' ' || it == '\t' }) {
                    codeEnd = cursor
                    next = nextLine
                    break
                }
                cursor = nextLine
            }
            result += MarkdownBlock.Code(source.substring(start, codeEnd), fence.groupValues[2].trim())
            offset = next
        } else {
            val h = heading.matchEntire(line)
            val item = listItem.matchEntire(line)
            when {
                h != null -> result += MarkdownBlock.Prose(h.groupValues[2], heading = h.groupValues[1].length)
                item != null -> result += MarkdownBlock.Prose(item.groupValues[2],
                    prefix = if (item.groupValues[1] in listOf("-", "+", "*")) "• " else "${item.groupValues[1]} ")
                line.isNotEmpty() -> result += MarkdownBlock.Prose(line)
            }
            offset = end
        }
    }
    return result.ifEmpty { literal() }
}

internal fun markdownInline(source: String): AnnotatedString {
    if (source.length > 65_536) return AnnotatedString(source)
    return buildAnnotatedString {
        var index = 0
        while (index < source.length) {
            val marker = when {
                source.startsWith("**", index) -> "**"
                source.startsWith("*", index) -> "*"
                source.startsWith("`", index) && !source.startsWith("``", index) -> "`"
                else -> null
            }
            val close = marker?.let { source.indexOf(it, index + it.length) } ?: -1
            if (marker != null && close > index + marker.length) {
                val style = when (marker) {
                    "**" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "*" -> SpanStyle(fontStyle = FontStyle.Italic)
                    else -> SpanStyle(fontFamily = FontFamily.Monospace)
                }
                withStyle(style) { append(source.substring(index + marker.length, close)) }
                index = close + marker.length
            } else {
                // Unmatched delimiters remain literal instead of silently consuming content.
                val count = marker?.length ?: 1
                append(source.substring(index, minOf(source.length, index + count)))
                index += count
            }
        }
    }
}

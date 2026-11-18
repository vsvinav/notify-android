package app.notify.android.ui

import org.junit.Assert.*
import org.junit.Test

class MessageMarkdownTest {
    @Test fun fencedCopyRetainsWhitespaceLineEndingsAndNestedShortFences() {
        val source = "Before\r\n````kotlin\r\n  val x = 1  \r\n```\r\n\r\n````\r\nAfter"
        val code = parseMessageMarkdown(source).filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("  val x = 1  \r\n```\r\n\r\n", code.source)
        assertEquals("Before", (parseMessageMarkdown(source).first() as MarkdownBlock.Prose).source)
    }

    @Test fun unclosedFenceAndUnsupportedSyntaxRemainReadable() {
        val source = "~~~sh\n  echo literal\n"
        assertEquals("  echo literal\n", (parseMessageMarkdown(source).single() as MarkdownBlock.Code).source)
        for (text in listOf("<script>alert(1)</script>", "![alt](https://example.invalid/a.png)", "| a | b |", "```not ` a fence")) {
            assertEquals(text, (parseMessageMarkdown(text).single() as MarkdownBlock.Prose).source)
        }
    }

    @Test fun headingsListsAndInlineStylesHaveNativeReadableContent() {
        val blocks = parseMessageMarkdown("# Heading\n\n- **Bold** and *soft* with `x`\n1. First")
        assertEquals(1, (blocks[0] as MarkdownBlock.Prose).heading)
        assertEquals("Heading", (blocks[0] as MarkdownBlock.Prose).source)
        assertEquals("• ", (blocks[1] as MarkdownBlock.Prose).prefix)
        assertEquals("1. ", (blocks[2] as MarkdownBlock.Prose).prefix)
        val inline = markdownInline("**Bold** and *soft* with `x`")
        assertEquals("Bold and soft with x", inline.text)
        assertEquals(3, inline.spanStyles.size)
        assertEquals("literal **unfinished", markdownInline("literal **unfinished").text)
        assertEquals("a_b_c", markdownInline("a_b_c").text)
    }

    @Test fun longOrHighlyFragmentedInputFallsBackWithoutDroppingSource() {
        for (source in listOf("x".repeat(65_537), "# h\n".repeat(600))) {
            val block = parseMessageMarkdown(source).single() as MarkdownBlock.Prose
            assertEquals(source, block.source)
            assertTrue(block.literal)
        }
    }
}

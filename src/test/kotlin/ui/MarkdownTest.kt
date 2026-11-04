package margin.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test fun blocks() {
        val md = "## Why\nSlow on **3G**,\nreally.\n\n- one\n  - nested\n1. first\n- [x] done\n- [ ] todo\n\n> quoted\n> more\n\n```kotlin\nval a = 1\n\n```\n---\nend"
        assertEquals(
            listOf(
                MdBlock.Heading(2, "Why"),
                MdBlock.Paragraph("Slow on **3G**, really."),
                MdBlock.ListItem("one", "•", 0), MdBlock.ListItem("nested", "•", 1), MdBlock.ListItem("first", "1.", 0),
                MdBlock.ListItem("done", "☑", 0), MdBlock.ListItem("todo", "☐", 0),
                MdBlock.Quote("quoted\nmore"),
                MdBlock.Code("val a = 1\n"),
                MdBlock.Rule,
                MdBlock.Paragraph("end"),
            ),
            parseMarkdown(md),
        )
    }

    @Test fun inlineSpansAndOnlySafeLinks() {
        val s = inlineMarkdown(
            "a **b** *c* `d` [e](https://x.io/p) [f](javascript:alert) see https://y.io/q.", SpanStyle(), TextLinkStyles(),
        )
        assertEquals("a b c d e f see https://y.io/q.", s.text)
        assertEquals(listOf("https://x.io/p", "https://y.io/q"), s.getLinkAnnotations(0, s.length).map { (it.item as LinkAnnotation.Url).url })
    }
}

package margin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import margin.ui.components.Glyph
import margin.ui.components.GlyphIcon
import margin.ui.theme.MarginTheme
import margin.ui.theme.Radius
import margin.ui.theme.Spacing

// Just enough Markdown for GitLab descriptions and comments: headings, lists, task lists, quotes, fenced code, rules,
// and inline bold / italic / code / links. Anything else shows as plain text.

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val text: String, val marker: String, val depth: Int) : MdBlock // marker: "•", "3.", "☐", "☑"
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val listItem = Regex("""^(\s*)([-*+]|\d+[.)])\s+(\[[ xX]]\s+)?(.*)$""")
private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

internal fun parseMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim()); para.clear() }
    val lines = src.replace("\r\n", "\n").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = line.trimStart().takeIf { it.startsWith("```") || it.startsWith("~~~") }?.take(3)
        when {
            fence != null -> {
                flush()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) code += lines[i++]
                out += MdBlock.Code(code.joinToString("\n"))
            }
            line.isBlank() -> flush()
            rule.matches(line) && para.isEmpty() -> out += MdBlock.Rule
            heading.matches(line) -> { flush(); heading.find(line)!!.let { out += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2]) } }
            line.trimStart().startsWith(">") -> { flush(); out += MdBlock.Quote(line.trimStart().removePrefix(">").trim()) }
            listItem.matches(line) -> {
                flush()
                val m = listItem.find(line)!!.groupValues
                val marker = when {
                    m[3].isNotEmpty() -> if (m[3].trim().lowercase() == "[x]") "☑" else "☐"
                    m[2].first().isDigit() -> m[2].dropLast(1) + "."
                    else -> "•"
                }
                out += MdBlock.ListItem(m[4], marker, m[1].replace("\t", "  ").length / 2)
            }
            else -> para.append(line.trim()).append(' ')
        }
        i++
    }
    flush()
    // merge consecutive quote lines into one block
    return out.fold(mutableListOf()) { acc, b ->
        val last = acc.lastOrNull()
        if (b is MdBlock.Quote && last is MdBlock.Quote) acc[acc.lastIndex] = MdBlock.Quote(last.text + "\n" + b.text) else acc += b
        acc
    }
}

private val inlineToken = Regex("""`([^`]+)`|\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)|\[([^\]]+)]\(([^)\s]+)\)|(https?://[^\s<>()]+[^\s<>().,;:!?'"])""")

/** Inline spans; only http(s) and mailto targets become links. */
internal fun inlineMarkdown(text: String, code: SpanStyle, link: TextLinkStyles): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in inlineToken.findAll(text)) {
        append(text.substring(at, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(code) { append(g[1]) }
            g[2].isNotEmpty() || g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inlineMarkdown(g[2] + g[3], code, link)) }
            g[4].isNotEmpty() || g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineMarkdown(g[4] + g[5], code, link)) }
            g[6].isNotEmpty() -> if (safeUrl(g[7])) withLink(LinkAnnotation.Url(g[7], link)) { append(g[6]) } else append(g[6])
            else -> withLink(LinkAnnotation.Url(g[8], link)) { append(g[8]) }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

private fun safeUrl(url: String) = url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")

@Composable
fun Markdown(src: String, modifier: Modifier = Modifier, base: TextStyle = MarginTheme.type.body.copy(color = MarginTheme.colors.text)) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    val blocks = remember(src) { parseMarkdown(src) }
    val code = SpanStyle(fontFamily = t.mono.fontFamily, fontSize = t.monoSmall.fontSize, background = c.hover)
    val link = TextLinkStyles(SpanStyle(color = c.accentText, textDecoration = TextDecoration.Underline))
    @Composable fun Text(s: String, style: TextStyle = base, m: Modifier = Modifier) = BasicText(inlineMarkdown(s, code, link), m, style = style)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        for (b in blocks) when (b) {
            is MdBlock.Heading -> Text(b.text, (if (b.level <= 2) t.title else t.bodyStrong).copy(color = c.text), Modifier.padding(top = if (b.level <= 2) Spacing.sm else 0.dp))
            is MdBlock.Paragraph -> Text(b.text)
            is MdBlock.ListItem -> Row(Modifier.padding(start = Spacing.lg * b.depth), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (b.marker == "☑" || b.marker == "☐") TaskBox(b.marker == "☑", Modifier.padding(top = 3.dp))
                else BasicText(b.marker, Modifier.width(if (b.marker.length > 2) 28.dp else 16.dp), style = base.copy(color = c.textTertiary))
                Text(b.text, m = Modifier.weight(1f))
            }
            is MdBlock.Code -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).background(c.hover).horizontalScroll(rememberScrollState())) {
                BasicText(b.text, Modifier.padding(Spacing.md), style = t.mono.copy(color = c.text), softWrap = false)
            }
            is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c.borderStrong))
                Text(b.text, base.copy(color = c.textSecondary), Modifier.padding(start = Spacing.md))
            }
            MdBlock.Rule -> Box(Modifier.padding(vertical = Spacing.xs).fillMaxWidth().height(1.dp).background(c.border))
        }
    }
}

/** Read-only task-list box, drawn rather than a ☐/☑ glyph so it matches the app's checkboxes in any font. */
@Composable
private fun TaskBox(done: Boolean, modifier: Modifier = Modifier) {
    val c = MarginTheme.colors
    val shape = RoundedCornerShape(Radius.xs)
    Box(
        modifier.width(16.dp).height(14.dp).padding(end = 2.dp).clip(shape)
            .background(if (done) c.success.copy(alpha = 0.85f) else c.hover).border(1.dp, if (done) c.success else c.textTertiary, shape),
        contentAlignment = Alignment.Center,
    ) { if (done) GlyphIcon(Glyph.Check, size = 10.dp, tint = c.onAccent) }
}

package margin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import margin.git.GitException
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import margin.git.Commit
import margin.git.DiffLine
import margin.git.FileChange
import margin.git.LineType
import margin.ui.components.*
import margin.ui.theme.MarginTheme
import margin.ui.theme.Motion
import margin.ui.theme.Radius
import margin.ui.theme.Sizes
import margin.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val NumberWidth = 40.dp
private val SignWidth = 20.dp
private val UnifiedGutter = NumberWidth * 2 + SignWidth
private val SideGutter = NumberWidth + SignWidth

@Composable
fun DiffPane(state: AppState) {
    if (state.tab == Tab.History) return CommitPane(state)
    val change = state.selected ?: return Box(Modifier.fillMaxSize()) {
        Placeholder(Glyph.Commit, "No file selected", "Pick a changed file to see its diff", Modifier.align(Alignment.Center))
    }
    val sides = state.status?.changes.orEmpty().filter { it.path == change.path }
    DiffSection(state, change, state.diff, preview = { state.repo?.fileText(change) }) {
        // partially staged: both diffs stay reachable
        if (sides.size == 2) Segmented(listOf("Unstaged" to null, "Staged" to null), if (change.staged) 1 else 0) { state.select(sides.first { s -> s.staged == (it == 1) }) }
        else Pill(if (change.staged) "Staged" else "Unstaged")
    }
}

// ---------- Commit ----------

@Composable
private fun CommitPane(state: AppState) {
    val commit = state.selectedCommit ?: return Box(Modifier.fillMaxSize()) {
        Placeholder(Glyph.Commit, "No commit selected", "Pick a commit to see what it changed", Modifier.align(Alignment.Center))
    }
    val files = state.commitFiles
    val file = state.commitFile
    Column(Modifier.fillMaxSize()) {
        CommitHeader(commit)
        HairlineDivider()
        when {
            files == null -> Box(Modifier.fillMaxSize()) { Spinner(Modifier.align(Alignment.Center)) }
            files.isEmpty() -> Box(Modifier.fillMaxSize()) {
                Placeholder(Glyph.Commit, "No file changes", "This commit changes nothing against its first parent", Modifier.align(Alignment.Center))
            }
            else -> {
                SectionHeader("Files", count = files.size, modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs))
                Box(Modifier.fillMaxWidth().heightIn(max = Sizes.rowHeight * 6 + Sizes.rowHeight / 2)) { // half a row peeks out when it scrolls
                    val listState = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxWidth(), listState, PaddingValues(start = Spacing.xs, end = Spacing.xs, bottom = Spacing.xs)) {
                        items(files, key = { it.path }) { FileRow(it, selected = it == file, onClick = { state.selectCommitFile(it) }) }
                    }
                    Box(Modifier.matchParentSize()) {
                        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
                    }
                }
                HairlineDivider()
                if (file != null) DiffSection(state, file, state.commitDiff, preview = { state.repo?.commitFileText(commit.hash, file) })
            }
        }
    }
}

@Composable
private fun CommitHeader(commit: Commit) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                BasicText(commit.subject, style = t.title.copy(color = c.text))
                if (commit.body.isNotEmpty()) BasicText(
                    commit.body, Modifier.heightIn(max = 112.dp).verticalScroll(rememberScrollState()),
                    style = t.body.copy(color = c.textSecondary),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(c.pressed), contentAlignment = Alignment.Center) {
                BasicText(commit.author.take(1).uppercase(), style = t.caption.copy(color = c.textSecondary, fontWeight = t.label.fontWeight))
            }
            BasicText(commit.author, style = t.label.copy(color = c.text), maxLines = 1)
            val absolute = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(commit.epochSeconds))
            BasicText(
                "${relativeTime(commit.epochSeconds)}  ·  $absolute", Modifier.weight(1f),
                style = t.caption.copy(color = c.textTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            HashChip(commit)
        }
    }
}

/** Short hash; a click copies the full hash and flips to "Copied" for a moment. */
@Composable
private fun HashChip(commit: Commit) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    var copied by remember(commit.hash) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) c.hover else c.hover.copy(alpha = 0f), Motion.fast())
    Row(
        Modifier.height(24.dp).clip(RoundedCornerShape(Radius.sm)).background(bg).border(1.dp, c.border, RoundedCornerShape(Radius.sm))
            .hoverable(source).clickable(source, null) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(commit.hash), null)
                copied = true
            }
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        GlyphIcon(if (copied) Glyph.Check else Glyph.Copy, size = 12.dp, tint = if (copied) c.success else c.textTertiary)
        BasicText(if (copied) "Copied" else commit.shortHash, style = (if (copied) t.label else t.monoSmall).copy(color = if (hovered || copied) c.text else c.textSecondary, lineHeight = t.label.lineHeight))
    }
}

// ---------- Diff ----------

/** A side-by-side row: [left] is the old side, [right] the new one, null is filler. Context and hunk rows carry the same line on both. */
data class SideBySideRow(val left: DiffLine?, val right: DiffLine?)

private val DiffLine?.isChange get() = this != null && (type == LineType.Added || type == LineType.Removed)

/** Pairs each change block's removed lines (left) with its added lines (right), row by row. */
internal fun sideBySide(lines: List<DiffLine>): List<SideBySideRow> {
    val rows = ArrayList<SideBySideRow>(lines.size)
    var i = 0
    while (i < lines.size) {
        if (!lines[i].isChange) { rows += SideBySideRow(lines[i], lines[i]); i++; continue }
        var end = i
        while (end < lines.size && lines[end].isChange) end++
        val (removed, added) = lines.subList(i, end).partition { it.type == LineType.Removed }
        repeat(maxOf(removed.size, added.size)) { rows += SideBySideRow(removed.getOrNull(it), added.getOrNull(it)) }
        i = end
    }
    return rows
}

/** Index ranges of the maximal runs of rows where [isChange] holds: the change blocks F7 walks. */
internal fun <T> changeBlocks(rows: List<T>, isChange: (T) -> Boolean): List<IntRange> {
    val blocks = mutableListOf<IntRange>()
    var start = -1
    for (i in 0..rows.size) {
        val change = i < rows.size && isChange(rows[i])
        if (change && start < 0) start = i
        if (!change && start >= 0) { blocks += start until i; start = -1 }
    }
    return blocks
}

internal fun unifiedBlocks(lines: List<DiffLine>) = changeBlocks(lines) { it.isChange }
internal fun sideBySideBlocks(rows: List<SideBySideRow>) = changeBlocks(rows) { it.left.isChange || it.right.isChange }

/** Keyed by line numbers, so toggling context or layout keeps the scroll anchored on the same line. Falls back to indices if they ever collide. */
private fun lineKeys(rows: Int, key: (Int) -> Long): List<Long> =
    List(rows, key).let { keys -> if (keys.toSet().size == keys.size) keys else List(rows) { it.toLong() } }

private fun key(i: Int, old: DiffLine?, new: DiffLine?): Long =
    if (old?.type == LineType.Hunk) -1L - i else (old?.oldNo ?: 0).toLong() shl 32 or (new?.newNo ?: 0).toLong()

/** Everything the body derives from one load, computed once: rows, blocks and keys for both layouts. */
private class DiffModel(val lines: List<DiffLine>) {
    val rows = sideBySide(lines)
    val unifiedBlocks = unifiedBlocks(lines)
    val splitBlocks = sideBySideBlocks(rows)
    val unifiedKeys = lineKeys(lines.size) { key(it, lines[it], lines[it]) }
    val splitKeys = lineKeys(rows.size) { key(it, rows[it].left, rows[it].right) }
    val chars = lines.maxOf { it.chars }
    val leftChars = lines.filter { it.type != LineType.Added }.maxOfOrNull { it.chars } ?: 0
    val rightChars = lines.filter { it.type != LineType.Removed }.maxOfOrNull { it.chars } ?: 0
}

private val DiffLine.chars get() = text.length + 3 * text.count { it == '\t' } // after expandTabs

/**
 * Header and body of one file's diff; owns the change-block navigation both layouts share.
 * [below] adds content under a line (MR comments); [onGutter] makes line numbers clickable (new inline comment).
 * [wholeFileToggle] is off where only the hunks exist, e.g. a merge request diff from the API.
 */
@Composable
internal fun DiffSection(
    state: AppState,
    change: FileChange,
    lines: List<DiffLine>?,
    wholeFileToggle: Boolean = true,
    below: ((DiffLine) -> (@Composable () -> Unit)?)? = null,
    onGutter: ((DiffLine) -> Unit)? = null,
    preview: (suspend () -> String?)? = null,
    trailing: @Composable () -> Unit = {},
) = key(change.path) {
    val split = state.sideBySide
    val canPreview = preview != null && change.path.substringAfterLast('.').lowercase() in MarkdownExtensions
    val previewing = canPreview && state.mdPreview
    val model = remember(lines) { lines?.takeIf { it.isNotEmpty() }?.let(::DiffModel) }
    val blocks = model?.let { if (split) it.splitBlocks else it.unifiedBlocks }.orEmpty()
    var current by remember(model) { mutableIntStateOf(-1) } // -1 until the first navigation
    var navTick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState() // a new file starts scrolled to the top
    val flash = remember { Animatable(0f) }
    fun go(step: Int) {
        if (current + step !in blocks.indices) return
        current += step
        navTick++
    }
    LaunchedEffect(blocks) { state.changeNav.collect(::go) }
    LaunchedEffect(navTick) {
        val block = blocks.getOrNull(current) ?: return@LaunchedEffect
        launch { listState.animateScrollToItem(block.first, -listState.layoutInfo.viewportSize.height / 3) } // block lands ~1/3 down
        flash.animateTo(1f, Motion.fast())
        delay(Motion.SLOW * 3L)
        flash.animateTo(0f, Motion.exit())
    }
    Column(Modifier.fillMaxSize()) {
        DiffHeader(change, lines) {
            if (canPreview) Segmented(listOf("Diff" to null, "Preview" to null), if (previewing) 1 else 0) { state.mdPreview = it == 1 }
            if (!previewing && blocks.isNotEmpty()) ChangeNav(current, blocks.size, ::go)
            if (!previewing) GlyphSegmented(
                listOf(Triple(Glyph.Unified, null, "Unified"), Triple(Glyph.Split, null, "Side-by-side")),
                if (split) 1 else 0,
            ) { state.showSideBySide(it == 1) }
            if (wholeFileToggle && !previewing) GlyphSegmented(
                listOf(Triple(Glyph.Fold, null, "Changes only"), Triple(Glyph.Unfold, null, "Whole file")),
                if (state.wholeFile) 1 else 0,
            ) { state.showWholeFile(it == 1) }
            trailing()
        }
        HairlineDivider()
        Box(Modifier.fillMaxSize()) {
            when {
                previewing -> MarkdownPreview(lines, preview!!) // keyed on lines: reloads when the diff refreshes
                lines == null -> Spinner(Modifier.align(Alignment.Center))
                model == null -> Placeholder(Glyph.Commit, "No text changes", "Binary file, empty file, pure rename or mode change", Modifier.align(Alignment.Center))
                else -> DiffLines(model, split, listState, blocks.getOrNull(current), flash, below, onGutter)
            }
        }
    }
}

private val MarkdownExtensions = setOf("md", "markdown", "mdx")

/** The rendered file, readable width, selectable. */
@Composable
private fun MarkdownPreview(reloadKey: Any?, load: suspend () -> String?) {
    val text by produceState<String?>(null, reloadKey) {
        value = try { load().orEmpty() } catch (e: GitException) { "" } catch (e: java.io.IOException) { "" }
    }
    Box(Modifier.fillMaxSize()) {
        when (val src = text) {
            null -> Spinner(Modifier.align(Alignment.Center))
            "" -> Placeholder(Glyph.Commit, "Nothing to preview", "The file is empty or can't be read", Modifier.align(Alignment.Center))
            else -> {
                val scroll = rememberScrollState()
                SelectionContainer(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xl), contentAlignment = Alignment.TopCenter) {
                        Markdown(src, Modifier.widthIn(max = 760.dp).fillMaxWidth())
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
            }
        }
    }
}

@Composable
private fun DiffHeader(change: FileChange, lines: List<DiffLine>?, trailing: @Composable RowScope.() -> Unit) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(start = Spacing.lg, end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        KindBadge(change.kind)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BasicText(change.path.substringAfterLast('/'), style = t.bodyStrong.copy(color = c.text), maxLines = 1)
            StartEllipsisText(
                listOfNotNull(change.oldPath?.let { "$it →" }, change.path.substringBeforeLast('/', "").ifEmpty { null }).joinToString(" "),
                t.caption.copy(color = c.textTertiary), Modifier.weight(1f, fill = false),
            )
        }
        if (lines != null) {
            val added = lines.count { it.type == LineType.Added }
            val removed = lines.count { it.type == LineType.Removed }
            if (added > 0) BasicText("+$added", style = t.monoSmall.copy(color = c.diffAddedSign))
            if (removed > 0) BasicText("−$removed", style = t.monoSmall.copy(color = c.diffRemovedSign))
        }
        trailing()
    }
}

/** "7 changes" until the first jump, then "2 / 7", followed by ↑ ↓. */
@Composable
private fun ChangeNav(current: Int, count: Int, go: (Int) -> Unit) {
    val c = MarginTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            if (current < 0) "$count ${if (count == 1) "change" else "changes"}" else "${current + 1} / $count",
            Modifier.padding(end = Spacing.xs), style = MarginTheme.type.caption.copy(color = c.textTertiary, fontFeatureSettings = "tnum"), maxLines = 1,
        )
        for ((step, glyph, tip) in listOf(Triple(-1, Glyph.ArrowUp, "Previous change  ⇧F7"), Triple(1, Glyph.ArrowDown, "Next change  F7"))) {
            val enabled = current + step in 0 until count
            TooltipIconButton(tip, { go(step) }, enabled = enabled) { GlyphIcon(glyph, size = 14.dp, tint = if (enabled) c.textSecondary else c.textDisabled) }
        }
    }
}

/** Compact glyph segmented control for the diff header: (glyph, optional label, tooltip). Styled like [Segmented]. */
@Composable
private fun GlyphSegmented(options: List<Triple<Glyph, String?, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val c = MarginTheme.colors
    Row(
        Modifier.height(Sizes.iconButton).clip(RoundedCornerShape(Radius.sm)).background(c.hover).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, (glyph, label, tip) ->
            val on = i == selected
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            val bg by animateColorAsState(if (on) c.surfaceRaised else c.hover.copy(alpha = 0f), Motion.base())
            val fg by animateColorAsState(if (on || hovered) c.text else c.textTertiary, Motion.fast())
            Tooltip(tip) {
                Row(
                    Modifier.fillMaxHeight().clip(RoundedCornerShape(Radius.sm - 2.dp)).background(bg)
                        .then(if (on) Modifier.border(1.dp, c.border, RoundedCornerShape(Radius.sm - 2.dp)) else Modifier)
                        .hoverable(source).clickable(source, null) { onSelect(i) }
                        .padding(horizontal = if (label == null) Spacing.sm else Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    GlyphIcon(glyph, size = 14.dp, tint = fg)
                    if (label != null) BasicText(label, style = MarginTheme.type.label.copy(color = fg), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DiffLines(
    model: DiffModel, split: Boolean, listState: androidx.compose.foundation.lazy.LazyListState, current: IntRange?, flash: Animatable<Float, *>,
    below: ((DiffLine) -> (@Composable () -> Unit)?)?, onGutter: ((DiffLine) -> Unit)?,
) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val charWidth = remember(measurer) { measurer.measure("0".repeat(100), t.mono).size.width / 100f }
    fun width(chars: Int) = with(density) { (charWidth * chars).toDp() } + Spacing.xxl
    val rowHeight = remember(measurer) { with(density) { measurer.measure("0", t.mono).size.height.toDp() } } // what a one-line BasicText really takes
    // One ScrollState per text column, shared by all its rows: every row has the same width, so they scroll as one.
    val hUnified = rememberScrollState()
    val hLeft = rememberScrollState()
    val hRight = rememberScrollState()
    val highlight = Modifier.drawWithContent {
        drawRect(c.accent.copy(alpha = 0.14f * flash.value))
        drawContent()
        drawRect(c.accent, size = Size(3.dp.toPx(), size.height))
    }
    Box(Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), listState, PaddingValues(bottom = Spacing.md)) {
                if (split) items(model.rows.size, { model.splitKeys[it] }, { model.rows[it].left?.type == LineType.Hunk }) { i ->
                    val (left, right) = model.rows[i]
                    if (left?.type == LineType.Hunk) return@items HunkRow(left.text, i == 0, SideGutter)
                    Column {
                        Row(Modifier.fillMaxWidth().then(if (current != null && i in current) highlight else Modifier)) {
                            Cell(left, listOf(left?.oldNo), hLeft, width(model.leftChars), rowHeight, Modifier.weight(1f), onGutter)
                            Box(Modifier.width(1.dp).height(rowHeight).background(c.border))
                            Cell(right, listOf(right?.newNo), hRight, width(model.rightChars), rowHeight, Modifier.weight(1f), onGutter)
                        }
                        // a context row carries the same line on both sides: show its notes once
                        listOfNotNull(left, right.takeIf { it !== left }).forEach { l -> below?.invoke(l)?.let { DisableSelection { it() } } }
                    }
                } else items(model.lines.size, { model.unifiedKeys[it] }, { model.lines[it].type }) { i ->
                    val line = model.lines[i]
                    if (line.type == LineType.Hunk) return@items HunkRow(line.text, i == 0, UnifiedGutter)
                    Column {
                        Cell(
                            line, listOf(line.oldNo, line.newNo), hUnified, width(model.chars), rowHeight,
                            Modifier.fillMaxWidth().then(if (current != null && i in current) highlight else Modifier), onGutter,
                        )
                        below?.invoke(line)?.let { DisableSelection { it() } }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(2.dp)) {
            if (split) {
                HorizontalScrollbar(rememberScrollbarAdapter(hLeft), Modifier.weight(1f).padding(start = SideGutter))
                HorizontalScrollbar(rememberScrollbarAdapter(hRight), Modifier.weight(1f).padding(start = SideGutter))
            } else HorizontalScrollbar(rememberScrollbarAdapter(hUnified), Modifier.weight(1f).padding(start = UnifiedGutter))
        }
    }
}

@Composable
private fun HunkRow(text: String, first: Boolean, gutter: Dp) {
    val c = MarginTheme.colors
    Column {
        if (!first) Spacer(Modifier.height(Spacing.md))
        BasicText(
            text, Modifier.fillMaxWidth().background(c.diffHunkBg).padding(start = gutter, top = 2.dp, bottom = 2.dp),
            style = MarginTheme.type.monoSmall.copy(color = c.diffHunkText), maxLines = 1, softWrap = false,
        )
    }
}

/** Line numbers, sign and text; the text scrolls with [scroll] while the gutter stays put. A null line is hatched filler. */
@Composable
private fun Cell(line: DiffLine?, numbers: List<Int?>, scroll: ScrollState, textWidth: Dp, height: Dp, modifier: Modifier, onGutter: ((DiffLine) -> Unit)? = null) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    if (line == null) return Box(modifier.height(height).hatched(c.border))
    val (gutter, sign, signColor) = when (line.type) {
        LineType.Added -> Triple(c.diffAddedGutter, "+", c.diffAddedSign)
        LineType.Removed -> Triple(c.diffRemovedGutter, "-", c.diffRemovedSign)
        else -> Triple(c.diffLineBg(line.type), " ", c.diffLineNumber)
    }
    Row(modifier.background(c.diffLineBg(line.type))) {
        DisableSelection {
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Row(
                Modifier.background(if (onGutter != null && hovered) c.accent.copy(alpha = 0.22f) else gutter)
                    .then(if (onGutter != null) Modifier.hoverable(source).clickable(source, null) { onGutter(line) } else Modifier),
            ) { numbers.forEach { LineNumber(it) } }
            BasicText(sign, Modifier.width(SignWidth), style = t.mono.copy(color = signColor, textAlign = TextAlign.Center))
        }
        BasicText(line.text.expandTabs(), Modifier.weight(1f).horizontalScroll(scroll).width(textWidth), style = t.mono.copy(color = c.text), maxLines = 1, softWrap = false)
    }
}

/** 45° hairline stripes, three per row so they run on unbroken from row to row. */
private fun Modifier.hatched(color: Color) = clipToBounds().drawBehind {
    val h = size.height
    var x = -h
    while (x < size.width) {
        drawLine(color, Offset(x, h), Offset(x + h, 0f), 1.dp.toPx())
        x += h / 3
    }
}

@Composable
private fun LineNumber(n: Int?) {
    BasicText(
        n?.toString().orEmpty(), Modifier.width(NumberWidth).padding(end = Spacing.md),
        style = MarginTheme.type.monoSmall.copy(color = MarginTheme.colors.diffLineNumber, textAlign = TextAlign.End), maxLines = 1,
    )
}

private fun String.expandTabs() = replace("\t", "    ")

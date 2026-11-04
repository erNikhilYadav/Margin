package margin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import margin.git.Branch
import margin.git.GitRepo
import margin.git.Worktree
import margin.ui.components.*
import margin.ui.theme.MarginTheme
import margin.ui.theme.Motion
import margin.ui.theme.Radius
import margin.ui.theme.Sizes
import margin.ui.theme.Spacing

/** Places a Popup 4dp under a 28dp toolbar chip. */
@Composable
private fun belowChip() = with(LocalDensity.current) { IntOffset(0, (Sizes.iconButton + Spacing.xs).roundToPx()) }

// ---------- Repo switcher ----------

@Composable
fun RepoSwitcher(state: AppState, onOpen: () -> Unit) {
    val c = MarginTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarChip(open, { open = !open }, Modifier.widthIn(max = 200.dp)) {
            BasicText(
                state.repo?.dir?.name.orEmpty(), Modifier.weight(1f, fill = false),
                style = MarginTheme.type.title.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            GlyphIcon(Glyph.ChevronDown, size = 12.dp, tint = c.textTertiary)
        }
        if (open) Popup(offset = belowChip(), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            LaunchedEffect(Unit) { state.loadWorktrees() }
            Popover(Modifier.width(280.dp)) {
                Column(Modifier.padding(Spacing.xs)) {
                    if (state.worktrees.size > 1) {
                        SectionHeader("Worktrees", trailing = { BasicText("↑↓ vs ${GitRepo.BASE}", style = MarginTheme.type.caption.copy(color = c.textTertiary)) })
                        state.worktrees.forEach { wt -> WorktreeRow(state, wt) { open = false; state.open(wt.dir) } }
                        HairlineDivider(Modifier.padding(vertical = Spacing.xs))
                    }
                    SectionHeader("Recent")
                    state.recents.forEach { dir ->
                        SidebarItem(
                            dir.name, { open = false; state.open(dir) },
                            selected = dir == state.repo?.dir,
                            leading = { GlyphIcon(Glyph.Repo, tint = if (dir == state.repo?.dir) c.accentText else c.textTertiary) },
                        )
                    }
                    HairlineDivider(Modifier.padding(vertical = Spacing.xs))
                    SidebarItem(
                        "Open…", { open = false; onOpen() },
                        leading = { GlyphIcon(Glyph.Plus, tint = c.textTertiary) },
                        trailing = { BasicText("⌘O", style = MarginTheme.type.caption.copy(color = c.textTertiary)) },
                    )
                }
            }
        }
    }
}

/** Click opens the worktree; × asks once, then removes it. The open one and the main one can't be removed. */
@Composable
private fun WorktreeRow(state: AppState, wt: Worktree, onOpen: () -> Unit) {
    val c = MarginTheme.colors
    val current = wt.dir.canonicalFile == state.repo?.dir?.canonicalFile // git reports real paths; ours may go through a symlink
    var confirming by remember(wt) { mutableStateOf(false) }
    SidebarItem(
        wt.dir.name, onOpen,
        selected = current,
        subtitle = listOfNotNull(wt.branch ?: "detached", wt.from?.let { "from $it" }).joinToString(" · "),
        leading = { GlyphIcon(Glyph.Branch, tint = if (current) c.accentText else c.textTertiary) },
        trailing = {
            if (confirming) PrimaryButton("Remove", { confirming = false; state.removeWorktree(wt) }, danger = true)
            else {
                if (wt.ahead != null && wt.behind != null) Tooltip("${wt.ahead} ahead, ${wt.behind} behind ${GitRepo.BASE}") {
                    BasicText("↑${wt.ahead} ↓${wt.behind}", style = MarginTheme.type.caption.copy(color = c.textSecondary))
                }
                if (!current && !wt.isMain) TooltipIconButton("Remove worktree", { confirming = true }) {
                    GlyphIcon(Glyph.Close, size = 12.dp, tint = c.textTertiary)
                }
            }
        },
    )
}

/** ⋯ at the toolbar's end; its popover opens right-aligned under it. */
@Composable
fun MoreMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton("More", { open = !open }) { GlyphIcon(Glyph.More) }
        if (open) Popup(Alignment.TopEnd, belowChip(), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Popover(Modifier.width(220.dp)) {
                Column(Modifier.padding(Spacing.xs)) {
                    items.forEach { (label, action) -> SidebarItem(label, { open = false; action() }) }
                }
            }
        }
    }
}

// ---------- Branch chip + popup ----------

/** Current branch (or "Detached at <hash>"); opens the branch popup. Truncates before the repo chip does. */
@Composable
fun BranchChip(state: AppState, modifier: Modifier = Modifier) {
    val c = MarginTheme.colors
    val s = state.status ?: return
    val name = s.branch ?: "Detached at ${state.log.firstOrNull()?.shortHash ?: "HEAD"}"
    Box(modifier) {
        Tooltip("$name  ⌘B") {
            ToolbarChip(state.branchMenuOpen, { state.branchMenuOpen = !state.branchMenuOpen }, Modifier.widthIn(max = 280.dp)) {
                if (Action.Checkout in state.busy) Spinner(size = 14.dp) else GlyphIcon(Glyph.Branch, size = 14.dp, tint = c.textTertiary)
                BasicText(
                    name, Modifier.weight(1f, fill = false),
                    style = MarginTheme.type.bodyStrong.copy(color = c.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                GlyphIcon(Glyph.ChevronDown, size = 12.dp, tint = c.textTertiary)
            }
        }
        if (state.branchMenuOpen) Popup(
            offset = belowChip(),
            onDismissRequest = { state.branchMenuOpen = false },
            properties = PopupProperties(focusable = true),
        ) { BranchPopup(state) }
    }
}

private sealed interface Entry {
    data object New : Entry
    data class Header(val title: String, val count: Int) : Entry
    data class Item(val section: String, val branch: Branch) : Entry
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BranchPopup(state: AppState) {
    val c = MarginTheme.colors
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val q = query.trim()
    val entries = remember(state.branches, q) { branchEntries(state.branches, q) }
    var hi by remember(entries) { mutableStateOf(entries.indexOfFirst { it is Entry.Item }.coerceAtLeast(0)) }
    val list = rememberLazyListState()
    val search = remember { FocusRequester() }
    LaunchedEffect(Unit) { search.requestFocus() }
    LaunchedEffect(hi) { // keep the highlight in view, with its section header when it is the first row
        val info = list.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull { it.offset >= 0 }?.index ?: return@LaunchedEffect
        val last = info.visibleItemsInfo.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: return@LaunchedEffect
        if (hi < first) list.scrollToItem(if (entries.getOrNull(hi - 1) is Entry.Header) hi - 1 else hi)
        else if (hi > last) list.scrollToItem(first + hi - last)
    }
    val close = { state.branchMenuOpen = false }
    fun activate(e: Entry?) {
        if (e == Entry.New) creating = true
        if (e is Entry.Item) { close(); state.checkout(e.branch) }
    }
    fun step(d: Int) {
        var i = hi + d
        while (entries.getOrNull(i) is Entry.Header) i += d
        if (i in entries.indices) hi = i
    }

    Popover(Modifier.width(360.dp).heightIn(max = 480.dp)) {
        Column {
            InputField(
                query, { query = it }, "Search branches",
                Modifier.fillMaxWidth().padding(Spacing.sm).focusRequester(search).onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (it.key) {
                        Key.DirectionDown -> step(1)
                        Key.DirectionUp -> step(-1)
                        Key.Enter, Key.NumPadEnter -> activate(entries.getOrNull(hi))
                        Key.Escape -> close()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
            )
            HairlineDivider()
            Box(Modifier.weight(1f, fill = false)) {
                LazyColumn(state = list, contentPadding = PaddingValues(Spacing.xs)) {
                    itemsIndexed(entries, key = { _, e -> e.key() }) { i, e ->
                        val hover = Modifier.onPointerEvent(PointerEventType.Enter) { ev ->
                            if (ev.awtEventOrNull != null) hi = i // real mouse moves only: rows scrolling under a resting pointer don't steal the highlight
                        }
                        when (e) {
                            is Entry.Header -> SectionHeader(e.title, Modifier.padding(top = if (i > 1) Spacing.xs else 0.dp), count = e.count)
                            Entry.New -> if (creating) NewBranchInput(from = state.status?.branch) { name ->
                                creating = false
                                if (name.isNullOrBlank()) search.requestFocus() else { close(); state.createBranch(name) }
                            } else MenuRow(i == hi, { activate(e) }, hover) {
                                GlyphIcon(Glyph.Plus, size = 14.dp, tint = c.textTertiary)
                                BasicText("New Branch…", style = MarginTheme.type.body.copy(color = c.text))
                            }
                            is Entry.Item -> BranchRow(e.branch, q, i == hi, { activate(e) }, hover)
                        }
                    }
                    if (entries.none { it is Entry.Item }) item {
                        BasicText(
                            if (q.isEmpty()) "No branches yet" else "No branches match “$q”",
                            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            style = MarginTheme.type.caption.copy(color = c.textTertiary),
                        )
                    }
                }
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
                }
            }
        }
    }
}

private fun Entry.key() = when (this) {
    Entry.New -> "new"
    is Entry.Header -> "h/$title"
    is Entry.Item -> "$section/${branch.name}"
}

/** Recent = the 5 newest local branches other than the current one ([AppState.branches] is newest-commit first). */
private fun branchEntries(branches: List<Branch>, q: String): List<Entry> {
    val (local, remote) = branches.partition { !it.isRemote }
    val sections = listOf(
        "Recent" to local.filter { !it.isCurrent }.take(5),
        "Local" to local.sortedBy { it.name.lowercase() },
        "Remote" to remote.sortedBy { it.name.lowercase() },
    )
    return buildList {
        add(Entry.New)
        for ((title, all) in sections) {
            val shown = all.filter { it.name.contains(q, ignoreCase = true) }
            if (shown.isEmpty()) continue
            add(Entry.Header(title, shown.size))
            shown.mapTo(this) { Entry.Item(title, it) }
        }
    }
}

/** Popup row: highlight (keyboard or hover) uses the selection tint, like a native menu. */
@Composable
private fun MenuRow(highlighted: Boolean, onClick: () -> Unit, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val c = MarginTheme.colors
    val bg by animateColorAsState(if (highlighted) c.selected else c.selected.copy(alpha = 0f), Motion.fast())
    Row(
        modifier.fillMaxWidth().height(Sizes.rowHeight).clip(RoundedCornerShape(Radius.sm)).background(bg)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

@Composable
private fun BranchRow(b: Branch, q: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = MarginTheme.colors
    val base = if (b.isRemote) c.textSecondary else c.text
    val label = remember(b.name, q, base, c) {
        buildAnnotatedString {
            val at = if (q.isEmpty()) -1 else b.name.indexOf(q, ignoreCase = true)
            if (at < 0) return@buildAnnotatedString append(b.name)
            append(b.name.take(at))
            withStyle(SpanStyle(color = c.accentText, fontWeight = FontWeight.SemiBold)) { append(b.name.substring(at, at + q.length)) }
            append(b.name.drop(at + q.length))
        }
    }
    MenuRow(highlighted, onClick, modifier) {
        GlyphIcon(Glyph.Branch, size = 14.dp, tint = if (b.isCurrent) c.accentText else c.textTertiary)
        BasicText(
            label, Modifier.weight(1f),
            style = (if (b.isCurrent) MarginTheme.type.bodyStrong else MarginTheme.type.body).copy(color = base),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (b.isCurrent) GlyphIcon(Glyph.Check, size = 14.dp, tint = c.accentText)
    }
}

/** Inline "+ New Branch…" editor: Enter creates from the current branch and switches, Esc cancels. */
@Composable
private fun NewBranchInput(from: String?, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.padding(vertical = Spacing.xxs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        InputField(
            name, { name = it.replace(' ', '-') }, "new-branch-name",
            Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.Enter, Key.NumPadEnter -> { onDone(name); true }
                    Key.Escape -> { onDone(null); true }
                    else -> false
                }
            },
        )
        BasicText(
            "↵ create and switch  ·  esc cancel  ·  from ${from ?: "HEAD"}", Modifier.padding(horizontal = Spacing.sm),
            style = MarginTheme.type.caption.copy(color = MarginTheme.colors.textTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

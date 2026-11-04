package margin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.awt.Desktop
import java.io.File
import kotlin.concurrent.thread
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.state.ToggleableState
import margin.git.ChangeKind
import margin.git.Commit
import margin.git.FileChange
import margin.ui.components.*
import margin.ui.theme.MarginTheme
import margin.ui.theme.Motion
import margin.ui.theme.Radius
import margin.ui.theme.Sizes
import margin.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------- Changes ----------

@Composable
fun ChangesPane(state: AppState) {
    val (untracked, tracked) = state.files.partition { it[0].kind == ChangeKind.Untracked }
    // Changes always shows, like Android Studio; Unversioned only when there are untracked files.
    val groups = listOf("Changes" to tracked, "Unversioned" to untracked).filter { it.first == "Changes" || it.second.isNotEmpty() }
    val ordered = groups.filter { it.first !in state.collapsedGroups }.flatMap { it.second }.map { it[0].path } // display order, for ⇧-click ranges and ⌘A (collapsed groups are skipped)
    var anchor by remember { mutableStateOf<String?>(null) } // last plain or ⌘ click
    val listFocus = remember { FocusRequester() } // a row click focuses the list, so ⌘A lands here and not in the commit box
    Column(Modifier.fillMaxSize()) {
        ChangesActions(state)
        HairlineDivider()
        Box(
            Modifier.weight(1f).fillMaxWidth().focusRequester(listFocus).focusable().onKeyEvent {
                if (it.type != KeyEventType.KeyDown || !it.isMetaPressed || it.key != Key.A) return@onKeyEvent false
                state.selection = ordered.toSet()
                true
            },
        ) {
            ScrollableList { listState ->
                LazyColumn(Modifier.fillMaxSize(), listState) {
                    groups.forEachIndexed { i, (title, group) ->
                        val open = title !in state.collapsedGroups
                        val onToggle = {
                            // collapsing a stuck header would leave the list scrolled past it: bring the header back to the top
                            val at = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == title }?.index
                            state.toggleGroup(title)
                            if (at != null && listState.firstVisibleItemIndex > at) listState.requestScrollToItem(at)
                        }
                        group(title, last = i == groups.lastIndex, open, header = { GroupHeader(state, title, group, open, onToggle) }) {
                            if (group.isEmpty() && state.status != null) item(key = "$title-empty") {
                                BasicText(
                                    "No changes found",
                                    Modifier.padding(start = Spacing.md + 20.dp + Spacing.sm, top = Spacing.sm, bottom = Spacing.sm), // under the header title
                                    style = MarginTheme.type.caption.copy(color = MarginTheme.colors.textTertiary),
                                )
                            }
                            items(group, key = { it[0].path }) { sides -> ChangeRow(state, sides, ordered, anchor, listFocus) { anchor = it } }
                        }
                    }
                }
            }
        }
        HairlineDivider()
        CommitBox(state)
    }
}

private fun checkState(sides: List<FileChange>) = when {
    sides.all { it.staged } -> ToggleableState.On
    sides.none { it.staged } -> ToggleableState.Off
    else -> ToggleableState.Indeterminate
}

/** On unstages; Off and Indeterminate stage everything, like Android Studio. */
private fun toggle(state: AppState, sides: List<FileChange>, current: ToggleableState) =
    if (current == ToggleableState.On) state.unstage(sides) else state.stage(sides)

/** One file in the Changes list: click selects, ⌘-click toggles, ⇧-click selects a range; right-click opens the row menu. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChangeRow(state: AppState, sides: List<FileChange>, ordered: List<String>, anchor: String?, listFocus: FocusRequester, setAnchor: (String) -> Unit) {
    val path = sides[0].path
    var mods by remember { mutableStateOf(PointerKeyboardModifiers()) } // of the last press, read by the click that follows
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    val showDiff = { state.select(state.selected?.takeIf { it.path == path } ?: sides.last()) } // partially staged: unstaged side first
    val rollback = { state.requestRollback(if (path in state.selection) state.selection else setOf(path)) }
    Box(Modifier.padding(horizontal = Spacing.xs)) {
        FileRow(
            sides[0], selected = path in state.selection, strong = path == state.selected?.path,
            selectedFill = MarginTheme.colors.pressed, // neutral: the accent here would read as "checked for commit"
            onClick = {
                listFocus.requestFocus()
                val sel = state.selection
                state.selection = when {
                    mods.isMetaPressed -> if (path in sel) sel - path else sel + path
                    mods.isShiftPressed && anchor in ordered && path in ordered -> {
                        val (a, b) = listOf(ordered.indexOf(anchor), ordered.indexOf(path)).sorted()
                        ordered.subList(a, b + 1).toSet()
                    }
                    else -> setOf(path)
                }
                if (!mods.isShiftPressed) setAnchor(path)
                if (path in state.selection) showDiff()
            },
            modifier = Modifier.onPointerEvent(PointerEventType.Press) { e ->
                mods = e.keyboardModifiers
                if (e.buttons.isSecondaryPressed) menuAt = e.changes.first().position.round()
            },
            trailing = { hovered -> if (hovered) RollbackButton("Rollback…", rollback) },
        ) { Checkbox(checkState(sides), { toggle(state, sides, checkState(sides)) }) }
        menuAt?.let { at -> RowMenu(at, sides[0], state.repo?.dir, onDismiss = { menuAt = null }, onShowDiff = { state.selection = setOf(path); setAnchor(path); showDiff() }, onRollback = rollback) }
    }
}

/** Android Studio style bar over the list, so Rollback is visible without hovering or right-clicking. */
@Composable
private fun ChangesActions(state: AppState) {
    val c = MarginTheme.colors
    val enabled = state.files.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Tooltip("Rollback…  ⌥⌘Z") {
            GhostButton(
                "Rollback", { state.requestRollback() }, Modifier.height(24.dp), enabled, // no count: the dialog's checkboxes show what goes
                leading = { GlyphIcon(Glyph.Rollback, size = 14.dp, tint = if (enabled) c.textSecondary else c.textDisabled) },
            )
        }
        Spacer(Modifier.weight(1f))
        TooltipIconButton("Refresh  ⌘R", state::refresh, Modifier.size(24.dp)) { GlyphIcon(Glyph.Fetch, size = 14.dp) }
    }
}

@Composable
private fun RollbackButton(tooltip: String, onClick: () -> Unit) =
    TooltipIconButton(tooltip, onClick, Modifier.size(22.dp)) { GlyphIcon(Glyph.Rollback, size = 14.dp) }

/** Android Studio style row menu, drawn as a Popover at the click point. */
@Composable
private fun RowMenu(at: IntOffset, change: FileChange, root: File?, onDismiss: () -> Unit, onShowDiff: () -> Unit, onRollback: () -> Unit) {
    val c = MarginTheme.colors
    @Composable fun item(label: String, shortcut: String? = null, action: () -> Unit) = SidebarItem(
        label, { onDismiss(); action() },
        trailing = shortcut?.let { { BasicText(it, style = MarginTheme.type.caption.copy(color = c.textTertiary)) } },
    )
    Popup(offset = at, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Popover(Modifier.width(220.dp)) {
            Column(Modifier.padding(Spacing.xs)) {
                item("Show Diff", action = onShowDiff)
                item("Rollback…", "⌥⌘Z", onRollback)
                if (root != null && change.kind != ChangeKind.Deleted) {
                    HairlineDivider(Modifier.padding(vertical = Spacing.xs))
                    item("Reveal in Finder") { thread { runCatching { Desktop.getDesktop().browseFileDirectory(File(root, change.path)) } } }
                }
            }
        }
    }
}

/**
 * One list group: a sticky header band, then (when open) its rows with a little air above and below, then a full-width hairline before the next group.
 * The band carries its own bottom hairline, so a collapsed group or a band stuck under the action bar never doubles a divider.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.group(key: String, last: Boolean, open: Boolean, header: @Composable () -> Unit, rows: LazyListScope.() -> Unit) {
    stickyHeader(key) { header() }
    if (!open) return
    item(key = "$key-top") { Spacer(Modifier.height(Spacing.xs)) }
    rows()
    item(key = "$key-end") { Spacer(Modifier.height(Spacing.xs)) }
    if (!last) item(key = "$key-rule") { HairlineDivider() }
}

/** Full-width header band shared by the Changes list and the rollback dialog: sectionBand fill, hairline below, checkbox in the rows' checkbox column. */
@Composable
private fun GroupBand(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) = Column(Modifier.fillMaxWidth().background(MarginTheme.colors.sectionBand)) {
    Row(
        modifier.fillMaxWidth().height(Sizes.rowHeight).padding(start = Spacing.md, end = Spacing.md + Spacing.xxs), // matches the inset rows, so checkboxes and rollback icons line up
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content,
    )
    HairlineDivider()
}

/** Overline title and its count in a quiet pill. */
@Composable
private fun GroupTitle(title: String, count: Int) {
    val c = MarginTheme.colors
    BasicText(title.uppercase(), style = MarginTheme.type.overline.copy(color = c.textSecondary), maxLines = 1)
    BasicText(
        count.toString(),
        Modifier.clip(RoundedCornerShape(50)).background(c.hover).padding(horizontal = Spacing.sm),
        style = MarginTheme.type.caption.copy(color = c.textTertiary, fontWeight = MarginTheme.type.label.fontWeight),
    )
}

/** Header with a select-all/none checkbox, a collapse chevron (click anywhere on the band), and a hover "Rollback all". The checkbox skips conflicted files: staging marks them resolved, so that stays per file. */
@Composable
private fun GroupHeader(state: AppState, title: String, group: List<List<FileChange>>, open: Boolean, onToggle: () -> Unit) {
    val c = MarginTheme.colors
    val target = group.filter { it[0].kind != ChangeKind.Conflicted }
    val all = target.flatten()
    val check = if (all.isEmpty()) ToggleableState.Off else checkState(all)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    GroupBand(Modifier.hoverable(source).clickable(source, null, onClick = onToggle)) {
        Checkbox(check, { if (all.isNotEmpty()) toggle(state, all, check) })
        GroupTitle(title, group.size)
        GlyphIcon(if (open) Glyph.ChevronDown else Glyph.ChevronRight, size = 12.dp, tint = if (hovered) c.textSecondary else c.textTertiary)
        Spacer(Modifier.weight(1f))
        if (hovered && group.isNotEmpty()) RollbackButton("Rollback all") { state.requestRollback(group.map { it[0].path }) }
    }
}

/** File row: optional leading control, KindBadge, name, the directory truncated at the start, then [trailing] (given hover). */
@Composable
fun FileRow(
    change: FileChange,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    strong: Boolean = selected,
    selectedFill: Color = MarginTheme.colors.selected,
    trailing: (@Composable (hovered: Boolean) -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = MarginTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when { selected -> selectedFill; hovered -> c.hover; else -> c.hover.copy(alpha = 0f) }, Motion.fast(),
    )
    Row(
        modifier.fillMaxWidth().height(Sizes.rowHeight).clip(RoundedCornerShape(Radius.sm)).background(bg)
            .hoverable(source).clickable(source, null, onClick = onClick)
            .padding(start = if (leading != null) Spacing.xs else Spacing.md, end = if (trailing != null) Spacing.xs + Spacing.xxs else Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        leading?.invoke()
        KindBadge(change.kind)
        FileLabel(change, strong, Modifier.weight(1f).padding(start = Spacing.xxs))
        trailing?.invoke(hovered)
    }
}

/** File name, then its directory in caption, truncated at the start. */
@Composable
private fun FileLabel(change: FileChange, strong: Boolean, modifier: Modifier = Modifier) {
    val c = MarginTheme.colors
    val dir = change.path.substringBeforeLast('/', "")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BasicText(
            change.path.substringAfterLast('/'),
            style = (if (strong) MarginTheme.type.bodyStrong else MarginTheme.type.body).copy(color = c.text),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // weighted, so a deep directory shrinks before the file name does
        if (dir.isNotEmpty()) StartEllipsisText(dir, MarginTheme.type.caption.copy(color = c.textTertiary), Modifier.weight(1f, fill = false))
    }
}

// ---------- Rollback ----------

/**
 * Android Studio style chooser for [AppState.rollbackTarget]: every changed file with a checkbox, grouped like the list.
 * Cancel is the default: Enter and Esc cancel, only a click or ⌘↵ rolls back the checked files.
 */
@Composable
fun RollbackDialog(state: AppState) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    var last by remember { mutableStateOf(state.rollbackTarget) } // keeps content alive during the exit fade
    state.rollbackTarget?.let { last = it }
    Modal(state.rollbackTarget != null, state::cancelRollback, 480.dp) {
        val checked = last.orEmpty()
        val (untracked, tracked) = state.files.map { sides -> sides.firstOrNull { it.kind == ChangeKind.Conflicted } ?: sides[0] }
            .partition { it.kind == ChangeKind.Untracked }
        val groups = listOf("Changes" to tracked, "Unversioned" to untracked).filter { it.second.isNotEmpty() }
        val all = tracked + untracked
        val on = all.filter { it.path in checked }
        val conflicts = on.count { it.kind == ChangeKind.Conflicted }
        fun set(files: List<FileChange>, value: Boolean) {
            val cur = state.rollbackTarget ?: return // closing: don't reopen from the fading content
            val paths = files.map { it.path }
            state.rollbackTarget = if (value) cur + paths else cur - paths.toSet()
        }
        @Composable fun TriCheckbox(files: List<FileChange>) {
            val n = files.count { it.path in checked }
            val s = when (n) { 0 -> ToggleableState.Off; files.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
            Checkbox(s, { set(files, s != ToggleableState.On) })
        }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(
            Modifier.focusRequester(focus).focusable().onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown || (it.key != Key.Enter && it.key != Key.NumPadEnter)) return@onPreviewKeyEvent false
                if (it.isMetaPressed) state.confirmRollback() else state.cancelRollback()
                true
            }.padding(Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            BasicText("Rollback Changes", style = t.title.copy(color = c.text))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(Modifier.padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TriCheckbox(all)
                    BasicText("Select all", Modifier.weight(1f), style = t.body.copy(color = c.text))
                    BasicText("${on.size} of ${all.size} selected", style = t.caption.copy(color = c.textTertiary))
                }
                // each group adds a band hairline, two spacers and the rule between groups
                Panel(Modifier.fillMaxWidth().height(Sizes.rowHeight * minOf(groups.size + all.size + 0f, 10.5f) + (Spacing.md + 2.dp) * groups.size)) {
                    ScrollableList { listState ->
                        LazyColumn(Modifier.fillMaxSize(), listState) {
                            groups.forEachIndexed { i, (title, group) ->
                                group(title, last = i == groups.lastIndex, open = true, header = { GroupBand { TriCheckbox(group); GroupTitle(title, group.size) } }) {
                                    items(group, key = { it.path }) { f ->
                                        val toggle = { set(listOf(f), f.path !in checked) }
                                        FileRow(f, selected = false, onClick = toggle, Modifier.padding(horizontal = Spacing.xs)) {
                                            Checkbox(if (f.path in checked) ToggleableState.On else ToggleableState.Off, toggle)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            BasicText(
                "Changes to tracked files will be lost. New and untracked files will be moved to the Trash.",
                style = t.body.copy(color = c.textSecondary),
            )
            if (conflicts > 0) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).background(c.warning.copy(alpha = 0.1f))
                    .border(1.dp, c.warning.copy(alpha = 0.35f), RoundedCornerShape(Radius.sm)).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                BasicText("$conflicts conflicted ${if (conflicts == 1) "file" else "files"}", style = t.bodyStrong.copy(color = c.warning))
                BasicText("Rollback resets ${if (conflicts == 1) "it" else "them"} to HEAD and discards your merge resolution.", style = t.caption.copy(color = c.textSecondary))
            }
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                BasicText("⌘↵ to roll back", Modifier.weight(1f), style = t.caption.copy(color = c.textTertiary))
                GhostButton("Cancel", state::cancelRollback)
                PrimaryButton("Rollback ${on.size} ${if (on.size == 1) "file" else "files"}", state::confirmRollback, enabled = on.isNotEmpty(), danger = true)
            }
        }
    }
}

@Composable
private fun CommitBox(state: AppState) {
    val c = MarginTheme.colors
    val n = state.stagedFileCount
    Column(
        Modifier.fillMaxWidth().background(c.surfaceRaised).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        InputField(state.message, { state.message = it }, "Commit message", Modifier.fillMaxWidth(), singleLine = false, minHeight = 64.dp)
        val label = when (n) {
            0 -> "Nothing staged"
            else -> "Commit $n ${if (n == 1) "file" else "files"}" + (state.status?.branch?.let { " to $it" } ?: "")
        }
        PrimaryButton(label, state::commit, Modifier.fillMaxWidth(), enabled = state.canCommit || Action.Commit in state.busy, loading = Action.Commit in state.busy)
        BasicText(
            "⌘↵ to commit", Modifier.align(Alignment.CenterHorizontally),
            style = MarginTheme.type.caption.copy(color = c.textTertiary),
        )
    }
}

// ---------- History ----------

@Composable
fun HistoryPane(state: AppState) {
    val log = state.log
    if (log.isEmpty()) return Box(Modifier.fillMaxSize()) {
        Placeholder(Glyph.Commit, "No commits yet", "Your first commit will show up here", Modifier.align(Alignment.Center))
    }
    val unpushed = state.status?.takeIf { it.upstream != null }?.ahead ?: 0
    ScrollableList { listState ->
        LazyColumn(Modifier.fillMaxSize(), listState, PaddingValues(Spacing.xs)) {
            items(log.size, key = { log[it].hash }) { i ->
                CommitRow(log[i], unpushed = i < unpushed, selected = state.selectedCommit?.hash == log[i].hash) { state.selectCommit(log[i]) }
            }
        }
    }
}

@Composable
private fun CommitRow(commit: Commit, unpushed: Boolean, selected: Boolean, onClick: () -> Unit) {
    val c = MarginTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when { selected -> c.selected; hovered -> c.hover; else -> c.hover.copy(alpha = 0f) }, Motion.fast(),
    )
    Row(
        Modifier.fillMaxWidth().height(Sizes.historyRowHeight).clip(RoundedCornerShape(Radius.sm))
            .background(bg).hoverable(source).clickable(source, null, onClick = onClick)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(Spacing.lg)) {
            if (unpushed) Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            BasicText(commit.subject, style = (if (selected) MarginTheme.type.bodyStrong else MarginTheme.type.body).copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                BasicText(commit.shortHash, style = MarginTheme.type.monoSmall.copy(color = c.textSecondary, lineHeight = MarginTheme.type.caption.lineHeight))
                BasicText(
                    "${commit.author}  ·  ${relativeTime(commit.epochSeconds)}",
                    style = MarginTheme.type.caption.copy(color = c.textTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

fun relativeTime(epochSeconds: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val s = now - epochSeconds
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        s < 7 * 86_400 -> "${s / 86_400}d ago"
        s < 30 * 86_400 -> "${s / (7 * 86_400)}w ago"
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))
    }
}

// ---------- Shared ----------

/** List with a slim overlay scrollbar. */
@Composable
fun ScrollableList(content: @Composable (LazyListState) -> Unit) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        content(listState)
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
    }
}

@Composable
fun Placeholder(glyph: Glyph, title: String, subtitle: String, modifier: Modifier = Modifier) {
    val c = MarginTheme.colors
    Column(modifier.padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        GlyphIcon(glyph, size = 24.dp, tint = c.textTertiary)
        Spacer(Modifier.height(Spacing.xxs))
        BasicText(title, style = MarginTheme.type.bodyStrong.copy(color = c.textSecondary))
        BasicText(subtitle, style = MarginTheme.type.caption.copy(color = c.textTertiary))
    }
}

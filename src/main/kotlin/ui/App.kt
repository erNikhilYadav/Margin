package margin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import margin.ui.components.*
import margin.ui.theme.MarginTheme
import margin.ui.theme.Motion
import margin.ui.theme.Radius
import margin.ui.theme.Sizes
import margin.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.io.File


/** [menu] fills the ⋯ button at the toolbar's end: what the hidden app menus held, since Margin lives in the menu bar. */
@Composable
fun WindowScope.App(state: AppState, mr: MrState, onOpen: () -> Unit, menu: List<Pair<String, () -> Unit>> = emptyList()) {
    val c = MarginTheme.colors
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(mr.showing) { state.inMergeRequests = mr.showing }
    LaunchedEffect(focused, state.repo) {
        if (!focused || state.repo == null) return@LaunchedEffect
        state.refresh() // pick up edits made in the IDE while we were in the background
        state.autoFetch()
        while (true) {
            delay(3000)
            state.poll()
            state.autoFetch()
        }
    }
    LaunchedEffect(focused, mr.showing) {
        while (focused && mr.showing) {
            delay(60_000)
            mr.autoRefresh()
        }
    }
    CompositionLocalProvider(
        LocalScrollbarStyle provides ScrollbarStyle(
            minimalHeight = 24.dp, thickness = 6.dp, shape = RoundedCornerShape(3.dp), hoverDurationMillis = Motion.FAST,
            unhoverColor = c.text.copy(alpha = 0.12f), hoverColor = c.text.copy(alpha = 0.24f),
        ),
    ) {
        Box(Modifier.fillMaxSize().background(c.canvas)) {
            if (state.repo == null) EmptyState(state, onOpen)
            else Column(Modifier.fillMaxSize()) {
                Toolbar(state, mr, onOpen, menu)
                Row(
                    Modifier.fillMaxSize().padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (mr.showing) Panel(Modifier.fillMaxSize()) { MergeRequestsPane(mr, state) }
                    else {
                        Panel(Modifier.width(Sizes.changesWidth).fillMaxHeight()) {
                            if (state.tab == Tab.Changes) ChangesPane(state) else HistoryPane(state)
                        }
                        Panel(Modifier.weight(1f).fillMaxHeight()) { DiffPane(state) }
                    }
                }
            }
            ToastHost(state, Modifier.align(Alignment.BottomEnd).padding(Spacing.xxl))
            MrToast(mr, Modifier.align(Alignment.BottomEnd).padding(Spacing.xxl))
            MrSheet(mr, state)
            MrDialogs(mr)
            RollbackDialog(state)
        }
    }
}

// ---------- Toolbar ----------

@Composable
private fun WindowScope.Toolbar(state: AppState, mr: MrState, onOpen: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    val s = state.status
    WindowDraggableArea {
        Row(
            Modifier.fillMaxWidth().height(Sizes.toolbarHeight).padding(start = Spacing.md, end = Spacing.md + Sizes.iconButton + Spacing.xs), // the end leaves room for Main's close button
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Repo, segmented and the right group keep their size; the branch name absorbs the squeeze first.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                AppMark(20.dp)
                Spacer(Modifier.width(Spacing.sm))
                BasicText("Margin", style = MarginTheme.type.title.copy(color = MarginTheme.colors.text))
                HairlineDivider(Modifier.padding(horizontal = Spacing.md).height(16.dp), vertical = true)
                RepoSwitcher(state, onOpen)
                BranchChip(state, Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(Spacing.md))
                Segmented(
                    listOf("Changes" to s?.changes?.distinctBy { it.path }?.size, "History" to null, "${mr.terms.short}s" to mr.list?.size),
                    selected = if (mr.showing) 2 else state.tab.ordinal,
                    onSelect = { mr.showing = it == 2; if (it < 2) state.tab = Tab.entries[it] },
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                MrToolbarButton(mr, state)
                Spacer(Modifier.width(Spacing.xs))
                TooltipIconButton("Appearance: ${state.themeMode.name}", state::cycleTheme) {
                    GlyphIcon(when (state.themeMode) { ThemeMode.System -> Glyph.Auto; ThemeMode.Light -> Glyph.Sun; ThemeMode.Dark -> Glyph.Moon })
                }
                SyncButton("Fetch", Glyph.Fetch, Action.Fetch in state.busy, onClick = state::fetch)
                SyncButton("Pull  ⇧⌘L", Glyph.Pull, Action.Pull in state.busy, count = s?.behind?.takeIf { it > 0 }?.let { "↓$it" }, onClick = state::pull)
                SyncButton("Push  ⇧⌘P", Glyph.Push, Action.Push in state.busy, count = s?.ahead?.takeIf { it > 0 }?.let { "↑$it" }, onClick = state::push)
                if (menu.isNotEmpty()) MoreMenu(menu)
            }
        }
    }
}

@Composable
private fun SyncButton(tooltip: String, glyph: Glyph, busy: Boolean, onClick: () -> Unit, count: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TooltipIconButton(tooltip, onClick, enabled = !busy) {
            if (busy) Spinner(size = 14.dp) else GlyphIcon(glyph)
        }
        if (count != null) Pill(count, Modifier.padding(end = Spacing.xs), color = MarginTheme.colors.accentText)
    }
}

/** Two-or-more option segmented control, e.g. Changes / History. */
@Composable
fun Segmented(options: List<Pair<String, Int?>>, selected: Int, onSelect: (Int) -> Unit) {
    val c = MarginTheme.colors
    Row(
        Modifier.height(Sizes.iconButton).clip(RoundedCornerShape(Radius.sm)).background(c.hover).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, (label, count) ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.surfaceRaised else c.hover.copy(alpha = 0f), Motion.base())
            val fg by animateColorAsState(if (on) c.text else c.textSecondary, Motion.base())
            Row(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(Radius.sm - 2.dp)).background(bg)
                    .then(if (on) Modifier.border(1.dp, c.border, RoundedCornerShape(Radius.sm - 2.dp)) else Modifier)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }
                    .padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                BasicText(label, style = MarginTheme.type.label.copy(color = fg))
                if (count != null && count > 0) BasicText(count.toString(), style = MarginTheme.type.caption.copy(color = c.textTertiary))
            }
        }
    }
}

// ---------- Toast ----------

@Composable
private fun ToastHost(state: AppState, modifier: Modifier) {
    val c = MarginTheme.colors
    var last by remember { mutableStateOf(state.toast) } // keeps content alive during the exit fade
    state.toast?.let { last = it }
    AnimatedVisibility(
        state.toast != null, modifier,
        enter = fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { 8 },
        exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { 8 },
    ) {
        val t = last ?: return@AnimatedVisibility
        Panel(
            Modifier.widthIn(max = 480.dp)
                .then(if (t.isError) Modifier.border(1.dp, c.danger.copy(alpha = 0.45f), RoundedCornerShape(Radius.md)) else Modifier),
            color = c.surfaceRaised,
        ) {
            Row(
                Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // Top-aligned: icon, first text line and dismiss share one centerline even for multi-line errors.
                GlyphIcon(if (t.isError) Glyph.Close else Glyph.Check, Modifier.padding(top = Spacing.md), size = 14.dp, tint = if (t.isError) c.danger else c.success)
                if (t.isError) Column(Modifier.weight(1f, fill = false).padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    t.title?.let { BasicText(it, style = MarginTheme.type.bodyStrong.copy(color = c.text)) }
                    SelectionContainer {
                        BasicText(t.text, style = MarginTheme.type.monoSmall.copy(color = c.textSecondary), maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                } else BasicText(t.text, Modifier.padding(vertical = Spacing.sm), style = MarginTheme.type.bodyStrong.copy(color = c.text))
                TooltipIconButton("Dismiss", state::dismissToast) { GlyphIcon(Glyph.Close, size = 12.dp, tint = c.textTertiary) }
            }
        }
    }
}

// ---------- Empty state ----------

@Composable
private fun WindowScope.EmptyState(state: AppState, onOpen: () -> Unit) {
    val c = MarginTheme.colors
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(Modifier.fillMaxSize()) {
        WindowDraggableArea(Modifier.fillMaxWidth().height(Sizes.toolbarHeight)) {}
        Box(Modifier.fillMaxSize().padding(vertical = Sizes.toolbarHeight), contentAlignment = Alignment.Center) {
            AnimatedVisibility(shown, enter = fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { 8 }) {
                Column(Modifier.width(360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(Radius.lg)).background(c.surface)
                            .border(1.dp, c.border, RoundedCornerShape(Radius.lg)),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Repo, size = 28.dp, tint = c.textTertiary) }
                    Spacer(Modifier.height(Spacing.xl))
                    BasicText("Open a repository", style = MarginTheme.type.display.copy(color = c.text))
                    Spacer(Modifier.height(Spacing.sm))
                    BasicText(
                        "Pick the root folder of a git project to see its changes, branches and history.",
                        style = MarginTheme.type.body.copy(color = c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    )
                    Spacer(Modifier.height(Spacing.xxl))
                    PrimaryButton("Open Repository…   ⌘O", onOpen)
                    if (state.recents.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.xxxl))
                        Panel(Modifier.fillMaxWidth(), radius = Radius.lg) {
                            Column(Modifier.padding(Spacing.xs)) {
                                SectionHeader("Recent")
                                state.recents.forEach { RecentRow(it) { state.open(it) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecentRow(dir: File, onClick: () -> Unit) {
    val c = MarginTheme.colors
    SidebarItem(
        dir.name, onClick,
        leading = { GlyphIcon(Glyph.Repo, tint = c.textTertiary) },
        trailing = {
            StartEllipsisText(
                dir.parentFile?.path.orEmpty().replaceFirst(System.getProperty("user.home"), "~"),
                MarginTheme.type.caption.copy(color = c.textTertiary), Modifier.widthIn(max = 180.dp),
            )
        },
    )
}

package margin.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import margin.git.*
import margin.ui.components.*
import margin.ui.theme.MarginTheme
import margin.ui.theme.Motion
import margin.ui.theme.Radius
import margin.ui.theme.Sizes
import margin.ui.theme.Spacing

enum class MrDialog { Merge, Close, Delete }

/** One opened merge request. Null parts are still loading. */
class MrDetail(val iid: Int) {
    var info by mutableStateOf<MrInfo?>(null)
    var error by mutableStateOf<String?>(null)
    var approvals by mutableStateOf<Approvals?>(null)
    var discussions by mutableStateOf<List<Discussion>?>(null)
    var discussionsError by mutableStateOf<String?>(null)
    var commits by mutableStateOf<List<MrCommit>?>(null)
    var commitsError by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false) // a (re)load is running; parts already shown stay on screen
    var diffs by mutableStateOf<List<MrDiffFile>?>(null)
    var diffsError by mutableStateOf<String?>(null)
    var file by mutableStateOf<MrDiffFile?>(null)
    var tab by mutableStateOf(0) // Overview, Changes, Commits
    var dialog by mutableStateOf<MrDialog?>(null)
    var commentingOn by mutableStateOf<Pair<String, DiffLine>?>(null) // (path, line) of the open inline composer
}

private val runningPipeline = setOf("created", "waiting_for_resource", "preparing", "pending", "running", "scheduled")
internal fun pipelineRunning(m: MrInfo) = m.pipeline in runningPipeline

/** Why Merge can't run now, or null. A running pipeline still allows "merge when it succeeds". */
internal fun mergeBlocker(m: MrInfo): String? = when {
    m.state != "opened" -> "Already ${m.state}"
    !m.canMerge -> "You don't have permission to merge"
    m.draft -> "Mark it as ready before merging"
    m.mergeStatus == "mergeable" -> null
    pipelineRunning(m) && m.mergeStatus in setOf("ci_still_running", "ci_must_pass") -> null
    else -> mergeStatusLabel(m.mergeStatus)
}

private val ReadingWidth = 880.dp
private fun Modifier.reading() = fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ReadingWidth).fillMaxWidth()

// ---------- Pane ----------

@Composable
fun MrDetailPane(mr: MrState, state: AppState, d: MrDetail) {
    val m = d.info
    Column(Modifier.fillMaxSize()) {
        TopBar(mr, d)
        HairlineDivider()
        if (m == null && d.error != null) Box(Modifier.fillMaxSize()) { ErrorBox(d.error!!, mr::refresh, Modifier.align(Alignment.Center)) }
        else Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Reveal(m != null, { HeaderSkeleton() }) { d.info?.let { Header(mr, d, it) } }
                Row(Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.md)) {
                    Segmented(
                        listOf("Overview" to d.discussions?.count { !it.system }, "Changes" to d.diffs?.size, "Commits" to d.commits?.size),
                        d.tab,
                    ) { d.tab = it }
                }
                HairlineDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (d.tab) {
                        0 -> Overview(mr, d)
                        1 -> ChangesTab(mr, state, d)
                        else -> CommitsTab(mr, d)
                    }
                }
            }
            ProgressStrip(d.refreshing && m != null, Modifier.align(Alignment.TopCenter))
        }
    }
}

// ---------- Skeletons ----------

private val lineWidths = listOf(0.92f, 0.84f, 0.97f, 0.6f, 0.88f, 0.74f, 0.95f, 0.5f)

/** Mirrors [Header]: state pill and title, the meta line with its branch capsule, the actions, then the chip row. */
@Composable
private fun HeaderSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SkeletonBlock(Modifier.size(44.dp, 20.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(18.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SkeletonBlock(Modifier.size(28.dp, 10.dp))
                    SkeletonBlock(Modifier.size(240.dp, 10.dp))
                    SkeletonBlock(Modifier.size(220.dp, 18.dp))
                }
            }
            SkeletonBlock(Modifier.size(84.dp, 28.dp), RoundedCornerShape(Radius.sm))
            SkeletonBlock(Modifier.size(76.dp, 28.dp), RoundedCornerShape(Radius.sm))
            SkeletonBlock(Modifier.size(28.dp), RoundedCornerShape(Radius.sm))
        }
        Row(Modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            listOf(70, 110, 90, 80, 96).forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonBlock(Modifier.size(48.dp, 8.dp))
                    SkeletonBlock(Modifier.size(w.dp, 10.dp))
                }
            }
        }
    }
}

/** A raised card with an avatar, a name line and a few body lines, like a note in [ThreadBody]. */
@Composable
private fun CommentSkeleton(lines: Int, seed: Int) {
    Panel(Modifier.reading(), color = MarginTheme.colors.surfaceRaised) {
        Row(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SkeletonBlock(Modifier.size(28.dp), CircleShape)
            Column(Modifier.weight(1f).padding(top = Spacing.xxs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonBlock(Modifier.size(96.dp, 10.dp))
                    SkeletonBlock(Modifier.size(120.dp, 10.dp))
                }
                repeat(lines) { SkeletonBlock(Modifier.fillMaxWidth(lineWidths[(seed + it) % lineWidths.size]).height(9.dp)) }
            }
        }
    }
}

@Composable
private fun DescriptionSkeleton() {
    Panel(Modifier.reading(), color = MarginTheme.colors.surfaceRaised) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SkeletonBlock(Modifier.size(90.dp, 14.dp))
            repeat(3) { SkeletonBlock(Modifier.fillMaxWidth(lineWidths[it]).height(9.dp)) }
            SkeletonBlock(Modifier.padding(top = Spacing.sm).size(70.dp, 14.dp))
            repeat(4) { SkeletonBlock(Modifier.padding(start = Spacing.lg).fillMaxWidth(lineWidths[it + 3] * 0.7f).height(9.dp)) }
        }
    }
}

/** File column of [FileRow]-shaped bars, then a diff of gutter numbers and code lines. */
@Composable
private fun ChangesSkeleton() {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().padding(Spacing.xs)) {
            repeat(9) { i ->
                Row(Modifier.height(Sizes.rowHeight).padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonBlock(Modifier.size(14.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(lineWidths[i % lineWidths.size] * 0.7f).height(9.dp))
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MarginTheme.colors.border))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.size(260.dp, 10.dp))
            }
            HairlineDivider()
            Column(Modifier.padding(vertical = Spacing.sm)) {
                repeat(22) { i ->
                    Row(Modifier.height(20.dp).padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        SkeletonBlock(Modifier.size(22.dp, 8.dp))
                        SkeletonBlock(Modifier.size(22.dp, 8.dp))
                        if (i % 7 != 3) SkeletonBlock(Modifier.padding(start = (if (i % 5 == 0) 0 else 24).dp).fillMaxWidth(lineWidths[(i * 3) % lineWidths.size] * 0.65f).height(9.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitsSkeleton() {
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.xl, vertical = Spacing.md)) {
        repeat(6) { i ->
            Row(Modifier.reading().height(Sizes.historyRowHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SkeletonBlock(Modifier.size(14.dp), CircleShape)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonBlock(Modifier.fillMaxWidth(lineWidths[i] * 0.6f).height(10.dp))
                    SkeletonBlock(Modifier.size(150.dp, 8.dp))
                }
                SkeletonBlock(Modifier.size(56.dp, 10.dp))
            }
            HairlineDivider(Modifier.reading())
        }
    }
}

@Composable
private fun TopBar(mr: MrState, d: MrDetail) {
    val c = MarginTheme.colors
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(start = Spacing.xs, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        GhostButton("${mr.terms.nounCap}s", mr::closeDetail, Modifier.height(28.dp), leading = { GlyphIcon(Glyph.ChevronLeft, size = 14.dp) })
        BasicText("/  ${mr.terms.ref(d.iid)}", style = MarginTheme.type.label.copy(color = c.textTertiary))
        Spacer(Modifier.weight(1f))
        d.info?.let { m -> TooltipIconButton("Open in browser", { openInBrowser(m.webUrl) }) { GlyphIcon(Glyph.External, size = 14.dp) } }
        TooltipIconButton("Refresh", mr::refresh, enabled = mr.acting == null) {
            if (mr.acting != null) Spinner(size = 14.dp) else GlyphIcon(Glyph.Fetch, size = 14.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(mr: MrState, d: MrDetail, m: MrInfo) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    StatePill(m)
                    SelectionContainer(Modifier.weight(1f, fill = false)) {
                        BasicText(m.title.let { draftTitle(it, false) }, style = t.display.copy(color = c.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    BasicText(mr.terms.ref(m.iid), style = t.monoSmall.copy(color = c.textSecondary))
                    BasicText(
                        "${m.author.name} opened ${relativeTime(m.createdEpoch)}  ·  updated ${relativeTime(m.updatedEpoch)}",
                        style = t.caption.copy(color = c.textTertiary), maxLines = 1,
                    )
                    BranchFlow(m.source, m.target, Modifier.widthIn(max = 420.dp))
                }
            }
            Actions(mr, d, m)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Meta(mr.terms.pipeline) { StatusDot(pipelineColor(m.pipeline)); MetaText(m.pipeline?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "None") }
            Meta("Approvals") {
                val a = d.approvals
                if (a != null && a.approvedBy.isNotEmpty()) GlyphIcon(Glyph.Check, size = 12.dp, tint = c.success)
                MetaText(
                    when {
                        a == null -> "…"
                        a.approvedBy.isEmpty() -> if (a.required != null) "0 of ${a.required}" else "None yet"
                        else -> a.approvedBy.joinToString { it.name } + (a.required?.let { "  (${a.approvedBy.size} of $it)" } ?: "")
                    },
                )
            }
            Meta("Merge") {
                val ok = m.mergeStatus == "mergeable"
                StatusDot(if (ok) c.success else if (m.state != "opened") c.textTertiary else c.warning)
                MetaText(if (m.state == "opened") mergeStatusLabel(m.mergeStatus) else m.state.replaceFirstChar { it.uppercase() })
            }
            Meta("Reviewers") { MetaText(m.reviewers.joinToString { it.name }.ifEmpty { "None" }) }
            Meta("Assignees") { MetaText(m.assignees.joinToString { it.name }.ifEmpty { "None" }) }
        }
    }
}

@Composable
private fun Meta(label: String, value: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BasicText(label, style = MarginTheme.type.caption.copy(color = MarginTheme.colors.textTertiary))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs), content = value)
    }
}

@Composable
private fun MetaText(text: String) =
    BasicText(text, Modifier.widthIn(max = 360.dp), style = MarginTheme.type.label.copy(color = MarginTheme.colors.text), maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun StatusDot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

@Composable
private fun pipelineColor(status: String?): Color {
    val c = MarginTheme.colors
    return when (status) {
        "success" -> c.success
        "failed" -> c.danger
        in runningPipeline -> c.info
        null -> c.textDisabled
        else -> c.textTertiary // canceled, skipped, manual
    }
}

@Composable
fun StatePill(m: MrInfo) = StatePill(m.state, m.draft)

@Composable
fun StatePill(state: String, draft: Boolean) {
    val c = MarginTheme.colors
    val (label, color) = when {
        state == "merged" -> "Merged" to c.accentText
        state == "closed" || state == "locked" -> "Closed" to c.danger
        draft -> "Draft" to c.textSecondary
        else -> "Open" to c.success
    }
    BasicText(
        label, Modifier.clip(RoundedCornerShape(Radius.xs)).background(color.copy(alpha = 0.14f)).padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        style = MarginTheme.type.label.copy(color = color),
    )
}

// ---------- Actions ----------

@Composable
private fun Actions(mr: MrState, d: MrDetail, m: MrInfo) {
    val c = MarginTheme.colors
    val open = m.state == "opened"
    val busy = mr.acting != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val a = d.approvals
        if (open && a != null) {
            if (a.userHasApproved) { if (mr.forge?.canUnapprove == true) GhostButton("Revoke approval", mr::unapprove, enabled = !busy) }
            else if (a.userCanApprove) GhostButton("Approve", mr::approve, enabled = !busy, leading = { GlyphIcon(Glyph.Check, size = 14.dp, tint = c.success) })
        }
        if (open) {
            val blocker = mergeBlocker(m)
            val button = @Composable { PrimaryButton("Merge…", { d.dialog = MrDialog.Merge }, enabled = blocker == null && !busy, loading = mr.acting == "Merge") }
            if (blocker != null) Tooltip(blocker) { button() } else button()
        } else if (m.state == "closed") GhostButton("Reopen", mr::reopen, enabled = !busy)
        MoreMenu(mr, d, m)
    }
}

@Composable
private fun MoreMenu(mr: MrState, d: MrDetail, m: MrInfo) {
    val c = MarginTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton("More actions", { open = true }) { GlyphIcon(Glyph.More, size = 16.dp) }
        if (open) Popup(
            alignment = Alignment.TopEnd,
            offset = with(LocalDensity.current) { IntOffset(0, (Sizes.iconButton + Spacing.xs).roundToPx()) },
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true),
        ) {
            Popover(Modifier.width(240.dp)) {
                Column(Modifier.padding(Spacing.xs)) {
                    @Composable fun item(label: String, enabled: Boolean = true, danger: Boolean = false, action: () -> Unit) = SidebarItem(
                        label, { if (enabled) { open = false; action() } },
                        leading = if (danger) ({ GlyphIcon(Glyph.Close, size = 14.dp, tint = c.danger) }) else null,
                        secondary = !enabled,
                    )
                    val editable = m.state == "opened" && mr.acting == null
                    item(if (m.draft) "Mark as ready" else "Mark as draft", editable) { mr.setDraft(!m.draft) }
                    item("Check out branch") { mr.checkoutBranch() }
                    item("Copy source branch") { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(m.source), null) }
                    item("Open in browser") { openInBrowser(m.webUrl) }
                    HairlineDivider(Modifier.padding(vertical = Spacing.xs))
                    if (m.state == "opened") item("Close ${mr.terms.noun}…", mr.acting == null, danger = true) { d.dialog = MrDialog.Close }
                    else if (m.state == "closed") item("Reopen", mr.acting == null) { mr.reopen() }
                    if (mr.forge?.canDelete == true) item("Delete ${mr.terms.noun}…", mr.acting == null, danger = true) { d.dialog = MrDialog.Delete }
                }
            }
        }
    }
}

// ---------- Overview ----------

@Composable
private fun Overview(mr: MrState, d: MrDetail) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    val m = d.info
    val discussions = d.discussions
    val fade = tween<Float>(Motion.BASE, easing = Motion.Standard)
    ScrollableList { listState ->
        LazyColumn(Modifier.fillMaxSize(), listState, PaddingValues(horizontal = Spacing.xl, vertical = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            item("description") {
                Reveal(m != null, { DescriptionSkeleton() }) {
                    Panel(Modifier.reading(), color = c.surfaceRaised) {
                        val text = m?.description.orEmpty()
                        if (text.isBlank()) BasicText("No description", Modifier.padding(Spacing.lg), style = t.body.copy(color = c.textTertiary))
                        else SelectionContainer { Markdown(text, Modifier.padding(Spacing.lg)) }
                    }
                }
            }
            item("activity") { Box(Modifier.reading().padding(top = Spacing.md)) { BasicText("ACTIVITY", style = t.overline.copy(color = c.textSecondary)) } }
            when {
                discussions == null && d.discussionsError != null -> item("disc-error") {
                    BasicText("Couldn't load the discussion: ${d.discussionsError}", Modifier.reading(), style = t.caption.copy(color = c.danger))
                }
                discussions == null -> item("disc-loading") {
                    Column(Modifier.animateItem(fade, null, fade), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ShimmerHost { CommentSkeleton(2, 0); CommentSkeleton(3, 2); CommentSkeleton(1, 5) }
                    }
                }
                else -> items(discussions, key = { it.id }) { Box(Modifier.animateItem(fade, null, fade)) { DiscussionView(mr, d, it) } }
            }
            if (m != null) item("composer") {
                Box(Modifier.reading().padding(top = Spacing.sm)) {
                    Composer("Write a comment… Markdown works", "Comment", mr.acting == "Comment", m.state != "locked") { body, done -> mr.comment(body, done) }
                }
            }
        }
    }
}

@Composable
private fun DiscussionView(mr: MrState, d: MrDetail, disc: Discussion) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    if (disc.system) return Row(Modifier.reading().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.borderStrong))
        val n = disc.notes.first()
        BasicText(
            "${n.author.name} ${n.body.lines().first()}  ·  ${relativeTime(n.createdEpoch)}", Modifier.weight(1f),
            style = t.caption.copy(color = c.textTertiary), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
    Panel(Modifier.reading(), color = c.surfaceRaised) {
        Column {
            disc.position?.let { p ->
                Row(
                    Modifier.fillMaxWidth().background(c.hover).clickable(remember { MutableInteractionSource() }, null) {
                        d.diffs?.firstOrNull { it.change.path == p.newPath }?.let { d.file = it }
                        d.tab = 1
                    }.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    GlyphIcon(Glyph.Comment, size = 12.dp, tint = c.textTertiary)
                    BasicText(p.newPath.substringAfterLast('/') + ":" + (p.newLine ?: p.oldLine ?: ""), style = t.monoSmall.copy(color = c.textSecondary))
                    BasicText("Show in Changes", style = t.caption.copy(color = c.accentText))
                }
                HairlineDivider()
            }
            ThreadBody(mr, disc)
        }
    }
}

/** Notes, then reply / resolve. Shared by the Overview and the inline threads under diff lines. */
@Composable
private fun ThreadBody(mr: MrState, disc: Discussion) {
    val c = MarginTheme.colors
    var replying by remember(disc.id) { mutableStateOf(false) }
    Column {
        disc.notes.filter { !it.system }.forEachIndexed { i, n ->
            if (i > 0) HairlineDivider(Modifier.padding(start = Spacing.lg + 28.dp))
            NoteView(n)
        }
        if (replying) Box(Modifier.padding(start = Spacing.lg + 28.dp, end = Spacing.lg, bottom = Spacing.lg)) {
            Composer("Reply…", "Reply", mr.acting == "Reply", autofocus = true, onCancel = { replying = false }) { body, done -> mr.reply(disc.id, body) { done(); replying = false } }
        } else if (!disc.notes.all { it.system } && (disc.resolvable || disc.notes.size > 1 || disc.position != null)) {
            HairlineDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                GhostButton("Reply", { replying = true }, Modifier.height(24.dp), enabled = mr.acting == null)
                Spacer(Modifier.weight(1f))
                if (disc.resolvable) {
                    if (disc.resolved) BasicText("Resolved", style = MarginTheme.type.caption.copy(color = c.success))
                    GhostButton(if (disc.resolved) "Unresolve" else "Resolve thread", { mr.resolve(disc.id, !disc.resolved) }, Modifier.height(24.dp), enabled = mr.acting == null)
                }
            }
        }
    }
}

@Composable
private fun NoteView(n: Note) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    Row(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Avatar(n.author)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BasicText(n.author.name, style = t.bodyStrong.copy(color = c.text))
                BasicText("@${n.author.username}  ·  ${relativeTime(n.createdEpoch)}", style = t.caption.copy(color = c.textTertiary))
            }
            SelectionContainer { Markdown(n.body) }
        }
    }
}

@Composable
private fun Avatar(p: Person) {
    val c = MarginTheme.colors
    Box(Modifier.size(28.dp).clip(CircleShape).background(c.selected), contentAlignment = Alignment.Center) {
        BasicText(p.name.firstOrNull()?.uppercase() ?: "?", style = MarginTheme.type.label.copy(color = c.accentText))
    }
}

/** Multi-line box with a submit button; [onSubmit] gets the text and a callback that clears it once the forge took it. */
@Composable
private fun Composer(
    placeholder: String, action: String, busy: Boolean, enabled: Boolean = true, autofocus: Boolean = false,
    onCancel: (() -> Unit)? = null, onSubmit: (String, () -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    if (autofocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        InputField(text, { text = it }, placeholder, Modifier.fillMaxWidth().heightIn(max = 220.dp).focusRequester(focus), singleLine = false, minHeight = 64.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            onCancel?.let { GhostButton("Cancel", it, enabled = !busy) }
            PrimaryButton(action, { onSubmit(text) { text = "" } }, enabled = enabled && text.isNotBlank() && !busy, loading = busy)
        }
    }
}

// ---------- Changes ----------

@Composable
private fun ChangesTab(mr: MrState, state: AppState, d: MrDetail) =
    Reveal(d.diffs != null || d.diffsError != null, { ChangesSkeleton() }) { ChangesBody(mr, state, d) }

@Composable
private fun ChangesBody(mr: MrState, state: AppState, d: MrDetail) {
    val c = MarginTheme.colors
    val files = d.diffs
    when {
        files == null && d.diffsError != null -> return Box(Modifier.fillMaxSize()) { ErrorBox(d.diffsError!!, mr::refresh, Modifier.align(Alignment.Center)) }
        files == null -> return // only while fading back to the skeleton
        files.isEmpty() -> return Box(Modifier.fillMaxSize()) { Placeholder(Glyph.Commit, "No changes", "This ${mr.terms.noun} changes no files", Modifier.align(Alignment.Center)) }
    }
    val threads = d.discussions.orEmpty().filter { it.position != null && !it.system }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(300.dp).fillMaxHeight()) {
            ScrollableList { listState ->
                LazyColumn(Modifier.fillMaxSize(), listState, PaddingValues(Spacing.xs)) {
                    items(files!!, key = { it.change.path }) { f ->
                        val count = threads.count { it.position?.newPath == f.change.path }
                        FileRow(f.change, selected = f == d.file, onClick = { d.file = f; d.commentingOn = null }, trailing = {
                            if (count > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                                GlyphIcon(Glyph.Comment, size = 12.dp, tint = c.textTertiary)
                                BasicText(count.toString(), style = MarginTheme.type.caption.copy(color = c.textTertiary))
                            }
                        })
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
        val f = d.file ?: return@Row
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (f.tooLarge) return@Box Placeholder(Glyph.Commit, "Diff too large to show", "Open the ${mr.terms.noun} in the browser to see it", Modifier.align(Alignment.Center))
            val anchored = remember(f, threads) {
                f.lines.associateWith { line -> threads.filter { it.position!!.matches(f.change, line) } }.filterValues { it.isNotEmpty() }
            }
            val commenting = d.commentingOn?.takeIf { it.first == f.change.path }?.second
            val canComment = d.info?.diffRefs != null && d.info?.state == "opened"
            DiffSection(
                state, f.change, f.lines, wholeFileToggle = false,
                below = { line ->
                    val here = anchored[line]
                    if (here == null && line != commenting) null else ({
                        Column(Modifier.fillMaxWidth().background(c.surface).padding(start = 100.dp, end = Spacing.xl, top = Spacing.sm, bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            here?.forEach { disc -> Panel(Modifier.widthIn(max = ReadingWidth), color = c.surfaceRaised) { ThreadBody(mr, disc) } }
                            if (line == commenting) Panel(Modifier.widthIn(max = ReadingWidth), color = c.surfaceRaised) {
                                Box(Modifier.padding(Spacing.md)) {
                                    Composer("Comment on line ${line.newNo ?: line.oldNo}…", "Comment", mr.acting == "Comment", autofocus = true, onCancel = { d.commentingOn = null }) { body, done ->
                                        mr.commentOnLine(f.change, line, body) { done(); d.commentingOn = null }
                                    }
                                }
                            }
                        }
                    })
                },
                onGutter = if (canComment) ({ line -> d.commentingOn = f.change.path to line }) else null,
            )
        }
    }
}

// ---------- Commits ----------

@Composable
private fun CommitsTab(mr: MrState, d: MrDetail) =
    Reveal(d.commits != null || d.commitsError != null, { CommitsSkeleton() }) { CommitsBody(mr, d) }

@Composable
private fun CommitsBody(mr: MrState, d: MrDetail) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    val commits = d.commits ?: return d.commitsError.let { e -> Box(Modifier.fillMaxSize()) { if (e != null) ErrorBox(e, mr::refresh, Modifier.align(Alignment.Center)) } }
    ScrollableList { listState ->
        LazyColumn(Modifier.fillMaxSize(), listState, PaddingValues(horizontal = Spacing.xl, vertical = Spacing.md)) {
            items(commits, key = { it.sha }) { k ->
                Row(Modifier.reading().height(Sizes.historyRowHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    GlyphIcon(Glyph.Commit, size = 14.dp, tint = c.textTertiary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        BasicText(k.title, style = t.body.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText("${k.author}  ·  ${relativeTime(k.epoch)}", style = t.caption.copy(color = c.textTertiary), maxLines = 1)
                    }
                    SelectionContainer { BasicText(k.shortSha, style = t.monoSmall.copy(color = c.textSecondary)) }
                }
                HairlineDivider(Modifier.reading())
            }
        }
    }
}

// ---------- Dialogs ----------

/** Merge / Close / Delete confirmations. Lives at the window root so the scrim covers everything. */
@Composable
fun MrDialogs(mr: MrState) {
    val d = mr.detail
    val m = d?.info
    var last by remember { mutableStateOf<Pair<MrDialog, MrInfo>?>(null) } // keeps content alive during the exit fade
    if (d?.dialog != null && m != null) last = d.dialog!! to m
    val dismiss = { if (mr.acting == null) d?.dialog = null }
    val (kind, info) = last ?: return
    Modal(d?.dialog != null, dismiss, if (kind == MrDialog.Merge) 480.dp else 440.dp) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(kind) { focus.requestFocus() }
        // Enter never confirms here: it cancels, like the rollback dialog
        Column(
            Modifier.focusRequester(focus).focusable().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { dismiss(); true } else false
            }.padding(Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            when (kind) {
                MrDialog.Merge -> MergeForm(mr, info, dismiss)
                MrDialog.Close -> Confirm(
                    "Close ${mr.terms.ref(info.iid)}?", "It stops being reviewable until someone reopens it. The source branch is kept.",
                    "Close ${mr.terms.noun}", mr.acting == "Close", dismiss, onConfirm = mr::close,
                )
                MrDialog.Delete -> DeleteForm(mr, info, dismiss)
            }
        }
    }
}

@Composable
private fun ColumnScope.MergeForm(mr: MrState, m: MrInfo, dismiss: () -> Unit) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    val waitable = pipelineRunning(m)
    val mustWait = waitable && m.mergeStatus != "mergeable"
    var squash by remember { mutableStateOf(false) }
    var removeSource by remember { mutableStateOf(false) }
    var wait by remember { mutableStateOf(waitable) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BasicText("Merge ${mr.terms.ref(m.iid)}?", style = t.title.copy(color = c.text))
        BranchFlow(m.source, m.target)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Option("Squash commits", "Combine the branch's commits into one", squash) { squash = it }
        Option("Delete source branch", "Remove ${m.source} after merging", removeSource) { removeSource = it }
        val checks = mr.terms.pipeline.lowercase()
        if (waitable) Option(mr.terms.autoMerge, if (mustWait) "Required: $checks still running" else "Waits for $checks to finish", wait || mustWait) { if (!mustWait) wait = it }
    }
    BasicText(
        "${mr.terms.site} merges exactly ${m.sha?.take(8) ?: "the current head"}; if someone pushes first, it refuses.",
        style = t.caption.copy(color = c.textTertiary),
    )
    Buttons("Cancel", dismiss, if (wait || mustWait) "Set to merge" else "Merge", mr.acting == "Merge", danger = false) {
        mr.merge(MergeOptions(squash, removeSource, wait || mustWait))
    }
}

@Composable
private fun DeleteForm(mr: MrState, m: MrInfo, dismiss: () -> Unit) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    var typed by remember { mutableStateOf("") }
    BasicText("Delete !${m.iid} permanently?", style = t.title.copy(color = c.text))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).background(c.danger.copy(alpha = 0.1f))
            .border(1.dp, c.danger.copy(alpha = 0.35f), RoundedCornerShape(Radius.sm)).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        BasicText("This can't be undone", style = t.bodyStrong.copy(color = c.danger))
        BasicText("The merge request, its discussion and approvals are removed. Only project owners and admins can delete; otherwise GitLab refuses.", style = t.caption.copy(color = c.textSecondary))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BasicText("Type ${m.iid} to confirm", style = t.label.copy(color = c.textSecondary))
        InputField(typed, { typed = it }, m.iid.toString(), Modifier.fillMaxWidth())
    }
    Buttons("Cancel", dismiss, "Delete merge request", mr.acting == "Delete", danger = true, enabled = typed.trim() == m.iid.toString(), onConfirm = mr::delete)
}

@Composable
private fun Confirm(title: String, body: String, action: String, busy: Boolean, dismiss: () -> Unit, onConfirm: () -> Unit) {
    BasicText(title, style = MarginTheme.type.title.copy(color = MarginTheme.colors.text))
    BasicText(body, style = MarginTheme.type.body.copy(color = MarginTheme.colors.textSecondary))
    Buttons("Cancel", dismiss, action, busy, danger = true, onConfirm = onConfirm)
}

@Composable
private fun Buttons(cancel: String, dismiss: () -> Unit, action: String, busy: Boolean, danger: Boolean, enabled: Boolean = true, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.End)) {
        GhostButton(cancel, dismiss, enabled = !busy)
        PrimaryButton(action, onConfirm, enabled = enabled && !busy, loading = busy, danger = danger)
    }
}

@Composable
private fun Option(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MarginTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).clickable(remember { MutableInteractionSource() }, null) { onChange(!checked) }.padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Toggle(checked)
        Column {
            BasicText(title, style = MarginTheme.type.body.copy(color = c.text))
            BasicText(subtitle, style = MarginTheme.type.caption.copy(color = c.textTertiary))
        }
    }
}

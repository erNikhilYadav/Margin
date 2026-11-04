package margin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import kotlin.concurrent.thread

/** Merge/pull request state, kept apart from AppState. Loads lazily: on tab open and branch change, never on the status poll. */
class MrState(
    private val scope: CoroutineScope,
    private val notify: (text: String, isError: Boolean, title: String?) -> Unit = { _, _, _ -> },
    private val checkout: (branch: String) -> Unit = {},
) {
    private var me: String? = null // signed-in username, fetched once per repo for the Review scope
    private var branch: String? = null
    private var toastJob: Job? = null

    var forge by mutableStateOf<Forge?>(null); private set // GitHub or GitLab, picked from the repo's origin
    val terms get() = forge?.terms ?: GitLabTerms
    var host by mutableStateOf<String?>(null); private set // origin's host: whose account the tab uses
    var status by mutableStateOf<ForgeStatus?>(null); private set // null while checking
    var list by mutableStateOf<List<MergeRequest>?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var current by mutableStateOf<MergeRequest?>(null); private set // open MR whose source is the current branch
    var defaultBranch by mutableStateOf<String?>(null); private set
    var showing by mutableStateOf(false)
    var sheetOpen by mutableStateOf(false)
    var creating by mutableStateOf(false); private set
    var createError by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null) // URL of the MR just created
    var signingIn by mutableStateOf(false); private set
    var signInError by mutableStateOf<String?>(null); private set
    var stateFilter by mutableStateOf(MrStateFilter.Open)
    var scopeFilter by mutableStateOf(MrScope.All)
    var query by mutableStateOf("")
    var detail by mutableStateOf<MrDetail?>(null); private set
    var acting by mutableStateOf<String?>(null); private set // label of the MR action in flight

    /** [with] lets tests point at a fake CLI; the app picks GitHub or GitLab from origin. */
    suspend fun open(dir: File?, with: Forge? = null) {
        status = null; list = null; error = null; current = null; defaultBranch = null; showing = false; detail = null; me = null; host = null
        forge = with ?: dir?.let { forgeFor(it) }
        val l = forge ?: return
        host = originHost(l.dir)
        status = l.available()
        if (status == ForgeStatus.Ready) defaultBranch = runCatching { l.defaultBranch() }.getOrNull()
    }

    suspend fun loadCurrent(branch: String?) {
        this.branch = branch
        val l = forge?.takeIf { status == ForgeStatus.Ready } ?: return
        current = branch?.let { runCatching { l.forBranch(it) }.getOrNull() }
    }

    suspend fun loadList() {
        val l = forge?.takeIf { status == ForgeStatus.Ready } ?: return
        loading = true
        try {
            val who = if (scopeFilter == MrScope.Review) me ?: l.currentUsername().also { me = it } else null
            list = l.listMergeRequests(stateFilter, scopeFilter, query, who)
            error = null
        } catch (e: GitException) {
            error = e.message
        } finally {
            loading = false
        }
    }

    /** Re-checks the CLI when it wasn't ready (e.g. after `gh auth login`); the effects then reload. */
    fun refresh() {
        scope.launch {
            if (status != ForgeStatus.Ready) open(forge?.dir, forge).also { showing = true }
            else { detail?.let { loadDetail(it) }; loadList(); loadCurrent(branch) } // the open detail first: it is what is on screen
        }
    }

    /** Timer refresh: only when signed in and idle, so it never races an action. */
    fun autoRefresh() {
        if (status == ForgeStatus.Ready && acting == null && !loading && detail?.refreshing != true) refresh()
    }

    /** Hands the token to the CLI (its keyring keeps it), then re-checks so the list loads. */
    fun signIn(host: String, token: String) {
        val l = forge ?: return
        if (signingIn || token.isBlank()) return
        val h = normalizeHost(host) ?: run { signInError = "$host isn't a server name Margin can pass to ${l.terms.cli}."; return }
        signingIn = true
        signInError = null
        scope.launch {
            try {
                l.signIn(h, token)
                open(l.dir, l)
                showing = true
                if (status is ForgeStatus.NotAuthenticated) signInError = "${l.terms.cli} accepted the token but still isn't signed in to $h."
            } catch (e: GitException) {
                signInError = e.message
            } finally {
                signingIn = false
            }
        }
    }

    /** Removes the CLI's token for this repo's host; the tab then shows the sign-in form. */
    fun signOut() {
        val l = forge ?: return
        val h = host ?: return
        scope.launch {
            runCatching { l.signOut(h) }.onFailure { notify(it.message ?: "Sign out failed", true, "Sign out failed") }
            open(l.dir, l)
            showing = true
        }
    }

    fun create(source: String, target: String, title: String, description: String, draft: Boolean) {
        val l = forge ?: return
        if (creating) return
        creating = true
        createError = null
        scope.launch {
            try {
                val url = l.create(source, target, title.trim(), description.trim(), draft)
                sheetOpen = false
                toast = url
                toastJob?.cancel()
                toastJob = scope.launch { delay(6000); if (toast == url) toast = null }
                openInBrowser(url)
                loadCurrent(source)
                if (list != null) loadList()
            } catch (e: GitException) {
                createError = e.message
            } finally {
                creating = false
            }
        }
    }

    fun openDetail(iid: Int) {
        val d = MrDetail(iid)
        detail = d
        scope.launch { loadDetail(d) }
    }

    fun closeDetail() { detail = null }

    /** Everything at once, in parallel; each part fails on its own so one bad endpoint doesn't blank the page. */
    suspend fun loadDetail(d: MrDetail) {
        d.refreshing = true
        try { fetchDetail(d) } finally { d.refreshing = false }
    }

    private suspend fun fetchDetail(d: MrDetail) = coroutineScope {
        val l = forge ?: return@coroutineScope
        val info = async { runCatching { l.mergeRequest(d.iid) } }
        launch { d.approvals = runCatching { l.approvals(d.iid) }.getOrNull() }
        launch { runCatching { l.discussions(d.iid) }.onSuccess { d.discussions = it }.onFailure { d.discussionsError = it.message } }
        launch { runCatching { l.commits(d.iid) }.onSuccess { d.commits = it }.onFailure { d.commitsError = it.message } }
        launch {
            runCatching { l.diffs(d.iid) }.onSuccess { files ->
                d.diffs = files
                d.file = files.firstOrNull { it.change.path == d.file?.change?.path } ?: files.firstOrNull()
            }.onFailure { d.diffsError = it.message }
        }
        info.await().onSuccess { d.info = it; d.error = null }.onFailure { d.error = it.message }
    }

    /**
     * Runs one review write, then reloads the detail (and the list) and says how it went.
     * [onDone] runs only on success, e.g. to clear a comment box.
     */
    fun act(label: String, done: String, onDone: () -> Unit = {}, block: suspend (Forge, MrInfo) -> Unit) {
        val l = forge ?: return
        val d = detail ?: return
        val info = d.info ?: return
        if (acting != null) return
        acting = label
        scope.launch {
            try {
                block(l, info)
                d.dialog = null
                onDone()
                notify(done, false, null)
                loadDetail(d)
                loadList()
                loadCurrent(branch)
            } catch (e: GitException) {
                notify(e.message ?: "${terms.site} refused the request", true, "$label failed")
            } finally {
                acting = null
            }
        }
    }

    fun approve() = act("Approve", "Approved") { l, m -> l.approve(m.iid, m.sha) }
    fun unapprove() = act("Revoke approval", "Approval revoked") { l, m -> l.unapprove(m.iid) }
    fun merge(o: MergeOptions) = act("Merge", "Merge started") { l, m -> l.merge(m.iid, o, m.sha) }
    fun close() = act("Close", "${terms.nounCap} closed") { l, m -> l.close(m.iid) }
    fun reopen() = act("Reopen", "${terms.nounCap} reopened") { l, m -> l.reopen(m.iid) }
    fun setDraft(draft: Boolean) = act(if (draft) "Mark as draft" else "Mark as ready", if (draft) "Marked as draft" else "Marked as ready") { l, m -> l.setDraft(m.iid, m.title, draft) }
    fun comment(body: String, onDone: () -> Unit) = act("Comment", "Comment posted", onDone) { l, m -> l.comment(m.iid, body.trim()) }
    fun reply(discussion: String, body: String, onDone: () -> Unit) = act("Reply", "Reply posted", onDone) { l, m -> l.reply(m.iid, discussion, body.trim()) }
    fun resolve(discussion: String, resolved: Boolean) = act(if (resolved) "Resolve" else "Unresolve", if (resolved) "Thread resolved" else "Thread reopened") { l, m -> l.resolve(m.iid, discussion, resolved) }
    fun commentOnLine(file: FileChange, line: DiffLine, body: String, onDone: () -> Unit) = act("Comment", "Comment posted", onDone) { l, m ->
        l.commentOnLine(m.iid, m.diffRefs ?: throw GitException("${terms.site} didn't send this ${terms.short}'s diff refs"), file, line, body.trim())
    }

    fun delete() {
        val d = detail ?: return
        act("Delete", "${terms.nounCap} ${terms.ref(d.iid)} deleted", onDone = { detail = null }) { l, m -> l.deleteMergeRequest(m.iid) }
    }

    fun checkoutBranch() = detail?.info?.let { checkout(it.source) }
}

fun openInBrowser(url: String) {
    thread { runCatching { Desktop.getDesktop().browse(URI(url)) } }
}

@Composable
fun rememberMrState(state: AppState): MrState {
    val scope = rememberCoroutineScope()
    val mr = remember { MrState(scope, notify = state::notify, checkout = state::checkoutRemote) }
    val s = state.status
    LaunchedEffect(state.repo?.dir) { mr.open(state.repo?.dir) }
    LaunchedEffect(mr.status, s != null, s?.branch) { if (s != null) mr.loadCurrent(s.branch) }
    LaunchedEffect(mr.status, mr.showing, s?.branch, mr.stateFilter, mr.scopeFilter, mr.query) {
        if (!mr.showing) return@LaunchedEffect
        if (mr.query.isNotBlank()) delay(300) // typing restarts this effect, so only the last keystroke queries
        mr.loadList()
    }
    return mr
}

// ---------- Pane ----------

@Composable
fun MergeRequestsPane(mr: MrState, state: AppState) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    val status = mr.status
    val list = mr.list
    mr.detail?.let { return MrDetailPane(mr, state, it) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = Spacing.lg, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            BasicText("${mr.terms.nounCap}s", style = t.bodyStrong.copy(color = c.text))
            if (status == ForgeStatus.Ready) {
                Segmented(MrStateFilter.entries.map { it.name to null }, mr.stateFilter.ordinal) { mr.stateFilter = MrStateFilter.entries[it] }
                Segmented(listOf("All" to null, "Mine" to null, "Assigned" to null, "Review requested" to null), mr.scopeFilter.ordinal) { mr.scopeFilter = MrScope.entries[it] }
                InputField(mr.query, { mr.query = it }, "Search title or description", Modifier.widthIn(min = 160.dp, max = 260.dp).weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            if (!list.isNullOrEmpty()) BasicText("${list.size}${if (list.size == 50) "+" else ""}", style = t.caption.copy(color = c.textTertiary))
            TooltipIconButton("Refresh", mr::refresh, enabled = !mr.loading && status != null) {
                if (mr.loading || status == null) Spinner(size = 14.dp) else GlyphIcon(Glyph.Fetch, size = 14.dp)
            }
        }
        HairlineDivider()
        Box(Modifier.fillMaxSize()) {
            val ready = status != null && (status != ForgeStatus.Ready || list != null || mr.error != null)
            Reveal(ready, { ListSkeleton() }, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    when {
                        status == null -> {}
                        status != ForgeStatus.Ready -> Unavailable(status, mr, Modifier.align(Alignment.Center))
                        list == null -> mr.error?.let { ErrorBox(it, mr::refresh, Modifier.align(Alignment.Center)) }
                        list.isEmpty() -> Placeholder(
                            Glyph.MergeRequest,
                            if (mr.query.isBlank() && mr.stateFilter == MrStateFilter.Open && mr.scopeFilter == MrScope.All) "No open ${mr.terms.noun}s" else "No ${mr.terms.noun}s match",
                            "Try another state, scope or search", Modifier.align(Alignment.Center),
                        )
                        else -> ScrollableList { listState ->
                            LazyColumn(Modifier.fillMaxSize(), listState, PaddingValues(Spacing.xs)) {
                                items(list, key = { it.iid }) { MrRow(it, mr.terms, isCurrent = it.source == state.status?.branch && it.state == "opened") { mr.openDetail(it.iid) } }
                            }
                        }
                    }
                }
            }
            ProgressStrip(mr.loading && list != null, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun MrRow(m: MergeRequest, terms: Terms, isCurrent: Boolean, onOpen: () -> Unit) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(if (isCurrent) c.selected else if (hovered) c.hover else c.hover.copy(alpha = 0f), Motion.fast())
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(Radius.sm)).background(bg)
            .hoverable(source).clickable(source, null, onClick = onOpen)
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        GlyphIcon(Glyph.MergeRequest, tint = if (isCurrent) c.accentText else c.textTertiary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    m.title.removePrefix("Draft: "), Modifier.weight(1f, fill = false), // the Draft pill already says it
                    style = (if (isCurrent) t.bodyStrong else t.body).copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (m.state != "opened") StatePill(m.state, m.draft) else if (m.draft) Pill("Draft")
                if (isCurrent) Pill("Your branch", color = c.accentText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                BasicText(terms.ref(m.iid), style = t.monoSmall.copy(color = c.textSecondary, lineHeight = t.caption.lineHeight))
                BasicText(
                    "${m.author}  ·  ${relativeTime(m.updatedEpoch)}",
                    style = t.caption.copy(color = c.textTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BranchFlow(m.source, m.target, Modifier.widthIn(max = 340.dp))
        Box(Modifier.alpha(if (hovered) 1f else 0f)) {
            TooltipIconButton("Open in browser", { openInBrowser(m.webUrl) }) { GlyphIcon(Glyph.External, size = 14.dp, tint = c.textTertiary) }
        }
    }
}

/** "source → target" in a mono capsule; the source truncates first. */
@Composable
internal fun BranchFlow(source: String, target: String, modifier: Modifier = Modifier) {
    val c = MarginTheme.colors
    val style = MarginTheme.type.monoSmall.copy(color = c.textSecondary, lineHeight = MarginTheme.type.caption.lineHeight)
    Row(
        modifier.clip(RoundedCornerShape(Radius.xs)).background(c.hover).padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(source, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("  →  ", style = style.copy(color = c.textTertiary))
        BasicText(target, style = style, maxLines = 1)
    }
}

/** Eight rows laid out like [MrRow]: icon, title, meta line, branch capsule, and the hover button's slot. */
@Composable
private fun ListSkeleton() {
    val titles = listOf(0.72f, 0.6f, 0.85f, 0.66f, 0.78f, 0.62f, 0.8f, 0.7f)
    val branches = listOf(190, 150, 230, 170, 210, 160, 200, 180)
    Column(Modifier.fillMaxSize().padding(Spacing.xs)) {
        titles.forEachIndexed { i, w ->
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                SkeletonBlock(Modifier.size(Sizes.icon), CircleShape)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonBlock(Modifier.fillMaxWidth(w).height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SkeletonBlock(Modifier.width(28.dp).height(8.dp))
                        SkeletonBlock(Modifier.width((110 + i % 3 * 24).dp).height(8.dp))
                    }
                }
                SkeletonBlock(Modifier.width(branches[i].dp).height(18.dp))
                Spacer(Modifier.width(Sizes.iconButton))
            }
        }
    }
}

@Composable
private fun Unavailable(status: ForgeStatus, mr: MrState, modifier: Modifier) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    val f = mr.forge ?: return
    val w = f.terms
    val (title, body, fix) = when (status) {
        ForgeStatus.NotInstalled -> Triple("Install ${w.cli}", "Margin reads ${w.noun}s through ${w.site}'s ${w.cli} CLI, so your token stays in its keychain.", "brew install ${w.cli}")
        is ForgeStatus.NotAuthenticated -> Triple("Sign in to ${status.host}", "Paste a personal access token with ${w.scopes}. ${w.cli} stores it in your keychain; Margin never keeps it.", null)
        is ForgeStatus.Unsupported -> Triple("Not a GitHub or GitLab repository", "origin points to ${status.host}. Reviews need a GitHub or GitLab remote.", null)
        ForgeStatus.NoOrigin -> Triple("No remote", "This repository has no origin remote to read ${w.noun}s from.", "git remote add origin <url>")
        ForgeStatus.Ready -> return
    }
    Column(modifier.width(460.dp).padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(Radius.lg)).background(c.surfaceRaised).border(1.dp, c.border, RoundedCornerShape(Radius.lg)),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.MergeRequest, size = 22.dp, tint = c.textTertiary) }
        Spacer(Modifier.height(Spacing.xl))
        BasicText(title, style = t.title.copy(color = c.text))
        Spacer(Modifier.height(Spacing.sm))
        BasicText(body, style = t.body.copy(color = c.textSecondary, textAlign = TextAlign.Center))
        if (fix != null) {
            Spacer(Modifier.height(Spacing.xl))
            Panel(Modifier.fillMaxWidth(), color = c.surfaceRaised) {
                Row(Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("$ ", style = t.monoSmall.copy(color = c.textTertiary))
                    SelectionContainer(Modifier.weight(1f)) { BasicText(fix, style = t.monoSmall.copy(color = c.text), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    var copied by remember { mutableStateOf(false) }
                    TooltipIconButton(if (copied) "Copied" else "Copy", {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(fix), null)
                        copied = true
                    }) { GlyphIcon(if (copied) Glyph.Check else Glyph.Copy, size = 14.dp, tint = if (copied) c.success else c.textTertiary) }
                }
            }
            BasicText("Run this in Terminal, then try again.", Modifier.padding(top = Spacing.sm), style = t.caption.copy(color = c.textTertiary))
        }
        if (status is ForgeStatus.NotAuthenticated) {
            Spacer(Modifier.height(Spacing.xl))
            SignInForm(mr.signingIn, mr.signInError, w.tokenHint, f.tokenUrl(status.host), status.host) { mr.signIn(status.host, it) }
        }
        Spacer(Modifier.height(Spacing.xl))
        GhostButton("Try again", mr::refresh)
    }
}

@Composable
internal fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier) {
    val c = MarginTheme.colors
    Column(modifier.widthIn(max = 520.dp).padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Panel(Modifier.border(1.dp, c.danger.copy(alpha = 0.45f), RoundedCornerShape(Radius.md)), color = c.surfaceRaised) {
            SelectionContainer {
                BasicText(message, Modifier.padding(Spacing.lg), style = MarginTheme.type.monoSmall.copy(color = c.textSecondary), maxLines = 10, overflow = TextOverflow.Ellipsis)
            }
        }
        GhostButton("Try again", onRetry)
    }
}

// ---------- Toolbar entry point ----------

/** "Create MR", or "View MR !123" when the current branch already has one ("PR #123" on GitHub). Hidden until the CLI is ready. */
@Composable
fun MrToolbarButton(mr: MrState, state: AppState) {
    val c = MarginTheme.colors
    val s = state.status ?: return
    if (mr.status != ForgeStatus.Ready) return
    val existing = mr.current?.takeIf { it.source == s.branch }
    if (existing != null) GhostButton(
        "View ${mr.terms.short} ${mr.terms.ref(existing.iid)}", { openInBrowser(existing.webUrl) },
        leading = { GlyphIcon(Glyph.External, size = 14.dp, tint = c.accentText) },
    ) else {
        val enabled = s.branch != null && s.branch != mr.defaultBranch
        GhostButton(
            "Create ${mr.terms.short}", { mr.sheetOpen = true }, enabled = enabled,
            leading = { GlyphIcon(Glyph.MergeRequest, size = 14.dp, tint = if (enabled) c.textSecondary else c.textDisabled) },
        )
    }
}

// ---------- Create sheet ----------

private val SheetWidth = 480.dp

@Composable
fun MrSheet(mr: MrState, state: AppState) {
    val close = { if (!mr.creating) mr.sheetOpen = false }
    Modal(mr.sheetOpen, close, SheetWidth) { CreateForm(mr, state, close) }
}

@Composable
private fun CreateForm(mr: MrState, state: AppState, close: () -> Unit) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    val source = state.status?.branch.orEmpty()
    val targets = state.branches.filter { it.isRemote && it.name.startsWith("origin/") }.map { it.name.removePrefix("origin/") }.filter { it != source }
    var title by remember { mutableStateOf(state.log.firstOrNull()?.subject.orEmpty()) }
    var description by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(false) }
    val target = picked ?: mr.defaultBranch ?: targets.firstOrNull { it == "main" || it == "master" } ?: targets.firstOrNull().orEmpty()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.padding(Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BasicText("New ${mr.terms.noun}", style = t.title.copy(color = c.text))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BasicText("From", style = t.caption.copy(color = c.textTertiary))
                BranchFlow(source, target.ifEmpty { "…" }, Modifier.weight(1f, fill = false))
            }
        }
        Field("Title") { InputField(title, { title = it }, "Summarize the change", Modifier.fillMaxWidth().focusRequester(focus)) }
        Field("Description") {
            InputField(description, { description = it }, "What changed and why (optional)", Modifier.fillMaxWidth().heightIn(max = 180.dp), singleLine = false, minHeight = 96.dp)
        }
        Field("Target branch") { BranchPicker(target, targets) { picked = it } }
        Row(
            Modifier.clip(RoundedCornerShape(Radius.sm)).clickable(remember { MutableInteractionSource() }, null) { draft = !draft }.padding(vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Toggle(draft)
            Column {
                BasicText("Mark as draft", style = t.body.copy(color = c.text))
                BasicText("It can't be merged until you mark it ready", style = t.caption.copy(color = c.textTertiary))
            }
        }
        mr.createError?.let { err ->
            Panel(Modifier.fillMaxWidth().border(1.dp, c.danger.copy(alpha = 0.45f), RoundedCornerShape(Radius.md)), color = c.surface) {
                SelectionContainer {
                    BasicText(err, Modifier.padding(Spacing.md), style = t.monoSmall.copy(color = c.textSecondary), maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.End)) {
            GhostButton("Cancel", close, enabled = !mr.creating)
            PrimaryButton(
                if (mr.creating) "Creating…" else "Create ${mr.terms.noun}",
                { mr.create(source, target, title, description, draft) },
                enabled = title.isNotBlank() && source.isNotEmpty() && target.isNotEmpty() && target != source,
                loading = mr.creating,
            )
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BasicText(label, style = MarginTheme.type.label.copy(color = MarginTheme.colors.textSecondary))
        content()
    }
}

@Composable
internal fun Toggle(checked: Boolean) {
    val c = MarginTheme.colors
    val track by animateColorAsState(if (checked) c.accent else c.borderStrong, Motion.base())
    val x by animateDpAsState(if (checked) 12.dp else 0.dp, Motion.base())
    Box(Modifier.size(28.dp, 16.dp).clip(RoundedCornerShape(50)).background(track).padding(2.dp)) {
        Box(Modifier.offset(x = x).size(12.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun BranchPicker(value: String, options: List<String>, onPick: (String) -> Unit) {
    val c = MarginTheme.colors
    var open by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (open) c.focusRing else c.border, Motion.fast())
    Box {
        Row(
            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(Radius.sm)).background(c.surfaceRaised)
                .border(1.dp, border, RoundedCornerShape(Radius.sm))
                .clickable(remember { MutableInteractionSource() }, null) { open = !open }
                .padding(horizontal = Spacing.md + Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GlyphIcon(Glyph.Branch, size = 14.dp, tint = c.textTertiary)
            BasicText(value, Modifier.weight(1f), style = MarginTheme.type.body.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            GlyphIcon(Glyph.ChevronDown, size = 12.dp, tint = c.textTertiary)
        }
        if (open) Popup(
            offset = with(LocalDensity.current) { IntOffset(0, (32.dp + Spacing.xs).roundToPx()) },
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true),
        ) {
            var query by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            val shown = options.filter { it.contains(query.trim(), ignoreCase = true) }
            Panel(Modifier.width(SheetWidth - Spacing.xxl * 2).border(1.dp, c.borderStrong, RoundedCornerShape(Radius.md)), color = c.surfaceRaised) {
                Column(Modifier.padding(Spacing.xs)) {
                    InputField(query, { query = it }, "Filter branches", Modifier.fillMaxWidth().focusRequester(focus))
                    LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = Spacing.xs)) {
                        items(shown) { b ->
                            SidebarItem(
                                b, { onPick(b); open = false }, selected = b == value,
                                leading = { GlyphIcon(Glyph.Branch, size = 14.dp, tint = if (b == value) c.accentText else c.textTertiary) },
                            )
                        }
                    }
                    if (shown.isEmpty()) BasicText("No matching branches", Modifier.padding(Spacing.md), style = MarginTheme.type.caption.copy(color = c.textTertiary))
                }
            }
        }
    }
}

// ---------- Toast ----------

@Composable
fun MrToast(mr: MrState, modifier: Modifier) {
    val c = MarginTheme.colors
    var last by remember { mutableStateOf(mr.toast) } // keeps content alive during the exit fade
    mr.toast?.let { last = it }
    AnimatedVisibility(
        mr.toast != null, modifier,
        enter = fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { 8 },
        exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { 8 },
    ) {
        val url = last ?: return@AnimatedVisibility
        Panel(Modifier.widthIn(max = 480.dp), color = c.surfaceRaised) {
            Row(
                Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                GlyphIcon(Glyph.Check, size = 14.dp, tint = c.success)
                Column(Modifier.weight(1f, fill = false).padding(vertical = Spacing.sm)) {
                    BasicText("${mr.terms.nounCap} created", style = MarginTheme.type.bodyStrong.copy(color = c.text))
                    BasicText(
                        url, Modifier.clickable(remember { MutableInteractionSource() }, null) { openInBrowser(url) },
                        style = MarginTheme.type.caption.copy(color = c.accentText), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                TooltipIconButton("Dismiss", { mr.toast = null }) { GlyphIcon(Glyph.Close, size = 12.dp, tint = c.textTertiary) }
            }
        }
    }
}

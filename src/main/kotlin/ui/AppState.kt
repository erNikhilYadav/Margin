package margin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import margin.git.Branch
import margin.git.ChangeKind
import margin.git.Commit
import margin.git.DiffLine
import margin.git.FileChange
import margin.git.GitException
import margin.git.GitRepo
import margin.git.RepoStatus
import margin.git.Worktree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.prefs.Preferences

enum class Action { Fetch, Pull, Push, Commit, Checkout, Rollback }
enum class Tab { Changes, History }
enum class ThemeMode { System, Light, Dark }
data class Toast(val text: String, val isError: Boolean = false, val title: String? = null)

/** All app state. Mutations run on the UI dispatcher of [scope]; GitRepo hops to IO itself. */
class AppState(
    private val scope: CoroutineScope,
    private val prefs: Preferences = Preferences.userRoot().node("margin"),
    private val newRepo: (File) -> GitRepo = { GitRepo(it) }, // tests inject a GitRepo with a fake trash
) {
    private val gitLock = Mutex() // one git process at a time, so index.lock never collides
    private var diffJob: Job? = null
    private var commitJob: Job? = null
    private var commitDiffJob: Job? = null
    private var writes = 0 // bumped by every write, so a status read that started earlier can't undo an optimistic update
    private var toastJob: Job? = null

    var repo by mutableStateOf<GitRepo?>(null); private set
    var status by mutableStateOf<RepoStatus?>(null); private set
    var branches by mutableStateOf(emptyList<Branch>()); private set
    var log by mutableStateOf(emptyList<Commit>()); private set
    var worktrees by mutableStateOf(emptyList<Worktree>()); private set
    var selected by mutableStateOf<FileChange?>(null); private set
    var selection by mutableStateOf(emptySet<String>()) // selected paths in the Changes list; [selected] is the one in the diff
    var rollbackTarget by mutableStateOf<Set<String>?>(null) // checked paths in the open rollback dialog; null when closed
    var diff by mutableStateOf<List<DiffLine>?>(null); private set // null while loading
    var selectedCommit by mutableStateOf<Commit?>(null); private set
    var commitFiles by mutableStateOf<List<FileChange>?>(null); private set // null while loading
    var commitFile by mutableStateOf<FileChange?>(null); private set
    var commitDiff by mutableStateOf<List<DiffLine>?>(null); private set
    var themeMode by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == prefs.get(THEME, null) } ?: ThemeMode.System); private set
    var sideBySide by mutableStateOf(prefs.getBoolean(SIDE_BY_SIDE, false)); private set
    /** Markdown files show the rendered file instead of the diff; sticks while browsing files. */
    var mdPreview by mutableStateOf(false)
    var wholeFile by mutableStateOf(prefs.getBoolean(WHOLE_FILE, false)); private set
    var collapsedGroups by mutableStateOf(prefs.get(COLLAPSED_GROUPS, "").split(',').filter { it.isNotEmpty() }.toSet()); private set
    /** Previous (-1) / next (+1) change requests from the menu and F7; the visible diff consumes them. */
    val changeNav = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    var message by mutableStateOf("")
    var tab by mutableStateOf(Tab.Changes)
    var branchMenuOpen by mutableStateOf(false)
    var inMergeRequests by mutableStateOf(false) // the MRs tab is up: ⌘↵ there must not commit from the hidden commit box
    var busy by mutableStateOf(emptySet<Action>()); private set
    var switchingTo by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<Toast?>(null); private set
    var recents by mutableStateOf(
        prefs.get(RECENTS, "").lines().filter { it.isNotBlank() }.map(::File).filter { it.isDirectory }
    ); private set

    /** One entry per path: its staged and/or unstaged side, staged first. Conflicts sort first. */
    val files get() = status?.changes.orEmpty().groupBy { it.path }.values.sortedBy { it[0].kind != ChangeKind.Conflicted }
    val stagedFileCount get() = status?.changes.orEmpty().filter { it.staged }.distinctBy { it.path }.size
    val canCommit get() = stagedFileCount > 0 && message.isNotBlank() && Action.Commit !in busy

    init {
        recents.firstOrNull()?.let(::open)
    }

    fun open(dir: File) {
        scope.launch {
            if (!withContext(Dispatchers.IO) { GitRepo.isRepo(dir) }) {
                recents = recents - dir
                prefs.put(RECENTS, recents.joinToString("\n") { it.path })
                showError("Choose the folder that contains .git", "“${dir.name}” isn't a git repository")
                return@launch
            }
            repo = newRepo(dir)
            status = null; branches = emptyList(); log = emptyList(); worktrees = emptyList(); message = ""; tab = Tab.Changes; branchMenuOpen = false
            selection = emptySet(); rollbackTarget = null
            select(null)
            selectCommit(null)
            recents = (listOf(dir) + recents.filter { it != dir }).take(8)
            prefs.put(RECENTS, recents.joinToString("\n") { it.path })
            load(full = true)
        }
    }

    fun openWithPicker(parent: Frame) = chooseFolder(parent)?.let(::open)

    fun refresh() {
        scope.launch { load(full = true) }
    }

    /** Background poll: status only, and never while an action is running. */
    fun poll() {
        if (busy.isEmpty() && !gitLock.isLocked) scope.launch { load(full = false) }
    }

    private var lastAutoFetch = 0L
    private var autoFetching = false

    /** Quiet `git fetch` at most every [AUTO_FETCH_MS], so new upstream commits show up without a click. */
    fun autoFetch(now: Long = System.currentTimeMillis()) {
        val r = repo ?: return
        if (autoFetching || busy.isNotEmpty() || now - lastAutoFetch < AUTO_FETCH_MS) return
        autoFetching = true
        lastAutoFetch = now
        val before = status
        scope.launch {
            try {
                r.fetch() // outside gitLock: a stalled network must not freeze the status poll
                if (repo !== r) return@launch
                load(full = true)
                val s = status ?: return@launch
                val n = s.behind - (before?.behind ?: 0)
                if (n > 0 && s.branch == before?.branch) show(Toast("$n new ${if (n == 1) "commit" else "commits"} on ${s.upstream} · Pull ⇧⌘L"))
            } catch (_: GitException) {
                // offline or no remote: say nothing, a manual Fetch shows the error
            } finally {
                autoFetching = false
            }
        }
    }

    private suspend fun load(full: Boolean) {
        val r = repo ?: return
        try {
            val w = writes
            val (s, b, l) = gitLock.withLock {
                val s = r.status()
                // a commit, checkout or pull made outside Margin moves HEAD: reload branches and history too
                val all = full || s.head != status?.head || s.branch != status?.branch
                Triple(s, if (all) r.branches() else null, if (all) r.log() else null)
            }
            if (repo !== r) return
            b?.let { branches = it }
            l?.let { log = it }
            if (w == writes) applyStatus(s)
        } catch (e: GitException) {
            showError(e.message)
        }
    }

    /** Keeps the selection; after stage/unstage it follows the file to the other side. */
    private fun applyStatus(s: RepoStatus) {
        status = s
        val paths = s.changes.mapTo(HashSet()) { it.path }
        selection = selection.filterTo(HashSet()) { it in paths }
        selected?.let { sel ->
            select(s.changes.find { it.path == sel.path && it.staged == sel.staged } ?: s.changes.find { it.path == sel.path })
        }
    }

    fun selectCommit(commit: Commit?) {
        val r = repo
        commitJob?.cancel() // a cancelled job never assigns: withContext drops a result that lands after cancellation
        commitDiffJob?.cancel()
        selectedCommit = commit
        commitFiles = null; commitFile = null; commitDiff = null
        if (commit == null || r == null) return
        commitJob = scope.launch {
            try {
                val files = r.commitFiles(commit.hash)
                commitFiles = files
                files.firstOrNull()?.let { selectCommitFile(it) }
            } catch (e: GitException) {
                showError(e.message)
            }
        }
    }

    fun selectCommitFile(change: FileChange) {
        val r = repo ?: return
        val commit = selectedCommit ?: return
        commitDiffJob?.cancel()
        if (change.path != commitFile?.path) commitDiff = null // same file, other context: keep the old diff until the new one lands
        commitFile = change
        commitDiffJob = scope.launch {
            try {
                commitDiff = r.commitDiff(commit.hash, change, context)
            } catch (e: GitException) {
                showError(e.message)
            }
        }
    }

    fun select(change: FileChange?) {
        val r = repo
        if (change?.path != selected?.path) diff = null // same file, other side: keep the old diff until the new one lands
        selected = change
        diffJob?.cancel()
        if (change == null || r == null) return
        diffJob = scope.launch {
            try {
                diff = r.diff(change, context)
            } catch (e: GitException) {
                showError(e.message)
            }
        }
    }

    private val context get() = if (wholeFile) GitRepo.WHOLE_FILE else 3

    fun showTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.put(THEME, mode.name)
    }

    /** Toolbar button: System → Light → Dark → System. */
    fun cycleTheme() = showTheme(ThemeMode.entries[(themeMode.ordinal + 1) % ThemeMode.entries.size])

    fun showSideBySide(on: Boolean) {
        sideBySide = on
        prefs.putBoolean(SIDE_BY_SIDE, on)
    }

    /** Collapses or expands a Changes list group ("Changes", "Unversioned"); remembered across launches. */
    fun toggleGroup(title: String) {
        collapsedGroups = if (title in collapsedGroups) collapsedGroups - title else collapsedGroups + title
        prefs.put(COLLAPSED_GROUPS, collapsedGroups.joinToString(","))
    }

    /** Reloads both diffs; select() keeps the stale-load protection. */
    fun showWholeFile(on: Boolean) {
        if (on == wholeFile) return
        wholeFile = on
        prefs.putBoolean(WHOLE_FILE, on)
        selected?.let(::select)
        commitFile?.let(::selectCommitFile)
    }

    fun nextChange() { changeNav.tryEmit(1) }
    fun previousChange() { changeNav.tryEmit(-1) }

    fun stage(changes: List<FileChange>) {
        optimistic(changes, staged = true)
        write { it.stage(changes.map { c -> c.path }.distinct()) }
    }

    fun unstage(changes: List<FileChange>) {
        optimistic(changes, staged = false)
        write { it.unstage(changes.flatMap { c -> listOfNotNull(c.oldPath, c.path) }.distinct()) }
    }

    /** Collapses each path to the side it is about to be on, so checkboxes flip before git returns. The next status() reconciles. */
    private fun optimistic(changes: List<FileChange>, staged: Boolean) {
        val s = status ?: return
        val paths = changes.mapTo(HashSet()) { it.path }
        applyStatus(s.copy(changes = s.changes.groupBy { it.path }.flatMap { (path, sides) ->
            if (path !in paths) return@flatMap sides
            val k = sides[0].kind
            val kind = when {
                staged && k == ChangeKind.Untracked -> ChangeKind.Added
                staged && k == ChangeKind.Conflicted -> ChangeKind.Modified
                !staged && k == ChangeKind.Added -> ChangeKind.Untracked
                else -> k
            }
            listOf(sides[0].copy(kind = kind, staged = staged))
        }))
    }

    fun commit() {
        if (!canCommit) return
        val msg = message.trim()
        val n = stagedFileCount
        write(Action.Commit, "Committed $n ${if (n == 1) "file" else "files"}") {
            it.commit(msg)
            message = ""
        }
    }

    fun checkout(branch: Branch) {
        if (branch.isCurrent) return
        switchingTo = branch.name
        write(Action.Checkout) { it.checkout(branch) }
    }

    /** Fetches, then switches to [name] from origin (a tracking branch is created if needed). */
    fun checkoutRemote(name: String) {
        switchingTo = name
        write(Action.Checkout, "Checked out $name") { it.fetch(); it.checkout(Branch("origin/$name", isRemote = true, isCurrent = false)) }
    }

    fun createBranch(name: String) {
        switchingTo = name
        write(Action.Checkout) { it.createBranch(name.trim()) }
    }

    /** Loaded when the repo popover opens, not on every poll: worktrees rarely change. */
    fun loadWorktrees() {
        val r = repo ?: return
        scope.launch {
            try {
                val w = gitLock.withLock { r.worktrees() }
                if (repo === r) worktrees = w
            } catch (e: GitException) {
                showError(e.message)
            }
        }
    }

    fun removeWorktree(wt: Worktree) = write(success = "Removed worktree ${wt.dir.name}") {
        it.removeWorktree(wt)
        val w = it.worktrees()
        if (repo === it) worktrees = w
    }

    fun fetch() = write(Action.Fetch) { it.fetch() }
    fun pull() = write(Action.Pull, "Pulled latest changes") { it.pull() }
    fun push() = write(Action.Push, "Pushed ${status?.branch ?: "branch"}") { it.push() }

    /**
     * Opens the rollback dialog with [paths] checked. With none of them present it checks every tracked change,
     * never an untracked one, so a default rollback can't trash new files.
     */
    fun requestRollback(paths: Collection<String> = selection) {
        val changes = status?.changes.orEmpty().ifEmpty { return }
        val present = changes.mapTo(HashSet()) { it.path }
        rollbackTarget = paths.filterTo(HashSet()) { it in present }
            .ifEmpty { changes.filter { it.kind != ChangeKind.Untracked }.mapTo(HashSet()) { it.path } }
    }

    fun cancelRollback() {
        rollbackTarget = null
    }

    fun confirmRollback() {
        val checked = rollbackTarget ?: return // idempotent: ⌘↵ may arrive from both the dialog and the menu
        val changes = status?.changes.orEmpty().filter { it.path in checked }.ifEmpty { return } // every side of each checked path
        rollbackTarget = null
        val n = changes.distinctBy { it.path }.size
        selection = emptySet()
        write(Action.Rollback, "Rolled back $n ${if (n == 1) "file" else "files"}") { it.rollback(changes) }
    }

    /** Toast for work done outside AppState, e.g. merge request actions. */
    fun notify(text: String, isError: Boolean = false, title: String? = null) = if (isError) showError(text, title) else show(Toast(text))

    fun dismissToast() {
        toast = null
    }

    private fun write(action: Action? = null, success: String? = null, block: suspend (GitRepo) -> Unit) {
        val r = repo ?: return
        if (action != null) {
            if (action in busy) return
            busy = busy + action
        }
        writes++
        scope.launch {
            try {
                gitLock.withLock { block(r) }
                success?.let { show(Toast(it)) }
            } catch (e: GitException) {
                showError(e.message, action?.let { "${it.name} failed" })
            } finally {
                if (action != null) busy = busy - action
                load(full = action != null)
            }
        }
    }

    /** Drops git's "hint:" lines so the actual error is what the user sees first. */
    private fun showError(msg: String?, title: String? = null) {
        val text = msg.orEmpty().lines().filterNot { it.startsWith("hint:") }.joinToString("\n").trim()
        show(Toast(text.ifEmpty { msg ?: "Something went wrong" }, isError = true, title = title))
    }

    private fun show(t: Toast) {
        toast = t
        toastJob?.cancel()
        if (!t.isError) toastJob = scope.launch { delay(2500); if (toast == t) toast = null }
    }

    private companion object {
        const val RECENTS = "recentRepos"
        const val AUTO_FETCH_MS = 3 * 60_000L
        const val THEME = "themeMode"
        const val SIDE_BY_SIDE = "diffSideBySide"
        const val WHOLE_FILE = "diffWholeFile"
        const val COLLAPSED_GROUPS = "changesCollapsedGroups"
    }
}

/** Native macOS folder picker. Blocks until the user picks or cancels. */
fun chooseFolder(parent: Frame): File? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    try {
        val dialog = FileDialog(parent, "Open Repository", FileDialog.LOAD)
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    } finally {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}

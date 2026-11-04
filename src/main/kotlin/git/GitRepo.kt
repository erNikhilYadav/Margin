package margin.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/** [trash] moves a file to the Trash; tests inject their own so they never touch the real one. */
class GitRepo(val dir: File, private val trash: (File) -> Unit = ::moveToTrash) {

    suspend fun status(): RepoStatus =
        parseStatus(git("status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all"))

    /** Newest commit first (ties by name), so the branch popup's "Recent" is just the head of the local list. */
    suspend fun branches(): List<Branch> =
        parseBranches(git("for-each-ref", "--sort=-committerdate", "--format=%(HEAD) %(refname)", "refs/heads", "refs/remotes"))

    suspend fun log(limit: Int = 200): List<Commit> = try {
        parseLog(git("log", "-z", "-n", limit.toString(), "--no-show-signature", "--format=%H%x00%an%x00%at%x00%s%x00%b"))
    } catch (e: GitException) {
        if ("does not have any commits" in e.message.orEmpty()) emptyList() else throw e
    }

    /** [context] lines around each change; [WHOLE_FILE] shows the entire file as one hunk. Untracked files are always whole. */
    suspend fun diff(change: FileChange, context: Int = 3): List<DiffLine> {
        if (change.kind == ChangeKind.Untracked) return withContext(Dispatchers.IO) {
            // shortcut: reads the whole file into memory, add a size cap if huge untracked files show up
            val file = File(dir, change.path)
            val bytes = if (file.isFile) file.readBytes() else ByteArray(0)
            if (bytes.isEmpty() || 0.toByte() in bytes) emptyList()
            else String(bytes).removeSuffix("\n").split('\n')
                .mapIndexed { i, line -> DiffLine(LineType.Added, line, null, i + 1) }
        }
        val cached = if (change.staged) listOf("--cached") else emptyList()
        val paths = listOfNotNull(change.oldPath, change.path)
        return parseDiff(git(*(listOf("diff", "--no-color", "--no-ext-diff", "-M", "-U$context") + cached + "--" + paths).toTypedArray()))
    }

    /** Files changed by [hash]; merges are diffed against their first parent, the root commit against the empty tree. */
    suspend fun commitFiles(hash: String): List<FileChange> =
        parseNameStatus(git("diff-tree", "-r", "-z", "-M", "--root", "--name-status", "--no-commit-id", "--diff-merges=first-parent", hash))

    /** Binary files yield an empty list, like [diff]. */
    suspend fun commitDiff(hash: String, change: FileChange, context: Int = 3): List<DiffLine> = parseDiff(git(
        *(listOf("diff-tree", "-p", "-M", "-U$context", "--root", "--no-color", "--no-ext-diff", "--no-commit-id", "--diff-merges=first-parent", hash, "--") +
            listOfNotNull(change.oldPath, change.path)).toTypedArray(),
    ))

    /** Text of the side a diff shows as new (worktree, or the index when staged); a deletion yields what was removed. For Markdown preview. */
    suspend fun fileText(change: FileChange): String = when {
        change.kind == ChangeKind.Deleted -> git("show", "HEAD:${change.path}")
        change.staged -> git("show", ":${change.path}")
        else -> withContext(Dispatchers.IO) { File(dir, change.path).readText() }
    }

    /** [fileText] for a file as of commit [hash]. */
    suspend fun commitFileText(hash: String, change: FileChange): String =
        if (change.kind == ChangeKind.Deleted) git("show", "$hash^:${change.path}") else git("show", "$hash:${change.path}")

    suspend fun stage(paths: List<String>) { git("add", "--", *paths.toTypedArray()) }

    /** For a staged rename pass both oldPath and path, or the deletion side stays staged. */
    suspend fun unstage(paths: List<String>) { git("reset", "-q", "--", *paths.toTypedArray()) } // works with an unborn HEAD

    /**
     * Discards every local change to [changes], staged and unstaged. Paths in HEAD (incl. a rename's old path and
     * conflicts) are restored from it in index and worktree; the rest (new, untracked, rename targets) leave the
     * index and go to the Trash, never rm. Literal pathspecs, so a file named `*.kt` can't widen the restore.
     */
    suspend fun rollback(changes: List<FileChange>) {
        val tracked = changes.filter { it.kind != ChangeKind.Untracked }.flatMap { listOfNotNull(it.oldPath, it.path) }.distinct()
        val untracked = changes.filter { it.kind == ChangeKind.Untracked }.map { it.path }
        val inHead = if (tracked.isEmpty()) emptySet() else try {
            git("--literal-pathspecs", "ls-tree", "-r", "-z", "--name-only", "HEAD", "--", *tracked.toTypedArray()).split('\u0000').toSet()
        } catch (e: GitException) {
            if ("Not a valid object name HEAD" in e.message.orEmpty()) emptySet() else throw e // no commits yet
        }
        val (restore, added) = tracked.partition { it in inHead }
        // Trash first: an untracked file sitting at a path we restore keeps its content in the Trash.
        withContext(Dispatchers.IO) { (untracked + added).distinct().map { File(dir, it) }.filter { it.exists() }.forEach(trash) }
        if (added.isNotEmpty()) git("--literal-pathspecs", "reset", "-q", "--", *added.toTypedArray())
        if (restore.isNotEmpty()) git("--literal-pathspecs", "restore", "--source=HEAD", "--staged", "--worktree", "--", *restore.toTypedArray())
    }

    suspend fun commit(message: String) { git("commit", "-m", message) }

    suspend fun checkout(branch: Branch) {
        if (!branch.isRemote) {
            git("switch", branch.name)
            return
        }
        val local = branch.name.substringAfter('/')
        val exists = try { git("show-ref", "--verify", "--quiet", "refs/heads/$local"); true } catch (_: GitException) { false }
        if (exists) git("switch", local) else git("switch", "-c", local, "--track", branch.name)
    }

    suspend fun createBranch(name: String) { git("switch", "-c", name) }

    /** Main worktree first, with ahead/behind against [BASE] and the source branch. Skips bare repos and worktrees whose folder is gone. */
    suspend fun worktrees(): List<Worktree> {
        val list = parseWorktrees(git("worktree", "list", "--porcelain", "-z")).filter { it.dir.isDirectory }
        val hasBase = try { git("show-ref", "--verify", "--quiet", "refs/heads/$BASE"); true } catch (_: GitException) { false }
        val main = list.firstOrNull { it.isMain }
        val headLog = reflog(main?.dir ?: dir, "HEAD")
        return list.map { wt ->
            val counts = if (!hasBase || wt.branch == BASE) null
            else git("rev-list", "--left-right", "--count", "$BASE...${wt.head}").trim().split(Regex("\\s+")).map(String::toInt)
            wt.copy(behind = counts?.get(0), ahead = counts?.get(1), from = wt.branch?.let { parseCreatedFrom(reflog(dir, "refs/heads/$it"), headLog, main?.branch) })
        }
    }

    /** Empty when the ref has no reflog. */
    private suspend fun reflog(at: File, ref: String): String = withContext(Dispatchers.IO) {
        try { runGit(at, listOf("reflog", "show", "--date=unix", "--format=%gd%x00%gs", ref, "--")) } catch (_: GitException) { "" }
    }

    /** Git refuses a worktree with changes or untracked files, or a locked one; that error reaches the user as is. */
    suspend fun removeWorktree(wt: Worktree) { git("worktree", "remove", wt.dir.path) }

    suspend fun fetch() { git("fetch") }

    suspend fun pull() { git("pull") }

    suspend fun push() {
        val s = status()
        val branch = s.branch ?: throw GitException("Cannot push: HEAD is detached")
        if (s.upstream == null) git("push", "-u", "origin", branch) else git("push")
    }

    private suspend fun git(vararg args: String): String = withContext(Dispatchers.IO) { runGit(dir, args.toList()) }

    companion object {
        /** Huge enough for any file: -U100000 cuts files over 200k lines and --function-context stops at function bounds. */
        const val WHOLE_FILE = Int.MAX_VALUE

        /** Worktrees show ahead/behind against this branch. shortcut: fixed to develop, make it per-repo if a repo uses main. */
        const val BASE = "develop"

        /** True only for the top level of a work tree, since all paths we parse are root-relative. */
        fun isRepo(dir: File): Boolean = try {
            dir.isDirectory && runGit(dir, listOf("rev-parse", "--show-prefix")).isBlank()
        } catch (_: GitException) {
            false
        }
    }
}

// A Finder-launched .app gets only /usr/bin:/bin:..., so add Homebrew for git itself, credential helpers and repo hooks.
private val toolPath = "/opt/homebrew/bin:/usr/local/bin:" + (System.getenv("PATH") ?: "/usr/bin:/bin:/usr/sbin:/sbin")
private val gitBin = findBinary("git", toolPath)?.path ?: "git"

/** Finder's Trash, so a rollback of new files can be undone. Never falls back to deleting. */
private fun moveToTrash(file: File) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop().takeIf { it.isSupported(Desktop.Action.MOVE_TO_TRASH) } else null
    if (desktop?.moveToTrash(file) != true) throw GitException("Couldn't move “${file.name}” to the Trash")
}

/** Blocking. Reads stderr on a side thread so a full pipe never deadlocks the process. */
internal fun runGit(dir: File, args: List<String>): String {
    val process = try {
        ProcessBuilder(listOf(gitBin, "-c", "core.quotepath=false") + args).directory(dir).apply {
            // GIT_OPTIONAL_LOCKS=0: our background status poll must never grab index.lock from the IDE
            environment() += mapOf("GIT_TERMINAL_PROMPT" to "0", "LC_ALL" to "C", "GIT_EDITOR" to "true", "GIT_OPTIONAL_LOCKS" to "0", "PATH" to toolPath)
        }.start()
    } catch (e: IOException) {
        throw GitException("Could not run git: ${e.message}")
    }
    process.outputStream.close()
    var err = ""
    val errReader = thread { err = process.errorStream.bufferedReader().readText() }
    val out = process.inputStream.bufferedReader().readText()
    errReader.join()
    if (process.waitFor() != 0) throw GitException(err.trim().ifEmpty { "git ${args.firstOrNull()} failed" })
    return out
}

internal fun parseStatus(raw: String): RepoStatus {
    var branch: String? = null
    var upstream: String? = null
    var ahead = 0
    var behind = 0
    var head: String? = null
    val changes = mutableListOf<FileChange>()
    val records = raw.split('\u0000')
    var i = 0
    while (i < records.size) {
        val r = records[i++]
        when (r.firstOrNull()) {
            '#' -> {
                val key = r.substringAfter("# ").substringBefore(' ')
                val value = r.substringAfter("$key ", "")
                when (key) {
                    "branch.oid" -> head = value
                    "branch.head" -> branch = value.takeIf { it != "(detached)" }
                    "branch.upstream" -> upstream = value
                    "branch.ab" -> value.split(' ').let { ahead = it[0].drop(1).toInt(); behind = it[1].drop(1).toInt() }
                }
            }
            '1', '2' -> {
                val f = r.split(' ', limit = if (r[0] == '1') 9 else 10)
                val old = if (r[0] == '2') records[i++] else null // renames carry the original path as the next record
                f[1].forEachIndexed { side, c ->
                    if (c != '.') changes += FileChange(f.last(), changeKind(c), staged = side == 0, oldPath = old.takeIf { c == 'R' })
                }
            }
            'u' -> changes += FileChange(r.split(' ', limit = 11).last(), ChangeKind.Conflicted, staged = false)
            '?' -> changes += FileChange(r.drop(2), ChangeKind.Untracked, staged = false)
        }
    }
    return RepoStatus(branch, upstream, ahead, behind, changes, head)
}

private fun changeKind(c: Char) = when (c) {
    'A', 'C' -> ChangeKind.Added
    'D' -> ChangeKind.Deleted
    'R' -> ChangeKind.Renamed
    else -> ChangeKind.Modified
}

/** Input: `git for-each-ref --format='%(HEAD) %(refname)'`. */
internal fun parseBranches(raw: String): List<Branch> = raw.lines().mapNotNull { line ->
    val ref = line.drop(2)
    when {
        ref.startsWith("refs/heads/") -> Branch(ref.removePrefix("refs/heads/"), isRemote = false, isCurrent = line[0] == '*')
        ref.startsWith("refs/remotes/") && !ref.endsWith("/HEAD") -> Branch(ref.removePrefix("refs/remotes/"), isRemote = true, isCurrent = false)
        else -> null
    }
}

/** Input: `git worktree list --porcelain -z`: NUL-terminated "key value" lines, an empty one ends each worktree. */
internal fun parseWorktrees(raw: String): List<Worktree> {
    val result = mutableListOf<Worktree>()
    var path: String? = null
    var branch: String? = null
    var head = ""
    var bare = false
    var first = true // git lists the main worktree first; when it's bare it is skipped and nothing is main
    for (line in raw.split('\u0000')) {
        when {
            line.startsWith("worktree ") -> path = line.removePrefix("worktree ")
            line.startsWith("HEAD ") -> head = line.removePrefix("HEAD ")
            line.startsWith("branch ") -> branch = line.removePrefix("branch ").removePrefix("refs/heads/")
            line == "bare" -> bare = true
            line.isEmpty() -> {
                if (path == null) continue
                if (!bare) result += Worktree(File(path), branch, isMain = first, head = head)
                path = null; branch = null; head = ""; bare = false; first = false
            }
        }
    }
    return result
}

private val checkoutMove = Regex("^checkout: moving from (.+) to (.+)$")

/**
 * The branch a branch was created from, per its oldest reflog entry ("branch: Created from develop").
 * "Created from HEAD" (switch -c, worktree add -b) is resolved to what the main worktree had checked out at that moment;
 * with no checkout in its history, that's still its [current] branch.
 * Inputs: `git reflog show --date=unix --format=%gd%x00%gs`, newest first.
 */
internal fun parseCreatedFrom(branchLog: String, headLog: String, current: String?): String? {
    val (sel, msg) = branchLog.lines().lastOrNull { '\u0000' in it }?.split('\u0000', limit = 2) ?: return null
    val from = msg.removePrefix("branch: Created from ").takeIf { it != msg } ?: return null
    if (from != "HEAD") return from
    val created = reflogTime(sel)
    // shortcut: assumes the branch was made from the main worktree; one made inside a linked worktree may name the wrong source
    val moves = headLog.lines().mapNotNull { line ->
        val (s, m) = line.split('\u0000', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        checkoutMove.find(m)?.let { reflogTime(s) to it.groupValues }
    }
    moves.firstOrNull { it.first <= created }?.let { return it.second[2] } // the last switch before: where HEAD went
    return moves.lastOrNull()?.second?.get(1) ?: current // else the first switch after: where HEAD came from
}

private fun reflogTime(selector: String) = selector.substringAfterLast("@{").substringBefore('}').toLongOrNull() ?: Long.MAX_VALUE

/** Input: `git log -z --format=%H%x00%an%x00%at%x00%s%x00%b`, so every field and record is NUL-separated. */
internal fun parseLog(raw: String): List<Commit> = raw.split('\u0000').chunked(5).filter { it.size == 5 }
    .map { (hash, author, time, subject, body) -> Commit(hash.trim(), subject, author, time.toLong(), body.trim()) }

/** Input: `git diff-tree -r -z --name-status`: a status record ("M", "R100", ...) then one path, or two for renames and copies. */
internal fun parseNameStatus(raw: String): List<FileChange> {
    val records = raw.split('\u0000').filter { it.isNotEmpty() }
    val result = mutableListOf<FileChange>()
    var i = 0
    while (i < records.size) {
        val code = records[i++].first()
        val two = code == 'R' || code == 'C'
        val old = if (two) records[i++] else null
        val path = records.getOrNull(i++) ?: break
        result += FileChange(path, if (code == 'U') ChangeKind.Conflicted else changeKind(code), staged = false, oldPath = old.takeIf { code == 'R' })
    }
    return result
}

private val hunkHeader = Regex("""^(@+) -(\d+)[^+]*\+(\d+)""")

/** Handles plain and combined (merge conflict) diffs. Binary diffs have no hunks, so they yield an empty list. */
internal fun parseDiff(raw: String): List<DiffLine> {
    val result = mutableListOf<DiffLine>()
    var width = 0 // prefix columns per line: 1 for plain diffs, one per parent for combined
    var oldNo = 0
    var newNo = 0
    for (line in raw.split('\n')) {
        val header = hunkHeader.find(line)
        when {
            header != null -> {
                width = header.groupValues[1].length - 1
                oldNo = header.groupValues[2].toInt()
                newNo = header.groupValues[3].toInt()
                result += DiffLine(LineType.Hunk, line, null, null)
            }
            line.startsWith("diff ") -> width = 0
            width == 0 || line.length < width || line.startsWith("\\") -> Unit // outside a hunk, or "\ No newline"
            else -> {
                val prefix = line.take(width)
                val inOld = prefix[0] != '+' // first parent has it unless that column added it
                val inNew = '-' !in prefix
                val type = when {
                    '+' in prefix -> LineType.Added
                    '-' in prefix -> LineType.Removed
                    else -> LineType.Context
                }
                result += DiffLine(type, line.drop(width), if (inOld) oldNo++ else null, if (inNew) newNo++ else null)
            }
        }
    }
    return result
}

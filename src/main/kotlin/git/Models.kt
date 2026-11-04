package margin.git

enum class ChangeKind { Modified, Added, Deleted, Renamed, Untracked, Conflicted }

/** A file with both staged and unstaged edits appears twice: once per side. [oldPath] is set only for Renamed. */
data class FileChange(val path: String, val kind: ChangeKind, val staged: Boolean, val oldPath: String? = null)

data class Branch(val name: String, val isRemote: Boolean, val isCurrent: Boolean)

/**
 * [branch] is null when detached. The main worktree can't be removed. [ahead]/[behind] count against [GitRepo.BASE],
 * null when there's no such branch or this is it. [from] is the branch it was created from, when git's reflog still knows.
 */
data class Worktree(
    val dir: java.io.File, val branch: String?, val isMain: Boolean, val head: String = "",
    val ahead: Int? = null, val behind: Int? = null, val from: String? = null,
)

data class Commit(val hash: String, val subject: String, val author: String, val epochSeconds: Long, val body: String = "") {
    val shortHash get() = hash.take(7)
}

data class RepoStatus(
    val branch: String?,          // null when HEAD is detached
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val changes: List<FileChange>,
    val head: String? = null,     // commit id; "(initial)" before the first commit
)

enum class LineType { Context, Added, Removed, Hunk }

data class DiffLine(val type: LineType, val text: String, val oldNo: Int?, val newNo: Int?)

/** Thrown with git's stderr so the UI can show it verbatim. */
class GitException(message: String) : Exception(message)

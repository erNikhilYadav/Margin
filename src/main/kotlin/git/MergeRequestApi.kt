package margin.git

import java.net.URLEncoder
import java.time.Instant

// Review models shared by both forges, plus GitLab REST v4 paths and parsers. Parsers are pure so fixtures can pin the JSON shapes.

data class Person(val name: String, val username: String)

data class DiffRefs(val base: String, val start: String, val head: String)

data class MrInfo(
    val iid: Int,
    val title: String,
    val description: String,
    val state: String, // opened, merged, closed, locked
    val draft: Boolean,
    val author: Person,
    val source: String,
    val target: String,
    val createdEpoch: Long,
    val updatedEpoch: Long,
    val webUrl: String,
    val pipeline: String?, // head pipeline status: success, failed, running, pending, canceled, …
    val mergeStatus: String, // detailed_merge_status: mergeable, conflict, ci_must_pass, …
    val canMerge: Boolean, // the signed-in user may merge
    val reviewers: List<Person>,
    val assignees: List<Person>,
    val diffRefs: DiffRefs?,
    val sha: String?,
)

data class Approvals(val approvedBy: List<Person>, val userHasApproved: Boolean, val userCanApprove: Boolean, val required: Int?, val left: Int?)

/** Where an inline (diff) note sits. A context line has both numbers; an added line only [newLine], a removed one only [oldLine]. */
data class NotePosition(val oldPath: String, val newPath: String, val oldLine: Int?, val newLine: Int?)

data class Note(
    val id: Long,
    val author: Person,
    val body: String,
    val createdEpoch: Long,
    val system: Boolean,
    val resolvable: Boolean,
    val resolved: Boolean,
    val position: NotePosition?,
)

data class Discussion(val id: String, val notes: List<Note>) {
    val resolvable get() = notes.any { it.resolvable }
    val resolved get() = resolvable && notes.filter { it.resolvable }.all { it.resolved }
    val position get() = notes.firstOrNull()?.position
    val system get() = notes.all { it.system }
}

data class MrCommit(val sha: String, val title: String, val author: String, val epoch: Long) {
    val shortSha get() = sha.take(8)
}

data class MrDiffFile(val change: FileChange, val lines: List<DiffLine>, val tooLarge: Boolean)

enum class MrStateFilter(val api: String) { Open("opened"), Merged("merged"), Closed("closed"), All("all") }
enum class MrScope { All, Mine, Assigned, Review }

data class MergeOptions(val squash: Boolean, val removeSource: Boolean, val whenPipelineSucceeds: Boolean)

internal fun mrPath(iid: Int) = "projects/:id/merge_requests/$iid"
internal fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
private val discussionId = Regex("[0-9a-f]{8,64}")

/** Path of the MR list query; [me] is needed only for the Review scope. */
internal fun listPath(state: MrStateFilter, scope: MrScope, search: String, me: String?): String {
    val q = mutableListOf("state=${state.api}", "order_by=updated_at", "per_page=50")
    q += when (scope) {
        MrScope.All -> "scope=all"
        MrScope.Mine -> "scope=created_by_me"
        MrScope.Assigned -> "scope=assigned_to_me"
        MrScope.Review -> "scope=all&reviewer_username=${enc(me ?: throw GitException("Couldn't tell who is signed in to GitLab"))}"
    }
    if (search.isNotBlank()) q += "search=${enc(search.trim())}"
    return "projects/:id/merge_requests?" + q.joinToString("&")
}

internal fun checkedId(id: String) = id.takeIf { discussionId.matches(it) } ?: throw GitException("Unexpected discussion id")

/** GitLab wants old_line for removed lines, new_line for added ones, and both for context. */
internal fun linePosition(refs: DiffRefs, file: FileChange, line: DiffLine): Map<String, Any?> = buildMap {
    put("position_type", "text")
    put("base_sha", refs.base); put("start_sha", refs.start); put("head_sha", refs.head)
    put("old_path", file.oldPath ?: file.path); put("new_path", file.path)
    if (line.type != LineType.Added) put("old_line", line.oldNo)
    if (line.type != LineType.Removed) put("new_line", line.newNo)
}

private val draftPrefix = Regex("""^\s*(\[draft]|\(draft\)|draft:|draft\s+-|wip:|\[wip])\s*""", RegexOption.IGNORE_CASE)

/** GitLab marks drafts by title prefix: strip any of its spellings, then add "Draft: " when [draft]. */
internal fun draftTitle(title: String, draft: Boolean): String {
    var t = title
    while (draftPrefix.containsMatchIn(t)) t = t.replaceFirst(draftPrefix, "")
    return if (draft) "Draft: $t" else t
}

// ---------- Parsers ----------

internal fun Map<*, *>.str(k: String) = this[k] as? String
internal fun Map<*, *>.int(k: String) = (this[k] as? Number)?.toInt()
internal fun epoch(s: Any?) = (s as? String)?.let { Instant.parse(it).epochSecond } ?: 0
private fun person(v: Any?): Person = (v as? Map<*, *>).let { Person(it?.str("name") ?: it?.str("username") ?: "", it?.str("username") ?: "") }
private fun people(v: Any?) = (v as? List<*>).orEmpty().map(::person)

internal fun parseMrInfo(json: String): MrInfo {
    val m = parseJson(json) as Map<*, *>
    val refs = m["diff_refs"] as? Map<*, *>
    return MrInfo(
        iid = m.int("iid")!!,
        title = m.str("title").orEmpty(),
        description = m.str("description").orEmpty(),
        state = m.str("state") ?: "opened",
        draft = m["draft"] == true || m["work_in_progress"] == true,
        author = person(m["author"]),
        source = m.str("source_branch").orEmpty(),
        target = m.str("target_branch").orEmpty(),
        createdEpoch = epoch(m["created_at"]),
        updatedEpoch = epoch(m["updated_at"]),
        webUrl = m.str("web_url").orEmpty(),
        pipeline = ((m["head_pipeline"] ?: m["pipeline"]) as? Map<*, *>)?.str("status"),
        mergeStatus = m.str("detailed_merge_status") ?: m.str("merge_status") ?: "unchecked",
        canMerge = (m["user"] as? Map<*, *>)?.get("can_merge") == true,
        reviewers = people(m["reviewers"]),
        assignees = people(m["assignees"]),
        diffRefs = refs?.let { r -> DiffRefs(r.str("base_sha") ?: return@let null, r.str("start_sha") ?: return@let null, r.str("head_sha") ?: return@let null) },
        sha = m.str("sha"),
    )
}

/** CE servers omit approvals_required/left; those stay null. */
internal fun parseApprovals(json: String): Approvals {
    val m = parseJson(json) as Map<*, *>
    return Approvals(
        approvedBy = (m["approved_by"] as? List<*>).orEmpty().map { person((it as? Map<*, *>)?.get("user")) },
        userHasApproved = m["user_has_approved"] == true,
        userCanApprove = m["user_can_approve"] == true,
        required = m.int("approvals_required"),
        left = m.int("approvals_left"),
    )
}

internal fun parseDiscussions(json: String): List<Discussion> = (parseJson(json) as? List<*>).orEmpty().map { d ->
    val m = d as Map<*, *>
    Discussion(m.str("id").orEmpty(), (m["notes"] as? List<*>).orEmpty().map { parseNote(it as Map<*, *>) })
}

private fun parseNote(n: Map<*, *>): Note {
    val p = (n["position"] as? Map<*, *>)?.takeIf { it.str("position_type") == null || it.str("position_type") == "text" }
    return Note(
        id = (n["id"] as Number).toLong(),
        author = person(n["author"]),
        body = n.str("body").orEmpty(),
        createdEpoch = epoch(n["created_at"]),
        system = n["system"] == true,
        resolvable = n["resolvable"] == true,
        resolved = n["resolved"] == true,
        position = p?.let { NotePosition(it.str("old_path").orEmpty(), it.str("new_path").orEmpty(), it.int("old_line"), it.int("new_line")) },
    )
}

internal fun parseMrCommits(json: String): List<MrCommit> = (parseJson(json) as? List<*>).orEmpty().map {
    val m = it as Map<*, *>
    MrCommit(m.str("id").orEmpty(), m.str("title").orEmpty(), m.str("author_name").orEmpty(), epoch(m["authored_date"] ?: m["created_at"]))
}

/** Each `diff` is one file's hunks without the `diff --git` header; parseDiff reads them as they are. */
internal fun parseMrDiffs(json: String): List<MrDiffFile> = (parseJson(json) as? List<*>).orEmpty().map {
    val m = it as Map<*, *>
    val path = m.str("new_path").orEmpty()
    val old = m.str("old_path").orEmpty()
    val kind = when {
        m["new_file"] == true -> ChangeKind.Added
        m["deleted_file"] == true -> ChangeKind.Deleted
        m["renamed_file"] == true -> ChangeKind.Renamed
        else -> ChangeKind.Modified
    }
    MrDiffFile(
        FileChange(path, kind, staged = false, oldPath = old.takeIf { kind == ChangeKind.Renamed }),
        parseDiff(m.str("diff").orEmpty()),
        tooLarge = m["too_large"] == true || m["collapsed"] == true,
    )
}

/** Whether [line] is where [p] points: new_line for added and context lines, old_line for removed ones. */
fun NotePosition.matches(file: FileChange, line: DiffLine): Boolean =
    newPath == file.path && when (line.type) {
        LineType.Removed -> newLine == null && oldLine != null && oldLine == line.oldNo
        LineType.Added, LineType.Context -> newLine != null && newLine == line.newNo
        LineType.Hunk -> false
    }

/** Human label for detailed_merge_status. */
fun mergeStatusLabel(status: String): String = when (status) {
    "mergeable", "can_be_merged" -> "Ready to merge"
    "conflict", "cannot_be_merged", "broken_status" -> "Has conflicts"
    "ci_must_pass" -> "Pipeline must succeed"
    "ci_still_running" -> "Pipeline still running"
    "discussions_not_resolved" -> "Unresolved threads"
    "draft_status" -> "Draft"
    "not_approved" -> "Needs approval"
    "requested_changes" -> "Changes requested"
    "need_rebase" -> "Needs rebase"
    "not_open" -> "Not open"
    "checking", "unchecked", "preparing", "approvals_syncing", "cannot_be_merged_recheck" -> "Checking mergeability…"
    "blocked_status" -> "Blocked by another MR"
    "external_status_checks" -> "External checks pending"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

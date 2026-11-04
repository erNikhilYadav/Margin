package margin.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GitHub pull requests over the `gh` CLI, run in the repo dir; auth lives in gh's keyring. REST where it covers the job,
 * GraphQL for search, review threads, drafts and auto-merge, which REST can't do. PR states and merge statuses are
 * mapped to GitLab's words so the UI has one vocabulary.
 */
class GitHub(override val dir: File, bin: File? = findBinary("gh")) : Forge {
    private val cli = Cli("gh", bin, dir)
    override val terms = GitHubTerms
    override val canUnapprove = false // GitHub has no "unapprove"; only maintainers can dismiss a review, on the site
    override val canDelete = false // pull requests can only be closed

    private data class Origin(val host: String, val owner: String, val name: String) { val repo get() = "repos/$owner/$name" }
    private var origin: Origin? = null

    private suspend fun origin(): Origin = origin ?: run {
        val url = originUrl(dir)
        val host = remoteHost(url) ?: throw GitException("This repository has no GitHub origin")
        val (owner, name) = remoteRepo(url) ?: throw GitException("Can't read owner/repo from origin $url")
        Origin(host, owner, name).also { origin = it }
    }

    override fun tokenUrl(host: String) = "https://$host/settings/tokens/new?description=Margin&scopes=repo,read:org,gist"

    override suspend fun available(): ForgeStatus {
        if (cli.bin == null) return ForgeStatus.NotInstalled
        val url = originUrl(dir)
        val host = remoteHost(url) ?: return ForgeStatus.NoOrigin
        return when {
            remoteRepo(url) == null -> ForgeStatus.Unsupported(host)
            !signedIn(host) -> ForgeStatus.NotAuthenticated(host)
            else -> ForgeStatus.Ready
        }
    }

    override suspend fun signedIn(host: String) = cli.signedIn(host)

    /** Token goes in on stdin, never argv (visible to `ps`); gh keeps it in its keyring. */
    override suspend fun signIn(host: String, token: String) { cli.run("auth", "login", "--hostname", host, "--with-token", input = token.trim()) }

    override suspend fun signOut(host: String) { cli.run("auth", "logout", "--hostname", host) }

    /**
     * One call through `gh api`, which owns auth. A [body] goes in as JSON on stdin (`--input -`), so user text never
     * becomes a gh flag. `--slurp` wraps every page in one outer array; [flatPages] undoes that.
     */
    internal suspend fun api(method: String, path: String, body: Map<String, Any?>? = null, paginate: Boolean = false): String = cli.run(
        "api", "--hostname", origin().host, path, *(if (method == "GET") emptyArray() else arrayOf("-X", method)),
        *(if (paginate) arrayOf("--paginate", "--slurp") else emptyArray()), *(if (body != null) arrayOf("--input", "-") else emptyArray()),
        input = body?.let(::toJson),
    )

    private suspend fun graphql(query: String, vars: Map<String, Any?> = emptyMap()): String = api("POST", "graphql", mapOf("query" to query, "variables" to vars))

    private suspend fun pr(iid: Int) = origin().let { mapOf("o" to it.owner, "r" to it.name, "n" to iid) }

    override suspend fun defaultBranch(): String =
        (parseJson(api("GET", origin().repo)) as Map<*, *>).str("default_branch") ?: throw GitException("GitHub did not report a default branch")

    override suspend fun forBranch(branch: String): MergeRequest? =
        origin().let { parseGhPulls(api("GET", "${it.repo}/pulls?state=open&per_page=1&head=${enc("${it.owner}:$branch")}")).firstOrNull() }

    override suspend fun create(source: String, target: String, title: String, description: String, draft: Boolean): String {
        withContext(Dispatchers.IO) { runGit(dir, listOf("push", "-u", "origin", source)) }
        val body = mapOf("title" to title, "head" to source, "base" to target, "body" to description, "draft" to draft)
        return (parseJson(api("POST", "${origin().repo}/pulls", body)) as Map<*, *>).str("html_url")
            ?: throw GitException("GitHub did not return a pull request URL")
    }

    /** Search covers every filter and scope in one query, including Merged, which the REST list can't filter on. */
    override suspend fun listMergeRequests(state: MrStateFilter, scope: MrScope, search: String, me: String?): List<MergeRequest> =
        origin().let { parseGhSearch(graphql(SEARCH, mapOf("q" to searchQuery(it.owner, it.name, state, scope, search)))) }

    override suspend fun currentUsername(): String = (parseJson(api("GET", "user")) as Map<*, *>).str("login") ?: throw GitException("Couldn't tell who is signed in to GitHub")

    override suspend fun mergeRequest(iid: Int) = parseGhPr(graphql(DETAIL, pr(iid)))
    override suspend fun approvals(iid: Int) = parseGhApprovals(graphql(APPROVALS, pr(iid)))
    override suspend fun discussions(iid: Int) = parseGhDiscussions(graphql(DISCUSSIONS, pr(iid)))
    override suspend fun commits(iid: Int) = parseGhCommits(api("GET", "${origin().repo}/pulls/$iid/commits?per_page=100", paginate = true))
    override suspend fun diffs(iid: Int) = parseGhFiles(api("GET", "${origin().repo}/pulls/$iid/files?per_page=100", paginate = true))

    // ---------- Writes ----------

    /** [sha] pins the review to the commit the user looked at. */
    override suspend fun approve(iid: Int, sha: String?) {
        api("POST", "${origin().repo}/pulls/$iid/reviews", buildMap { put("event", "APPROVE"); sha?.let { put("commit_id", it) } })
    }

    override suspend fun unapprove(iid: Int) = throw GitException("GitHub can't withdraw an approval. A maintainer can dismiss it on GitHub.")

    /** [sha] makes GitHub refuse if the branch moved. Auto-merge can't delete the branch per request; the repo setting decides. */
    override suspend fun merge(iid: Int, o: MergeOptions, sha: String?) {
        val pull = parseJson(api("GET", "${origin().repo}/pulls/$iid")) as Map<*, *>
        if (o.whenPipelineSucceeds) {
            graphql(AUTO_MERGE, mapOf("id" to pull.str("node_id"), "method" to if (o.squash) "SQUASH" else "MERGE"))
            return
        }
        api("PUT", "${origin().repo}/pulls/$iid/merge", buildMap { put("merge_method", if (o.squash) "squash" else "merge"); sha?.let { put("sha", it) } })
        val head = pull["head"] as? Map<*, *>
        val ref = head?.str("ref")
        val sameRepo = (head?.get("repo") as? Map<*, *>)?.str("full_name").equals("${origin().owner}/${origin().name}", ignoreCase = true)
        if (o.removeSource && ref != null && sameRepo) { // a fork's branch isn't ours to delete
            runCatching { api("DELETE", "${origin().repo}/git/refs/heads/${ref.split('/').joinToString("/", transform = ::enc)}") }
                .onFailure { throw GitException("Merged, but couldn't delete $ref: ${it.message}") }
        }
    }

    override suspend fun close(iid: Int) { api("PATCH", "${origin().repo}/pulls/$iid", mapOf("state" to "closed")) }
    override suspend fun reopen(iid: Int) { api("PATCH", "${origin().repo}/pulls/$iid", mapOf("state" to "open")) }

    /** GitHub keeps draft as a flag, not a title prefix; [title] is left alone. */
    override suspend fun setDraft(iid: Int, title: String, draft: Boolean) {
        val id = (parseJson(api("GET", "${origin().repo}/pulls/$iid")) as Map<*, *>).str("node_id")
        graphql(if (draft) TO_DRAFT else TO_READY, mapOf("id" to id))
    }

    override suspend fun deleteMergeRequest(iid: Int) = throw GitException("GitHub can't delete pull requests. Close it instead.")

    override suspend fun comment(iid: Int, body: String) { api("POST", "${origin().repo}/issues/$iid/comments", mapOf("body" to body)) }

    /** [discussion] is a review thread's node id; it travels as a GraphQL variable, never in a path. */
    override suspend fun reply(iid: Int, discussion: String, body: String) { graphql(REPLY, mapOf("id" to discussion, "body" to body)) }

    override suspend fun resolve(iid: Int, discussion: String, resolved: Boolean) { graphql(if (resolved) RESOLVE else UNRESOLVE, mapOf("id" to discussion)) }

    /** A removed line is addressed on the LEFT side by its old number; added and context lines on the RIGHT by the new one. */
    override suspend fun commentOnLine(iid: Int, refs: DiffRefs, file: FileChange, line: DiffLine, body: String) {
        val removed = line.type == LineType.Removed
        api("POST", "${origin().repo}/pulls/$iid/comments", mapOf(
            "body" to body, "commit_id" to refs.head, "path" to file.path,
            "line" to if (removed) line.oldNo else line.newNo, "side" to if (removed) "LEFT" else "RIGHT",
        ))
    }
}

private const val ACTOR = "login ... on User { name }"

private const val SEARCH = $$"""query($q: String!) { search(query: $q, type: ISSUE, first: 50) { nodes { ... on PullRequest {
  number title headRefName baseRefName isDraft url updatedAt state author { login } } } } }"""

private const val DETAIL = $$"""query($o: String!, $r: String!, $n: Int!) { repository(owner: $o, name: $r) { viewerPermission pullRequest(number: $n) {
  number title body state isDraft url createdAt updatedAt headRefName baseRefName headRefOid baseRefOid mergeStateStatus reviewDecision
  author { $$ACTOR } assignees(first: 20) { nodes { login name } }
  reviewRequests(first: 20) { nodes { requestedReviewer { ... on User { login name } ... on Team { name slug } } } }
  commits(last: 1) { nodes { commit { statusCheckRollup { state } } } } } } }"""

private const val APPROVALS = $$"""query($o: String!, $r: String!, $n: Int!) { viewer { login } repository(owner: $o, name: $r) { pullRequest(number: $n) {
  state author { login } latestOpinionatedReviews(first: 100) { nodes { state author { $$ACTOR } } } } } }"""

/** shortcut: first 100 comments, reviews and threads, unpaged; add endCursor paging when PRs get that long. */
private const val DISCUSSIONS = $$"""query($o: String!, $r: String!, $n: Int!) { repository(owner: $o, name: $r) { pullRequest(number: $n) {
  comments(first: 100) { nodes { databaseId body createdAt author { $$ACTOR } } }
  reviews(first: 100) { nodes { databaseId body state submittedAt author { $$ACTOR } } }
  reviewThreads(first: 100) { nodes { id isResolved path line diffSide
    comments(first: 100) { nodes { databaseId body createdAt author { $$ACTOR } } } } } } } }"""

private const val AUTO_MERGE = $$"""mutation($id: ID!, $method: PullRequestMergeMethod!) {
  enablePullRequestAutoMerge(input: {pullRequestId: $id, mergeMethod: $method}) { clientMutationId } }"""
private const val TO_DRAFT = $$"""mutation($id: ID!) { convertPullRequestToDraft(input: {pullRequestId: $id}) { clientMutationId } }"""
private const val TO_READY = $$"""mutation($id: ID!) { markPullRequestReadyForReview(input: {pullRequestId: $id}) { clientMutationId } }"""
private const val REPLY = $$"""mutation($id: ID!, $body: String!) {
  addPullRequestReviewThreadReply(input: {pullRequestReviewThreadId: $id, body: $body}) { clientMutationId } }"""
private const val RESOLVE = $$"""mutation($id: ID!) { resolveReviewThread(input: {threadId: $id}) { clientMutationId } }"""
private const val UNRESOLVE = $$"""mutation($id: ID!) { unresolveReviewThread(input: {threadId: $id}) { clientMutationId } }"""

/**
 * GitHub search for one repo's PRs. Each search word is quoted so text like `repo:other/x` stays a word,
 * never a qualifier that pulls in another repo's PRs.
 */
internal fun searchQuery(owner: String, name: String, state: MrStateFilter, scope: MrScope, search: String): String = (listOfNotNull(
    "repo:$owner/$name", "is:pr",
    when (state) { MrStateFilter.Open -> "is:open"; MrStateFilter.Merged -> "is:merged"; MrStateFilter.Closed -> "is:closed is:unmerged"; MrStateFilter.All -> null },
    when (scope) { MrScope.All -> null; MrScope.Mine -> "author:@me"; MrScope.Assigned -> "assignee:@me"; MrScope.Review -> "review-requested:@me" },
    "sort:updated-desc",
) + search.split(Regex("\\s+")).map { it.replace("\"", "") }.filter { it.isNotEmpty() }.map { "\"$it\"" }).joinToString(" ")

// ---------- Parsers ----------

private fun ghState(s: String?) = when (s?.uppercase()) { "MERGED" -> "merged"; "CLOSED" -> "closed"; else -> "opened" }

private fun actor(v: Any?): Person = (v as? Map<*, *>).let { m ->
    val login = m?.str("login") ?: m?.str("slug") ?: "ghost" // deleted accounts come back as null
    Person(m?.str("name")?.takeIf { it.isNotBlank() } ?: login, login)
}

private fun nodes(v: Any?) = ((v as? Map<*, *>)?.get("nodes") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

/** `data` of a GraphQL reply; gh already exits non-zero when `errors` is set. */
private fun data(json: String) = (parseJson(json) as? Map<*, *>)?.get("data") as? Map<*, *> ?: throw GitException("GitHub returned no data")

private fun pullRequest(json: String) = ((data(json)["repository"] as? Map<*, *>)?.get("pullRequest") as? Map<*, *>)
    ?: throw GitException("GitHub has no such pull request")

/** `--paginate --slurp` output: an array of page arrays. A plain array passes through. */
internal fun flatPages(json: String): List<Map<*, *>> =
    (parseJson(json) as? List<*>).orEmpty().flatMap { it as? List<*> ?: listOf(it) }.filterIsInstance<Map<*, *>>()

/** REST `pulls` list objects. */
internal fun parseGhPulls(json: String): List<MergeRequest> = flatPages(json).map { m ->
    MergeRequest(
        iid = m.int("number")!!, title = m.str("title").orEmpty(),
        source = (m["head"] as? Map<*, *>)?.str("ref").orEmpty(), target = (m["base"] as? Map<*, *>)?.str("ref").orEmpty(),
        author = actor(m["user"]).name, draft = m["draft"] == true, webUrl = m.str("html_url").orEmpty(),
        updatedEpoch = epoch(m["updated_at"]), state = if (m["merged_at"] != null) "merged" else ghState(m.str("state")),
    )
}

internal fun parseGhSearch(json: String): List<MergeRequest> =
    nodes(data(json)["search"]).filter { it["number"] != null }.map { m ->
        MergeRequest(
            iid = m.int("number")!!, title = m.str("title").orEmpty(), source = m.str("headRefName").orEmpty(), target = m.str("baseRefName").orEmpty(),
            author = actor(m["author"]).name, draft = m["isDraft"] == true, webUrl = m.str("url").orEmpty(),
            updatedEpoch = epoch(m["updatedAt"]), state = ghState(m.str("state")),
        )
    }

/** Check rollup in GitLab pipeline words, so the pipeline dot and "merge when it succeeds" work unchanged. */
private fun ghPipeline(rollup: String?) = when (rollup) {
    null -> null
    "SUCCESS" -> "success"
    "FAILURE", "ERROR" -> "failed"
    else -> "pending" // PENDING, EXPECTED
}

/** mergeStateStatus in GitLab detailed_merge_status words; BLOCKED is split by its likely cause. */
internal fun ghMergeStatus(state: String?, reviewDecision: String?, pipeline: String?): String = when (state) {
    "CLEAN", "HAS_HOOKS", "UNSTABLE" -> "mergeable" // UNSTABLE: checks failing but not required
    "DIRTY" -> "conflict"
    "BEHIND" -> "need_rebase"
    "DRAFT" -> "draft_status"
    "BLOCKED" -> when {
        reviewDecision == "CHANGES_REQUESTED" -> "requested_changes"
        reviewDecision == "REVIEW_REQUIRED" -> "not_approved"
        pipeline == "pending" -> "ci_still_running"
        pipeline == "failed" -> "ci_must_pass"
        else -> "blocked"
    }
    else -> "checking" // UNKNOWN: GitHub computes it lazily
}

internal fun parseGhPr(json: String): MrInfo {
    val repo = data(json)["repository"] as? Map<*, *>
    val p = pullRequest(json)
    val pipeline = ghPipeline(((nodes(p["commits"]).firstOrNull()?.get("commit") as? Map<*, *>)?.get("statusCheckRollup") as? Map<*, *>)?.str("state"))
    val head = p.str("headRefOid")
    val base = p.str("baseRefOid")
    return MrInfo(
        iid = p.int("number")!!, title = p.str("title").orEmpty(), description = p.str("body").orEmpty(),
        state = ghState(p.str("state")), draft = p["isDraft"] == true, author = actor(p["author"]),
        source = p.str("headRefName").orEmpty(), target = p.str("baseRefName").orEmpty(),
        createdEpoch = epoch(p["createdAt"]), updatedEpoch = epoch(p["updatedAt"]), webUrl = p.str("url").orEmpty(),
        pipeline = pipeline, mergeStatus = ghMergeStatus(p.str("mergeStateStatus"), p.str("reviewDecision"), pipeline),
        canMerge = repo?.str("viewerPermission") in setOf("ADMIN", "MAINTAIN", "WRITE"),
        reviewers = nodes(p["reviewRequests"]).map { actor(it["requestedReviewer"]) },
        assignees = nodes(p["assignees"]).map(::actor),
        diffRefs = if (head != null && base != null) DiffRefs(base, base, head) else null,
        sha = head,
    )
}

/** GitHub can't report required approvals to non-admins, so [Approvals.required] stays null, like GitLab CE. */
internal fun parseGhApprovals(json: String): Approvals {
    val me = (data(json)["viewer"] as? Map<*, *>)?.str("login")
    val p = pullRequest(json)
    val approved = nodes(p["latestOpinionatedReviews"]).filter { it.str("state") == "APPROVED" }.map { actor(it["author"]) }
    val mine = approved.any { it.username == me }
    val author = (p["author"] as? Map<*, *>)?.str("login")
    return Approvals(approved, mine, userCanApprove = !mine && me != author && p.str("state") == "OPEN", required = null, left = null)
}

private fun note(m: Map<*, *>, at: String, body: String = m.str("body").orEmpty(), system: Boolean = false, thread: Map<*, *>? = null) = Note(
    id = (m["databaseId"] as? Number)?.toLong() ?: 0, author = actor(m["author"]), body = body, createdEpoch = epoch(m[at]),
    system = system, resolvable = thread != null, resolved = thread?.get("isResolved") == true,
    position = thread?.let { t ->
        val path = t.str("path").orEmpty()
        val line = t.int("line") // null once the thread is outdated: it then shows in Overview only
        if (t.str("diffSide") == "LEFT") NotePosition(path, path, line, null) else NotePosition(path, path, null, line)
    },
)

/** Conversation comments, review summaries and inline review threads, as one time-ordered list like GitLab's discussions. */
internal fun parseGhDiscussions(json: String): List<Discussion> {
    val p = pullRequest(json)
    val comments = nodes(p["comments"]).map { Discussion("c${it["databaseId"]}", listOf(note(it, "createdAt"))) }
    val reviews = nodes(p["reviews"]).mapNotNull { r ->
        val body = r.str("body").orEmpty()
        val verdict = when (r.str("state")) { "APPROVED" -> "approved these changes"; "CHANGES_REQUESTED" -> "requested changes"; else -> null }
        when {
            body.isNotBlank() -> Discussion("r${r["databaseId"]}", listOf(note(r, "submittedAt")))
            verdict != null -> Discussion("r${r["databaseId"]}", listOf(note(r, "submittedAt", verdict, system = true)))
            else -> null // a bare COMMENTED review only holds inline comments, which arrive as threads
        }
    }
    val threads = nodes(p["reviewThreads"]).map { t -> Discussion(t.str("id").orEmpty(), nodes(t["comments"]).map { note(it, "createdAt", thread = t) }) }
    return (comments + reviews + threads).filter { it.notes.isNotEmpty() }.sortedBy { it.notes.first().createdEpoch }
}

internal fun parseGhCommits(json: String): List<MrCommit> = flatPages(json).map { m ->
    val c = m["commit"] as? Map<*, *>
    val author = c?.get("author") as? Map<*, *>
    MrCommit(m.str("sha").orEmpty(), c?.str("message").orEmpty().lineSequence().first(), author?.str("name").orEmpty(), epoch(author?.get("date")))
}

/** `patch` is one file's hunks without the `diff --git` header, the same shape GitLab sends; GitHub omits it for binary or huge files. */
internal fun parseGhFiles(json: String): List<MrDiffFile> = flatPages(json).map { m ->
    val kind = when (m.str("status")) { "added" -> ChangeKind.Added; "removed" -> ChangeKind.Deleted; "renamed" -> ChangeKind.Renamed; else -> ChangeKind.Modified }
    val patch = m.str("patch")
    MrDiffFile(
        FileChange(m.str("filename").orEmpty(), kind, staged = false, oldPath = m.str("previous_filename").takeIf { kind == ChangeKind.Renamed }),
        parseDiff(patch.orEmpty()),
        tooLarge = patch == null && ((m["changes"] as? Number)?.toInt() ?: 0) > 0,
    )
}

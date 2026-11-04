package margin.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** GitLab merge requests over the `glab` CLI, run in the repo dir. Auth lives in glab's keyring, never in the app. */
class GitLab(override val dir: File, bin: File? = findBinary("glab")) : Forge {
    private val cli = Cli("glab", bin, dir)
    override val terms = GitLabTerms

    override fun tokenUrl(host: String) = "https://$host/-/user_settings/personal_access_tokens?name=Margin&scopes=api,write_repository"

    override suspend fun available(): ForgeStatus {
        if (cli.bin == null) return ForgeStatus.NotInstalled
        val host = originHost(dir) ?: return ForgeStatus.NoOrigin
        return when {
            host in nonGitLabHosts -> ForgeStatus.Unsupported(host)
            !signedIn(host) -> ForgeStatus.NotAuthenticated(host)
            else -> ForgeStatus.Ready
        }
    }

    override suspend fun signedIn(host: String) = cli.signedIn(host)

    /** Removes glab's stored token for [host]. */
    override suspend fun signOut(host: String) { cli.run("auth", "logout", "--hostname", host) }

    /** Token goes in on stdin, never argv (visible to `ps`); glab keeps it in its keyring. */
    override suspend fun signIn(host: String, token: String) { cli.run("auth", "login", "--hostname", host, "--stdin", input = token.trim()) }

    override suspend fun forBranch(branch: String): MergeRequest? =
        parseMergeRequests(cli.run("mr", "list", "-F", "json", "--source-branch", branch)).firstOrNull()

    override suspend fun defaultBranch(): String =
        (parseJson(cli.run("repo", "view", "-F", "json")) as Map<*, *>)["default_branch"] as? String
            ?: throw GitException("GitLab did not report a default branch")

    override suspend fun create(source: String, target: String, title: String, description: String, draft: Boolean): String {
        withContext(Dispatchers.IO) { runGit(dir, listOf("push", "-u", "origin", source)) }
        val out = cli.run(*createArgs(source, target, title, description, draft).toTypedArray())
        return mrUrl.find(out)?.value ?: throw GitException(out.trim().ifEmpty { "glab did not return a merge request URL" })
    }

    /**
     * One GitLab REST call through `glab api`, which owns auth. `:id` in [path] is the current repo's project.
     * A [body] goes in as JSON on stdin (`--input -`), so user text never becomes a glab flag.
     */
    internal suspend fun api(method: String, path: String, body: Map<String, Any?>? = null, paginate: Boolean = false): String = cli.run(
        "api", path, *(if (method == "GET") emptyArray() else arrayOf("-X", method)),
        *(if (paginate) arrayOf("--paginate") else emptyArray()), *(if (body != null) arrayOf("--input", "-") else emptyArray()),
        input = body?.let(::toJson),
    )

    override suspend fun listMergeRequests(state: MrStateFilter, scope: MrScope, search: String, me: String?): List<MergeRequest> =
        parseMergeRequests(api("GET", listPath(state, scope, search, me)))

    override suspend fun currentUsername(): String = (parseJson(api("GET", "user")) as Map<*, *>)["username"] as String

    override suspend fun mergeRequest(iid: Int) = parseMrInfo(api("GET", mrPath(iid)))
    override suspend fun approvals(iid: Int) = parseApprovals(api("GET", "${mrPath(iid)}/approvals"))
    override suspend fun discussions(iid: Int) = parseDiscussions(api("GET", "${mrPath(iid)}/discussions?per_page=100", paginate = true))
    override suspend fun commits(iid: Int) = parseMrCommits(api("GET", "${mrPath(iid)}/commits?per_page=100", paginate = true))
    override suspend fun diffs(iid: Int) = parseMrDiffs(api("GET", "${mrPath(iid)}/diffs?per_page=100", paginate = true))

    // ---------- Writes: each is one REST call, so tests can pin the exact request ----------

    /** [sha] guards against approving commits pushed after the user looked. */
    override suspend fun approve(iid: Int, sha: String?) { api("POST", "${mrPath(iid)}/approve", sha?.let { mapOf("sha" to it) }) }
    override suspend fun unapprove(iid: Int) { api("POST", "${mrPath(iid)}/unapprove") }

    /** [sha] makes GitLab refuse if the branch moved since the user looked. auto_merge is the newer name of merge_when_pipeline_succeeds. */
    override suspend fun merge(iid: Int, o: MergeOptions, sha: String?) {
        api("PUT", "${mrPath(iid)}/merge", buildMap {
            put("squash", o.squash)
            put("should_remove_source_branch", o.removeSource)
            if (o.whenPipelineSucceeds) { put("merge_when_pipeline_succeeds", true); put("auto_merge", true) }
            sha?.let { put("sha", it) }
        })
    }

    override suspend fun close(iid: Int) { api("PUT", mrPath(iid), mapOf("state_event" to "close")) }
    override suspend fun reopen(iid: Int) { api("PUT", mrPath(iid), mapOf("state_event" to "reopen")) }
    override suspend fun setDraft(iid: Int, title: String, draft: Boolean) { api("PUT", mrPath(iid), mapOf("title" to draftTitle(title, draft))) }
    override suspend fun deleteMergeRequest(iid: Int) { api("DELETE", mrPath(iid)) }
    override suspend fun comment(iid: Int, body: String) { api("POST", "${mrPath(iid)}/notes", mapOf("body" to body)) }

    override suspend fun reply(iid: Int, discussion: String, body: String) {
        api("POST", "${mrPath(iid)}/discussions/${checkedId(discussion)}/notes", mapOf("body" to body))
    }

    override suspend fun resolve(iid: Int, discussion: String, resolved: Boolean) {
        api("PUT", "${mrPath(iid)}/discussions/${checkedId(discussion)}", mapOf("resolved" to resolved))
    }

    /** New inline thread on one diff line; the position must use the MR's current diff_refs. */
    override suspend fun commentOnLine(iid: Int, refs: DiffRefs, file: FileChange, line: DiffLine, body: String) {
        api("POST", "${mrPath(iid)}/discussions", mapOf("body" to body, "position" to linePosition(refs, file, line)))
    }

    private companion object {
        val mrUrl = Regex("""https?://\S+/merge_requests/\d+""")
    }
}

internal fun createArgs(source: String, target: String, title: String, description: String, draft: Boolean): List<String> =
    listOf(
        "mr", "create", "--source-branch", source, "--target-branch", target,
        "--title", title, "--description", description, "--yes",
    ) + if (draft) listOf("--draft") else emptyList()

internal val nonGitLabHosts = setOf("github.com", "bitbucket.org", "ssh.dev.azure.com", "dev.azure.com")

/** Input: `glab mr list -F json`, an array of GitLab API merge request objects. */
internal fun parseMergeRequests(json: String): List<MergeRequest> = (parseJson(json) as? List<*>).orEmpty().map {
    val m = it as Map<*, *>
    val author = m["author"] as? Map<*, *>
    MergeRequest(
        iid = (m["iid"] as Number).toInt(),
        title = m["title"] as String,
        source = m["source_branch"] as String,
        target = m["target_branch"] as String,
        author = (author?.get("name") ?: author?.get("username")) as? String ?: "",
        draft = m["draft"] == true || m["work_in_progress"] == true,
        webUrl = m["web_url"] as String,
        updatedEpoch = (m["updated_at"] as? String)?.let { s -> Instant.parse(s).epochSecond } ?: 0,
        state = m["state"] as? String ?: "opened",
    )
}

/** JSON for request bodies: Map, List, String, Number, Boolean, null. Strings are fully escaped. */
internal fun toJson(v: Any?): String = when (v) {
    null -> "null"
    is String -> buildString {
        append('"')
        for (ch in v) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' || ch == '\u2028' || ch == '\u2029' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }
    is Boolean, is Int, is Long -> v.toString()
    is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> toJson(k.toString()) + ":" + toJson(x) }
    is List<*> -> v.joinToString(",", "[", "]") { toJson(it) }
    else -> throw IllegalArgumentException("toJson: unsupported ${v::class}")
}

/** Minimal JSON reader: objects become Map, arrays List, numbers Long or Double. Trusts its input to be valid JSON. */
internal fun parseJson(s: String): Any? {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun expect(ch: Char) { ws(); if (s.getOrNull(i) != ch) throw GitException("Bad JSON at $i: expected '$ch'"); i++ }
    fun string(): String {
        expect('"')
        val sb = StringBuilder()
        while (s[i] != '"') {
            val ch = s[i++]
            if (ch != '\\') { sb.append(ch); continue }
            when (val e = s[i++]) {
                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                else -> sb.append(e) // \" \\ \/
            }
        }
        i++
        return sb.toString()
    }
    fun value(): Any? {
        ws()
        return when (s.getOrNull(i)) {
            '{' -> {
                i++
                val map = LinkedHashMap<String, Any?>()
                ws()
                if (s[i] == '}') i++ else do {
                    val k = string(); expect(':'); map[k] = value(); ws()
                } while (s[i++] == ',')
                map
            }
            '[' -> {
                i++
                val list = ArrayList<Any?>()
                ws()
                if (s[i] == ']') i++ else do { list += value(); ws() } while (s[i++] == ',')
                list
            }
            '"' -> string()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            null -> throw GitException("Bad JSON: unexpected end")
            else -> {
                val start = i
                while (i < s.length && s[i] in "+-.eE0123456789") i++
                val num = s.substring(start, i)
                num.toLongOrNull() ?: num.toDoubleOrNull() ?: throw GitException("Bad JSON at $start")
            }
        }
    }
    return value()
}

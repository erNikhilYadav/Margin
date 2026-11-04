package margin.git

import kotlinx.coroutines.runBlocking
import margin.ui.MrState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures follow real `gh api` replies (gh 2.103, github.com), trimmed. Nothing here talks to GitHub. */
class GitHubTest {

    private fun repo(origin: String?): File = createTempDirectory("gh").toFile().also { dir ->
        runGit(dir, listOf("init", "-q"))
        origin?.let { runGit(dir, listOf("remote", "add", "origin", it)) }
    }

    /** A fake gh that appends `argv | stdin` per call to [log] and answers from [replies] (argv substring to stdout). */
    private fun fakeGh(dir: File, log: File, replies: List<Pair<String, String>>, fallback: String = "echo '{}'"): File {
        val cases = replies.joinToString("\n") { (match, json) ->
            File(dir, "r${json.hashCode()}.json").writeText(json)
            "  *\"$match\"*) cat '${File(dir, "r${json.hashCode()}.json").path}' ;;"
        }
        return File(dir, "gh").apply {
            writeText("#!/bin/sh\nargs=\"\$*\"\nprintf '%s | ' \"\$args\" >> '${log.path}'; cat >> '${log.path}'; echo >> '${log.path}'\ncase \"\$args\" in\n$cases\n  *) $fallback ;;\nesac\n")
            setExecutable(true)
        }
    }

    @Test fun routesOriginToTheRightForge() = runBlocking<Unit> {
        assertIs<GitHub>(forgeFor(repo("git@github.com:acme/app.git")) { emptyList() })
        assertIs<GitLab>(forgeFor(repo("https://gitlab.example.com/a/b.git")) { emptyList() })
        assertIs<GitHub>(forgeFor(repo("https://ghe.corp.io/acme/app.git")) { listOf("ghe.corp.io") }) // Enterprise: gh knows the host
        assertIs<GitLab>(forgeFor(repo(null)) { error("hosts.yml read without an origin") })
    }

    @Test fun readsOwnerAndRepoFromEveryUrlForm() {
        assertEquals("acme" to "app", remoteRepo("git@github.com:acme/app.git"))
        assertEquals("acme" to "app.kt", remoteRepo("https://github.com/acme/app.kt"))
        assertEquals("acme" to "app", remoteRepo("ssh://git@github.com:22/acme/app.git/"))
        assertNull(remoteRepo("https://github.com/acme/.."))
        assertNull(remoteRepo("https://github.com/a%2F..%2Fx/b"))
        assertNull(remoteRepo("https://github.com/no-repo-path")) // the host is never the owner
    }

    @Test fun readsHostsFromGhConfig() {
        val yaml = "github.com:\n    users:\n        asha:\n    user: asha\n# note\n\"GHE.corp.io\":\n    git_protocol: ssh\n"
        assertEquals(listOf("github.com", "ghe.corp.io"), topLevelKeys(yaml))
        val dir = createTempDirectory("ghcfg").toFile()
        File(dir, "hosts.yml").writeText(yaml)
        assertEquals(listOf("github.com", "ghe.corp.io"), ghConfigHosts(mapOf("GH_CONFIG_DIR" to dir.path), "/nonexistent"))
        assertEquals(emptyList(), ghConfigHosts(emptyMap(), "/nonexistent"))
    }

    @Test fun searchQueryQuotesUserWords() {
        assertEquals("repo:acme/app is:pr is:open sort:updated-desc", searchQuery("acme", "app", MrStateFilter.Open, MrScope.All, " "))
        assertEquals(
            "repo:acme/app is:pr is:closed is:unmerged review-requested:@me sort:updated-desc \"seat\" \"repo:evil/x\"",
            searchQuery("acme", "app", MrStateFilter.Closed, MrScope.Review, "seat  repo:evil/x\""),
        )
        assertEquals("repo:acme/app is:pr author:@me sort:updated-desc", searchQuery("acme", "app", MrStateFilter.All, MrScope.Mine, ""))
    }

    @Test fun parsesSearchAndRestLists() {
        val list = parseGhSearch(SEARCH)
        assertEquals(listOf(42, 7), list.map { it.iid })
        assertEquals(MergeRequest(42, "Cache seat layouts", "feat/seat-cache", "main", "asha-k", false, "https://github.com/acme/app/pull/42", 1793727616, "opened"), list[0])
        assertEquals("ghost", list[1].author) // deleted account
        assertEquals("merged", list[1].state)
        assertTrue(list[1].draft)
        val pulls = parseGhPulls(PULLS)
        assertEquals(listOf("opened", "merged"), pulls.map { it.state })
        assertEquals("feat/seat-cache" to "main", pulls[0].source to pulls[0].target)
    }

    @Test fun parsesDetailInGitLabWords() {
        val m = parseGhPr(DETAIL)
        assertEquals("pending", m.pipeline)
        assertEquals("not_approved", m.mergeStatus)
        assertTrue(m.canMerge)
        assertEquals(Person("Asha K", "asha-k"), m.author)
        assertEquals(listOf(Person("Meera", "meera"), Person("Mobile", "mobile")), m.reviewers)
        assertEquals(listOf(Person("ravi", "ravi")), m.assignees)
        assertEquals(DiffRefs("b1", "b1", "h1"), m.diffRefs)
        assertEquals("h1", m.sha)
        assertEquals("opened", m.state)
        assertEquals("ci_still_running", ghMergeStatus("BLOCKED", "APPROVED", "pending"))
        assertEquals("mergeable", ghMergeStatus("UNSTABLE", null, "failed"))
        assertEquals("conflict", ghMergeStatus("DIRTY", null, null))
        assertEquals("checking", ghMergeStatus("UNKNOWN", null, null))
    }

    @Test fun parsesApprovals() {
        val a = parseGhApprovals(APPROVALS)
        assertEquals(listOf(Person("Ravi", "ravi")), a.approvedBy)
        assertFalse(a.userHasApproved)
        assertTrue(a.userCanApprove)
        assertNull(a.required)
        val own = parseGhApprovals(APPROVALS.replace("\"viewer\":{\"login\":\"meera\"}", "\"viewer\":{\"login\":\"asha-k\"}"))
        assertFalse(own.userCanApprove) // GitHub won't let authors approve their own PR
    }

    @Test fun parsesCommentsReviewsAndThreadsInTimeOrder() {
        val d = parseGhDiscussions(DISCUSSIONS)
        assertEquals(listOf("PRRT_b", "PRRT_a", "r23", "c11", "r21"), d.map { it.id }) // the bare COMMENTED review is dropped
        val (removed, open) = d
        assertTrue(removed.resolved)
        assertEquals(NotePosition("app/SeatRepository.kt", "app/SeatRepository.kt", 20, null), removed.position)
        assertTrue(removed.position!!.matches(FileChange("app/SeatRepository.kt", ChangeKind.Modified, false), DiffLine(LineType.Removed, "x", 20, null)))
        assertTrue(open.resolvable && !open.resolved)
        assertEquals(listOf("Why 32?", "Typical trip count"), open.notes.map { it.body })
        assertEquals(NotePosition("app/SeatRepository.kt", "app/SeatRepository.kt", null, 21), open.position)
        assertFalse(d[2].system)
        assertEquals("Please split this", d[2].notes.single().body)
        assertTrue(d[4].system)
        assertEquals("approved these changes", d[4].notes.single().body)
        assertFalse(d[3].resolvable)
    }

    @Test fun parsesPagedFilesAndCommits() {
        val files = parseGhFiles(FILES)
        assertEquals(listOf("app/SeatCache.kt", "app/New.kt", "assets/logo.png"), files.map { it.change.path })
        assertEquals(ChangeKind.Added, files[0].change.kind)
        assertEquals(2, files[0].lines.count { it.type == LineType.Added })
        assertEquals("app/Old.kt", files[1].change.oldPath)
        assertFalse(files[1].tooLarge) // a pure rename has no patch and nothing to show
        assertTrue(files[2].tooLarge) // binary: changed, but no patch
        assertEquals(listOf(MrCommit("abc123456789", "Cache seat layouts", "Asha K", 1793527200)), parseGhCommits(COMMITS))
    }

    @Test fun availabilityAndTokenOnStdin() = runBlocking<Unit> {
        assertEquals(ForgeStatus.NotInstalled, GitHub(repo("git@github.com:acme/app.git"), null).available())
        val dir = repo(null)
        val log = File(dir, "log")
        val fake = fakeGh(dir, log, emptyList(), fallback = "echo 'You are not logged into any accounts on github.com' >&2; exit 1")
        assertEquals(ForgeStatus.NoOrigin, GitHub(dir, fake).available())
        runGit(dir, listOf("remote", "add", "origin", "git@github.com:acme/app.git"))
        assertEquals(ForgeStatus.NotAuthenticated("github.com"), GitHub(dir, fake).available())
        runGit(dir, listOf("remote", "set-url", "origin", "https://github.com/no-repo-path"))
        assertEquals(ForgeStatus.Unsupported("github.com"), GitHub(dir, fake).available())
        val ok = fakeGh(dir, log, emptyList())
        runGit(dir, listOf("remote", "set-url", "origin", "git@github.com:acme/app.git"))
        assertEquals(ForgeStatus.Ready, GitHub(dir, ok).available())
        log.delete()
        GitHub(dir, ok).signIn("github.com", "  ghp_secret\n")
        assertEquals("auth login --hostname github.com --with-token | ghp_secret", log.readLines().single())
    }

    /** One request per write (two where GitHub needs the PR's node id first); the fake records argv and the stdin body. */
    @Test fun writeActionsSendExactRequests() = runBlocking<Unit> {
        val dir = repo("git@github.com:acme/app.git")
        val log = File(dir, "log")
        val gh = GitHub(dir, fakeGh(dir, log, listOf(" repos/acme/app/pulls/42" to PULL)))
        val file = FileChange("a/Login.kt", ChangeKind.Modified, false)
        gh.approve(42, "h1")
        gh.merge(42, MergeOptions(squash = true, removeSource = true, whenPipelineSucceeds = false), "h1")
        gh.merge(42, MergeOptions(squash = false, removeSource = true, whenPipelineSucceeds = true), null)
        gh.close(42)
        gh.reopen(42)
        gh.setDraft(42, "Add login", draft = true)
        gh.comment(42, "Ship it\n-- \"me\"")
        gh.reply(42, "PRRT_a", "Done")
        gh.resolve(42, "PRRT_a", true)
        gh.commentOnLine(42, DiffRefs("b1", "b1", "h1"), file, DiffLine(LineType.Removed, "old", 10, null), "Why?")
        gh.commentOnLine(42, DiffRefs("b1", "b1", "h1"), file, DiffLine(LineType.Context, "ctx", 10, 11), "And?")
        assertTrue(runCatching { gh.deleteMergeRequest(42) }.exceptionOrNull() is GitException)
        assertTrue(runCatching { gh.unapprove(42) }.exceptionOrNull() is GitException)
        val p = "api --hostname github.com repos/acme/app"
        val q = "api --hostname github.com graphql -X POST --input - |"
        // a GraphQL body is shown as the operation it runs plus its variables
        val op = Regex(""""query":"[^"]*?\{\s*(?:\\n)?\s*(\w+)[^"]*"""")
        assertEquals(
            listOf(
                """$p/pulls/42/reviews -X POST --input - | {"event":"APPROVE","commit_id":"h1"}""",
                "$p/pulls/42 |",
                """$p/pulls/42/merge -X PUT --input - | {"merge_method":"squash","sha":"h1"}""",
                "$p/git/refs/heads/feat/seat-cache -X DELETE |",
                "$p/pulls/42 |",
                """$q {"query":"enablePullRequestAutoMerge","variables":{"id":"PR_42","method":"MERGE"}}""",
                """$p/pulls/42 -X PATCH --input - | {"state":"closed"}""",
                """$p/pulls/42 -X PATCH --input - | {"state":"open"}""",
                "$p/pulls/42 |",
                """$q {"query":"convertPullRequestToDraft","variables":{"id":"PR_42"}}""",
                """$p/issues/42/comments -X POST --input - | {"body":"Ship it\n-- \"me\""}""",
                """$q {"query":"addPullRequestReviewThreadReply","variables":{"id":"PRRT_a","body":"Done"}}""",
                """$q {"query":"resolveReviewThread","variables":{"id":"PRRT_a"}}""",
                """$p/pulls/42/comments -X POST --input - | {"body":"Why?","commit_id":"h1","path":"a/Login.kt","line":10,"side":"LEFT"}""",
                """$p/pulls/42/comments -X POST --input - | {"body":"And?","commit_id":"h1","path":"a/Login.kt","line":11,"side":"RIGHT"}""",
            ),
            log.readLines().map { op.replace(it, """"query":"$1"""").trimEnd() },
        )
    }

    @Test fun forkBranchIsNotDeletedAfterMerge() = runBlocking<Unit> {
        val dir = repo("git@github.com:acme/app.git")
        val log = File(dir, "log")
        val gh = GitHub(dir, fakeGh(dir, log, listOf(" repos/acme/app/pulls/42" to PULL.replace("acme/app", "someone/app"))))
        gh.merge(42, MergeOptions(squash = false, removeSource = true, whenPipelineSucceeds = false), null)
        assertFalse(log.readText().contains("DELETE"))
    }

    @Test fun mrStateListsPullRequests() = runBlocking<Unit> {
        val dir = repo("git@github.com:acme/app.git")
        val log = File(dir, "log")
        val fake = fakeGh(dir, log, listOf("graphql" to SEARCH, " repos/acme/app" to """{"default_branch":"trunk"}"""))
        val mr = MrState(this)
        mr.open(dir, GitHub(dir, fake))
        assertEquals(ForgeStatus.Ready, mr.status)
        assertEquals("github.com", mr.host)
        assertEquals("trunk", mr.defaultBranch)
        assertEquals("PR" to "#42", mr.terms.short to mr.terms.ref(42))
        mr.loadList()
        assertEquals(listOf(42, 7), mr.list?.map { it.iid })
        assertTrue(log.readText().contains(""""q":"repo:acme/app is:pr is:open sort:updated-desc""""))
    }

    private companion object {
        const val SEARCH = """{"data":{"search":{"nodes":[
          {"number":42,"title":"Cache seat layouts","headRefName":"feat/seat-cache","baseRefName":"main","isDraft":false,
           "url":"https://github.com/acme/app/pull/42","updatedAt":"2026-11-03T17:40:16Z","state":"OPEN","author":{"login":"asha-k"}},
          {"number":7,"title":"Old","headRefName":"x","baseRefName":"main","isDraft":true,"url":"https://github.com/acme/app/pull/7",
           "updatedAt":"1970-01-01T00:01:40Z","state":"MERGED","author":null},
          {}]}}}"""

        const val PULLS = """[{"number":42,"title":"T","head":{"ref":"feat/seat-cache"},"base":{"ref":"main"},"user":{"login":"asha-k"},
          "draft":false,"html_url":"u","updated_at":"1970-01-01T00:01:40Z","state":"open","merged_at":null},
          {"number":9,"title":"M","head":{"ref":"m"},"base":{"ref":"main"},"user":{"login":"ravi"},"draft":false,"html_url":"u9",
           "updated_at":"1970-01-01T00:01:40Z","state":"closed","merged_at":"1970-01-01T00:01:40Z"}]"""

        const val PULL = """{"number":42,"node_id":"PR_42","head":{"ref":"feat/seat-cache","repo":{"full_name":"acme/app"}},"base":{"ref":"main"}}"""

        const val DETAIL = """{"data":{"repository":{"viewerPermission":"WRITE","pullRequest":{"number":42,"title":"Cache seat layouts",
          "body":"Adds an LRU","state":"OPEN","isDraft":false,"url":"https://github.com/acme/app/pull/42","createdAt":"2026-11-01T10:00:00Z",
          "updatedAt":"2026-11-03T17:40:16Z","headRefName":"feat/seat-cache","baseRefName":"main","headRefOid":"h1","baseRefOid":"b1",
          "mergeStateStatus":"BLOCKED","reviewDecision":"REVIEW_REQUIRED","author":{"login":"asha-k","name":"Asha K"},
          "assignees":{"nodes":[{"login":"ravi","name":null}]},
          "reviewRequests":{"nodes":[{"requestedReviewer":{"login":"meera","name":"Meera"}},{"requestedReviewer":{"name":"Mobile","slug":"mobile"}}]},
          "commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"PENDING"}}}]}}}}}"""

        const val APPROVALS = """{"data":{"viewer":{"login":"meera"},"repository":{"pullRequest":{"state":"OPEN","author":{"login":"asha-k"},
          "latestOpinionatedReviews":{"nodes":[{"state":"APPROVED","author":{"login":"ravi","name":"Ravi"}},
          {"state":"CHANGES_REQUESTED","author":{"login":"dev","name":null}}]}}}}}"""

        const val DISCUSSIONS = """{"data":{"repository":{"pullRequest":{
          "comments":{"nodes":[{"databaseId":11,"body":"Looks good overall","createdAt":"2026-11-02T10:00:00Z","author":{"login":"ravi","name":"Ravi"}}]},
          "reviews":{"nodes":[
            {"databaseId":21,"body":"","state":"APPROVED","submittedAt":"2026-11-03T10:00:00Z","author":{"login":"ravi","name":"Ravi"}},
            {"databaseId":22,"body":"","state":"COMMENTED","submittedAt":"2026-11-01T12:00:00Z","author":{"login":"meera","name":"Meera"}},
            {"databaseId":23,"body":"Please split this","state":"CHANGES_REQUESTED","submittedAt":"2026-11-01T13:00:00Z","author":{"login":"meera","name":"Meera"}}]},
          "reviewThreads":{"nodes":[
            {"id":"PRRT_a","isResolved":false,"path":"app/SeatRepository.kt","line":21,"diffSide":"RIGHT","comments":{"nodes":[
              {"databaseId":31,"body":"Why 32?","createdAt":"2026-11-01T12:00:00Z","author":{"login":"meera","name":"Meera"}},
              {"databaseId":32,"body":"Typical trip count","createdAt":"2026-11-01T14:00:00Z","author":{"login":"asha-k","name":"Asha K"}}]}},
            {"id":"PRRT_b","isResolved":true,"path":"app/SeatRepository.kt","line":20,"diffSide":"LEFT","comments":{"nodes":[
              {"databaseId":33,"body":"Gone?","createdAt":"2026-11-01T11:00:00Z","author":{"login":"meera","name":"Meera"}}]}}]}}}}}"""

        const val FILES = """[[{"filename":"app/SeatCache.kt","status":"added","changes":2,"patch":"@@ -0,0 +1,2 @@\n+class SeatCache\n+// end"},
          {"filename":"app/New.kt","previous_filename":"app/Old.kt","status":"renamed","changes":0}],
          [{"filename":"assets/logo.png","status":"modified","changes":5}]]"""

        const val COMMITS = """[[{"sha":"abc123456789","commit":{"message":"Cache seat layouts\n\nLRU of 32","author":{"name":"Asha K","date":"2026-11-01T10:00:00Z"}}}]]"""
    }
}

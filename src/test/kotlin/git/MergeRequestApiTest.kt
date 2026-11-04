package margin.git

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures follow real GitLab 17/18 responses (shapes checked read-only against a self-managed server); values are made up. */
class MergeRequestApiTest {

    private val mrFixture = """
        {"id":9001,"iid":42,"project_id":7,"title":"Draft: Add login","description":"## Why\nBecause.\n","state":"opened",
         "created_at":"2026-10-20T10:00:00.000Z","updated_at":"2026-10-21T11:30:00.000Z","target_branch":"main","source_branch":"feat/login",
         "author":{"id":1,"username":"asha.k","name":"Asha K"},"assignees":[{"id":2,"username":"ravi","name":"Ravi"}],
         "reviewers":[{"id":3,"username":"meera","name":"Meera S"}],"draft":true,"work_in_progress":true,"merge_status":"can_be_merged",
         "detailed_merge_status":"draft_status","has_conflicts":false,"sha":"abc123","web_url":"https://gitlab.example.com/g/p/-/merge_requests/42",
         "head_pipeline":{"id":5,"status":"running","ref":"refs/merge-requests/42/head"},"pipeline":{"id":4,"status":"success"},
         "diff_refs":{"base_sha":"b1","head_sha":"h1","start_sha":"s1"},"user":{"can_merge":true},"labels":[],"time_stats":{"time_estimate":0}}
    """.trimIndent()

    @Test fun parsesMergeRequestDetail() {
        val m = parseMrInfo(mrFixture)
        assertEquals(42, m.iid)
        assertEquals("Draft: Add login", m.title)
        assertTrue(m.draft)
        assertEquals(Person("Asha K", "asha.k"), m.author)
        assertEquals("running", m.pipeline) // head_pipeline wins over the older pipeline field
        assertEquals("draft_status", m.mergeStatus)
        assertTrue(m.canMerge)
        assertEquals(listOf(Person("Meera S", "meera")), m.reviewers)
        assertEquals(listOf(Person("Ravi", "ravi")), m.assignees)
        assertEquals(DiffRefs("b1", "s1", "h1"), m.diffRefs)
        assertEquals(1792490400, m.createdEpoch)
    }

    @Test fun parsesApprovalsOnCeAndEe() {
        val ce = parseApprovals("""{"approved":false,"user_has_approved":false,"user_can_approve":true,"approved_by":[]}""")
        assertEquals(Approvals(emptyList(), false, true, null, null), ce)
        val ee = parseApprovals("""{"approvals_required":2,"approvals_left":1,"user_has_approved":true,"user_can_approve":false,
            "approved_by":[{"user":{"id":3,"username":"meera","name":"Meera S"}}]}""")
        assertEquals(Approvals(listOf(Person("Meera S", "meera")), true, false, 2, 1), ee)
    }

    private val discussionsFixture = """
        [{"id":"6a9c1f0e2b","individual_note":true,"notes":[{"id":1,"type":null,"body":"requested review from @meera","author":{"username":"asha.k","name":"Asha K"},
           "created_at":"2026-10-20T10:01:00.000Z","system":true,"resolvable":false}]},
         {"id":"7b0d2a1f3c","individual_note":true,"notes":[{"id":2,"type":null,"body":"Looks good **overall**","author":{"username":"meera","name":"Meera S"},
           "created_at":"2026-10-20T11:00:00.000Z","system":false,"resolvable":false}]},
         {"id":"8c1e3b2a4d","individual_note":false,"notes":[
           {"id":3,"type":"DiffNote","body":"Null check?","author":{"username":"meera","name":"Meera S"},"created_at":"2026-10-20T12:00:00.000Z",
            "system":false,"resolvable":true,"resolved":false,"position":{"base_sha":"b1","start_sha":"s1","head_sha":"h1","old_path":"a/Login.kt",
            "new_path":"a/Login.kt","position_type":"text","old_line":null,"new_line":12}},
           {"id":4,"type":"DiffNote","body":"Added","author":{"username":"asha.k","name":"Asha K"},"created_at":"2026-10-20T12:05:00.000Z",
            "system":false,"resolvable":true,"resolved":true,"position":{"old_path":"a/Login.kt","new_path":"a/Login.kt","position_type":"text","old_line":null,"new_line":12}}]},
         {"id":"9d2f4c3b5e","individual_note":false,"notes":[{"id":5,"type":"DiffNote","body":"image note","author":{"username":"x","name":"X"},
            "created_at":"2026-10-20T12:00:00.000Z","system":false,"resolvable":true,"resolved":true,"position":{"position_type":"image","new_path":"i.png"}}]}]
    """.trimIndent()

    @Test fun parsesDiscussionsWithThreadsAndPositions() {
        val (sys, plain, thread, image) = parseDiscussions(discussionsFixture)
        assertTrue(sys.system)
        assertFalse(plain.resolvable)
        assertEquals("Looks good **overall**", plain.notes.single().body)
        assertEquals(2, thread.notes.size)
        assertTrue(thread.resolvable)
        assertFalse(thread.resolved) // one resolvable note still open
        assertEquals(NotePosition("a/Login.kt", "a/Login.kt", null, 12), thread.position)
        assertNull(image.position) // image notes have no line to anchor to
    }

    @Test fun parsesDiffsCommitsAndAnchorsNotes() {
        val files = parseMrDiffs("""[
            {"old_path":"a/Login.kt","new_path":"a/Login.kt","new_file":false,"renamed_file":false,"deleted_file":false,"too_large":false,"collapsed":false,
             "diff":"@@ -10,3 +10,4 @@ class Login\n ctx\n-old\n+new\n+added\n"},
            {"old_path":"Old.kt","new_path":"New.kt","new_file":false,"renamed_file":true,"deleted_file":false,"diff":""},
            {"old_path":"Gone.kt","new_path":"Gone.kt","deleted_file":true,"diff":"@@ -1 +0,0 @@\n-x\n"}]""")
        assertEquals(listOf(ChangeKind.Modified, ChangeKind.Renamed, ChangeKind.Deleted), files.map { it.change.kind })
        assertEquals("Old.kt", files[1].change.oldPath)
        val lines = files[0].lines
        assertEquals(listOf(LineType.Hunk, LineType.Context, LineType.Removed, LineType.Added, LineType.Added), lines.map { it.type })
        val pos = NotePosition("a/Login.kt", "a/Login.kt", null, 12)
        assertEquals(listOf(lines[4]), lines.filter { pos.matches(files[0].change, it) }) // +added is new line 12
        assertEquals(listOf(lines[2]), lines.filter { NotePosition("a/Login.kt", "a/Login.kt", 11, null).matches(files[0].change, it) })

        val commits = parseMrCommits("""[{"id":"0123456789abcdef","short_id":"01234567","title":"Add login","author_name":"Asha K","authored_date":"2026-10-20T10:00:00.000+05:30"}]""")
        assertEquals(MrCommit("0123456789abcdef", "Add login", "Asha K", 1792470600), commits.single())
    }

    @Test fun draftTitlesAndListQueries() {
        assertEquals("Draft: Add login", draftTitle("Add login", true))
        assertEquals("Add login", draftTitle("Draft: Add login", false))
        assertEquals("Add login", draftTitle("[Draft] WIP: Add login", false))
        assertEquals("Draft: Add login", draftTitle("(draft) Add login", true))
        assertEquals("projects/:id/merge_requests?state=opened&order_by=updated_at&per_page=50&scope=all", listPath(MrStateFilter.Open, MrScope.All, " ", null))
        assertEquals(
            "projects/:id/merge_requests?state=all&order_by=updated_at&per_page=50&scope=all&reviewer_username=asha.k&search=seat+%26+layout",
            listPath(MrStateFilter.All, MrScope.Review, "seat & layout", "asha.k"),
        )
    }

    @Test fun jsonBodiesEscapeEverything() {
        val body = mapOf("body" to "line 1\n\"quoted\" \\ tab\t\u0001 é 😀", "n" to 3, "ok" to true, "x" to null, "list" to listOf("a", 1))
        assertEquals(body.mapValues { (_, v) -> if (v is Int) v.toLong() else if (v is List<*>) listOf("a", 1L) else v }, parseJson(toJson(body)))
    }

    /** Every write is exactly one `glab api` call; the fake records argv and the stdin body. */
    @Test fun writeActionsSendExactRequests() = runBlocking {
        val dir = createTempDirectory("mrapi").toFile()
        val log = File(dir, "log")
        val fake = File(dir, "glab").apply {
            writeText("#!/bin/sh\nprintf '%s ' \"\$@\" >> '${log.path}'\nprintf '| ' >> '${log.path}'\ncat >> '${log.path}'\necho >> '${log.path}'\necho '{}'\n")
            setExecutable(true)
        }
        val lab = GitLab(dir, fake)
        val refs = DiffRefs("b1", "s1", "h1")
        lab.approve(42, "h1")
        lab.unapprove(42)
        lab.merge(42, MergeOptions(squash = true, removeSource = true, whenPipelineSucceeds = false), "h1")
        lab.merge(42, MergeOptions(squash = false, removeSource = false, whenPipelineSucceeds = true), null)
        lab.close(42)
        lab.reopen(42)
        lab.setDraft(42, "Draft: Add login", draft = false)
        lab.comment(42, "Ship it\n-- \"me\"")
        lab.reply(42, "8c1e3b2a4d", "Done")
        lab.resolve(42, "8c1e3b2a4d", true)
        lab.deleteMergeRequest(42)
        lab.commentOnLine(42, refs, FileChange("a/Login.kt", ChangeKind.Modified, false), DiffLine(LineType.Context, "ctx", 10, 11), "Why?")
        val p = "api projects/:id/merge_requests/42"
        assertEquals(
            listOf(
                """$p/approve -X POST --input - | {"sha":"h1"}""",
                "$p/unapprove -X POST | ",
                """$p/merge -X PUT --input - | {"squash":true,"should_remove_source_branch":true,"sha":"h1"}""",
                """$p/merge -X PUT --input - | {"squash":false,"should_remove_source_branch":false,"merge_when_pipeline_succeeds":true,"auto_merge":true}""",
                """$p -X PUT --input - | {"state_event":"close"}""",
                """$p -X PUT --input - | {"state_event":"reopen"}""",
                """$p -X PUT --input - | {"title":"Add login"}""",
                """$p/notes -X POST --input - | {"body":"Ship it\n-- \"me\""}""",
                """$p/discussions/8c1e3b2a4d/notes -X POST --input - | {"body":"Done"}""",
                """$p/discussions/8c1e3b2a4d -X PUT --input - | {"resolved":true}""",
                "$p -X DELETE | ",
                """$p/discussions -X POST --input - | {"body":"Why?","position":{"position_type":"text","base_sha":"b1","start_sha":"s1","head_sha":"h1","old_path":"a/Login.kt","new_path":"a/Login.kt","old_line":10,"new_line":11}}""",
            ).map { it.trimEnd() },
            log.readLines().map { it.trimEnd() },
        )
        val bad = runCatching { lab.reply(42, "../../projects/1", "x") }.exceptionOrNull()
        assertTrue(bad is GitException) // ids from JSON only; never a path
    }
}

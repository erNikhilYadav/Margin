package margin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import margin.git.GitLab
import margin.git.ForgeStatus
import margin.git.MergeOptions
import margin.git.runGit
import margin.ui.components.Panel
import margin.ui.theme.MarginTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MR tab end to end against a fake glab that serves fixture JSON and records every call.
 * Nothing here talks to a real GitLab; renders go to MARGIN_RENDER_DIR when it is set.
 */
@OptIn(ExperimentalComposeUiApi::class)
class MrFlowTest {
    private val root = Files.createTempDirectory("margin-mr").toFile()
    private val fixtures = File(root, "fx").apply { mkdirs() }
    private val log = File(root, "log")
    private val repo = File(root, "repo").apply { mkdirs() }

    private fun fake(): File {
        val f = fixtures.path
        File(fixtures, "list.json").writeText(LIST)
        File(fixtures, "mr.json").writeText(MR)
        File(fixtures, "approvals.json").writeText(APPROVALS)
        File(fixtures, "discussions.json").writeText(DISCUSSIONS)
        File(fixtures, "commits.json").writeText(COMMITS)
        File(fixtures, "diffs.json").writeText(DIFFS)
        return File(root, "glab").apply {
            writeText(
                """
                #!/bin/sh
                args="${'$'}*"
                printf '%s | ' "${'$'}args" >> '${log.path}'; cat >> '${log.path}'; echo >> '${log.path}'
                while [ -f '$f/hold' ]; do sleep 0.05; done
                case "${'$'}args" in
                  "auth status"*) exit 0 ;;
                  "repo view"*) echo '{"default_branch":"main"}' ;;
                  "mr list"*) echo '[]' ;;
                  "api user") echo '{"username":"asha.k"}' ;;
                  *"-X DELETE"*) if [ -f '$f/deny' ]; then echo 'glab: 403 Forbidden' >&2; exit 1; fi ;;
                  *" -X "*) echo '{}' ;;
                  *"merge_requests?"*) cat '$f/list.json' ;;
                  *"merge_requests/42/approvals"*) cat '$f/approvals.json' ;;
                  *"merge_requests/42/discussions"*) cat '$f/discussions.json' ;;
                  *"merge_requests/42/commits"*) cat '$f/commits.json' ;;
                  *"merge_requests/42/diffs"*) cat '$f/diffs.json' ;;
                  "api projects/:id/merge_requests/42") cat '$f/mr.json' ;;
                  *) echo "unexpected: ${'$'}args" >&2; exit 1 ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
    }

    /** The write requests sent so far, without the GET reloads in between. */
    private fun writes() = log.readLines().filter { " -X " in it }.map { it.trimEnd() }

    @Test fun listDetailAndActionsAgainstFakeGitLab() = runBlocking<Unit> {
        val prefs = Preferences.userRoot().node("margin-test-${System.nanoTime()}")
        try {
            runGit(repo, listOf("init", "-q", "-b", "main"))
            runGit(repo, listOf("remote", "add", "origin", "https://gitlab.example.com/mobile/seat-app.git"))
            val toasts = mutableListOf<Pair<String, Boolean>>()
            val checkouts = mutableListOf<String>()
            val state = AppState(this, prefs)
            val glab = fake()
            val mr = MrState(this, notify = { text, err, _ -> toasts += text to err }, checkout = { checkouts += it })
            mr.open(repo, GitLab(repo, glab))
            assertEquals(ForgeStatus.Ready, mr.status)
            mr.showing = true
            val ui = Ui(this, mr, state)

            // A: filters reach the API query, the list renders and a row click opens the detail in the app
            mr.stateFilter = margin.git.MrStateFilter.All
            mr.scopeFilter = margin.git.MrScope.Review
            mr.query = "seat"
            mr.loadList()
            assertTrue(log.readText().contains("api projects/:id/merge_requests?state=all&order_by=updated_at&per_page=50&scope=all&reviewer_username=asha.k&search=seat"))
            mr.stateFilter = margin.git.MrStateFilter.Open; mr.scopeFilter = margin.git.MrScope.All; mr.query = ""
            mr.loadList()
            assertEquals(listOf(42, 41, 38), mr.list?.map { it.iid })
            ui.render("mr2_list")
            ui.click(400f, 44f + 1f + 4f + 26f) // first row
            val d = assertNotNull(mr.detail)
            withTimeout(15000) { while (d.info == null || d.discussions == null || d.diffs == null || d.commits == null || d.approvals == null) delay(20) }
            assertEquals(42, d.iid)

            // B: every tab renders, with the inline thread anchored under its line
            ui.render("mr2_detail_overview", light = true)
            d.tab = 1
            d.file = d.diffs!!.first { it.change.path.endsWith("SeatRepository.kt") }
            ui.render("mr2_changes_inline")
            val target = d.file!!.lines.first { it.newNo == 22 }
            d.commentingOn = d.file!!.change.path to target
            ui.render("mr2_inline_composer")
            mr.commentOnLine(d.file!!.change, target, "Name this layoutFor?", onDone = { d.commentingOn = null }); withTimeout(15000) { while (mr.acting != null) delay(20) }
            assertNull(d.commentingOn)
            d.tab = 2
            ui.render("mr2_commits")
            d.tab = 0

            // Actions: exactly one write each, then a reload and a toast
            mr.approve(); ui.settle(); withTimeout(15000) { while (mr.acting != null) delay(20) }
            mr.comment("LGTM **after** the cache fix", onDone = {}); withTimeout(15000) { while (mr.acting != null) delay(20) }
            mr.reply("8c1e3b2a4d", "Fixed in 3f2a", onDone = {}); withTimeout(15000) { while (mr.acting != null) delay(20) }
            mr.resolve("8c1e3b2a4d", true); withTimeout(15000) { while (mr.acting != null) delay(20) }
            mr.setDraft(true); withTimeout(15000) { while (mr.acting != null) delay(20) }
            d.dialog = MrDialog.Merge
            ui.render("mr2_merge_sheet")
            mr.merge(MergeOptions(squash = true, removeSource = true, whenPipelineSucceeds = false)); withTimeout(15000) { while (mr.acting != null) delay(20) }
            assertNull(d.dialog) // a finished action closes its dialog
            mr.checkoutBranch()
            assertEquals(listOf("feature/seat-cache"), checkouts)

            d.dialog = MrDialog.Close
            ui.render("mr2_close_confirm")
            mr.close(); withTimeout(15000) { while (mr.acting != null) delay(20) }

            // Delete refused by GitLab: its message is shown and the detail stays open
            File(fixtures, "deny").writeText("")
            d.dialog = MrDialog.Delete
            ui.render("mr2_delete_confirm")
            mr.delete(); withTimeout(15000) { while (mr.acting != null) delay(20) }
            assertEquals("glab: 403 Forbidden" to true, toasts.last())
            assertNotNull(mr.detail)
            File(fixtures, "deny").delete()
            mr.delete(); withTimeout(15000) { while (mr.acting != null) delay(20) }
            assertNull(mr.detail) // deleted: back to the list

            val p = "api projects/:id/merge_requests/42"
            assertEquals(
                listOf(
                    """$p/discussions -X POST --input - | {"body":"Name this layoutFor?","position":{"position_type":"text","base_sha":"f73f7a63","start_sha":"f73f7a63","head_sha":"990af5cd","old_path":"app/src/main/java/com/example/seat/SeatRepository.kt","new_path":"app/src/main/java/com/example/seat/SeatRepository.kt","new_line":22}}""",
                    """$p/approve -X POST --input - | {"sha":"990af5cd"}""",
                    """$p/notes -X POST --input - | {"body":"LGTM **after** the cache fix"}""",
                    """$p/discussions/8c1e3b2a4d/notes -X POST --input - | {"body":"Fixed in 3f2a"}""",
                    """$p/discussions/8c1e3b2a4d -X PUT --input - | {"resolved":true}""",
                    """$p -X PUT --input - | {"title":"Draft: Cache seat layouts per trip"}""",
                    """$p/merge -X PUT --input - | {"squash":true,"should_remove_source_branch":true,"sha":"990af5cd"}""",
                    """$p -X PUT --input - | {"state_event":"close"}""",
                    "$p -X DELETE |",
                    "$p -X DELETE |",
                ),
                writes(),
            )
            assertEquals(
                listOf("Comment posted", "Approved", "Comment posted", "Reply posted", "Thread resolved", "Marked as draft", "Merge started", "Merge request closed", "glab: 403 Forbidden", "Merge request !42 deleted"),
                toasts.map { it.first },
            )
            ui.close()
        } finally {
            prefs.removeNode()
            root.deleteRecursively()
        }
    }

    /** Skeletons while the first load runs; a refresh keeps the content and shows only the strip. */
    @Test fun skeletonsWhileLoadingThenStripOnRefresh() = runBlocking<Unit> {
        val prefs = Preferences.userRoot().node("margin-test-${System.nanoTime()}")
        try {
            runGit(repo, listOf("init", "-q", "-b", "main"))
            runGit(repo, listOf("remote", "add", "origin", "https://gitlab.example.com/mobile/seat-app.git"))
            val mr = MrState(this, notify = { _, _, _ -> }, checkout = {})
            mr.open(repo, GitLab(repo, fake()))
            mr.showing = true
            val ui = Ui(this, mr, AppState(this, prefs))
            val hold = File(fixtures, "hold")

            ui.shot("shim_list_dark") // list not loaded yet
            ui.dark = false
            ui.shot("shim_list_light")
            ui.dark = true
            val a = ui.shot("shim_frame_a", 250)
            ui.advance(250)
            val b = ui.frame("shim_frame_b")
            assertFalse(a.contentEquals(b)) // the sweep moved

            mr.loadList()
            hold.writeText("")
            mr.openDetail(42)
            val d = assertNotNull(mr.detail)
            ui.shot("shim_detail_overview")
            d.tab = 1
            ui.shot("shim_detail_changes")
            d.tab = 2
            ui.shot("shim_detail_commits")
            assertNull(d.info) // still loading: those were skeletons

            hold.delete()
            withTimeout(15000) { while (d.refreshing) delay(20) }
            d.tab = 0
            hold.writeText("")
            mr.refresh()
            withTimeout(15000) { while (!d.refreshing) delay(20) }
            ui.render("shim_refresh")
            assertNotNull(d.info) // the content stays while it reloads
            hold.delete()
            withTimeout(15000) { while (d.refreshing || mr.loading) delay(20) }
            ui.close()
        } finally {
            File(fixtures, "hold").delete()
            prefs.removeNode()
            root.deleteRecursively()
        }
    }

    /** MergeRequestsPane plus the window-level dialogs, at the real window size. */
    private class Ui(val scope: CoroutineScope, val mr: MrState, val state: AppState) {
        var dark by mutableStateOf(true)
        val density = 1.5f
        val scene = ImageComposeScene((1200 * density).toInt(), (780 * density).toInt(), Density(density)) {
            MarginTheme(dark) {
                Box(Modifier.fillMaxSize().background(MarginTheme.colors.canvas)) {
                    Panel(Modifier.fillMaxSize()) { MergeRequestsPane(mr, state) }
                    MrDialogs(mr)
                }
            }
        }
        var t = 0L

        suspend fun settle() { repeat(40) { t += 16_000_000; scene.render(t); delay(5) } }

        suspend fun click(xDp: Float, yDp: Float) {
            val p = Offset(xDp * density, yDp * density)
            scene.sendPointerEvent(PointerEventType.Move, p)
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons(), button = PointerButton.Primary)
            settle()
        }

        suspend fun render(name: String, light: Boolean = false) {
            val out = System.getenv("MARGIN_RENDER_DIR")?.let(::File)
            for (d in if (light) listOf(true, false) else listOf(true)) {
                dark = d
                settle()
                if (out != null) File(out.apply { mkdirs() }, "${name}${if (light) if (d) "_dark" else "_light" else ""}.png")
                    .writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            dark = true
            settle()
        }

        suspend fun advance(ms: Int) {
            repeat(ms / 16) { t += 16_000_000; scene.render(t); delay(2) }
            t += (ms % 16) * 1_000_000L; scene.render(t)
        }

        /** Settles, then one frame [atMs] into a shimmer sweep, so skeleton shots are comparable. */
        suspend fun shot(name: String, atMs: Int = 400): ByteArray {
            settle()
            advance((atMs - (t / 1_000_000 % 1200).toInt() + 1200) % 1200)
            return frame(name)
        }

        /** One frame at the current time, without settling; saved like [render]. */
        fun frame(name: String): ByteArray {
            val bytes = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            System.getenv("MARGIN_RENDER_DIR")?.let { File(File(it).apply { mkdirs() }, "$name.png").writeBytes(bytes) }
            return bytes
        }

        fun close() = scene.close()
    }

    private companion object {
        const val LIST = """[
          {"iid":42,"title":"Cache seat layouts per trip","state":"opened","draft":false,"source_branch":"feature/seat-cache","target_branch":"main",
           "author":{"username":"asha.k","name":"Asha Kulkarni"},"web_url":"https://gitlab.example.com/mobile/seat-app/-/merge_requests/42","updated_at":"2026-10-27T08:10:00Z"},
          {"iid":41,"title":"Draft: Passenger list redesign","state":"opened","draft":true,"source_branch":"feature/passengers","target_branch":"main",
           "author":{"username":"ravi","name":"Ravi Teja"},"web_url":"https://gitlab.example.com/mobile/seat-app/-/merge_requests/41","updated_at":"2026-10-26T15:00:00Z"},
          {"iid":38,"title":"Fix crash on empty boarding points","state":"merged","draft":false,"source_branch":"bugfix/boarding","target_branch":"release/5.2",
           "author":{"username":"meera","name":"Meera S"},"web_url":"https://gitlab.example.com/mobile/seat-app/-/merge_requests/38","updated_at":"2026-10-24T09:00:00Z"}]"""

        const val MR = """{"iid":42,"title":"Cache seat layouts per trip","state":"opened","draft":false,
          "description":"## Why\nSeat layouts are refetched on every screen open, which is **slow** on 3G.\n\n## What\n- Cache layouts per trip id in `SeatCache`\n- Invalidate when the trip's `layoutVersion` changes\n- [x] Unit tests\n- [ ] QA sign-off\n\n```kotlin\nval layout = cache.getOrPut(tripId) { api.layout(tripId) }\n```\n\nSee [the design doc](https://example.com/seat-cache).",
          "author":{"username":"asha.k","name":"Asha Kulkarni"},"source_branch":"feature/seat-cache","target_branch":"main",
          "created_at":"2026-10-25T09:00:00Z","updated_at":"2026-10-27T08:10:00Z","web_url":"https://gitlab.example.com/mobile/seat-app/-/merge_requests/42",
          "head_pipeline":{"id":7,"status":"success"},"detailed_merge_status":"mergeable","user":{"can_merge":true},
          "reviewers":[{"username":"meera","name":"Meera S"}],"assignees":[{"username":"asha.k","name":"Asha Kulkarni"}],
          "diff_refs":{"base_sha":"f73f7a63","head_sha":"990af5cd","start_sha":"f73f7a63"},"sha":"990af5cd"}"""

        const val APPROVALS = """{"approved":true,"user_has_approved":false,"user_can_approve":true,
          "approved_by":[{"user":{"username":"meera","name":"Meera S"}}]}"""

        const val DISCUSSIONS = """[
          {"id":"6a9c1f0e2b","individual_note":true,"notes":[{"id":1,"body":"requested review from @meera","author":{"username":"asha.k","name":"Asha Kulkarni"},
            "created_at":"2026-10-25T09:01:00Z","system":true,"resolvable":false}]},
          {"id":"7b0d2a1f3c","individual_note":true,"notes":[{"id":2,"body":"Nice, this should cut the **cold start** a lot. Did you check memory with 200+ seats?","author":{"username":"meera","name":"Meera S"},
            "created_at":"2026-10-26T10:00:00Z","system":false,"resolvable":false}]},
          {"id":"8c1e3b2a4d","individual_note":false,"notes":[
            {"id":3,"type":"DiffNote","body":"This never evicts. Can we cap it with an LRU?","author":{"username":"meera","name":"Meera S"},"created_at":"2026-10-26T10:05:00Z",
             "system":false,"resolvable":true,"resolved":false,"position":{"position_type":"text","old_path":"app/src/main/java/com/example/seat/SeatRepository.kt",
             "new_path":"app/src/main/java/com/example/seat/SeatRepository.kt","old_line":null,"new_line":20}},
            {"id":4,"type":"DiffNote","body":"Good call, switching to `LruCache(32)`.","author":{"username":"asha.k","name":"Asha Kulkarni"},"created_at":"2026-10-26T11:00:00Z",
             "system":false,"resolvable":true,"resolved":false,"position":{"position_type":"text","old_path":"app/src/main/java/com/example/seat/SeatRepository.kt",
             "new_path":"app/src/main/java/com/example/seat/SeatRepository.kt","old_line":null,"new_line":20}}]},
          {"id":"9d2f4c3b5e","individual_note":true,"notes":[{"id":5,"body":"approved this merge request","author":{"username":"meera","name":"Meera S"},
            "created_at":"2026-10-27T08:00:00Z","system":true,"resolvable":false}]}]"""

        const val COMMITS = """[
          {"id":"990af5cd4f4631dd","title":"Cap the seat cache with an LRU","author_name":"Asha Kulkarni","authored_date":"2026-10-26T11:30:00Z"},
          {"id":"5e1b2c3d4e5f6a7b","title":"Invalidate on layoutVersion change","author_name":"Asha Kulkarni","authored_date":"2026-10-25T18:00:00Z"},
          {"id":"1a2b3c4d5e6f7a8b","title":"Add SeatCache","author_name":"Asha Kulkarni","authored_date":"2026-10-25T09:00:00Z"}]"""

        const val DIFFS = """[
          {"old_path":"app/src/main/java/com/example/seat/SeatCache.kt","new_path":"app/src/main/java/com/example/seat/SeatCache.kt","new_file":true,"renamed_file":false,"deleted_file":false,
           "diff":"@@ -0,0 +1,6 @@\n+package com.example.seat\n+\n+class SeatCache(size: Int) {\n+    private val map = LruCache<String, SeatLayout>(size)\n+    fun getOrPut(id: String, load: () -> SeatLayout) = map[id] ?: load().also { map.put(id, it) }\n+}\n"},
          {"old_path":"app/src/main/java/com/example/seat/SeatRepository.kt","new_path":"app/src/main/java/com/example/seat/SeatRepository.kt","new_file":false,"renamed_file":false,"deleted_file":false,
           "diff":"@@ -18,9 +18,11 @@ class SeatRepository(\n     private val api: SeatApi,\n ) {\n-    suspend fun layout(tripId: String): SeatLayout =\n-        api.layout(tripId)\n+    private val cache = SeatCache(32)\n+\n+    suspend fun layout(tripId: String): SeatLayout =\n+        cache.getOrPut(tripId) { api.layout(tripId) }\n \n     suspend fun book(seats: List<Seat>) = api.book(seats)\n }\n"}]"""
    }
}

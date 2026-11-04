package margin.ui

import margin.git.GitRepo
import margin.git.runGit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A commit made outside Margin and new upstream commits both show up without a manual refresh. Local temp repos only. */
class AutoRefreshTest {
    private val root = Files.createTempDirectory("margin-auto").toFile()
    private fun git(dir: File, vararg a: String) = runGit(dir, a.toList())
    private fun clone(name: String) = File(root, name).also {
        git(root, "clone", "-q", File(root, "origin.git").path, it.path)
        git(it, "config", "user.email", "t@t"); git(it, "config", "user.name", "t"); git(it, "config", "commit.gpgsign", "false")
    }
    private fun commit(dir: File, msg: String) { File(dir, "$msg.txt").writeText(msg); git(dir, "add", "-A"); git(dir, "commit", "-qm", msg) }

    @Test fun pollPicksUpOutsideCommitAndAutoFetchReportsUpstream() = runBlocking<Unit> {
        val prefs = Preferences.userRoot().node("margin-test-${System.nanoTime()}")
        try {
            git(root, "init", "-q", "--bare", "-b", "main", "origin.git")
            val seed = File(root, "seed").apply { mkdirs() }
            git(seed, "init", "-q", "-b", "main"); git(seed, "config", "user.email", "t@t"); git(seed, "config", "user.name", "t"); git(seed, "config", "commit.gpgsign", "false")
            commit(seed, "base"); git(seed, "remote", "add", "origin", File(root, "origin.git").path); git(seed, "push", "-q", "-u", "origin", "main")
            val mine = clone("mine")
            val teammate = clone("teammate")

            val state = AppState(this, prefs) { GitRepo(it) { f -> f.delete() } }
            state.open(mine)
            withTimeout(15000) { while (state.log.isEmpty()) delay(20) }

            commit(mine, "from-ide") // like committing in Android Studio
            state.poll()
            withTimeout(15000) { while (state.log.first().subject != "from-ide") delay(20) }

            commit(teammate, "theirs"); git(teammate, "push", "-q")
            state.autoFetch()
            withTimeout(15000) { while (state.status?.behind != 1) delay(20) }
            withTimeout(5000) { while (state.toast == null) delay(20) }
            assertTrue("1 new commit on origin/main" in state.toast!!.text, state.toast!!.text)

            state.autoFetch() // throttled: no second fetch right away
            assertEquals(1, state.status?.behind)
        } finally {
            prefs.removeNode()
            root.deleteRecursively()
        }
    }
}

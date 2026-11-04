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

class RollbackStateTest {
    @Test fun dialogPrechecksAndConfirmRollsBackExactlyTheChecked() = runBlocking<Unit> {
        val root = Files.createTempDirectory("margin-rbstate").toFile()
        val prefs = Preferences.userRoot().node("margin-test-${System.nanoTime()}") // isolated: no recents, no real prefs
        try {
            val dir = File(root, "repo").apply { mkdirs() }
            val trashed = mutableListOf<String>()
            fun sh(vararg a: String) = runGit(dir, a.toList())
            fun write(n: String, t: String) = File(dir, n).apply { parentFile.mkdirs() }.writeText(t)
            sh("init", "-q"); sh("config", "user.email", "t@t"); sh("config", "user.name", "t"); sh("config", "commit.gpgsign", "false")
            write("a.txt", "a\n"); write("b.txt", "b\n"); sh("add", "-A"); sh("commit", "-qm", "base")
            write("a.txt", "a2\n"); write("b.txt", "b2\n"); write("u1.txt", "u1\n"); write("u2.txt", "u2\n")

            val state = AppState(this, prefs) { GitRepo(it) { f -> trashed += f.name; f.delete() } } // fake Trash
            state.open(dir)
            withTimeout(15000) { while (state.status == null) delay(20) }

            state.requestRollback() // nothing selected: tracked files only, never untracked
            assertEquals(setOf("a.txt", "b.txt"), state.rollbackTarget)
            state.cancelRollback()
            state.selection = setOf("b.txt", "u1.txt")
            state.requestRollback()
            assertEquals(setOf("b.txt", "u1.txt"), state.rollbackTarget)

            state.rollbackTarget = setOf("a.txt", "u2.txt") // the user's checks in the dialog
            state.confirmRollback()
            withTimeout(15000) { while (Action.Rollback in state.busy) delay(20) }
            assertEquals(listOf("u2.txt"), trashed)
            assertEquals("a\n", File(dir, "a.txt").readText())
            assertEquals("b2\n", File(dir, "b.txt").readText())
            assertTrue(File(dir, "u1.txt").exists())
        } finally {
            prefs.removeNode()
            root.deleteRecursively()
        }
    }
}

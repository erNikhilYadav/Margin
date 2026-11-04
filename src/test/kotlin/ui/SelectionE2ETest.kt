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
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import margin.git.ChangeKind
import margin.git.GitRepo
import margin.git.runGit
import margin.ui.theme.MarginTheme
import margin.ui.theme.Sizes
import margin.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real ChangesPane with in-process pointer events (never OS input) against a temp repo,
 * and checks git's index itself, not just UI state: viewing and selecting never stage; only checkboxes do.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SelectionE2ETest {
    private val root = Files.createTempDirectory("margin-sel").toFile()
    private val dir = File(root, "repo").apply { mkdirs() }
    private fun git(vararg a: String) = runGit(dir, a.toList())
    private fun write(n: String, t: String) = File(dir, n).writeText(t)
    /** Blob hashes per index entry plus HEAD-relative status: any staging change, even one hunk, alters it. */
    private fun index() = git("ls-files", "--stage") + git("diff", "--cached", "--name-status", "-M")

    @Test fun viewingNeverStagesAndCommitTakesExactlyTheChecked() = runBlocking<Unit> {
        val prefs = Preferences.userRoot().node("margin-test-${System.nanoTime()}") // isolated: no recents, no real prefs
        try {
            git("init", "-q", "-b", "main"); git("config", "user.email", "t@t"); git("config", "user.name", "t"); git("config", "commit.gpgsign", "false")
            for (n in listOf("a", "b", "c", "r")) write("$n.txt", "$n\n".repeat(20))
            git("add", "-A"); git("commit", "-qm", "base")
            write("a.txt", "a2\n" + "a\n".repeat(19)) // unstaged
            write("b.txt", "b2\n" + "b\n".repeat(19)); git("add", "b.txt") // staged
            write("c.txt", "c2\n" + "c\n".repeat(19)); git("add", "c.txt"); write("c.txt", "c2\n" + "c\n".repeat(18) + "c3\n") // partially staged
            git("mv", "r.txt", "r2.txt") // staged rename
            write("u.txt", "u\n") // untracked

            val state = AppState(this, prefs) { GitRepo(it) { f -> f.delete() } } // fake Trash
            state.open(dir)
            withTimeout(15000) { while (state.status == null) delay(20) }
            val ui = Ui(this, state)
            val i0 = index()

            // Plain click: views the file; the index is untouched.
            ui.click("a.txt")
            assertEquals("a.txt", state.selected?.path)
            ui.assertNeutral("a.txt")
            ui.settle(); assertEquals(i0, index(), "plain click changed the index")
            ui.render("sel_viewed")

            // ⌘-click and ⇧-click build a rollback selection; still no staging.
            ui.click("b.txt", PointerKeyboardModifiers(isMetaPressed = true))
            assertEquals(setOf("a.txt", "b.txt"), state.selection)
            ui.click("u.txt", PointerKeyboardModifiers(isShiftPressed = true))
            assertEquals(setOf("b.txt", "c.txt", "r2.txt", "u.txt"), state.selection) // range from the last plain or ⌘ click
            ui.settle(); assertEquals(i0, index(), "⌘/⇧-click changed the index")
            ui.render("sel_multi")

            // Right-click opens the row menu; its Show Diff views the file without staging.
            ui.click("c.txt", button = PointerButton.Secondary)
            ui.clickAt(Offset(200f + 30f, ui.y("c.txt") + 4f + 14f)) // first menu item, Show Diff
            ui.click("c.txt")
            assertEquals("c.txt", state.selected?.path)
            ui.settle(); assertEquals(i0, index(), "row menu changed the index")

            // The 3s poll keeps checkboxes, the viewed file and the selection.
            val before = Triple(state.status?.changes, state.selected?.path, state.selection)
            repeat(3) { state.poll(); ui.settle() }
            assertEquals(before, Triple(state.status?.changes, state.selected?.path, state.selection))

            // Only the checkbox stages, and it leaves the viewed file alone.
            ui.click("a.txt", onCheckbox = true)
            withTimeout(15000) { while ("a.txt" !in git("diff", "--cached", "--name-only")) delay(20) }
            ui.settle()
            assertEquals("c.txt", state.selected?.path, "checkbox moved the viewed row")
            ui.render("sel_checked")

            // Commit: the button's count equals the checked (and dash) rows, and HEAD gets exactly those.
            val checked = state.files.filter { sides -> sides.any { it.staged } }.map { it[0].path }.toSet()
            assertEquals(setOf("a.txt", "b.txt", "c.txt", "r2.txt"), checked)
            assertEquals(checked.size, state.stagedFileCount)
            state.message = "test commit"
            state.commit()
            withTimeout(15000) { while (Action.Commit in state.busy) delay(20) }
            assertEquals(checked, git("show", "--name-only", "--format=", "-M", "HEAD").lines().filter { it.isNotBlank() }.toSet())
            assertTrue("R100\tr.txt\tr2.txt" in git("show", "--name-status", "--format=", "-M", "HEAD"))
            assertEquals("c2\n" + "c\n".repeat(19), git("show", "HEAD:c.txt"), "partial file committed more than its staged hunk")
            assertTrue(File(dir, "c.txt").readText().endsWith("c3\n")) // unstaged hunk still in the working tree
            ui.close()
        } finally {
            prefs.removeNode()
            root.deleteRecursively()
        }
    }

    /** ChangesPane alone, sized like the real column. Row positions follow the list layout: action bar, band, spacer, rows. */
    private class Ui(val scope: CoroutineScope, val state: AppState) {
        val w = 340; val h = 640
        var dark by mutableStateOf(true)
        val scene = ImageComposeScene(w, h, Density(1f)) {
            MarginTheme(dark) { Box(Modifier.fillMaxSize().background(MarginTheme.colors.surface)) { ChangesPane(state) } }
        }
        var t = 0L

        fun frame() { t += 16_000_000; scene.render(t) }

        suspend fun settle() { repeat(30) { frame(); delay(10) } }

        fun y(path: String): Float {
            val (untracked, tracked) = state.files.partition { it[0].kind == ChangeKind.Untracked }
            val band = Sizes.rowHeight + 1.dp
            val top = 32.dp + 1.dp + band + Spacing.xs // actions, hairline, Changes band, top spacer
            val i = tracked.indexOfFirst { it[0].path == path }
            val at: Dp = if (i >= 0) top + Sizes.rowHeight * i
            else top + Sizes.rowHeight * maxOf(tracked.size, 1) + Spacing.xs + 1.dp + band + Spacing.xs + Sizes.rowHeight * untracked.indexOfFirst { it[0].path == path }
            return (at + Sizes.rowHeight / 2).value
        }

        suspend fun click(path: String, mods: PointerKeyboardModifiers = PointerKeyboardModifiers(), button: PointerButton = PointerButton.Primary, onCheckbox: Boolean = false) =
            clickAt(Offset(if (onCheckbox) (Spacing.xs + Spacing.xs + 10.dp).value else 200f, y(path)), mods, button)

        suspend fun clickAt(p: Offset, mods: PointerKeyboardModifiers = PointerKeyboardModifiers(), button: PointerButton = PointerButton.Primary) {
            val down = if (button == PointerButton.Secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, p, keyboardModifiers = mods)
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = down, keyboardModifiers = mods, button = button)
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons(), keyboardModifiers = mods, button = button)
            settle()
        }

        suspend fun render(name: String) {
            val out = System.getenv("MARGIN_RENDER_DIR")?.let(::File) ?: return
            for (d in listOf(true, false)) {
                dark = d
                settle()
                File(out.apply { mkdirs() }, "${name}_${if (d) "dark" else "light"}.png")
                    .writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            dark = true
            settle()
        }

        /** The viewed/selected row fill must be neutral, not the accent: the accent is the checkbox's "in the commit" color. */
        fun assertNeutral(path: String) {
            for (d in listOf(true, false)) {
                dark = d
                repeat(10) { frame() }
                val px = Bitmap.makeFromImage(scene.render(t)).getColor(250, y(path).toInt())
                val (r, g, b) = Triple(Color.getR(px), Color.getG(px), Color.getB(px))
                // warm paper/ink greys spread ~8; an accent tint spreads 25+
                assertTrue(maxOf(r, g, b) - minOf(r, g, b) <= 10, "row $path is tinted (rgb $r,$g,$b) in ${if (d) "dark" else "light"}")
            }
            dark = true
        }

        fun close() = scene.close()
    }
}

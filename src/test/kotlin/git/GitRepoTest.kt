package margin.git

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitRepoTest {
    private val nul = "\u0000"

    @Test fun parseStatusFull() {
        val raw = listOf(
            "# branch.oid 1234567890abcdef1234567890abcdef12345678",
            "# branch.head main",
            "# branch.upstream origin/main",
            "# branch.ab +2 -1",
            "1 MM N... 100644 100644 100644 aaaa bbbb src/my file.kt",
            "2 R. N... 100644 100644 100644 aaaa bbbb R100 new name.txt",
            "old name.txt",
            "1 .D N... 100644 100644 000000 aaaa bbbb gone.txt",
            "u UU N... 100644 100644 100644 100644 aaaa bbbb cccc conflict.txt",
            "? untracked dir/a.txt",
            "! ignored.log",
        ).joinToString(nul, postfix = nul)
        val s = parseStatus(raw)
        assertEquals("main", s.branch)
        assertEquals("origin/main", s.upstream)
        assertEquals(2, s.ahead)
        assertEquals(1, s.behind)
        assertEquals(
            listOf(
                FileChange("src/my file.kt", ChangeKind.Modified, staged = true),
                FileChange("src/my file.kt", ChangeKind.Modified, staged = false),
                FileChange("new name.txt", ChangeKind.Renamed, staged = true, oldPath = "old name.txt"),
                FileChange("gone.txt", ChangeKind.Deleted, staged = false),
                FileChange("conflict.txt", ChangeKind.Conflicted, staged = false),
                FileChange("untracked dir/a.txt", ChangeKind.Untracked, staged = false),
            ),
            s.changes,
        )
    }

    @Test fun parseStatusDetachedNoUpstream() {
        val s = parseStatus("# branch.oid abc${nul}# branch.head (detached)$nul")
        assertNull(s.branch)
        assertNull(s.upstream)
        assertEquals(0, s.ahead)
        assertTrue(s.changes.isEmpty())
    }

    @Test fun parseBranchesSkipsRemoteHead() {
        val raw = "* refs/heads/main\n  refs/heads/feature/x\n  refs/remotes/origin/HEAD\n  refs/remotes/origin/main\n"
        assertEquals(
            listOf(
                Branch("main", isRemote = false, isCurrent = true),
                Branch("feature/x", isRemote = false, isCurrent = false),
                Branch("origin/main", isRemote = true, isCurrent = false),
            ),
            parseBranches(raw),
        )
    }

    @Test fun parseLogFields() {
        val raw = "aaaa${nul}Ann Lee${nul}1700000000${nul}Fix: a b${nul}Line 1\n\nLine 2\n${nul}bbbb${nul}Bo${nul}1600000000${nul}${nul}$nul"
        assertEquals(
            listOf(Commit("aaaa", "Fix: a b", "Ann Lee", 1700000000, "Line 1\n\nLine 2"), Commit("bbbb", "", "Bo", 1600000000)),
            parseLog(raw),
        )
        assertTrue(parseLog("").isEmpty())
    }

    @Test fun parseNameStatusKinds() {
        val raw = listOf("M", "src/a b.kt", "A", "new.txt", "D", "gone.txt", "R087", "old name.txt", "new name.txt", "C100", "x.txt", "copy.txt", "T", "link")
            .joinToString(nul, postfix = nul)
        assertEquals(
            listOf(
                FileChange("src/a b.kt", ChangeKind.Modified, false),
                FileChange("new.txt", ChangeKind.Added, false),
                FileChange("gone.txt", ChangeKind.Deleted, false),
                FileChange("new name.txt", ChangeKind.Renamed, false, oldPath = "old name.txt"),
                FileChange("copy.txt", ChangeKind.Added, false),
                FileChange("link", ChangeKind.Modified, false),
            ),
            parseNameStatus(raw),
        )
        assertTrue(parseNameStatus("").isEmpty())
    }

    @Test fun parseDiffTracksLineNumbers() {
        val raw = """
            diff --git a/f.txt b/f.txt
            index 1111111..2222222 100644
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@ fun a+b
             one
            -two
            +TWO
             three
            @@ -10,2 +10,1 @@
            -ten
             eleven
            \ No newline at end of file
        """.trimIndent() + "\n"
        val d = parseDiff(raw)
        assertEquals(
            listOf(
                DiffLine(LineType.Hunk, "@@ -1,3 +1,3 @@ fun a+b", null, null),
                DiffLine(LineType.Context, "one", 1, 1),
                DiffLine(LineType.Removed, "two", 2, null),
                DiffLine(LineType.Added, "TWO", null, 2),
                DiffLine(LineType.Context, "three", 3, 3),
                DiffLine(LineType.Hunk, "@@ -10,2 +10,1 @@", null, null),
                DiffLine(LineType.Removed, "ten", 10, null),
                DiffLine(LineType.Context, "eleven", 11, 10),
            ),
            d,
        )
    }

    @Test fun parseDiffBinaryAndNewFile() {
        assertTrue(parseDiff("diff --git a/x.png b/x.png\nindex 1..2 100644\nBinary files a/x.png and b/x.png differ\n").isEmpty())
        assertEquals(
            listOf(DiffLine(LineType.Hunk, "@@ -0,0 +1 @@", null, null), DiffLine(LineType.Added, "a", null, 1)),
            parseDiff("diff --git a/a b/a\nnew file mode 100644\n--- /dev/null\n+++ b/a\n@@ -0,0 +1 @@\n+a\n"),
        )
    }

    @Test fun parseDiffCombined() {
        val raw = "diff --cc c.txt\nindex 1,2..0\n--- a/c.txt\n+++ b/c.txt\n@@@ -1,1 -1,1 +1,5 @@@\n++<<<<<<< HEAD\n +ours\n++=======\n+ theirs\n++>>>>>>> other\n"
        val d = parseDiff(raw)
        assertEquals(6, d.size)
        assertEquals(DiffLine(LineType.Added, "<<<<<<< HEAD", null, 1), d[1])
        assertEquals(DiffLine(LineType.Added, "ours", 1, 2), d[2])
        assertEquals(DiffLine(LineType.Added, "theirs", null, 4), d[4])
    }

    @Test fun integration() = runBlocking {
        val root = createTempDirectory("margin").toFile()
        try {
            val dir = File(root, "work").apply { mkdirs() }
            fun sh(vararg args: String, at: File = dir) = runGit(at, args.toList())
            sh("init", "-q", "-b", "main")
            sh("config", "user.name", "Test")
            sh("config", "user.email", "t@example.com")
            sh("config", "commit.gpgsign", "false")
            val repo = GitRepo(dir)
            assertTrue(GitRepo.isRepo(dir))
            assertFalse(GitRepo.isRepo(root))

            // empty repo: status, log, stage + unstage with an unborn HEAD
            File(dir, "a.txt").writeText("one\ntwo\n")
            assertEquals(listOf(FileChange("a.txt", ChangeKind.Untracked, false)), repo.status().changes)
            assertEquals(listOf(DiffLine(LineType.Added, "one", null, 1), DiffLine(LineType.Added, "two", null, 2)),
                repo.diff(FileChange("a.txt", ChangeKind.Untracked, false)))
            assertTrue(repo.log().isEmpty())
            repo.stage(listOf("a.txt"))
            assertEquals(listOf(FileChange("a.txt", ChangeKind.Added, true)), repo.status().changes)
            repo.unstage(listOf("a.txt"))
            assertEquals(ChangeKind.Untracked, repo.status().changes.single().kind)

            repo.stage(listOf("a.txt"))
            repo.commit("first")
            val log = repo.log()
            assertEquals("first", log.single().subject)
            assertEquals("Test", log.single().author)

            // modified (staged + unstaged), untracked, deleted
            File(dir, "a.txt").writeText("one\nTWO\n")
            repo.stage(listOf("a.txt"))
            File(dir, "a.txt").writeText("one\nTWO\nthree\n")
            File(dir, "new file.txt").writeText("x\n")
            val st = repo.status()
            assertEquals("main", st.branch)
            assertNull(st.upstream)
            assertEquals(
                setOf(
                    FileChange("a.txt", ChangeKind.Modified, true),
                    FileChange("a.txt", ChangeKind.Modified, false),
                    FileChange("new file.txt", ChangeKind.Untracked, false),
                ),
                st.changes.toSet(),
            )
            val staged = repo.diff(FileChange("a.txt", ChangeKind.Modified, true))
            assertTrue(DiffLine(LineType.Removed, "two", 2, null) in staged)
            assertTrue(DiffLine(LineType.Added, "TWO", null, 2) in staged)
            val unstaged = repo.diff(FileChange("a.txt", ChangeKind.Modified, false))
            assertEquals(listOf(DiffLine(LineType.Added, "three", null, 3)), unstaged.filter { it.type == LineType.Added })
            repo.stage(listOf("a.txt", "new file.txt"))
            repo.commit("second")

            // staged rename
            sh("mv", "new file.txt", "renamed.txt")
            val rename = repo.status().changes.single()
            assertEquals(FileChange("renamed.txt", ChangeKind.Renamed, true, oldPath = "new file.txt"), rename)
            assertTrue(repo.diff(rename).none { it.type == LineType.Added })
            repo.unstage(listOfNotNull(rename.oldPath, rename.path))
            repo.stage(listOf("new file.txt", "renamed.txt"))
            repo.commit("rename")

            // branches + checkout
            repo.createBranch("feature")
            assertEquals("feature", repo.status().branch)
            repo.checkout(Branch("main", isRemote = false, isCurrent = false))
            assertEquals(
                listOf(Branch("feature", false, false), Branch("main", false, true)),
                repo.branches(),
            )

            // remote: push -u without upstream, then track a remote-only branch
            val remote = File(root, "remote.git")
            sh("init", "-q", "--bare", "-b", "main", remote.path, at = root)
            sh("remote", "add", "origin", remote.path)
            repo.push()
            assertEquals("origin/main", repo.status().upstream)
            sh("push", "-q", "origin", "feature:topic")
            repo.fetch()
            assertTrue(Branch("origin/topic", isRemote = true, isCurrent = false) in repo.branches())
            repo.checkout(Branch("origin/topic", isRemote = true, isCurrent = false))
            assertEquals("topic", repo.status().branch)
            assertEquals("origin/topic", repo.status().upstream)
            repo.pull()

            // commit diffs: root commit, rename, binary, merge (diffed against the first parent)
            val (renameC, secondC, firstC) = repo.log().map { it.hash }
            assertEquals(listOf(FileChange("a.txt", ChangeKind.Added, false)), repo.commitFiles(firstC))
            assertEquals(listOf(DiffLine(LineType.Added, "one", null, 1), DiffLine(LineType.Added, "two", null, 2)),
                repo.commitDiff(firstC, FileChange("a.txt", ChangeKind.Added, false)).drop(1))
            assertEquals(
                setOf(FileChange("a.txt", ChangeKind.Modified, false), FileChange("new file.txt", ChangeKind.Added, false)),
                repo.commitFiles(secondC).toSet(),
            )
            assertTrue(DiffLine(LineType.Removed, "two", 2, null) in repo.commitDiff(secondC, FileChange("a.txt", ChangeKind.Modified, false)))
            val ren = repo.commitFiles(renameC).single()
            assertEquals(FileChange("renamed.txt", ChangeKind.Renamed, false, oldPath = "new file.txt"), ren)
            assertTrue(repo.commitDiff(renameC, ren).isEmpty()) // pure rename, no hunks
            sh("switch", "-q", "-c", "side")
            File(dir, "bin.dat").writeBytes(byteArrayOf(0, 1, 2))
            repo.stage(listOf("bin.dat")); repo.commit("binary")
            sh("switch", "-q", "main")
            File(dir, "a.txt").writeText("zero\none\nTWO\n")
            repo.stage(listOf("a.txt")); repo.commit("main side")
            sh("merge", "-q", "--no-edit", "side")
            val merge = repo.log().first().hash
            assertEquals(listOf(FileChange("bin.dat", ChangeKind.Added, false)), repo.commitFiles(merge))
            assertTrue(repo.commitDiff(merge, FileChange("bin.dat", ChangeKind.Added, false)).isEmpty())

            val e = assertFailsWith<GitException> { repo.checkout(Branch("nope", false, false)) }
            assertTrue(e.message!!.isNotBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rollback() = runBlocking {
        val root = createTempDirectory("margin-rb").toFile()
        try {
            val dir = File(root, "work").apply { mkdirs() }
            val trashed = mutableListOf<String>()
            val trashDir = File(root, "trash").apply { mkdirs() }
            val repo = GitRepo(dir) { f -> trashed += f.relativeTo(dir).path; f.renameTo(File(trashDir, f.name)) }
            fun sh(vararg args: String) = runGit(dir, args.toList())
            fun write(name: String, text: String) = File(dir, name).apply { parentFile.mkdirs() }.writeText(text)
            fun read(name: String) = File(dir, name).readText()
            sh("init", "-q", "-b", "main")
            sh("config", "user.name", "Test")
            sh("config", "user.email", "t@example.com")
            sh("config", "commit.gpgsign", "false")

            // no commits yet: a staged-added file is unstaged and trashed
            write("first.txt", "1\n")
            repo.stage(listOf("first.txt"))
            repo.rollback(repo.status().changes)
            assertTrue(repo.status().changes.isEmpty())
            assertEquals(listOf("first.txt"), trashed)
            trashed.clear()

            for (f in listOf("mod.txt", "staged.txt", "partial.txt", "gone.txt", "old.txt", "keep.txt", "*.txt")) write(f, "$f\n")
            sh("add", "-A"); sh("commit", "-q", "-m", "base")
            write("mod.txt", "edited\n")                                          // modified
            write("staged.txt", "edited\n"); sh("add", "staged.txt")             // staged-modified
            write("partial.txt", "a\n"); sh("add", "partial.txt"); write("partial.txt", "b\n") // partially staged
            File(dir, "gone.txt").delete()                                        // deleted
            write("new.txt", "new\n"); sh("add", "new.txt")                       // staged-added
            sh("mv", "old.txt", "moved.txt")                                       // renamed
            write("dir/untracked.txt", "u\n")                                     // untracked
            write("keep.txt", "keep me\n")                                        // not rolled back
            write("*.txt", "star\n")                                              // literal pathspec: must not widen to every .txt

            val all = repo.status().changes
            repo.rollback(all.filter { it.path != "keep.txt" })
            assertEquals(listOf(FileChange("keep.txt", ChangeKind.Modified, false)), repo.status().changes)
            for (f in listOf("mod.txt", "staged.txt", "partial.txt", "gone.txt", "old.txt", "*.txt")) assertEquals("$f\n", read(f))
            assertEquals("keep me\n", read("keep.txt"))
            assertEquals(setOf("new.txt", "moved.txt", "dir/untracked.txt"), trashed.toSet())
            for (f in listOf("new.txt", "moved.txt", "dir/untracked.txt")) assertFalse(File(dir, f).exists())
            assertEquals("new\n", File(trashDir, "new.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun wholeFileContext() = runBlocking {
        val dir = createTempDirectory("margin").toFile()
        try {
            fun sh(vararg args: String) = runGit(dir, args.toList())
            sh("init", "-q", "-b", "main")
            val f = File(dir, "f.txt")
            f.writeText((1..300_000).joinToString("\n", postfix = "\n")) // longer than 2 x -U100000
            sh("add", "f.txt")
            sh("-c", "user.name=T", "-c", "user.email=t@e", "-c", "commit.gpgsign=false", "commit", "-qm", "a")
            f.writeText(f.readText().replace("\n150000\n", "\nX\n"))
            val repo = GitRepo(dir)
            val change = FileChange("f.txt", ChangeKind.Modified, staged = false)
            assertEquals(1 + 2 + 2 * 3, repo.diff(change).size) // hunk, -/+, 3 lines of context each side
            val whole = repo.diff(change, GitRepo.WHOLE_FILE)
            assertEquals(1 + 300_001, whole.size)
            assertEquals(DiffLine(LineType.Context, "1", 1, 1), whole[1])
            assertEquals(DiffLine(LineType.Context, "300000", 300_000, 300_000), whole.last())
            sh("-c", "user.name=T", "-c", "user.email=t@e", "-c", "commit.gpgsign=false", "commit", "-qam", "b")
            val head = sh("rev-parse", "HEAD").trim()
            assertEquals(1 + 300_001, repo.commitDiff(head, change, GitRepo.WHOLE_FILE).size)
            assertEquals(9, repo.commitDiff(head, change).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun markdownPreviewReadsTheNewSide() = runBlocking {
        val dir = createTempDirectory("margin-md").toFile()
        try {
            fun sh(vararg args: String) = runGit(dir, args.toList())
            fun commit(msg: String) = sh("-c", "user.name=T", "-c", "user.email=t@e", "-c", "commit.gpgsign=false", "commit", "-qm", msg)
            sh("init", "-q", "-b", "main")
            File(dir, "a.md").writeText("# v1\n"); File(dir, "gone.md").writeText("# old\n")
            sh("add", "."); commit("one")
            val repo = GitRepo(dir)
            File(dir, "a.md").writeText("# staged\n"); sh("add", "a.md")
            File(dir, "a.md").writeText("# worktree\n")
            assertEquals("# worktree\n", repo.fileText(FileChange("a.md", ChangeKind.Modified, staged = false)))
            assertEquals("# staged\n", repo.fileText(FileChange("a.md", ChangeKind.Modified, staged = true)))
            File(dir, "gone.md").delete()
            assertEquals("# old\n", repo.fileText(FileChange("gone.md", ChangeKind.Deleted, staged = false)))
            sh("add", "-A"); commit("two")
            val head = sh("rev-parse", "HEAD").trim()
            assertEquals("# worktree\n", repo.commitFileText(head, FileChange("a.md", ChangeKind.Modified, staged = false)))
            assertEquals("# old\n", repo.commitFileText(head, FileChange("gone.md", ChangeKind.Deleted, staged = false)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun parseWorktreesSkipsBareAndMarksMain() {
        val raw = listOf(
            "worktree /r/main", "HEAD aaa", "branch refs/heads/main", "",
            "worktree /r/feat", "HEAD bbb", "branch refs/heads/feature/x", "locked", "",
            "worktree /r/det", "HEAD ccc", "detached", "",
        ).joinToString(nul, postfix = nul)
        assertEquals(
            listOf(
                Worktree(File("/r/main"), "main", true, head = "aaa"),
                Worktree(File("/r/feat"), "feature/x", false, head = "bbb"),
                Worktree(File("/r/det"), null, false, head = "ccc"),
            ),
            parseWorktrees(raw),
        )
        assertEquals(listOf(Worktree(File("/r/w"), "x", false)), parseWorktrees("worktree /r${nul}bare$nul${nul}worktree /r/w${nul}branch refs/heads/x$nul$nul"))
    }

    @Test fun parseCreatedFromNamedOrResolvesHead() {
        fun log(vararg e: Pair<Long, String>) = e.joinToString("\n") { (t, msg) -> "x@{$t}$nul$msg" }
        val head = log(300L to "checkout: moving from develop to main", 200L to "commit: y", 100L to "checkout: moving from main to develop")
        assertEquals("origin/develop", parseCreatedFrom(log(500L to "commit: z", 50L to "branch: Created from origin/develop"), head, "main"))
        assertEquals("develop", parseCreatedFrom(log(250L to "branch: Created from HEAD"), head, "main")) // made at 250, while on develop
        assertEquals("main", parseCreatedFrom(log(300L to "branch: Created from HEAD"), head, "main"))
        assertEquals("main", parseCreatedFrom(log(50L to "branch: Created from HEAD"), head, "x")) // before any switch: the first one left main
        assertEquals("trunk", parseCreatedFrom(log(50L to "branch: Created from HEAD"), log(10L to "commit (initial): a"), "trunk")) // never switched
        assertNull(parseCreatedFrom(log(10L to "clone: from git@x:y.git"), head, "main"))
        assertNull(parseCreatedFrom("", head, "main"))
    }

    @Test fun listsAndRemovesWorktrees() = runBlocking {
        val root = createTempDirectory("margin-wt").toFile().canonicalFile
        try {
            val dir = File(root, "repo").apply { mkdirs() }
            fun sh(vararg args: String, at: File = dir) = runGit(at, args.toList())
            sh("init", "-q", "-b", "main")
            sh("-c", "user.name=T", "-c", "user.email=t@e", "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "one")
            val wtDir = File(root, "repo-feat")
            sh("worktree", "add", "-q", "-b", "feat", wtDir.path)
            val linked = GitRepo(wtDir)
            assertTrue(GitRepo.isRepo(wtDir))
            val wts = linked.worktrees()
            assertEquals(listOf(dir to "main", wtDir to "feat"), wts.map { it.dir to it.branch })
            assertEquals("main", wts[1].from) // "Created from HEAD", resolved through the main worktree's checkout
            assertNull(wts[1].ahead) // no develop branch here

            sh("branch", "develop")
            sh("-c", "user.name=T", "-c", "user.email=t@e", "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "two", at = wtDir)
            val counted = linked.worktrees()
            assertEquals(1 to 0, counted[1].ahead to counted[1].behind)

            File(wtDir, "new.txt").writeText("x")
            assertFailsWith<GitException> { GitRepo(dir).removeWorktree(wts[1]) } // untracked file: git refuses
            assertTrue(wtDir.isDirectory)
            File(wtDir, "new.txt").delete()
            GitRepo(dir).removeWorktree(wts[1])
            assertFalse(wtDir.exists())
            assertEquals(listOf(dir), GitRepo(dir).worktrees().map { it.dir })
        } finally {
            root.deleteRecursively()
        }
    }
}

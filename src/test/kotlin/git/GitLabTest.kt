package margin.git

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitLabTest {

    /** Shaped like `glab mr list -F json` output (glab 1.123, GitLab self-managed); all names are made up. */
    private val listFixture = """
        [{"id":501,"iid":1204,"target_branch":"release/reader-mode","source_branch":"bugfix/rd-118-reader-light-only",
          "project_id":7,"title":"[bugfix][RD-118] Keep reader \"screens\" light-only — café","state":"opened",
          "created_at":"2026-10-26T10:14:42.689Z","updated_at":"2026-10-26T10:15:17.311Z","upvotes":0,
          "author":{"id":42,"username":"sam.lee","name":"Sam Lee","avatar_url":null},
          "labels":[],"draft":false,"work_in_progress":null,"head_pipeline":null,"time_stats":{"time_estimate":0,"human_time_estimate":null},
          "web_url":"https://gitlab.example.com/acme/notes-app/-/merge_requests/1204"},
         {"iid":7,"target_branch":"main","source_branch":"feat/x","title":"Draft: WIP","draft":true,
          "author":{"username":"only.user"},"web_url":"https://gitlab.example.com/g/p/-/merge_requests/7",
          "updated_at":"1970-01-01T00:01:40Z","ratio":-1.5e2}]
    """.trimIndent()

    @Test fun parsesMergeRequestList() {
        val (a, b) = parseMergeRequests(listFixture)
        assertEquals(
            MergeRequest(
                1204, "[bugfix][RD-118] Keep reader \"screens\" light-only — café", "bugfix/rd-118-reader-light-only",
                "release/reader-mode", "Sam Lee", false,
                "https://gitlab.example.com/acme/notes-app/-/merge_requests/1204", 1793009717,
            ),
            a,
        )
        assertEquals(MergeRequest(7, "Draft: WIP", "feat/x", "main", "only.user", true, "https://gitlab.example.com/g/p/-/merge_requests/7", 100), b)
        assertEquals(emptyList(), parseMergeRequests(" [ ] "))
    }

    @Test fun parsesJsonPrimitives() {
        assertEquals(mapOf("a" to listOf(1L, -2.5, true, false, null), "b" to "x\ny/\\"), parseJson("""{"a":[1,-2.5,true,false,null],"b":"x\ny\/\\"}"""))
    }

    @Test fun createArgvIsExact() {
        assertEquals(
            listOf(
                "mr", "create", "--source-branch", "feat/login", "--target-branch", "main",
                "--title", "Add login", "--description", "Line 1\nLine 2", "--yes", "--draft",
            ),
            createArgs("feat/login", "main", "Add login", "Line 1\nLine 2", draft = true),
        )
        assertEquals("--yes", createArgs("a", "b", "t", "", draft = false).last())
    }

    @Test fun remoteHosts() {
        assertEquals("gitlab.corp.example", remoteHost("https://gitlab.corp.example/acme/notes-app.git"))
        assertEquals("gitlab.com", remoteHost("https://user:tok@GitLab.com/a/b.git"))
        assertEquals("gitlab.corp.io", remoteHost("ssh://git@gitlab.corp.io:2222/a/b.git"))
        assertEquals("gitlab.com", remoteHost("git@gitlab.com:a/b.git"))
        assertNull(remoteHost("../origin.git"))
        assertNull(remoteHost("/srv/repos/x.git"))
    }

    @Test fun findsBinaryOnFallbackPath() {
        val dir = createTempDirectory("bin").toFile()
        val tool = File(dir, "margin-fake-tool").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        assertEquals(tool, findBinary("margin-fake-tool", "/nonexistent:${dir.path}"))
        assertNull(findBinary("margin-fake-tool", null))
    }

    @Test fun createPushesBranchThenReturnsUrl() = runBlocking {
        val root = createTempDirectory("mr").toFile()
        val remote = File(root, "origin.git").also { runGit(root, listOf("init", "-q", "--bare", it.path)) }
        val work = File(root, "work").also { it.mkdirs() }
        listOf(listOf("init", "-q", "-b", "feat/x"), listOf("remote", "add", "origin", remote.path),
            listOf("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "first")).forEach { runGit(work, it) }
        val argv = File(root, "argv")
        val fake = File(root, "glab").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > '${argv.path}'\necho 'Creating merge request'\necho ' https://gitlab.example.com/g/p/-/merge_requests/9'\n")
            setExecutable(true)
        }
        val url = GitLab(work, fake).create("feat/x", "main", "T", "", draft = false)
        assertEquals("https://gitlab.example.com/g/p/-/merge_requests/9", url)
        assertEquals(createArgs("feat/x", "main", "T", "", false), argv.readLines())
        assertTrue(runGit(remote, listOf("branch", "--list", "feat/x")).isNotBlank()) // pushed
        assertEquals("origin/feat/x", runGit(work, listOf("rev-parse", "--abbrev-ref", "@{u}")).trim()) // upstream set
    }

    @Test fun signInPipesTokenOnStdinNotArgv() = runBlocking {
        val dir = createTempDirectory("auth").toFile()
        val argv = File(dir, "argv")
        val stdin = File(dir, "stdin")
        val fake = File(dir, "glab").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > '${argv.path}'\ncat > '${stdin.path}'\n")
            setExecutable(true)
        }
        GitLab(dir, fake).signIn("gitlab.example.com", "  glpat-secret\n")
        assertEquals(listOf("auth", "login", "--hostname", "gitlab.example.com", "--stdin"), argv.readLines())
        assertEquals("glpat-secret", stdin.readText())
    }

    @Test fun normalizesHostsAndRejectsFlags() {
        assertEquals("gitlab.corp.io:8443", normalizeHost(" https://GitLab.corp.io:8443/ "))
        assertEquals("gitlab.corp.example", normalizeHost("gitlab.corp.example"))
        assertNull(normalizeHost("--token=x"))
        assertNull(normalizeHost("host/path"))
        assertNull(normalizeHost(""))
    }

    @Test fun availabilityExplainsWhatIsMissing() = runBlocking {
        val dir = createTempDirectory("glab").toFile()
        runGit(dir, listOf("init", "-q"))
        assertEquals(ForgeStatus.NotInstalled, GitLab(dir, bin = null).available())
        // auth status fails the way real glab does with no token
        val fake = File(dir, "glab").apply { writeText("#!/bin/sh\necho 'No token found' >&2\nexit 1\n"); setExecutable(true) }
        assertEquals(ForgeStatus.NoOrigin, GitLab(dir, fake).available())
        runGit(dir, listOf("remote", "add", "origin", "git@github.com:a/b.git"))
        assertEquals(ForgeStatus.Unsupported("github.com"), GitLab(dir, fake).available())
        runGit(dir, listOf("remote", "set-url", "origin", "https://gitlab.example.com/a/b.git"))
        assertEquals(ForgeStatus.NotAuthenticated("gitlab.example.com"), GitLab(dir, fake).available())
        fake.writeText("#!/bin/sh\nexit 0\n")
        assertTrue(GitLab(dir, fake).available() is ForgeStatus.Ready)
    }

    @Test fun offlineStillCountsAsSignedIn() = runBlocking {
        val dir = createTempDirectory("glab").toFile()
        val fake = File(dir, "glab").apply { writeText(""); setExecutable(true) }
        // VPN off: glab has a token but the API call fails
        fake.writeText("#!/bin/sh\necho 'x h: API call failed: context deadline exceeded' >&2\necho 'Token found in operating system keyring' >&2\nexit 1\n")
        assertTrue(GitLab(dir, fake).signedIn("h"))
        fake.writeText("#!/bin/sh\necho 'x h: API call failed: 401 {message: 401 Unauthorized}' >&2\nexit 1\n")
        assertFalse(GitLab(dir, fake).signedIn("h"))
        // never signed in to this host (real glab 1.123 wording)
        fake.writeText("#!/bin/sh\necho '  X h has not been authenticated with glab; run glab auth login --hostname h to authenticate.' >&2\nexit 1\n")
        assertFalse(GitLab(dir, fake).signedIn("h"))
        // a port containing 401 is not an auth failure
        fake.writeText("#!/bin/sh\necho 'x gitlab.corp:4010: API call failed: context deadline exceeded' >&2\nexit 1\n")
        assertTrue(GitLab(dir, fake).signedIn("gitlab.corp:4010"))
        fake.writeText("#!/bin/sh\nsleep 30\n") // server hangs: give up after the short timeout and let the user in
        val start = System.currentTimeMillis()
        assertTrue(GitLab(dir, fake).signedIn("h"))
        assertTrue(System.currentTimeMillis() - start < 10_000)
    }
}

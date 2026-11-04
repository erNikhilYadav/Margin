package margin.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class MergeRequest(
    val iid: Int,
    val title: String,
    val source: String,
    val target: String,
    val author: String,
    val draft: Boolean,
    val webUrl: String,
    val updatedEpoch: Long,
    val state: String = "opened", // opened, merged, closed, locked
)

/** Why reviews can't be shown, so the UI can name the fix. */
sealed interface ForgeStatus {
    data object Ready : ForgeStatus
    data object NotInstalled : ForgeStatus
    data object NoOrigin : ForgeStatus
    data class Unsupported(val host: String) : ForgeStatus
    data class NotAuthenticated(val host: String) : ForgeStatus
}

/** What a forge and its CLI are called, for UI text. */
data class Terms(
    val site: String, val cli: String, val noun: String, val short: String, val sigil: Char,
    val scopes: String, val tokenHint: String, val pipeline: String, val autoMerge: String,
) {
    val nounCap get() = noun.replaceFirstChar { it.uppercase() }
    fun ref(n: Int) = "$sigil$n"
}

val GitLabTerms = Terms("GitLab", "glab", "merge request", "MR", '!', "the api scope", "glpat-…", "Pipeline", "Merge when pipeline succeeds")
val GitHubTerms = Terms("GitHub", "gh", "pull request", "PR", '#', "the repo, read:org and gist scopes", "ghp_…", "Checks", "Enable auto-merge")

/**
 * A code host's review API: GitLab merge requests or GitHub pull requests. Each goes through its own CLI,
 * which owns auth, so tokens live in that CLI's keychain entry, never in Margin.
 */
interface Forge {
    val dir: File
    val terms: Terms
    val canUnapprove: Boolean get() = true
    val canDelete: Boolean get() = true

    fun tokenUrl(host: String): String

    suspend fun available(): ForgeStatus
    suspend fun signedIn(host: String): Boolean
    suspend fun signIn(host: String, token: String)
    suspend fun signOut(host: String)

    suspend fun defaultBranch(): String
    suspend fun forBranch(branch: String): MergeRequest?
    /** Pushes [source] to origin (setting upstream), then opens the review. Returns its web URL. */
    suspend fun create(source: String, target: String, title: String, description: String, draft: Boolean): String
    suspend fun listMergeRequests(state: MrStateFilter, scope: MrScope, search: String, me: String?): List<MergeRequest>
    suspend fun currentUsername(): String

    suspend fun mergeRequest(iid: Int): MrInfo
    suspend fun approvals(iid: Int): Approvals
    suspend fun discussions(iid: Int): List<Discussion>
    suspend fun commits(iid: Int): List<MrCommit>
    suspend fun diffs(iid: Int): List<MrDiffFile>

    suspend fun approve(iid: Int, sha: String?)
    suspend fun unapprove(iid: Int)
    suspend fun merge(iid: Int, o: MergeOptions, sha: String?)
    suspend fun close(iid: Int)
    suspend fun reopen(iid: Int)
    suspend fun setDraft(iid: Int, title: String, draft: Boolean)
    suspend fun deleteMergeRequest(iid: Int)
    suspend fun comment(iid: Int, body: String)
    suspend fun reply(iid: Int, discussion: String, body: String)
    suspend fun resolve(iid: Int, discussion: String, resolved: Boolean)
    suspend fun commentOnLine(iid: Int, refs: DiffRefs, file: FileChange, line: DiffLine, body: String)
}

/** GitHub when origin is github.com or a host gh has config for (GitHub Enterprise); GitLab otherwise. */
suspend fun forgeFor(dir: File, ghHosts: () -> List<String> = { ghConfigHosts() }): Forge {
    val host = originHost(dir)
    return if (host != null && (host == "github.com" || host in ghHosts())) GitHub(dir) else GitLab(dir)
}

internal suspend fun originUrl(dir: File): String = withContext(Dispatchers.IO) {
    try { runGit(dir, listOf("remote", "get-url", "origin")).trim() } catch (_: GitException) { "" }
}

suspend fun originHost(dir: File): String? = remoteHost(originUrl(dir))

/** Host of https://host/x.git, ssh://git@host:22/x.git or git@host:x.git; null for local paths. */
internal fun remoteHost(url: String): String? {
    val m = Regex("""^[a-z+]+://(?:[^@/]+@)?([^:/]+)""").find(url) ?: Regex("""^[^@/]+@([^:/]+):""").find(url)
    return m?.groupValues?.get(1)?.lowercase()
}

/** (owner, repo) from any origin URL form; null unless both are plain names, so they can't steer an API path. */
internal fun remoteRepo(url: String): Pair<String, String>? {
    val path = Regex("""^[a-z+]+://[^/]+/(.*)$""").find(url)?.groupValues?.get(1)
        ?: Regex("""^[^@/]+@[^:/]+:(.*)$""").find(url)?.groupValues?.get(1) ?: return null
    val m = Regex("""^/?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$""").find(path) ?: return null
    val (owner, repo) = m.destructured
    return (owner to repo).takeIf { owner.any { it != '.' } && repo.any { it != '.' } }
}

/** Hosts gh has config for: the top-level keys of its hosts.yml. */
fun ghConfigHosts(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): List<String> =
    listOfNotNull(env["GH_CONFIG_DIR"], env["XDG_CONFIG_HOME"]?.let { "$it/gh" }, "$home/.config/gh")
        .map { File(it, "hosts.yml") }.firstOrNull { it.isFile }?.let { topLevelKeys(it.readText()) }.orEmpty()

/** shortcut: line-based, not a YAML parser; enough for the host map gh writes. */
internal fun topLevelKeys(yaml: String): List<String> =
    yaml.lines().filter { it.isNotBlank() && !it.first().isWhitespace() && !it.startsWith("#") && it.trimEnd().endsWith(":") }
        .map { it.trimEnd().removeSuffix(":").trim('"', '\'').lowercase() }

/** "https://Host:8443/" → "host:8443"; null unless it's a bare hostname[:port], so it can't smuggle a flag into a CLI's argv. */
fun normalizeHost(input: String): String? =
    input.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').lowercase()
        .takeIf { Regex("""[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\d{1,5})?""").matches(it) }

/** `auth status` output meaning "you need to sign in", as opposed to a network failure. Covers glab and gh wording. */
internal fun authRejected(output: String) =
    listOf("No token found", "has not been authenticated", "401 Unauthorized", "is invalid").any { it in output } ||
        "not logged in" in output.lowercase()

/** Absolute path first: a Finder-launched .app gets only /usr/bin:/bin:/usr/sbin:/sbin on PATH. */
internal fun findBinary(name: String, path: String? = System.getenv("PATH")): File? =
    (listOf("/opt/homebrew/bin", "/usr/local/bin") + path.orEmpty().split(':').filter { it.isNotEmpty() })
        .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }

/** One forge CLI (glab or gh), run in [dir]. */
class Cli(private val name: String, val bin: File?, private val dir: File) {

    suspend fun run(vararg args: String, input: String? = null, timeoutS: Long = TIMEOUT_S): String =
        withContext(Dispatchers.IO) { exec(args.toList(), input, timeoutS) }

    /**
     * False only when the CLI has no token or the server rejects it. Offline (VPN off, server down, slow) counts as
     * signed in: signing in can't work then anyway, and git must never wait on the forge.
     */
    suspend fun signedIn(host: String): Boolean =
        bin != null && runCatching { run("auth", "status", "--hostname", host, timeoutS = 6) }
            .fold({ true }, { e -> !authRejected(e.message.orEmpty()) })

    /** Blocking. Both pipes are drained on side threads so the timeout can't be blocked by a full pipe. */
    private fun exec(args: List<String>, input: String?, timeoutS: Long): String {
        val bin = bin ?: throw GitException("$name is not installed")
        val process = try {
            ProcessBuilder(listOf(bin.path) + args).directory(dir).apply {
                // The CLI shells out to git; a Finder-launched app's PATH lacks Homebrew, so put the CLI's dir first.
                environment() += mapOf(
                    "GIT_TERMINAL_PROMPT" to "0", "NO_COLOR" to "1", "NO_PROMPT" to "1",
                    "GH_PROMPT_DISABLED" to "1", "GH_NO_UPDATE_NOTIFIER" to "1",
                    "PATH" to "${bin.parent}:${System.getenv("PATH") ?: "/usr/bin:/bin"}",
                )
            }.start()
        } catch (e: IOException) {
            throw GitException("Could not run $name: ${e.message}")
        }
        process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray()) } }
        var out = ""
        var err = ""
        val readers = listOf(thread { out = process.inputStream.bufferedReader().readText() }, thread { err = process.errorStream.bufferedReader().readText() })
        if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GitException("$name ${args.take(2).joinToString(" ")} timed out after ${timeoutS}s")
        }
        readers.forEach { it.join() }
        if (process.exitValue() != 0) throw GitException(err.trim().ifEmpty { "$name ${args.take(2).joinToString(" ")} failed" })
        return out
    }

    private companion object { const val TIMEOUT_S = 30L }
}

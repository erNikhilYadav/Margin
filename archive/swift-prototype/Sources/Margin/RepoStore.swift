import Foundation
import Observation
import GitKit

/// The single source of truth the UI binds to. Every action refreshes state and
/// reports failures through `error` instead of throwing.
@MainActor @Observable
final class RepoStore {
    private(set) var url: URL?
    private(set) var status = RepoStatus()
    private(set) var branches: [Branch] = []
    private(set) var commits: [Commit] = []
    /// Non-nil while a long operation runs, e.g. "Pushing…".
    private(set) var activity: String?
    var error: String?
    private(set) var recentRepos: [URL] = []

    var repoName: String { url?.lastPathComponent ?? "" }

    init() {}

    func open(_ url: URL) async {}
    func close() {}
    func refresh() async {}

    func diff(for change: FileChange) async -> String { "" }
    func stage(_ changes: [FileChange]) async {}
    func unstage(_ changes: [FileChange]) async {}
    /// Destructive: the UI must confirm first.
    func discard(_ changes: [FileChange]) async {}
    /// Returns true on success so the UI can clear the message field.
    func commit(message: String) async -> Bool { false }

    func checkout(_ branch: Branch) async {}
    func createBranch(named name: String) async {}

    func fetch() async {}
    func pull() async {}
    func push() async {}
}

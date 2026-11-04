import Foundation

public struct FileChange: Identifiable, Hashable, Sendable {
    public enum Kind: Sendable { case modified, added, deleted, renamed, untracked, conflicted }
    public let path: String
    public let kind: Kind
    /// A file with both staged and unstaged edits appears twice: once per side.
    public let staged: Bool
    public var id: String { (staged ? "S:" : "W:") + path }

    public init(path: String, kind: Kind, staged: Bool) {
        self.path = path; self.kind = kind; self.staged = staged
    }
}

public struct Branch: Identifiable, Hashable, Sendable {
    public let name: String          // "main" or "origin/main"
    public let isRemote: Bool
    public let isCurrent: Bool
    public var id: String { (isRemote ? "R:" : "L:") + name }

    public init(name: String, isRemote: Bool, isCurrent: Bool) {
        self.name = name; self.isRemote = isRemote; self.isCurrent = isCurrent
    }
}

public struct Commit: Identifiable, Hashable, Sendable {
    public let hash: String
    public let subject: String
    public let author: String
    public let date: Date
    public var id: String { hash }
    public var shortHash: String { String(hash.prefix(7)) }

    public init(hash: String, subject: String, author: String, date: Date) {
        self.hash = hash; self.subject = subject; self.author = author; self.date = date
    }
}

public struct RepoStatus: Hashable, Sendable {
    public var branch: String?       // nil when HEAD is detached
    public var upstream: String?
    public var ahead = 0
    public var behind = 0
    public var changes: [FileChange] = []

    public init() {}
}

public struct GitError: Error, LocalizedError, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

# Margin

A small macOS Git client that lives in the menu bar, with GitHub pull requests and GitLab merge requests built in.

Built with Kotlin and Compose Desktop.

## Features

- **Changes:** stage and unstage, commit (⌘↵), roll back (⌥⌘Z), side-by-side or whole-file diffs, jump between changes (F7 / ⇧F7).
- **History:** browse commits and the files they touched.
- **Branches and worktrees:** switch, create, or check out a remote branch (⌘B); list and remove worktrees.
- **Sync:** fetch (also runs automatically), pull (⇧⌘L), push (⇧⌘P).
- **Reviews:** list, search, read, comment on diff lines, reply to and resolve threads, approve, merge, and create.
  Margin picks the provider from the repo's `origin`: GitHub pull requests through `gh`, GitLab merge requests through `glab`.
- **Menu bar app:** the icon shows or hides the window, even over full-screen apps. Quit with ⌘Q.

## Requirements

- macOS
- JDK 17 (the build pins it with a Gradle toolchain)
- `git`
- Optional, for reviews: [`gh`](https://cli.github.com) for GitHub, [`glab`](https://gitlab.com/gitlab-org/cli) for GitLab

```sh
brew install gh glab
```

## Run

```sh
./gradlew run
```

## Test

```sh
./gradlew test
```

The tests use fake `gh` and `glab` scripts, so they never touch a real server.

## Package

```sh
./gradlew createDistributable   # Margin.app in build/compose/binaries/main/app
./gradlew packageDmg            # Margin DMG in build/compose/binaries/main/dmg
```

macOS packaging rejects a version that starts with 0, so `packageVersion` in `build.gradle.kts` must stay `1.0.0` or higher.

## Install with Homebrew

```sh
brew install --cask erNikhilYadav/margin/margin
```

Margin is not notarized. If macOS blocks it on first launch, run:

```sh
xattr -dr com.apple.quarantine /Applications/Margin.app
```

## Sign-in and privacy

Margin never stores tokens. It talks to GitHub and GitLab only through `gh` and `glab`, which keep your token in the macOS keychain.
If the CLI is already signed in for the repo's host, there is nothing to set up. Otherwise the PRs/MRs tab asks for a personal access token
and passes it to the CLI on stdin, never on the command line.

## Project layout

| Path | What it holds |
|---|---|
| `src/main/kotlin/git` | Git access, plus the `Forge` interface with its `GitHub` and `GitLab` implementations |
| `src/main/kotlin/ui` | Compose Desktop UI |
| `design/DESIGN.md` | Design system: colors, type, spacing, motion, components |
| `packaging` | App icon and its generator |
| `archive/swift-prototype` | An earlier SwiftUI prototype, not maintained |

## License

[MIT](LICENSE). The bundled fonts (Inter, Newsreader, JetBrains Mono) are under the SIL Open Font License 1.1;
see [`src/main/resources/fonts/OFL.txt`](src/main/resources/fonts/OFL.txt).

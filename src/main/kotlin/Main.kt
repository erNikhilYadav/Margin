package margin

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import margin.ui.App
import margin.ui.AppState
import margin.git.ForgeStatus
import margin.ui.ThemeMode
import margin.ui.rememberMrState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import margin.ui.components.Glyph
import margin.ui.components.GlyphIcon
import margin.ui.components.TooltipIconButton
import margin.ui.theme.Sizes
import margin.ui.theme.Spacing
import margin.ui.Tab
import margin.ui.theme.MarginTheme
import java.awt.Desktop
import java.awt.Dimension
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() {
    System.setProperty("apple.awt.UIElement", "true") // menu-bar app: no Dock icon or app menus, so it can open over full-screen apps
    System.setProperty("apple.awt.enableTemplateImages", "true") // the black menu bar icon is tinted for light/dark menu bars
    System.setProperty("apple.awt.application.appearance", "system") // native chrome follows dark mode
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    application {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }
        var visible by remember { mutableStateOf(true) }
        var inMenuBar by remember { mutableStateOf(false) }
        val hide: () -> Unit = { if (inMenuBar) visible = false else exitApplication() } // the icon brings it back; Quit is in ⋯ and ⌘Q
        // Maximized, not sized to the screen by hand: macOS treats an exactly screen-sized window as zoomed, and
        // Compose, still thinking it floating, un-zooms it to the minimum size.
        val windowState = remember { WindowState(WindowPlacement.Maximized, size = DpSize(1200.dp, 780.dp), position = WindowPosition(Alignment.Center)) }
        Window(
            onCloseRequest = hide,
            visible = visible,
            title = "Margin",
            state = windowState,
            onPreviewKeyEvent = { // ⌘⇧] / ⌘⇧[: Mac-style alternates for the F7 / ⇧F7 menu shortcuts
                if (it.type != KeyEventType.KeyDown || !it.isMetaPressed || !it.isShiftPressed || state.repo == null) false
                else when (it.key) {
                    Key.RightBracket -> { state.nextChange(); true }
                    Key.LeftBracket -> { state.previousChange(); true }
                    else -> false
                }
            },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(900, 600)
                window.rootPane.apply {
                    putClientProperty("apple.awt.fullWindowContent", true)
                    putClientProperty("apple.awt.transparentTitleBar", true)
                    putClientProperty("apple.awt.windowTitleVisible", false)
                }
                // Fills the display under the pointer, then comes forward on the active space, full-screen or not.
                fun show() {
                    screenUnderPointer()?.let { windowState.position = WindowPosition(it.x.dp, it.y.dp) } // the display under the pointer
                    windowState.placement = WindowPlacement.Maximized
                    thread(isDaemon = true) { // waits on the AppKit thread; never block the EDT
                        runCatching { AppKit.prepareMenuBarWindow(window.title) }.onFailure { System.err.println("Margin: menu-bar window setup failed: $it") }
                        SwingUtilities.invokeLater {
                            visible = true
                            scope.launch {
                                delay(50) // let the window map before asking macOS to bring the app forward
                                window.toFront()
                                if (Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) Desktop.getDesktop().requestForeground(true)
                            }
                        }
                    }
                }
                // Toggles what the user sees, not focus: open here closes it, and a window left on another space counts as closed.
                inMenuBar = MenuBarIcon.install {
                    if (!visible) show()
                    else thread(isDaemon = true) { // waits on the AppKit thread; never block the EDT
                        val here = runCatching { AppKit.isOnActiveSpace(window.title) }.getOrDefault(true)
                        SwingUtilities.invokeLater { if (here) visible = false else show() }
                    }
                }
                if (!inMenuBar) System.err.println("Margin: no menu bar icon (system tray unsupported); closing the window quits")
                show()
            }
            val hasRepo = state.repo != null
            val mr = rememberMrState(state)
            // Sign-in lives in the MRs/PRs tab, for the account of the open repo's host; no startup gate.
            val signOut: Pair<String, () -> Unit>? = mr.host?.takeIf { mr.status == ForgeStatus.Ready }?.let { "Sign Out of $it" to mr::signOut }
            val signIn: Pair<String, () -> Unit>? = ("Sign In…" to { mr.showing = true }).takeIf { mr.status is ForgeStatus.NotAuthenticated }
            MenuBar {
                Menu("File") {
                    Item("Open Repository…", shortcut = KeyShortcut(Key.O, meta = true), onClick = { state.openWithPicker(window) })
                    Item("Refresh", shortcut = KeyShortcut(Key.R, meta = true), enabled = hasRepo, onClick = state::refresh)
                }
                Menu("Repository") {
                    // The native menu may take ⌘↵ before the window does, so it confirms an open rollback dialog too.
                    Item("Commit", shortcut = KeyShortcut(Key.Enter, meta = true), enabled = hasRepo && !state.inMergeRequests, onClick = {
                        if (state.rollbackTarget != null) state.confirmRollback() else state.commit()
                    })
                    Item(
                        "Rollback…", shortcut = KeyShortcut(Key.Z, meta = true, alt = true),
                        enabled = hasRepo && state.tab == Tab.Changes && state.files.isNotEmpty(), onClick = { state.requestRollback() },
                    )
                    Item("Switch Branch…", shortcut = KeyShortcut(Key.B, meta = true), enabled = hasRepo, onClick = { state.branchMenuOpen = true })
                    Item("Fetch", enabled = hasRepo, onClick = state::fetch)
                    Item("Pull", shortcut = KeyShortcut(Key.L, meta = true, shift = true), enabled = hasRepo, onClick = state::pull)
                    Item("Push", shortcut = KeyShortcut(Key.P, meta = true, shift = true), enabled = hasRepo, onClick = state::push)
                }
                Menu("View") {
                    Item("Previous Change", shortcut = KeyShortcut(Key.F7, shift = true), enabled = hasRepo, onClick = state::previousChange)
                    Item("Next Change", shortcut = KeyShortcut(Key.F7), enabled = hasRepo, onClick = state::nextChange)
                    Separator()
                    Menu("Appearance") {
                        ThemeMode.entries.forEach { mode ->
                            RadioButtonItem(mode.name, selected = state.themeMode == mode, onClick = { state.showTheme(mode) })
                        }
                    }
                }
                Menu("Account") {
                    signOut?.let { (label, action) -> Item(label, onClick = action) }
                    signIn?.let { (label, action) -> Item(label, onClick = action) }
                    if (signOut == null && signIn == null) Item("No account for this repository", enabled = false, onClick = {})
                }
            }
            MarginTheme(darkTheme = when (state.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }) {
                Box {
                    App(state, mr, onOpen = { state.openWithPicker(window) }, menu = listOfNotNull(
                        "Open Repository…" to { state.openWithPicker(window) },
                        signIn, signOut,
                        "Quit Margin" to ::exitApplication,
                    ))
                    // The window has no title-bar buttons in menu-bar mode; this is the only close, on every screen.
                    // aligned on a wrapper: TooltipIconButton puts its modifier inside the tooltip area, out of this Box's reach
                    Box(Modifier.align(Alignment.TopEnd).padding(top = (Sizes.toolbarHeight - Sizes.iconButton) / 2, end = Spacing.md)) {
                        TooltipIconButton("Hide Margin", hide) { GlyphIcon(Glyph.Close) }
                    }
                }
            }
        }
    }
}

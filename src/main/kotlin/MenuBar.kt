package margin

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Margin as a menu-bar app: the branch icon in the top bar toggles the window. Needs `apple.awt.UIElement`
 * (no Dock icon), since macOS only lets such apps put a window over another app's full-screen space.
 */
object MenuBarIcon {
    /** False when the system has no tray (not macOS, headless). [onClick] runs on the EDT. */
    fun install(onClick: () -> Unit): Boolean {
        if (!SystemTray.isSupported()) return false
        val icon = TrayIcon(BaseMultiResolutionImage(branch(1), branch(2)), "Margin").apply {
            addMouseListener(object : MouseAdapter() {
                // no PopupMenu: with one, macOS AWT opens it on every click instead of reporting the click.
                // On press, like native menu-bar items: AWT drops the click if the pointer moves at all before release.
                override fun mousePressed(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1 && !e.isPopupTrigger) onClick() }
            })
        }
        SystemTray.getSystemTray().add(icon)
        return true
    }

    /** Glyph.Branch on its 16-unit grid, 18pt, black on clear: a template image the menu bar tints for light/dark. */
    private fun branch(scale: Int): BufferedImage {
        val px = 18 * scale
        val u = px / 16f
        return BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().run {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                color = Color.BLACK
                stroke = BasicStroke(1.5f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                fun circle(x: Float, y: Float) = draw(Ellipse2D.Float((x - 1.5f) * u, (y - 1.5f) * u, 3f * u, 3f * u))
                circle(4.5f, 3.5f); circle(4.5f, 12.5f); circle(11.5f, 5f)
                draw(Line2D.Float(4.5f * u, 5f * u, 4.5f * u, 11f * u))
                draw(CubicCurve2D.Float(11.5f * u, 6.5f * u, 11.5f * u, 9.5f * u, 4.5f * u, 8f * u, 4.5f * u, 11f * u))
                dispose()
            }
        }
    }
}

/** The usable area of the display under the pointer: minus the menu bar and Dock. Null when headless. */
fun screenUnderPointer(): Rectangle? {
    val gc = MouseInfo.getPointerInfo()?.device?.defaultConfiguration ?: return null
    val b = gc.bounds
    val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
}

/** AppKit through JNA. AppKit lives on the main thread (thread 0, not the EDT), so calls are queued there. macOS only. */
object AppKit {
    fun interface Work : Callback { fun invoke(context: Pointer?) }

    @Suppress("FunctionName")
    private interface Dispatch : Library { fun dispatch_async_f(queue: Pointer, context: Pointer?, work: Work) }

    @Suppress("FunctionName")
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, sel: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, sel: Pointer, arg: Long): Pointer?
    }

    private val pending = mutableListOf<Work>() // libdispatch keeps only the raw pointer; a few calls, never released

    /** Async plus a timeout, because AppKit sometimes waits on the EDT: call this off the EDT. */
    private fun <T> onMainThread(block: () -> T): T {
        check(System.getProperty("os.name").startsWith("Mac")) { "not macOS" }
        val mainQueue = NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q")
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        val work = Work { result = runCatching(block); done.countDown() }
        synchronized(pending) { pending += work }
        Native.load("System", Dispatch::class.java).dispatch_async_f(mainQueue, null, work)
        check(done.await(3, TimeUnit.SECONDS)) { "the main thread didn't run it" }
        return result!!.getOrThrow()
    }

    /**
     * Readies the window titled [title] for the menu-bar mode: it opens on whichever space is active, including over
     * another app's full-screen window, and its close/minimize/zoom buttons are hidden (Margin draws its own close).
     * Replaces AWT's collection mask, dropping FullScreenPrimary: AppKit throws when it's combined with FullScreenAuxiliary.
     */
    fun prepareMenuBarWindow(title: String) = onMainThread {
        val window = findWindow(title)
        window.send("setCollectionBehavior:", (1L shl 1) or (1L shl 8)) // MoveToActiveSpace | FullScreenAuxiliary
        for (button in 0L..2L) window.send("standardWindowButton:", button)?.send("setHidden:", 1L) // close, minimize, zoom
    }

    /** False when the window titled [title] was left behind on another space (desktop or full-screen app). */
    fun isOnActiveSpace(title: String) = onMainThread {
        findWindow(title).send("isOnActiveSpace")?.let { (Pointer.nativeValue(it) and 0xFF) != 0L } == true // BOOL: low byte
    }

    private val objc by lazy { Native.load("objc", ObjC::class.java) }
    private fun Pointer.send(sel: String) = objc.objc_msgSend(this, objc.sel_registerName(sel))
    private fun Pointer.send(sel: String, arg: Long) = objc.objc_msgSend(this, objc.sel_registerName(sel), arg)

    /** Main thread only. */
    private fun findWindow(title: String): Pointer {
        val windows = objc.objc_getClass("NSApplication")!!.send("sharedApplication")!!.send("windows")!!
        val count = windows.send("count")?.let { Pointer.nativeValue(it) } ?: 0L
        return (0 until count).map { windows.send("objectAtIndex:", it)!! }
            .firstOrNull { it.send("title")?.send("UTF8String")?.getString(0) == title } ?: error("no window titled $title")
    }
}

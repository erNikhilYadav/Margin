package margin.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import margin.git.ChangeKind
import margin.git.LineType

// ---------- Color ----------

@Immutable
data class MarginColors(
    val isDark: Boolean,
    // Surfaces, back to front
    val canvas: Color,          // window background behind panels
    val sidebar: Color,
    val surface: Color,         // main panels (changes list, diff, history)
    val surfaceRaised: Color,   // commit box, popovers, tooltips
    val sectionBand: Color,     // group header bands in lists (opaque, so sticky headers cover rows)
    val hover: Color,           // row / button hover fill
    val pressed: Color,
    val selected: Color,        // selected row fill (accent-tinted)
    val border: Color,          // hairlines between panels
    val borderStrong: Color,    // input outlines, focused-ish edges
    // Text
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    // Accent: selection, focus, primary action. Nothing else.
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    val accentText: Color,      // accent used as text/icon on surfaces
    val focusRing: Color,
    // Status
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    // Diff
    val diffAddedBg: Color,
    val diffAddedGutter: Color,
    val diffAddedSign: Color,
    val diffAddedEmphasis: Color,
    val diffRemovedBg: Color,
    val diffRemovedGutter: Color,
    val diffRemovedSign: Color,
    val diffRemovedEmphasis: Color,
    val diffHunkBg: Color,
    val diffHunkText: Color,
    val diffLineNumber: Color,
    // File kinds
    val kindModified: Color,
    val kindAdded: Color,
    val kindDeleted: Color,
    val kindRenamed: Color,
    val kindUntracked: Color,
    val kindConflicted: Color,
) {
    fun kind(kind: ChangeKind): Color = when (kind) {
        ChangeKind.Modified -> kindModified
        ChangeKind.Added -> kindAdded
        ChangeKind.Deleted -> kindDeleted
        ChangeKind.Renamed -> kindRenamed
        ChangeKind.Untracked -> kindUntracked
        ChangeKind.Conflicted -> kindConflicted
    }

    /** Row background for a diff line; Transparent for context. */
    fun diffLineBg(type: LineType): Color = when (type) {
        LineType.Added -> diffAddedBg
        LineType.Removed -> diffRemovedBg
        LineType.Hunk -> diffHunkBg
        LineType.Context -> Color.Transparent
    }
}

val DarkColors = MarginColors(
    isDark = true,
    canvas = Color(0xFF161512),         // warm ink
    sidebar = Color(0xFF1A1916),
    surface = Color(0xFF1E1C18),
    surfaceRaised = Color(0xFF25231E),
    sectionBand = Color(0xFF22201C),
    hover = Color(0x0FEAE4D8),
    pressed = Color(0x17EAE4D8),
    selected = Color(0x2EE0694D),
    border = Color(0xFF2B2823),
    borderStrong = Color(0xFF3A3630),
    text = Color(0xFFEAE4D8),
    textSecondary = Color(0xFFA39C8E),
    textTertiary = Color(0xFF756E62),
    textDisabled = Color(0xFF4F4A42),
    accent = Color(0xFFE0694D),         // vermilion
    accentHover = Color(0xFFE87D63),
    onAccent = Color(0xFF161512),       // ink: white on this vermilion fails contrast
    accentText = Color(0xFFEE8A70),
    focusRing = Color(0x99E0694D),
    success = Color(0xFF7FB083),
    warning = Color(0xFFD9A954),
    danger = Color(0xFFE25D78),         // pinker than the accent, so Commit never reads as destructive
    info = Color(0xFF7FA6C9),
    diffAddedBg = Color(0x177FB083),
    diffAddedGutter = Color(0x297FB083),
    diffAddedSign = Color(0xFF7FB083),
    diffAddedEmphasis = Color(0x407FB083),
    diffRemovedBg = Color(0x17E25D78),
    diffRemovedGutter = Color(0x29E25D78),
    diffRemovedSign = Color(0xFFE25D78),
    diffRemovedEmphasis = Color(0x40E25D78),
    diffHunkBg = Color(0x0FEAE4D8),
    diffHunkText = Color(0xFF8F887B),
    diffLineNumber = Color(0xFF5E584F),
    kindModified = Color(0xFFD9A954),
    kindAdded = Color(0xFF7FB083),
    kindDeleted = Color(0xFFE25D78),
    kindRenamed = Color(0xFF7FA6C9),
    kindUntracked = Color(0xFF8F887B),
    kindConflicted = Color(0xFFE0694D),
)

val LightColors = MarginColors(
    isDark = false,
    canvas = Color(0xFFF6F2EA),         // paper
    sidebar = Color(0xFFEFEAE0),
    surface = Color(0xFFFFFDF8),
    surfaceRaised = Color(0xFFFFFEFB),
    sectionBand = Color(0xFFF9F5EE),
    hover = Color(0x0A1B1A17),
    pressed = Color(0x141B1A17),
    selected = Color(0x1FC8462B),
    border = Color(0xFFE5DED1),
    borderStrong = Color(0xFFD6CDBC),
    text = Color(0xFF1B1A17),           // ink
    textSecondary = Color(0xFF5E594F),
    textTertiary = Color(0xFF938C7F),
    textDisabled = Color(0xFFC2BBAE),
    accent = Color(0xFFC8462B),         // vermilion
    accentHover = Color(0xFFB33C23),
    onAccent = Color(0xFFFFFDF8),
    accentText = Color(0xFFB03E25),
    focusRing = Color(0x80C8462B),
    success = Color(0xFF3F7A4A),
    warning = Color(0xFFA8741A),
    danger = Color(0xFFA3213B),         // crimson, darker and cooler than the accent
    info = Color(0xFF3D6A92),
    diffAddedBg = Color(0x1A3F7A4A),
    diffAddedGutter = Color(0x2E3F7A4A),
    diffAddedSign = Color(0xFF3F7A4A),
    diffAddedEmphasis = Color(0x403F7A4A),
    diffRemovedBg = Color(0x14A3213B),
    diffRemovedGutter = Color(0x26A3213B),
    diffRemovedSign = Color(0xFFA3213B),
    diffRemovedEmphasis = Color(0x33A3213B),
    diffHunkBg = Color(0x0D1B1A17),
    diffHunkText = Color(0xFF857D70),
    diffLineNumber = Color(0xFFB3AB9C),
    kindModified = Color(0xFFA8741A),
    kindAdded = Color(0xFF3F7A4A),
    kindDeleted = Color(0xFFA3213B),
    kindRenamed = Color(0xFF3D6A92),
    kindUntracked = Color(0xFF8C857A),
    kindConflicted = Color(0xFFC8462B),
)

// ---------- Type ----------

val InterFamily = FontFamily(
    Font(resource = "fonts/Inter-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/Inter-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/Inter-SemiBold.ttf", weight = FontWeight.SemiBold),
)
val SerifFamily = FontFamily( // Newsreader: display and titles only
    Font(resource = "fonts/Newsreader-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/Newsreader-SemiBold.ttf", weight = FontWeight.SemiBold),
)
val MonoFamily = FontFamily(Font(resource = "fonts/JetBrainsMono-Regular.ttf", weight = FontWeight.Normal))

@Immutable
data class MarginTypography(
    val display: TextStyle,     // empty-state headline
    val title: TextStyle,       // repo name, panel titles
    val body: TextStyle,        // default UI text, file paths, commit subjects
    val bodyStrong: TextStyle,  // selected / emphasized row text
    val label: TextStyle,       // buttons, badges, pills
    val caption: TextStyle,     // metadata: author, time, counts
    val overline: TextStyle,    // section headers (render uppercase)
    val mono: TextStyle,        // diff body
    val monoSmall: TextStyle,   // hashes, line numbers
)

private fun inter(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = InterFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp,
)

val DefaultTypography = MarginTypography(
    display = TextStyle(fontFamily = SerifFamily, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
    title = TextStyle(fontFamily = SerifFamily, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    body = inter(13, 18, FontWeight.Normal),
    bodyStrong = inter(13, 18, FontWeight.Medium),
    label = inter(12, 16, FontWeight.Medium),
    caption = inter(11, 14, FontWeight.Normal, 0.1f),
    overline = inter(11, 14, FontWeight.SemiBold, 0.6f),
    mono = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 19.sp),
    monoSmall = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 19.sp),
)

// ---------- Spacing / radius / sizes ----------

object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 6.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

object Radius {
    val xs = 4.dp    // badges, kind squares
    val sm = 6.dp    // buttons, rows, inputs
    val md = 8.dp    // panels, commit box
    val lg = 12.dp   // dialogs, empty-state card
}

object Sizes {
    val changesWidth = 340.dp
    val toolbarHeight = 44.dp
    val rowHeight = 28.dp       // list + menu rows
    val historyRowHeight = 44.dp
    val iconButton = 28.dp
    val icon = 16.dp
    val kindBadge = 16.dp
}

// ---------- Motion ----------

object Motion {
    const val FAST = 120   // hover, press, color changes
    const val BASE = 180   // selection, expand/collapse, fades
    const val SLOW = 260   // panel/sheet enter, empty-state reveal
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // enters
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)      // exits, at 0.7x duration
    fun <T> fast(): TweenSpec<T> = tween(FAST, easing = Standard)
    fun <T> base(): TweenSpec<T> = tween(BASE, easing = Standard)
    fun <T> enter(): TweenSpec<T> = tween(SLOW, easing = Decelerate)
    fun <T> exit(): TweenSpec<T> = tween((SLOW * 0.7f).toInt(), easing = Accelerate)
}

// ---------- Theme ----------

val LocalMarginColors = staticCompositionLocalOf { DarkColors }
val LocalMarginTypography = staticCompositionLocalOf { DefaultTypography }

object MarginTheme {
    val colors: MarginColors @Composable @ReadOnlyComposable get() = LocalMarginColors.current
    val type: MarginTypography @Composable @ReadOnlyComposable get() = LocalMarginTypography.current
}

@Composable
fun MarginTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkColors else LightColors
    val t = DefaultTypography
    val scheme = if (darkTheme) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.accentText, onSecondary = c.onAccent,
        background = c.canvas, onBackground = c.text, surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surfaceRaised, surfaceContainerHigh = c.surfaceRaised, surfaceContainerLow = c.surface,
        outline = c.borderStrong, outlineVariant = c.border, error = c.danger, onError = c.onAccent,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.accentText, onSecondary = c.onAccent,
        background = c.canvas, onBackground = c.text, surface = c.surface, onSurface = c.text,
        surfaceVariant = c.sidebar, onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surfaceRaised, surfaceContainerHigh = c.surfaceRaised, surfaceContainerLow = c.surface,
        outline = c.borderStrong, outlineVariant = c.border, error = c.danger, onError = c.onAccent,
    )
    val m3Type = Typography(
        titleLarge = t.display, titleMedium = t.title, titleSmall = t.bodyStrong,
        bodyLarge = t.body, bodyMedium = t.body, bodySmall = t.caption,
        labelLarge = t.label, labelMedium = t.label, labelSmall = t.caption,
    )
    CompositionLocalProvider(LocalMarginColors provides c, LocalMarginTypography provides t) {
        MaterialTheme(colorScheme = scheme, typography = m3Type, content = content)
    }
}

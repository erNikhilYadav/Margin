package margin.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import margin.git.ChangeKind
import margin.ui.theme.MarginTheme
import margin.ui.theme.Motion
import margin.ui.theme.Radius
import margin.ui.theme.Sizes
import margin.ui.theme.Spacing
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.cos
import kotlin.math.sin

/** Hovered / pressed state for any clickable surface. */
@Composable
private fun interactionFill(source: MutableInteractionSource, enabled: Boolean, rest: Color = Color.Transparent): Color {
    val c = MarginTheme.colors
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val target = when {
        !enabled -> rest
        pressed -> c.pressed
        hovered -> c.hover
        else -> rest
    }
    return animateColorAsState(target, Motion.fast()).value
}

private fun Modifier.interactive(source: MutableInteractionSource, enabled: Boolean, onClick: () -> Unit) =
    hoverable(source, enabled).clickable(source, indication = null, enabled = enabled, onClick = onClick)

// ---------- Surfaces ----------

/** Rounded, hairline-bordered panel. Use color = surfaceRaised for the commit box / popovers. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MarginTheme.colors.surface,
    bordered: Boolean = true,
    radius: Dp = Radius.md,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(color, shape)
            .then(if (bordered) Modifier.border(1.dp, MarginTheme.colors.border, shape) else Modifier),
        content = content,
    )
}

/** 1dp hairline. Horizontal by default; vertical = true for panel splits. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier, vertical: Boolean = false, color: Color = MarginTheme.colors.border) {
    Box(modifier.then(if (vertical) Modifier.width(1.dp).fillMaxHeight() else Modifier.height(1.dp).fillMaxWidth()).background(color))
}

// ---------- Text ----------

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MarginTheme.colors
    Row(
        modifier.fillMaxWidth().height(Sizes.rowHeight).padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        BasicText(title.uppercase(), style = MarginTheme.type.overline.copy(color = c.textTertiary), maxLines = 1)
        if (count != null) BasicText(count.toString(), style = MarginTheme.type.caption.copy(color = c.textTertiary))
        Spacer(Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** Single line that truncates at the start ("…/feature/ui"): Skia ignores TextOverflow.StartEllipsis. */
@Composable
fun StartEllipsisText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val max = constraints.maxWidth
        val shown = remember(text, style, max) {
            fun fits(s: String) = measurer.measure(s, style, softWrap = false, maxLines = 1).size.width <= max
            if (fits(text)) text
            else generateSequence(text.length - 1) { it - 1 }.takeWhile { it > 0 }
                .map { "…" + text.takeLast(it) }.firstOrNull(::fits) ?: ""
        }
        BasicText(shown, style = style, maxLines = 1, softWrap = false)
    }
}

// ---------- Rows ----------

/**
 * Sidebar / list row. 28dp, rounded, hover fill, accent-tinted when selected.
 * leading is typically a GlyphIcon or KindBadge; trailing a Pill or caption text.
 */
@Composable
fun SidebarItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    secondary: Boolean = false,
    subtitle: String? = null, // a caption line under the label; the row grows to fit it
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MarginTheme.colors
    val source = remember { MutableInteractionSource() }
    val hoverFill = interactionFill(source, true)
    val bg by animateColorAsState(if (selected) c.selected else hoverFill, Motion.fast())
    val textStyle = (if (selected) MarginTheme.type.bodyStrong else MarginTheme.type.body)
        .copy(color = if (selected) c.text else if (secondary) c.textSecondary else c.text)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (subtitle == null) Modifier.height(Sizes.rowHeight) else Modifier)
            .clip(RoundedCornerShape(Radius.sm))
            .background(bg)
            .interactive(source, true, onClick)
            .padding(horizontal = Spacing.md, vertical = if (subtitle == null) 0.dp else Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            BasicText(label, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) BasicText(
                subtitle, style = MarginTheme.type.caption.copy(color = MarginTheme.colors.textTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

// ---------- Badges ----------

/** Small tinted capsule, e.g. "↑2", "↓1", "main". */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MarginTheme.colors.textSecondary) {
    BasicText(
        text,
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = Spacing.sm, vertical = 1.dp),
        style = MarginTheme.type.label.copy(color = color),
        maxLines = 1,
    )
}

/** 16dp tinted square with the kind's initial: M A D R U C. */
@Composable
fun KindBadge(kind: ChangeKind, modifier: Modifier = Modifier) {
    val color = MarginTheme.colors.kind(kind)
    Box(
        modifier
            .size(Sizes.kindBadge)
            .clip(RoundedCornerShape(Radius.xs))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(kind.name.take(1), style = MarginTheme.type.caption.copy(color = color, fontWeight = MarginTheme.type.overline.fontWeight))
    }
}

// ---------- Buttons ----------

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false, // destructive action: danger fill instead of the accent
) {
    val c = MarginTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val base = if (danger) c.danger else c.accent
    val hover = if (danger) lerp(c.danger, if (c.isDark) Color.White else Color.Black, 0.1f) else c.accentHover // same direction as accentHover
    val bg by animateColorAsState(
        when { !enabled -> c.pressed; hovered -> hover; else -> base }, Motion.fast(), // neutral when disabled: readable in both themes
    )
    Row(
        modifier
            .height(Sizes.iconButton)
            .clip(RoundedCornerShape(Radius.sm))
            .background(bg)
            .interactive(source, enabled && !loading, onClick)
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
    ) {
        val fg = if (enabled) c.onAccent else c.textTertiary
        if (loading) Spinner(color = fg, size = 12.dp)
        BasicText(text, style = MarginTheme.type.label.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = MarginTheme.colors
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(Sizes.iconButton)
            .clip(RoundedCornerShape(Radius.sm))
            .background(interactionFill(source, enabled))
            .border(1.dp, c.border, RoundedCornerShape(Radius.sm))
            .interactive(source, enabled, onClick)
            .padding(horizontal = Spacing.md + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        leading?.invoke()
        BasicText(text, style = MarginTheme.type.label.copy(color = if (enabled) c.text else c.textDisabled), maxLines = 1)
    }
}

/** Hover tooltip (shown after 500ms) under [content]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip(text: String, content: @Composable () -> Unit) {
    val c = MarginTheme.colors
    TooltipArea(
        tooltip = {
            BasicText(
                text,
                Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(c.surfaceRaised)
                    .border(1.dp, c.borderStrong, RoundedCornerShape(Radius.sm))
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                style = MarginTheme.type.caption.copy(color = c.text),
            )
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.BottomCenter, Alignment.BottomCenter, DpOffset(0.dp, 4.dp)),
        content = content,
    )
}

/** 28dp square icon button with a hover tooltip. */
@Composable
fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Tooltip(tooltip) {
        Box(
            modifier
                .size(Sizes.iconButton)
                .clip(RoundedCornerShape(Radius.sm))
                .background(interactionFill(source, enabled))
                .interactive(source, enabled, onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** Toolbar dropdown trigger: 28dp, hover fill, stays filled while its menu is [open]. */
@Composable
fun ToolbarChip(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(Sizes.iconButton)
            .clip(RoundedCornerShape(Radius.sm))
            .background(interactionFill(source, true, if (open) MarginTheme.colors.hover else Color.Transparent))
            .interactive(source, true, onClick)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content,
    )
}

/** Floating menu surface for Popups: raised panel, strong hairline, soft shadow. */
@Composable
fun Popover(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = MarginTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    Panel(
        modifier
            .shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = if (c.isDark) 0.5f else 0.18f))
            .border(1.dp, c.borderStrong, shape),
        color = c.surfaceRaised, content = content,
    )
}

/** Centered dialog sheet over a dimmed scrim. A click on the scrim or Esc calls [onDismiss]; the content owns its focus. */
@Composable
fun Modal(visible: Boolean, onDismiss: () -> Unit, width: Dp, content: @Composable BoxScope.() -> Unit) {
    val c = MarginTheme.colors
    AnimatedVisibility(visible, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.isDark) 0.55f else 0.25f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Panel(
                Modifier.width(width)
                    .animateEnterExit(enter = scaleIn(Motion.enter(), initialScale = 0.96f), exit = scaleOut(Motion.exit(), targetScale = 0.98f))
                    .border(1.dp, c.borderStrong, RoundedCornerShape(Radius.lg))
                    .clickable(remember { MutableInteractionSource() }, null) {} // keep clicks off the scrim
                    .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false },
                color = c.surfaceRaised, radius = Radius.lg, content = content,
            )
        }
    }
}

/** 14dp tri-state checkbox in a 20dp hit target: accent fill with a check or dash, soft accent ring on hover. */
@Composable
fun Checkbox(state: ToggleableState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MarginTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val on = state != ToggleableState.Off
    val fill by animateColorAsState(if (!on) c.accent.copy(alpha = 0f) else if (hovered) c.accentHover else c.accent, Motion.fast())
    val edge by animateColorAsState(if (on) c.accent.copy(alpha = 0f) else if (hovered) c.textSecondary else c.textTertiary, Motion.fast())
    val ring by animateColorAsState(c.accent.copy(alpha = if (hovered) 0.22f else 0f), Motion.fast())
    val mark by animateFloatAsState(if (on) 1f else 0f, Motion.fast())
    Box(
        modifier.size(20.dp).hoverable(source)
            .triStateToggleable(state, source, indication = null, role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val r = 4.dp.toPx()
            val ringW = 3.dp.toPx()
            drawRoundRect(ring, Offset(-ringW, -ringW), Size(size.width + 2 * ringW, size.height + 2 * ringW), CornerRadius(r + ringW))
            drawRoundRect(fill, cornerRadius = CornerRadius(r))
            val e = 1.dp.toPx()
            drawRoundRect(edge, Offset(e / 2, e / 2), Size(size.width - e, size.height - e), CornerRadius(r - e / 2), style = Stroke(e))
            val u = size.width / 14f
            val stroke = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val ink = c.onAccent.copy(alpha = mark)
            if (state == ToggleableState.Indeterminate) drawLine(ink, Offset(4f * u, 7f * u), Offset(10f * u, 7f * u), stroke.width, StrokeCap.Round)
            else drawPath(Path().apply { moveTo(3.75f * u, 7.25f * u); lineTo(6f * u, 9.5f * u); lineTo(10.25f * u, 4.75f * u) }, ink, style = stroke)
        }
    }
}

// ---------- Input ----------

/** Borderless-feeling text field: raised fill, border turns accent on focus. Used by the commit box. */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = Sizes.iconButton,
    masked: Boolean = false, // password-style dots
) {
    val c = MarginTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border by animateColorAsState(if (focused) c.focusRing else c.border, Motion.fast())
    val style = MarginTheme.type.body.copy(color = c.text)
    BasicTextField(
        value, onValueChange,
        modifier
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(Radius.sm))
            .background(c.surfaceRaised)
            .border(1.dp, border, RoundedCornerShape(Radius.sm))
            .padding(horizontal = Spacing.md + Spacing.xxs, vertical = Spacing.sm),
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(c.accent),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        interactionSource = source,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) BasicText(placeholder, style = style.copy(color = c.textTertiary))
                inner()
            }
        },
    )
}

// ---------- Loading ----------

/** 270-degree arc rotating at 900ms per turn. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 14.dp, color: Color = MarginTheme.colors.textSecondary) {
    val angle by rememberInfiniteTransition().animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
    )
    Canvas(modifier.size(size).rotate(angle)) {
        val w = 1.5.dp.toPx()
        drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, style = Stroke(w), topLeft = Offset(w / 2, w / 2), size = this.size.copy(this.size.width - w, this.size.height - w))
        drawArc(color, 0f, 270f, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = this.size.copy(this.size.width - w, this.size.height - w))
    }
}

// ---------- Skeletons ----------

private const val ShimmerMs = 1200L
private val LocalShimmer = compositionLocalOf<State<Float>?> { null }

/**
 * Drives the sweep for every [SkeletonBlock] inside it, and only while it is on screen. The phase comes from the
 * frame clock rather than from when this host started, so separate hosts (header and tab skeletons) sweep as one.
 */
@Composable
fun ShimmerHost(content: @Composable () -> Unit) {
    if (LocalShimmer.current != null) return content()
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { phase.floatValue = (it % ShimmerMs) / ShimmerMs.toFloat() } }
    CompositionLocalProvider(LocalShimmer provides phase, content = content)
}

/**
 * A placeholder shape. Inside a [ShimmerHost] a soft diagonal highlight crosses it, positioned in window
 * coordinates so all blocks on screen show one continuous band. Reads the phase in draw only: no recomposition per frame.
 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(Radius.xs)) {
    val c = MarginTheme.colors
    val phase = LocalShimmer.current
    val glow = Color.White.copy(alpha = if (c.isDark) 0.075f else 0.9f)
    val at = remember { FloatArray(4) } // x, y, window width, window height; written by layout, read by draw
    Box(
        modifier.clip(shape).background(c.pressed)
            .onGloballyPositioned {
                val p = it.positionInRoot()
                val root = it.findRootCoordinates().size
                at[0] = p.x; at[1] = p.y; at[2] = root.width.toFloat(); at[3] = root.height.toFloat()
            }
            .drawBehind {
                val e = Motion.Standard.transform(phase?.value ?: return@drawBehind)
                val band = 220.dp.toPx()
                val slope = 0.5f // the band is tilted like "/", so lower blocks get it a little later
                val x = -band + (at[2] + 2 * band + slope * at[3]) * e - slope * at[1] - at[0]
                drawRect(Brush.linearGradient(listOf(Color.Transparent, glow, Color.Transparent), Offset(x - band / 2, -band / 4), Offset(x + band / 2, band / 4)))
            },
    )
}

/** Shows [skeleton] until [ready], then cross-fades [content] in over it. */
@Composable
fun Reveal(ready: Boolean, skeleton: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Crossfade(ready, modifier, tween(Motion.BASE, easing = Motion.Standard)) { if (it) content() else ShimmerHost(skeleton) }

/** A 2dp indeterminate bar for a refresh that keeps the current content on screen. */
@Composable
fun ProgressStrip(visible: Boolean, modifier: Modifier = Modifier) {
    val c = MarginTheme.colors
    AnimatedVisibility(visible, modifier, enter = fadeIn(Motion.base()), exit = fadeOut(Motion.base())) {
        val x by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(ShimmerMs.toInt(), easing = Motion.Standard)))
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            val seg = size.width * 0.35f
            val start = -seg + (size.width + seg) * x
            drawRect(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0f), c.accent, c.accent.copy(alpha = 0f)), start, start + seg))
        }
    }
}

// ---------- Icons ----------

/** Stroke icons on a 16-unit grid, 1.5dp line. No icon library on the classpath, so they are drawn. */
enum class Glyph { Fetch, Pull, Push, Branch, Repo, Commit, Plus, Minus, Check, ChevronDown, ChevronRight, Close, MergeRequest, External, Copy, Rollback,
    ArrowUp, ArrowDown, Unified, Split, Fold, Unfold, Sun, Moon, Auto, ChevronLeft, More, Comment }

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = Sizes.icon, tint: Color = MarginTheme.colors.textSecondary) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 16f
        val s = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg pts: Float) = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            for (i in 2 until pts.size step 2) lineTo(pts[i] * u, pts[i + 1] * u)
        }
        fun line(vararg pts: Float) = drawPath(p(*pts), tint, style = s)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, Offset(x * u, y * u), style = s)
        when (glyph) {
            Glyph.Fetch -> { // clockwise refresh arrow: 300-degree arc with a square arrowhead at its open end
                drawArc(tint, 20f, 290f, false, Offset(2f * u, 2f * u), Size(12 * u, 12 * u), style = s)
                line(13.75f, 1.75f, 13.75f, 5.25f, 10.25f, 5.25f)
            }
            Glyph.Pull -> { line(8f, 2.5f, 8f, 10.5f); line(4.5f, 7f, 8f, 10.5f, 11.5f, 7f); line(3f, 13.5f, 13f, 13.5f) }
            Glyph.Push -> { line(8f, 13.5f, 8f, 5.5f); line(4.5f, 9f, 8f, 5.5f, 11.5f, 9f); line(3f, 2.5f, 13f, 2.5f) }
            Glyph.Branch -> {
                circle(4.5f, 3.5f, 1.5f); circle(4.5f, 12.5f, 1.5f); circle(11.5f, 5f, 1.5f)
                line(4.5f, 5f, 4.5f, 11f)
                drawPath(Path().apply { moveTo(11.5f * u, 6.5f * u); cubicTo(11.5f * u, 9.5f * u, 4.5f * u, 8f * u, 4.5f * u, 11f * u) }, tint, style = s)
            }
            Glyph.Repo -> line(2f, 4f, 2f, 13f, 14f, 13f, 14f, 5.5f, 8f, 5.5f, 6.5f, 3f, 2f, 3f, 2f, 4f)
            Glyph.Commit -> { circle(8f, 8f, 2.75f); line(1.5f, 8f, 5.25f, 8f); line(10.75f, 8f, 14.5f, 8f) }
            Glyph.Plus -> { line(8f, 3.5f, 8f, 12.5f); line(3.5f, 8f, 12.5f, 8f) }
            Glyph.Minus -> line(3.5f, 8f, 12.5f, 8f)
            Glyph.Check -> line(3f, 8.5f, 6.5f, 12f, 13f, 4.5f)
            Glyph.ChevronDown -> line(4.5f, 6.5f, 8f, 10f, 11.5f, 6.5f)
            Glyph.ChevronRight -> line(6.5f, 4.5f, 10f, 8f, 6.5f, 11.5f)
            Glyph.ChevronLeft -> line(9.5f, 4.5f, 6f, 8f, 9.5f, 11.5f)
            Glyph.More -> for (x in listOf(3.5f, 8f, 12.5f)) drawCircle(tint, 1.25f * u, Offset(x * u, 8f * u))
            Glyph.Comment -> { // speech bubble with a tail at the bottom left
                drawRoundRect(tint, Offset(2f * u, 2.5f * u), Size(12f * u, 8.5f * u), CornerRadius(2f * u), style = s)
                line(4.5f, 11f, 4.5f, 13.75f, 7.5f, 11f)
            }
            Glyph.Close -> { line(4f, 4f, 12f, 12f); line(12f, 4f, 4f, 12f) }
            Glyph.MergeRequest -> { // two branch stems; the right one curls back into an arrow toward the left
                circle(4f, 3.5f, 1.5f); circle(4f, 12.5f, 1.5f); circle(12f, 12.5f, 1.5f)
                line(4f, 5f, 4f, 11f)
                drawPath(Path().apply { moveTo(12f * u, 11f * u); lineTo(12f * u, 6.5f * u); quadraticTo(12f * u, 3.5f * u, 9f * u, 3.5f * u); lineTo(7f * u, 3.5f * u) }, tint, style = s)
                line(8.75f, 1.75f, 7f, 3.5f, 8.75f, 5.25f)
            }
            Glyph.External -> { line(9.5f, 2.5f, 13.5f, 2.5f, 13.5f, 6.5f); line(13.5f, 2.5f, 7.5f, 8.5f); line(12f, 9.5f, 12f, 13.5f, 2.5f, 13.5f, 2.5f, 4f, 6.5f, 4f) }
            Glyph.Rollback -> { // undo: arrow back to the left, then a U-turn down and back
                line(6f, 2.5f, 2.5f, 6f, 6f, 9.5f)
                drawPath(Path().apply {
                    moveTo(2.5f * u, 6f * u); lineTo(10f * u, 6f * u)
                    cubicTo(11.93f * u, 6f * u, 13.5f * u, 7.57f * u, 13.5f * u, 9.5f * u)
                    cubicTo(13.5f * u, 11.43f * u, 11.93f * u, 13f * u, 10f * u, 13f * u)
                    lineTo(6f * u, 13f * u)
                }, tint, style = s)
            }
            Glyph.Copy -> {
                line(2.5f, 10.5f, 2.5f, 2.5f, 10.5f, 2.5f)
                drawRoundRect(tint, Offset(5.5f * u, 5.5f * u), Size(8f * u, 8f * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = s)
            }
            Glyph.ArrowUp -> { line(8f, 13f, 8f, 3f); line(4f, 7f, 8f, 3f, 12f, 7f) }
            Glyph.ArrowDown -> { line(8f, 3f, 8f, 13f); line(4f, 9f, 8f, 13f, 12f, 9f) }
            Glyph.Unified -> { // one pane, stacked lines
                drawRoundRect(tint, Offset(2f * u, 2.5f * u), Size(12f * u, 11f * u), CornerRadius(2f * u), style = s)
                line(5f, 6.5f, 11f, 6.5f); line(5f, 9.5f, 9f, 9.5f)
            }
            Glyph.Split -> { // two panes side by side
                drawRoundRect(tint, Offset(2f * u, 2.5f * u), Size(12f * u, 11f * u), CornerRadius(2f * u), style = s)
                line(8f, 2.5f, 8f, 13.5f)
            }
            Glyph.Fold -> { line(5f, 2.5f, 8f, 5.5f, 11f, 2.5f); line(5f, 13.5f, 8f, 10.5f, 11f, 13.5f); line(3f, 8f, 13f, 8f) }
            Glyph.Unfold -> { line(5f, 5f, 8f, 2f, 11f, 5f); line(5f, 11f, 8f, 14f, 11f, 11f); line(8f, 2f, 8f, 14f) }
            Glyph.Sun -> {
                circle(8f, 8f, 2.75f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val (cx, cy) = cos(a).toFloat() to sin(a).toFloat()
                    line(8f + 5f * cx, 8f + 5f * cy, 8f + 6.5f * cx, 8f + 6.5f * cy)
                }
            }
            Glyph.Moon -> { // crescent: a disc minus an offset disc
                val disc = Path().apply { addOval(Rect(Offset(2.5f * u, 2.5f * u), Size(11f * u, 11f * u))) }
                val bite = Path().apply { addOval(Rect(Offset(6.5f * u, 0.5f * u), Size(9.5f * u, 9.5f * u))) }
                drawPath(Path.combine(PathOperation.Difference, disc, bite), tint, style = s)
            }
            Glyph.Auto -> { // half-filled disc: follows the system
                circle(8f, 8f, 5.75f)
                drawArc(tint, 90f, 180f, true, Offset(2.25f * u, 2.25f * u), Size(11.5f * u, 11.5f * u))
            }
        }
    }
}

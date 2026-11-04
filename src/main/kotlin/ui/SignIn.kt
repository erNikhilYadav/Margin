package margin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import margin.ui.components.*
import margin.ui.theme.MarginTheme
import margin.ui.theme.Spacing

/** Token sign-in for the repo's host, shown in the MRs/PRs tab when the forge's CLI has no token for it. */
@Composable
fun SignInForm(
    busy: Boolean,
    error: String?,
    tokenHint: String,
    tokenUrl: String,
    host: String,
    autofocus: Boolean = false,
    onSubmit: (token: String) -> Unit,
) {
    val c = MarginTheme.colors
    val t = MarginTheme.type
    var token by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    if (autofocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val enter = Modifier.onPreviewKeyEvent { e -> (e.type == KeyEventType.KeyDown && e.key == Key.Enter).also { if (it) onSubmit(token) } }
    @Composable fun Label(text: String) = BasicText(text, style = t.label.copy(color = c.textSecondary))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Label("Personal access token")
        InputField(token, { token = it }, tokenHint, Modifier.fillMaxWidth().focusRequester(focus).then(enter), masked = true)
        if (error != null) BasicText(error, style = t.caption.copy(color = c.danger), maxLines = 4, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Spacing.xs))
        PrimaryButton("Sign in", { onSubmit(token) }, Modifier.fillMaxWidth(), enabled = token.isNotBlank(), loading = busy)
        GhostButton(
            "Create a token on $host", { openInBrowser(tokenUrl) }, Modifier.align(Alignment.CenterHorizontally),
            leading = { GlyphIcon(Glyph.External, size = 14.dp, tint = c.accentText) },
        )
    }
}

/** The dock icon in miniature: paper tile, vermilion margin rule, ink branch. Fixed colors in both themes, like the real icon. */
@Composable
fun AppMark(size: Dp = 56.dp) {
    val shape = RoundedCornerShape(size * 0.225f)
    Box(
        Modifier.size(size).clip(shape).background(Brush.verticalGradient(listOf(Color(0xFFFBF8F2), Color(0xFFECE5D7))))
            .drawBehind { // the margin, at the icon's 20% mark
                val x = this.size.width * 0.204f
                drawLine(Color(0xFFC8462B), Offset(x, 0f), Offset(x, this.size.height), strokeWidth = maxOf(1.dp.toPx(), this.size.width * 0.009f))
            }
            .border(1.dp, Color.Black.copy(alpha = 0.09f), shape),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(Glyph.Branch, size = size * 0.5f, modifier = Modifier.offset(x = size * 0.06f), tint = Color(0xFF1B1A17)) }
}

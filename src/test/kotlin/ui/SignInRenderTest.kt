package margin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import margin.git.GitHub
import margin.git.GitLab
import margin.ui.theme.MarginTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/** Offscreen renders of the MRs/PRs tab sign-in form for visual review; runs only with MARGIN_RENDER_DIR set. */
class SignInRenderTest {
    private val out = System.getenv("MARGIN_RENDER_DIR")?.let(::File)

    @Test fun renders() {
        out ?: return
        out.mkdirs()
        val dir = createTempDirectory("render").toFile()
        val cases = listOf(GitLab(dir, null) to "gitlab.example.com", GitHub(dir, null) to "github.com")
        for ((forge, host) in cases) for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(900, 700, Density(1.5f)) {
                MarginTheme(dark) {
                    Box(Modifier.fillMaxSize().background(MarginTheme.colors.surface), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(460.dp).padding(24.dp)) {
                            SignInForm(false, "${forge.terms.cli}: 401 Unauthorized", forge.terms.tokenHint, forge.tokenUrl(host), host) {}
                        }
                    }
                }
            }
            scene.render(0)
            val img = scene.render(1_000_000_000)
            File(out, "signin_${forge.terms.cli}_${if (dark) "dark" else "light"}.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
}

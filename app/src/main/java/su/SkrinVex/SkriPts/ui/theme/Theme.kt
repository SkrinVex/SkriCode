package su.SkrinVex.SkriPts.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Тёмно-синяя палитра
val Navy900  = Color(0xFF0A0E1A)
val Navy800  = Color(0xFF0D1321)
val Navy700  = Color(0xFF111827)
val Navy600  = Color(0xFF1A2540)
val Navy500  = Color(0xFF1E3A5F)
val Accent   = Color(0xFF4F8EF7)
val AccentAlt= Color(0xFF7C3AED)
val Success  = Color(0xFF22C55E)
val Warning  = Color(0xFFF59E0B)
val Danger   = Color(0xFFEF4444)
val TextPrim = Color(0xFFF1F5F9)
val TextSec  = Color(0xFF94A3B8)
val Surface1 = Color(0xFF131C2E)
val Surface2 = Color(0xFF1A2540)
val Surface3 = Color(0xFF243050)

private val DarkColors = darkColorScheme(
    primary          = Accent,
    onPrimary        = Color.White,
    secondary        = AccentAlt,
    onSecondary      = Color.White,
    background       = Navy900,
    onBackground     = TextPrim,
    surface          = Surface1,
    onSurface        = TextPrim,
    surfaceVariant   = Surface2,
    onSurfaceVariant = TextSec,
    outline          = Surface3,
    error            = Danger,
)

@Composable
fun SkriPtsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}

package su.SkrinVex.SkriPts.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Динамические цвета из ThemeManager
val Accent get() = ThemeManager.getAccent()
val Surface1 get() = ThemeManager.getSurface1()
val Surface2 get() = ThemeManager.getSurface2()
val Surface3 get() = ThemeManager.getSurface3()
val Navy900 get() = ThemeManager.getNavy900()
val TextPrim get() = ThemeManager.getTextPrim()
val TextSec get() = ThemeManager.getTextSec()

// Статичные цвета
val Success     = Color(0xFF22C55E)
val Warning     = Color(0xFFF59E0B)
val Danger      = Color(0xFFEF4444)
val TableAccent = Color(0xFF34D399)

@Composable
fun SkriPtsTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary          = Accent,
        onPrimary        = Color.White,
        secondary        = Accent,
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
    
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

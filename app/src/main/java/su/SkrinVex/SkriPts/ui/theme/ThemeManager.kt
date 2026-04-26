package su.SkrinVex.SkriPts.ui.theme

import androidx.compose.ui.graphics.Color

enum class AppTheme(
    val displayName: String,
    val accent: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val navy900: Color,
    val textPrim: Color,
    val textSec: Color
) {
    BLUE("Синяя", 
        Color(0xFF4F8EF7), Color(0xFF0A0E1A), Color(0xFF1A1F2E), Color(0xFF2A2F3E), Color(0xFF0F1419), Color(0xFFFFFFFF), Color(0xFFB0B8C8)),
    
    PURPLE("Фиолетовая", 
        Color(0xFFA855F7), Color(0xFF1A0A1A), Color(0xFF2E1A2E), Color(0xFF3E2A3E), Color(0xFF190F19), Color(0xFFFFFFFF), Color(0xFFC8B0C8)),
    
    PINK("Розовая", 
        Color(0xFFEC4899), Color(0xFF1A0A14), Color(0xFF2E1A28), Color(0xFF3E2A38), Color(0xFF190F14), Color(0xFFFFFFFF), Color(0xFFC8B0C0)),
    
    GREEN("Зелёная", 
        Color(0xFF10B981), Color(0xFF0A1A0F), Color(0xFF1A2E20), Color(0xFF2A3E30), Color(0xFF0F1914), Color(0xFFFFFFFF), Color(0xFFB0C8B8)),
    
    ORANGE("Оранжевая", 
        Color(0xFFF59E0B), Color(0xFF1A140A), Color(0xFF2E281A), Color(0xFF3E382A), Color(0xFF19140F), Color(0xFFFFFFFF), Color(0xFFC8C0B0)),
    
    RED("Красная", 
        Color(0xFFEF4444), Color(0xFF1A0A0A), Color(0xFF2E1A1A), Color(0xFF3E2A2A), Color(0xFF190F0F), Color(0xFFFFFFFF), Color(0xFFC8B0B0))
}

object ThemeManager {
    private var currentTheme = AppTheme.BLUE
    private const val PREFS = "skripts_prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_DEBUG = "debug_mode"

    var debugMode: Boolean = true
        private set

    fun init(ctx: android.content.Context) {
        val prefs = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_THEME, AppTheme.BLUE.name) ?: AppTheme.BLUE.name
        currentTheme = AppTheme.entries.find { it.name == name } ?: AppTheme.BLUE
        debugMode = prefs.getBoolean(KEY_DEBUG, true)
    }

    fun getCurrentTheme() = currentTheme

    fun setTheme(ctx: android.content.Context, theme: AppTheme) {
        currentTheme = theme
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme.name).apply()
    }

    fun setDebugMode(ctx: android.content.Context, enabled: Boolean) {
        debugMode = enabled
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEBUG, enabled).apply()
    }

    fun getAccent() = currentTheme.accent
    fun getSurface1() = currentTheme.surface1
    fun getSurface2() = currentTheme.surface2
    fun getSurface3() = currentTheme.surface3
    fun getNavy900() = currentTheme.navy900
    fun getTextPrim() = currentTheme.textPrim
    fun getTextSec() = currentTheme.textSec
}

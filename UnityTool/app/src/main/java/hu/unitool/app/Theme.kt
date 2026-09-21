package hu.unitool.app

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** A felhasználó által állítható megjelenés. */
data class Look(
    val mode: Int = 0,          // 0 Petrol, 1 Grafit, 2 AMOLED, 3 Világos
    val hue: Float = 38f,       // kiemelő szín árnyalata
    val sat: Float = 0.78f,
    val value: Float = 0.96f,
    val corner: Float = 14f,    // sarokrádiusz dp
    val scale: Float = 1f       // szövegméret szorzó
) {
    val accent: Color get() = Color.hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
}

val ModeNames = listOf("Petrol", "Grafit", "AMOLED", "Világos")

class LookStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("look", Context.MODE_PRIVATE)
    var look by mutableStateOf(
        Look(
            sp.getInt("mode", 0), sp.getFloat("hue", 38f), sp.getFloat("sat", 0.78f),
            sp.getFloat("value", 0.96f), sp.getFloat("corner", 14f), sp.getFloat("scale", 1f)
        )
    )
        private set

    fun set(l: Look) {
        look = l
        sp.edit().putInt("mode", l.mode).putFloat("hue", l.hue).putFloat("sat", l.sat)
            .putFloat("value", l.value).putFloat("corner", l.corner).putFloat("scale", l.scale).apply()
    }
}

private class Pal(val bg: Color, val surf: Color, val high: Color, val text: Color, val muted: Color, val line: Color, val dark: Boolean)

private fun pal(mode: Int) = when (mode) {
    1 -> Pal(Color(0xFF17181A), Color(0xFF212326), Color(0xFF2B2E32), Color(0xFFECECEC), Color(0xFF9A9DA3), Color(0xFF3A3D42), true)
    2 -> Pal(Color(0xFF000000), Color(0xFF0B0B0C), Color(0xFF151517), Color(0xFFF2F2F2), Color(0xFF8A8A90), Color(0xFF26262A), true)
    3 -> Pal(Color(0xFFEEF2F3), Color(0xFFFFFFFF), Color(0xFFE3EAEC), Color(0xFF13272B), Color(0xFF56696D), Color(0xFFC5D1D4), false)
    else -> Pal(Color(0xFF0D1B1F), Color(0xFF14282E), Color(0xFF1B343B), Color(0xFFE6F0F0), Color(0xFF8FA8AB), Color(0xFF2A4850), true)
}

@Composable
fun AppTheme(look: Look, content: @Composable () -> Unit) {
    val p = pal(look.mode)
    val accent = look.accent
    val onAccent = if (accent.luminance() > 0.45f) Color(0xFF10181A) else Color.White
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = accent.copy(alpha = 0.22f),
            onPrimaryContainer = p.text, background = p.bg, onBackground = p.text,
            surface = p.surf, onSurface = p.text, surfaceVariant = p.high, onSurfaceVariant = p.muted,
            outline = p.line, outlineVariant = p.line, error = Color(0xFFFF7A6B)
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = accent.copy(alpha = 0.22f),
            onPrimaryContainer = p.text, background = p.bg, onBackground = p.text,
            surface = p.surf, onSurface = p.text, surfaceVariant = p.high, onSurfaceVariant = p.muted,
            outline = p.line, outlineVariant = p.line, error = Color(0xFFB3261E)
        )
    }
    val c = look.corner
    val shapes = Shapes(
        extraSmall = RoundedCornerShape((c * 0.4f).dp), small = RoundedCornerShape((c * 0.6f).dp),
        medium = RoundedCornerShape(c.dp), large = RoundedCornerShape((c * 1.3f).dp),
        extraLarge = RoundedCornerShape((c * 1.8f).dp)
    )
    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        w.statusBarColor = p.bg.toArgb()
        w.navigationBarColor = p.surf.toArgb()
        val ic = WindowCompat.getInsetsController(w, view)
        ic.isAppearanceLightStatusBars = !p.dark
        ic.isAppearanceLightNavigationBars = !p.dark
    }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * look.scale)) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
    }
}

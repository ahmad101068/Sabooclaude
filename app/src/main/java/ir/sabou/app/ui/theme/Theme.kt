package ir.sabou.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.sabou.app.R

/** Color roles from docs/design/tokens.json. Dark mode maps the same roles; no new hues. */
@Immutable
data class Palette(
    val primary: Color,
    val primaryPressed: Color,
    val primarySurface: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    val onPrimaryMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val ink: Color,
    val muted: Color,
    val subtle: Color,
    val ground: Color,
    val surface: Color,
    val field: Color,
    val border: Color,
    val borderStrong: Color,
    val divider: Color,
    val track: Color,
    val moneyIn: Color,
    val moneyInSoft: Color,
    val moneyOut: Color,
    val moneyOutSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val bankSoft: Color,
    val bank: Color,
)

val LightPalette = Palette(
    primary = Color(0xFF0E5245), primaryPressed = Color(0xFF0A3B32), primarySurface = Color(0xFF0A4238),
    primarySoft = Color(0xFFE3EEEA), onPrimary = Color.White, onPrimaryMuted = Color(0xFFCFE3DC),
    accent = Color(0xFFF0B43C), onAccent = Color(0xFF15201C), accentSoft = Color(0xFFFDF1D8), onAccentSoft = Color(0xFF8A5A00),
    ink = Color(0xFF15201C), muted = Color(0xFF5A6560), subtle = Color(0xFF8A948F),
    ground = Color(0xFFF3F2EE), surface = Color.White, field = Color(0xFFFAFAF8),
    border = Color(0xFFE4E2DB), borderStrong = Color(0xFFD8D5CC), divider = Color(0xFFEFEDE7), track = Color(0xFFE7E5DE),
    moneyIn = Color(0xFF1F5FA8), moneyInSoft = Color(0xFFE1EBF6), moneyOut = Color(0xFFC2410C), moneyOutSoft = Color(0xFFFBE4DA),
    danger = Color(0xFFB3261E), dangerSoft = Color(0xFFFBE4DA), bankSoft = Color(0xFFECE7F6), bank = Color(0xFF5B3FA0),
)

val DarkPalette = Palette(
    primary = Color(0xFF5FB8A3), primaryPressed = Color(0xFF7CCAB7), primarySurface = Color(0xFF0A3B32),
    primarySoft = Color(0xFF173A33), onPrimary = Color(0xFF06231D), onPrimaryMuted = Color(0xFFB7D9CF),
    accent = Color(0xFFF0B43C), onAccent = Color(0xFF15201C), accentSoft = Color(0xFF3A2E14), onAccentSoft = Color(0xFFF5CF7F),
    ink = Color(0xFFE9ECEA), muted = Color(0xFFA5AEAA), subtle = Color(0xFF7D8783),
    ground = Color(0xFF111614), surface = Color(0xFF1A201E), field = Color(0xFF202725),
    border = Color(0xFF2C3431), borderStrong = Color(0xFF3A4440), divider = Color(0xFF252C2A), track = Color(0xFF252C2A),
    moneyIn = Color(0xFF7FB0EA), moneyInSoft = Color(0xFF1B2A3D), moneyOut = Color(0xFFF08A5D), moneyOutSoft = Color(0xFF3A2219),
    danger = Color(0xFFF2B8B5), dangerSoft = Color(0xFF3A1F1D), bankSoft = Color(0xFF2A2340), bank = Color(0xFFB9A6EC),
)

val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_extrabold, FontWeight.ExtraBold),
)

/** Type scale from the tokens: display 34/800, title 22/800, section 16/700, body 14/500, label 12/600. */
object SabouType {
    private val base = TextStyle(fontFamily = Vazirmatn)
    val display = base.copy(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 44.sp)
    val title = base.copy(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp)
    val section = base.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
    val bodyStrong = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
    val body = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
    val amount = base.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 24.sp)
    val label = base.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
    val caption = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
}

object SabouShapes {
    val card = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val hero = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    val control = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val field = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val chip = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val tile = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

object Sabou {
    val colors: Palette @Composable get() = LocalPalette.current
}

@Composable
fun SabouTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, secondary = p.accent, onSecondary = p.onAccent,
            background = p.ground, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.field, onSurfaceVariant = p.muted, outline = p.borderStrong, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, secondary = p.accent, onSecondary = p.onAccent,
            background = p.ground, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.field, onSurfaceVariant = p.muted, outline = p.borderStrong, error = p.danger,
        )
    }
    val typography = Typography(
        displayLarge = SabouType.display, headlineMedium = SabouType.title, titleLarge = SabouType.title, titleMedium = SabouType.section,
        bodyLarge = SabouType.body, bodyMedium = SabouType.body, bodySmall = SabouType.caption,
        labelLarge = SabouType.bodyStrong, labelMedium = SabouType.label, labelSmall = SabouType.caption,
    )
    CompositionLocalProvider(LocalPalette provides p, LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

package com.hapticsync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hapticsync.model.BandId
import com.hapticsync.model.SoundCategory
import com.hapticsync.model.SoundFamily

/** HapticSync palette: near-black stage, one warm accent, a cool secondary for data. */
object HsColors {
    val Background = Color(0xFF07080B)
    val Surface = Color(0xFF0E1015)
    val SurfaceHigh = Color(0xFF151820)
    val SurfaceHighest = Color(0xFF1C2029)
    val Outline = Color(0xFF2E333D)
    val Accent = Color(0xFFFF6B35)
    val AccentDim = Color(0xFF7A3418)
    val Teal = Color(0xFF59D3C2)
    val Violet = Color(0xFFB69CFF)
    val TextPrimary = Color(0xFFECEEF2)
    val TextSecondary = Color(0xFFA2A8B3)
    val TextMuted = Color(0xFF6C7380)
    val Danger = Color(0xFFFF5A5F)
    val Success = Color(0xFF4CD787)

    fun family(family: SoundFamily): Color = when (family) {
        SoundFamily.VEHICLE -> Accent
        SoundFamily.MUSIC -> Violet
        SoundFamily.CINEMATIC -> Color(0xFFFF4D5E)
        SoundFamily.ENVIRONMENT -> Success
        SoundFamily.BACKGROUND -> TextMuted
    }

    fun category(category: SoundCategory): Color = family(category.family)

    /** Deep to bright, following the frequency of each analysis band. */
    fun band(band: BandId): Color = when (band) {
        BandId.SUB_BASS -> Color(0xFFFF4D5E)
        BandId.BASS -> Accent
        BandId.LOW_MID -> Color(0xFFFFA94D)
        BandId.MID -> Color(0xFFE8D85A)
        BandId.HIGH_MID -> Teal
        BandId.HIGH -> Violet
    }
}

private val ColorScheme = darkColorScheme(
    primary = HsColors.Accent,
    onPrimary = Color(0xFF1C0B03),
    primaryContainer = Color(0xFF3A1A0E),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = HsColors.Teal,
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF123B36),
    onSecondaryContainer = Color(0xFFBDF2EA),
    tertiary = HsColors.Violet,
    onTertiary = Color(0xFF1E0F4D),
    background = HsColors.Background,
    onBackground = HsColors.TextPrimary,
    surface = HsColors.Surface,
    onSurface = HsColors.TextPrimary,
    surfaceVariant = HsColors.SurfaceHigh,
    onSurfaceVariant = HsColors.TextSecondary,
    surfaceContainerLowest = HsColors.Background,
    surfaceContainerLow = HsColors.Surface,
    surfaceContainer = HsColors.SurfaceHigh,
    surfaceContainerHigh = HsColors.SurfaceHighest,
    surfaceContainerHighest = Color(0xFF232833),
    outline = HsColors.Outline,
    outlineVariant = Color(0xFF22262E),
    error = HsColors.Danger,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
}

@Composable
fun HapticSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = AppTypography, content = content)
}

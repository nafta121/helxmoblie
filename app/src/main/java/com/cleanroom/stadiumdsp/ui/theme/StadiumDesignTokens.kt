package com.cleanroom.stadiumdsp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cleanroom.stadiumdsp.preset.BlockCategory

/**
 * Clean-room Helix Stadium Native v1.4 + LIVE Stage Design System Tokens.
 */
object StadiumColors {
    // Deep Stage Dark Surfaces
    val CanvasBlack = Color(0xFF000000)
    val SurfaceDeep = Color(0xFF0A0A0A)
    val SurfaceRail = Color(0xFF0D0D0D)
    val SurfacePanel = Color(0xFF0F0F0F)
    val SurfaceBar = Color(0xFF141414)
    val SurfaceCard = Color(0xFF191919)
    val SurfaceSliderTrack = Color(0xFF1F1F1F)
    val SurfaceHeader = Color(0xFF333333)
    val SurfaceSelected = Color(0xFF424242)
    val BorderSubtle = Color(0xFF262626)
    val BorderStrong = Color(0xFF3E3E3E)

    // Typography & Status Tints
    val TextPrimary = Color(0xFFF2F5F8)
    val TextPresetTitle = Color(0xFFE6F7FF)
    val TextSecondary = Color(0xFFB4B4B4)
    val TextMuted = Color(0xFF7D7D7D)
    val BadgeCyan = Color(0xFF38BDF8)

    // Official 11 Block Category Neon Colors
    val AmpRed = Color(0xFFFF3C3C)
    val CabPink = Color(0xFFFF509A)
    val DistortionAmber = Color(0xFFFF9B2D)
    val DynamicsGold = Color(0xFFE8D62F)
    val EqGold = Color(0xFFE8D62F)
    val ModulationAzure = Color(0xFF00A5FF)
    val DelayEmerald = Color(0xFF43FF40)
    val ReverbCoral = Color(0xFFFF6828)
    val PitchWahViolet = Color(0xFFA25DFF)
    val VolPanCyan = Color(0xFF0FFFFF)
    val RoutingTitanium = Color(0xFF949494)

    // Functional Stage Alerts
    val ClipRed = Color(0xFFFF2A2A)
    val MeterGreen = Color(0xFF43FF40)
    val MeterYellow = Color(0xFFE8D62F)
    val GoldLink = Color(0xFFE8D62F)
}

fun BlockCategory.composeColor(): Color = Color(colorHex)

private val StadiumDarkColorScheme = darkColorScheme(
    primary = StadiumColors.AmpRed,
    onPrimary = Color.White,
    secondary = StadiumColors.ModulationAzure,
    onSecondary = Color.Black,
    tertiary = StadiumColors.DelayEmerald,
    background = StadiumColors.CanvasBlack,
    onBackground = StadiumColors.TextPrimary,
    surface = StadiumColors.SurfaceBar,
    onSurface = StadiumColors.TextPrimary,
    surfaceVariant = StadiumColors.SurfaceCard,
    onSurfaceVariant = StadiumColors.TextSecondary,
    outline = StadiumColors.BorderStrong,
    error = StadiumColors.ClipRed,
)

private val StadiumTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.3.sp,
    ),
)

@Composable
fun StadiumDspTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StadiumDarkColorScheme,
        typography = StadiumTypography,
        content = content,
    )
}

package dev.timber.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Moss = Color(0xFF2F4A3A)
private val Sap = Color(0xFFC4D6A8)
private val Bark = Color(0xFF1A1F1C)
private val Clay = Color(0xFFB86F4A)
private val Mist = Color(0xFFE7EDE4)

private val DarkColors = darkColorScheme(
    primary = Sap,
    onPrimary = Bark,
    secondary = Clay,
    onSecondary = Mist,
    background = Bark,
    onBackground = Mist,
    surface = Color(0xFF232A26),
    onSurface = Mist,
    surfaceVariant = Moss,
    onSurfaceVariant = Sap,
)

private val LightColors = lightColorScheme(
    primary = Moss,
    onPrimary = Mist,
    secondary = Clay,
    onSecondary = Mist,
    background = Mist,
    onBackground = Bark,
    surface = Color(0xFFF3F6F0),
    onSurface = Bark,
    surfaceVariant = Sap,
    onSurfaceVariant = Bark,
)

// Bundled variable fonts can replace these later (Fraunces / Source Sans 3).
private val TimberTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
)

@Composable
fun TimberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = TimberTypography,
        content = content,
    )
}

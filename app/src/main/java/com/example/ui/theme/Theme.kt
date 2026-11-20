package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = StudioObsidian,
    primaryContainer = ElectricCyanContainerDark,
    onPrimaryContainer = ElectricCyan,
    secondary = SignalCoral,
    onSecondary = Color.White,
    secondaryContainer = SignalCoralContainerDark,
    onSecondaryContainer = SignalCoralContainerLight,
    tertiary = EmeraldLive,
    onTertiary = StudioObsidian,
    tertiaryContainer = EmeraldLiveContainerDark,
    onTertiaryContainer = EmeraldLive,
    background = StudioObsidian,
    onBackground = TextPrimaryDark,
    surface = StudioSlate,
    onSurface = TextPrimaryDark,
    surfaceVariant = StudioSlateElevated,
    onSurfaceVariant = TextSecondaryDark,
    outline = StudioBorderDark,
    error = SignalCoral,
    onError = Color.White,
    errorContainer = SignalCoralContainerDark,
    onErrorContainer = SignalCoralContainerLight
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricCyanDark,
    onPrimary = Color.White,
    primaryContainer = ElectricCyanContainerLight,
    onPrimaryContainer = StudioObsidian,
    secondary = SignalCoralDark,
    onSecondary = Color.White,
    secondaryContainer = SignalCoralContainerLight,
    onSecondaryContainer = SignalCoralDark,
    tertiary = EmeraldLive,
    onTertiary = Color.White,
    background = LightCanvas,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = LightOutline,
    error = SignalCoralDark,
    onError = Color.White,
    errorContainer = SignalCoralContainerLight,
    onErrorContainer = SignalCoralDark
)

val TechLiveShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TechLiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TechLiveTypography,
        shapes = TechLiveShapes,
        content = content
    )
}

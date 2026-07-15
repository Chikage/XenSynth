package icu.ringona.xensynth.hexkeyboard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object HexaPalette {
    val BackgroundDark = Color(0xFF0E1313)
    val SurfaceDark = Color(0xFF161D1C)
    val RaisedDark = Color(0xFF1B2221)
    val LineDark = Color(0xFF384A47)
    val PrimaryDark = Color(0xFFEDF5F2)
    val SecondaryDark = Color(0xFF9BAEAA)

    val BackgroundLight = Color(0xFFF4F8F7)
    val SurfaceLight = Color(0xFFFFFFFF)
    val RaisedLight = Color(0xFFE9F0EE)
    val LineLight = Color(0xFFB5C5C1)
    val PrimaryLight = Color(0xFF14201E)
    val SecondaryLight = Color(0xFF536762)

    val Accent = Color(0xFF40C7CC)
    val Selection = Color(0xFFFF9C45)
    val Outline = Color(0xFFAEABFF)

    val PitchColors = listOf(
        Color(0xFF2A7A81),
        Color(0xFF315F8B),
        Color(0xFF40458C),
        Color(0xFF5F3A91),
        Color(0xFF7C347A),
        Color(0xFF8C394C),
        Color(0xFF914D32),
        Color(0xFF806221),
        Color(0xFF68731F),
        Color(0xFF39742B),
        Color(0xFF247044),
        Color(0xFF276B68),
    )
}

private val DarkColors = darkColorScheme(
    primary = HexaPalette.Accent,
    secondary = HexaPalette.Outline,
    tertiary = HexaPalette.Selection,
    background = HexaPalette.BackgroundDark,
    surface = HexaPalette.SurfaceDark,
    surfaceVariant = HexaPalette.RaisedDark,
    outline = HexaPalette.LineDark,
    onPrimary = Color(0xFF002F31),
    onBackground = HexaPalette.PrimaryDark,
    onSurface = HexaPalette.PrimaryDark,
    onSurfaceVariant = HexaPalette.SecondaryDark,
)

@Composable
fun HexaKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        // The instrument surface follows the supplied dark reference UI. Launcher
        // icon backgrounds still switch independently through values-night.
        colorScheme = DarkColors,
        content = content,
    )
}



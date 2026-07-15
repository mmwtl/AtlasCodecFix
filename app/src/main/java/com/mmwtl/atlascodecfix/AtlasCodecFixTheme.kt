package com.mmwtl.atlascodecfix

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Composable
fun AtlasCodecFixTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaledDensity = remember(density.density, density.fontScale) {
        Density(
            density = density.density * APP_UI_SCALE,
            fontScale = density.fontScale
        )
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(colorScheme = appColors, content = content)
    }
}

private const val APP_UI_SCALE = 1.5f

private val appColors = darkColorScheme(
    primary = Color(0xFF7893A0),
    onPrimary = Color(0xFF071014),
    background = Color(0xFF171717),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF262626),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFFD4D4D4),
    outline = Color(0xFF737373)
)

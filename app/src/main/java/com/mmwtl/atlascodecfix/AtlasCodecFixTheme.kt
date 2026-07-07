package com.mmwtl.atlascodecfix

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Composable
fun AtlasCodecFixTheme(content: @Composable () -> Unit) {
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = baseDensity.density * UI_SCALE,
            fontScale = baseDensity.fontScale
        )
    ) {
        MaterialTheme(colorScheme = appColors, content = content)
    }
}

private const val UI_SCALE = 1.5f

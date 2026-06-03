package it.allard.multistream.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002E69),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF141821),
    onSurface = Color(0xFFE3E2E6),
)

@Composable
fun MultistreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

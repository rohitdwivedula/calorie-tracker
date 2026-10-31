package dev.rohit.labelaudit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TrackerColors =
    darkColorScheme(
        primary = Color(0xFFA1D5B5),
        onPrimary = Color(0xFF103824),
        primaryContainer = Color(0xFF294C38),
        secondary = Color(0xFFB3CBC0),
        tertiary = Color(0xFFDDC58C),
        background = Color(0xFF0F1411),
        surface = Color(0xFF161D18),
        surfaceVariant = Color(0xFF28332C),
        onBackground = Color(0xFFE1E9E2),
        onSurface = Color(0xFFE1E9E2),
        onSurfaceVariant = Color(0xFFB9C8BC),
        secondaryContainer = Color(0xFF344B3F),
        onSecondaryContainer = Color(0xFFD2E9DA),
        surfaceDim = Color(0xFF0F1411),
        surfaceBright = Color(0xFF303A33),
        surfaceContainerLowest = Color(0xFF0A100C),
        surfaceContainerLow = Color(0xFF171F19),
        surfaceContainer = Color(0xFF1D261F),
        surfaceContainerHigh = Color(0xFF253027),
        surfaceContainerHighest = Color(0xFF2D382F),
        outline = Color(0xFF849589),
    )

@Composable
fun TrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrackerColors,
        typography = Typography(),
        shapes =
            Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
            ),
        content = content,
    )
}

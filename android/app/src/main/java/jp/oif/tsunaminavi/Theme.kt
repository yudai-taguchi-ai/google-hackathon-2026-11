package jp.oif.tsunaminavi

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Brand {
    val Danger = Color(0xFFC81E1E)
    val Safe = Color(0xFF0F8A4B)
    val Info = Color(0xFF1D4ED8)
    val Warn = Color(0xFFB45309)
    val Teal = Color(0xFF0E7490)
    val Training = Color(0xFF1D4ED8)
    val Demo = Color(0xFF7C3AED)
}

private val Light = lightColorScheme(
    primary = Brand.Safe,
    onPrimary = Color.White,
    secondary = Brand.Info,
    error = Brand.Danger,
    background = Color(0xFFF4F6FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF2F7),
    onSurface = Color(0xFF0B1220),
    onSurfaceVariant = Color(0xFF5B6577),
    outline = Color(0xFFDBE1EA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF30A46C),
    onPrimary = Color.White,
    secondary = Color(0xFF6E9BFF),
    error = Color(0xFFE5484D),
    background = Color(0xFF0B1220),
    surface = Color(0xFF121A2B),
    surfaceVariant = Color(0xFF1A2438),
    onSurface = Color(0xFFEEF2F8),
    onSurfaceVariant = Color(0xFF9AA6BB),
    outline = Color(0xFF263248),
)

@Composable
fun TsunamiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
        ),
        content = content,
    )
}

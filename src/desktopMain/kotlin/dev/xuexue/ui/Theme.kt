package dev.xuexue.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val AppColors = lightColorScheme(
    primary = Color(0xFF2F6758),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E9E1),
    onPrimaryContainer = Color(0xFF173B32),
    secondary = Color(0xFF86582F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3DFCC),
    onSecondaryContainer = Color(0xFF452A13),
    tertiary = Color(0xFF4B6288),
    onTertiary = Color.White,
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF8FAF8),
    onBackground = Color(0xFF1A1D1B),
    surface = Color(0xFFF8FAF8),
    onSurface = Color(0xFF1A1D1B),
    surfaceVariant = Color(0xFFE8ECE9),
    onSurfaceVariant = Color(0xFF414845),
    outline = Color(0xFF737A76),
    outlineVariant = Color(0xFFC3C9C5),
)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)

val CodeFont = FontFamily.Monospace

@Composable
fun XueXueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        content = content,
    )
}


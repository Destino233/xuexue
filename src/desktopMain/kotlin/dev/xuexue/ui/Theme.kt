@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AppColors =
    lightColorScheme(
        primary = Color(0xFF286B59),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCECE6),
        onPrimaryContainer = Color(0xFF163D33),
        secondary = Color(0xFF8A572D),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF2E3D5),
        onSecondaryContainer = Color(0xFF452A13),
        tertiary = Color(0xFF52627A),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE2E8F1),
        onTertiaryContainer = Color(0xFF263448),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF3F5F4),
        onBackground = Color(0xFF202422),
        surface = Color(0xFFFBFCFB),
        onSurface = Color(0xFF202422),
        surfaceVariant = Color(0xFFEBEFED),
        onSurfaceVariant = Color(0xFF535B57),
        surfaceTint = Color(0xFF286B59),
        surfaceDim = Color(0xFFD8DDDA),
        surfaceBright = Color(0xFFFBFCFB),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF5F7F6),
        surfaceContainer = Color(0xFFEFF2F0),
        surfaceContainerHigh = Color(0xFFE9EDEA),
        surfaceContainerHighest = Color(0xFFE3E8E5),
        outline = Color(0xFF68716D),
        outlineVariant = Color(0xFFD5DBD8),
        inverseSurface = Color(0xFF2E322F),
        inverseOnSurface = Color(0xFFF0F2F0),
        inversePrimary = Color(0xFFA8D6C6),
        scrim = Color(0xFF000000),
    )

private val AppTypography =
    Typography(
        bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelLarge =
            TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
            ),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    )

private val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(3.dp),
        small = RoundedCornerShape(5.dp),
        medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(8.dp),
    )

val CodeFont = FontFamily.Monospace

@Composable
fun XueXueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

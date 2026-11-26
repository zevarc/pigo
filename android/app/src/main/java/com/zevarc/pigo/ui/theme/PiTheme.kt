package com.zevarc.pigo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FB4FF),
    secondary = Color(0xFF9FD8B3),
    tertiary = Color(0xFFD0A8FF),
    error = Color(0xFFFF8A80),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2C5FD8),
    secondary = Color(0xFF2F7D54),
    tertiary = Color(0xFF6B3FA0),
)

/** Monospace body text: agent output is code, alignment matters. */
private val Mono = FontFamily.Monospace

private val PiTypography = Typography().let { base ->
    Typography(
        displaySmall = base.displaySmall,
        headlineSmall = base.headlineSmall,
        titleLarge = base.titleLarge,
        titleMedium = base.titleMedium,
        bodyLarge = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = TextStyle(fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 16.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun PiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = colors, typography = PiTypography, content = content)
}

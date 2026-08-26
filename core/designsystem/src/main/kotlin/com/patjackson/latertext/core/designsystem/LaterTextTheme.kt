package com.patjackson.latertext.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF256A53),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2EAD7),
    onPrimaryContainer = Color(0xFF06301F),
    secondary = Color(0xFF50645A),
    background = Color(0xFFFDFBF7),
    surface = Color(0xFFFDFBF7),
    surfaceVariant = Color(0xFFECEAE5),
    outline = Color(0xFF777972),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93D8BA),
    onPrimary = Color(0xFF06301F),
    primaryContainer = Color(0xFF0C3F2C),
    onPrimaryContainer = Color(0xFFABF0D3),
    background = Color(0xFF111411),
    surface = Color(0xFF111411),
    surfaceVariant = Color(0xFF292D29),
)

@Composable
fun LaterTextTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}

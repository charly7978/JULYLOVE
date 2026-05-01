package com.julylove.medical.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = MedicalGreen,
    secondary = MedicalCyan,
    tertiary = MedicalAmber,
    background = MedicalBlack,
    surface = MedicalDarkGray,
    onPrimary = MedicalBlack,
    onSecondary = MedicalBlack,
    onTertiary = MedicalBlack,
    onBackground = MedicalGreen,
    onSurface = MedicalGreen,
)

@Composable
fun JULYLOVETheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

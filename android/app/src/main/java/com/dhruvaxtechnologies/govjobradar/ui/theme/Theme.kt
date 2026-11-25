package com.dhruvaxtechnologies.govjobradar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Teal = Color(0xFF0B5563)
val TealLight = Color(0xFF7FC8D3)
val Amber = Color(0xFFE8A33D)
val Danger = Color(0xFFC62828)
val Ok = Color(0xFF2E7D32)
val Warn = Color(0xFFEF6C00)

private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFCDEBEF), onPrimaryContainer = Color(0xFF00363D),
    secondary = Amber, onSecondary = Color(0xFF2A1800), secondaryContainer = Color(0xFFFFE3B8),
    onSecondaryContainer = Color(0xFF2A1800), background = Color(0xFFF6F8F9), surface = Color.White,
    surfaceVariant = Color(0xFFE6ECEE), onSurfaceVariant = Color(0xFF46555C), outline = Color(0xFFB5C2C7),
)
private val Dark = darkColorScheme(
    primary = TealLight, onPrimary = Color(0xFF00363D), primaryContainer = Color(0xFF0B4F59),
    onPrimaryContainer = Color(0xFFCDEBEF), secondary = Color(0xFFFFC56E), onSecondary = Color(0xFF452B00),
    secondaryContainer = Color(0xFF5C3F00), background = Color(0xFF0F1416), surface = Color(0xFF161C1E),
    surfaceVariant = Color(0xFF253034), onSurfaceVariant = Color(0xFFB9C7CC),
)

private val Type = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun GovJobTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}

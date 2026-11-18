package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val TacticalDarkColorScheme =
  darkColorScheme(
    primary = CautionAmber,
    onPrimary = OnAccentDark,
    secondary = TacticalCyan,
    onSecondary = OnAccentDark,
    tertiary = ReadyEmerald,
    onTertiary = OnAccentDark,
    error = HazardCrimson,
    onError = TextPrimary,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = TacticalSurface,
    onSurface = TextPrimary,
    surfaceVariant = TacticalSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = HudBorderSubtle,
  )

private val TacticalLightColorScheme =
  lightColorScheme(
    primary = CautionAmber,
    onPrimary = OnAccentDark,
    secondary = TacticalCyan,
    onSecondary = OnAccentDark,
    tertiary = ReadyEmerald,
    onTertiary = OnAccentDark,
    error = HazardCrimson,
    onError = TextPrimary,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = TacticalSurface,
    onSurface = TextPrimary,
    surfaceVariant = TacticalSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = HudBorderSubtle,
  )

@Composable
fun RoadWatchTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> TacticalDarkColorScheme
      else -> TacticalLightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

package dev.dsh.nativeapp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Appearance preference, matching the web app's three-way selector. */
enum class AppearanceMode { Light, Dark, System }

val LocalDshColors = staticCompositionLocalOf { LightColors }

@Composable
fun DshTheme(
    mode: AppearanceMode = AppearanceMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        AppearanceMode.Light -> false
        AppearanceMode.Dark -> true
        AppearanceMode.System -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkColors else LightColors

    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.brandPrimary,
            onPrimary = colors.buttonPrimaryForeground,
            background = colors.bgBase,
            onBackground = colors.labelPrimary,
            surface = colors.bgBase,
            onSurface = colors.labelPrimary,
            surfaceVariant = colors.bgLayer2,
            onSurfaceVariant = colors.labelSecondary,
            outline = colors.borderL3,
            error = colors.stateError,
        )
    } else {
        lightColorScheme(
            primary = colors.brandPrimary,
            onPrimary = colors.buttonPrimaryForeground,
            background = colors.bgBase,
            onBackground = colors.labelPrimary,
            surface = colors.bgBase,
            onSurface = colors.labelPrimary,
            surfaceVariant = colors.bgLayer2,
            onSurfaceVariant = colors.labelSecondary,
            outline = colors.borderL3,
            error = colors.stateError,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalDshColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

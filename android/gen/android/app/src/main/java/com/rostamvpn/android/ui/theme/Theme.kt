// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** The surface-container ramp — the roles Material uses for CARDS and MENUS. */
private fun lightSurface(fraction: Float): Color =
    BrandPalette.ink.copy(alpha = fraction).compositeOver(BrandPalette.background)

/** Dark ramp. */
private val DarkSurfaceBase = Color(0xFF1E1E1E)
private fun darkSurface(fraction: Float): Color =
    Color.White.copy(alpha = fraction).compositeOver(DarkSurfaceBase)

private val RostamLightColorScheme = lightColorScheme(
    primary = Brand1,
    onPrimary = Color.White,
    primaryContainer = Brand2,
    onPrimaryContainer = DarkBase,
    secondary = Accent2,
    onSecondary = Color.White,
    secondaryContainer = Accent3,
    onSecondaryContainer = DarkBase,
    tertiary = Accent1,
    onTertiary = Color.White,
    background = BaseBg,
    onBackground = DarkBase,
    surface = Color.White,
    onSurface = DarkBase,
    surfaceVariant = LightGray1,
    onSurfaceVariant = LightText,
    error = Danger,
    onError = Color.White,
    outline = LightGray2,
    outlineVariant = LightGray3,
    surfaceBright = Color.White.copy(alpha = 0.60f).compositeOver(BrandPalette.background),
    surfaceDim = lightSurface(0.18f),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White.copy(alpha = 0.35f).compositeOver(BrandPalette.background),
    surfaceContainer = lightSurface(0.04f),
    surfaceContainerHigh = lightSurface(0.07f),
    surfaceContainerHighest = lightSurface(0.10f),
)

private val RostamDarkColorScheme = darkColorScheme(
    primary = Brand2,
    onPrimary = Color.Black,
    primaryContainer = Brand1,
    onPrimaryContainer = Color.White,
    secondary = Accent2,
    onSecondary = Color.Black,
    secondaryContainer = Accent3,
    onSecondaryContainer = Color.White,
    tertiary = Accent1,
    onTertiary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = Danger,
    onError = Color.Black,
    outline = Color(0xFF424242),
    outlineVariant = Color(0xFF333333),
    surfaceBright = Color(0xFF2C2C2C),
    surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF0B0B0B),
    surfaceContainerLow = Color(0xFF171717),
    surfaceContainer = darkSurface(0.02f),
    surfaceContainerHigh = darkSurface(0.035f),
    surfaceContainerHighest = darkSurface(0.05f),
)

@Composable
fun RostamTheme(
    themeMode: String = "auto",
    content: @Composable () -> Unit
) {
    val light = when (themeMode) {
        "dark" -> false
        "light" -> true
        else -> !isSystemInDarkTheme()
    }
    val colorScheme = if (light) RostamLightColorScheme else RostamDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.activityOrNull()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RostamTypography,
        content = content
    )
}

/** Paint the system bar ICONS for a full-screen surface that owns its GROUND. */
@Composable
fun SystemBarsFor(ground: Color) {
    val light = mostLegibleOn(ground, Color.White, DarkBase) == DarkBase
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(light, view) {
        val window = view.context.activityOrNull()?.window
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = c?.isAppearanceLightStatusBars
        val prevNav = c?.isAppearanceLightNavigationBars
        c?.isAppearanceLightStatusBars = light
        c?.isAppearanceLightNavigationBars = light
        onDispose {
            if (c != null) {
                prevStatus?.let { c.isAppearanceLightStatusBars = it }
                prevNav?.let { c.isAppearanceLightNavigationBars = it }
            }
        }
    }
}

/**
 * Compose hands out a ContextWrapper, not the Activity. Unwrap to reach the
 * window.
 */
private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

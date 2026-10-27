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
import com.rostamvpn.android.ui.screens.BrandStyle
import com.rostamvpn.android.ui.screens.rememberBrandStyle

/**
 * The surface-container ramp — the roles Material uses for CARDS and MENUS.
 *
 * Neither scheme set them, so every default `Card` in the app rendered Google's
 * baseline neutral instead of a RostamVPN colour. `FilledCardTokens.ContainerColor`
 * is `SurfaceContainerHighest`, and `ColorLightTokens.SurfaceContainerHighest` is
 * `PaletteTokens.Neutral90` = #E6E0E9 — a purple-tinted lavender. That is the
 * measured fill of the Region card on the main screen, and it is why that card and
 * the upgrade banner above it read as two different design languages: neither one
 * was using the design system. (Verified against the resolved artifact,
 * ~/.gradle/caches/8.14.4/transforms/eabae44f…/material3/jars/classes.jar — note the
 * 8.14.3 copy in the same cache says `SurfaceVariant`; the wrapper is 8.14.4, so the
 * 8.14.4 transform is the one that ships.)
 *
 * Derived, not hardcoded, so a new brand is still a JSON edit: the ramp walks the
 * brand's OWN `background` toward its OWN `ink`. Deliberately NEUTRAL rather than
 * tinted toward `primary`. Tinting toward primary was tried first and rejected:
 * it is fine for a teal brand and breaks a saturated one. At an equivalent step
 * RedVPN (primary #E2232B) came out with pink cards (#F9E4E5) carrying its own
 * primary as text at 3.73:1, and in dark a maroon card carrying #69108C at
 * 1.48:1 — illegible. Every brand's `ink` is a near-neutral dark and every
 * `background` a near-neutral light, so tinting background→ink instead keeps each
 * brand's own temperature (Rostam cool #E1E2E2, DollarVPN warm #E2DED6, RedVPN a
 * plain #E5E6E7) and cannot produce a hue accident for a brand added later.
 *
 * The step sizes are chosen so the card keeps TODAY'S visual weight and only
 * changes hue: Rostam's #E1E2E2 sits at 1.19:1 against the #F5F5F5 page, where the
 * lavender it replaces sat at 1.19:1. Nothing gets louder or quieter; the screen's
 * hierarchy problem is fixed structurally, not by re-tuning this.
 *
 * WARNING — `compositeOver`, NOT `lerp`. They are not the same function here.
 * `Color.lerp` converts both ends to OKLAB, interpolates, and converts back
 * (verified in the bytecode of the resolved ui-graphics jar), so its result is a
 * perceptual blend whose sRGB value is not the one written above — and it would
 * move silently if a future Compose changed that space, recolouring every card in
 * the app for 691K installs. `compositeOver` composites per channel in the
 * destination's own space, which makes these exactly "4% / 7% / 10% of the
 * brand's ink over the brand's background" and makes the hexes above literally
 * true. Do not "simplify" this back.
 */
private fun lightSurface(fraction: Float): Color =
    BrandPalette.ink.copy(alpha = fraction).compositeOver(BrandPalette.background)

/**
 * Dark ramp. Tints the dark surface toward white — NOT toward the brand, for the
 * same reason as above — and lands `surfaceContainerHighest` on #292929, between
 * the two greys this scheme already picked by hand (`surface` #1E1E1E,
 * `surfaceVariant` #2C2C2C), so it is consistent with a deliberately neutral dark
 * scheme rather than a fourth invented value.
 */
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

/**
 * THE DARK-GROUNDED TREATMENT — RedVPN.
 *
 * "dark background with red accent, like tor". That is an IDENTITY, not a
 * preference, which is why this is a scheme the treatment selects rather than
 * the dark half of a light brand: a brand cannot be dark-grounded if half its
 * installs render it light because of an OS setting.
 *
 * It is NOT RostamDarkColorScheme with different accents. That scheme is
 * deliberately neutral grey (#121212 / #1E1E1E / #2C2C2C), and a neutral grey
 * dark app reads as "the OS dark theme happened to it". Tor's ground is an
 * indigo-violet and the hue is most of why it looks like itself. Here the
 * ground carries 10% of the brand's own accent (#191020 for RedVPN) and the
 * card sits one 7% step above it (#292130) — DERIVED in gen-branding.py and
 * read from BrandPalette, so this file and the generator cannot disagree, and
 * a sixth brand gets its own ground with nothing to author.
 *
 * ⚠️ `primary` is LIFTED, and that is a bug fix rather than a preference. The
 * shared dark scheme sets `primary = Brand2`, which for RedVPN is its PURPLE
 * secondary — that is why the region card's value text rendered purple on the
 * dark home screen, a defect reported against the previous build. Here primary
 * is the brand's own red raised only as far as legibility requires: #E2232B
 * measures 3.97:1 on this ground, which clears 3:1 for a graphic and fails 4.5
 * for the 14sp text that actually uses it, so liftOnto takes it to #E6444B at
 * 4.66:1. The hue is kept; only the saturation legibility costs is spent.
 */
/**
 * The rings treatment in LIGHT.
 *
 * It exists because the app has a theme setting and, without this, choosing
 * "Light" on a rings brand did nothing at all: `forcesDark` short-circuited
 * ahead of the explicit choice, so both modes rendered the same dark screen and
 * the control silently lied. A setting that does nothing is worse than an
 * absent one.
 *
 * Dark stays the DEFAULT and the identity. This is only reached when the user
 * has explicitly asked for light, which is a request the app should honour:
 * some people need it, in sunlight or for readability, and a brand cannot claim
 * that need away.
 *
 * `primary` is the brand's DEEP red (#BB0008, 6.57:1 on white) rather than the
 * standard #E2232B (4.54:1). The lighter red clears body text by 0.04, which is
 * a rounding error and not a margin. The mark itself is unchanged: it already
 * sits on a white disc in every state for this brand, so it reads here exactly
 * as it does on dark.
 */
private val RingsLightColorScheme = lightColorScheme(
    primary = liftOnto(Color.White, Brand1, min = 4.5f),
    onPrimary = Color.White,
    primaryContainer = Brand1,
    onPrimaryContainer = Color.White,
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
    surfaceBright = Color.White,
    surfaceDim = lightSurface(0.18f),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = lightSurface(0.02f),
    surfaceContainer = lightSurface(0.04f),
    surfaceContainerHigh = lightSurface(0.07f),
    surfaceContainerHighest = lightSurface(0.10f),
)

private val RingsDarkColorScheme = darkColorScheme(
    primary = liftOnto(BrandPalette.groundDark, Brand1, min = 4.5f),
    onPrimary = Color.White,
    primaryContainer = Brand1,
    onPrimaryContainer = Color.White,
    secondary = Accent2,
    onSecondary = Color.White,
    // The brand's accent, unmoved: it is the gradient's cool end on the pill
    // and the paywall, and both of those carry WHITE on top of it (10.23:1),
    // so it never needs lifting for its own use.
    secondaryContainer = Accent3,
    onSecondaryContainer = Color.White,
    tertiary = Accent1,
    onTertiary = Color.White,
    background = BrandPalette.groundDark,
    onBackground = Color(0xFFF4F1F6),
    surface = BrandPalette.cardDark,
    onSurface = Color(0xFFF4F1F6),
    surfaceVariant = Color.White.copy(alpha = 0.12f).compositeOver(BrandPalette.groundDark),
    // A muted that keeps the ground's temperature instead of going grey.
    onSurfaceVariant = Color(0xFFA9A2B4),
    error = liftOnto(BrandPalette.groundDark, Danger, min = 4.5f),
    onError = Color.White,
    outline = Color.White.copy(alpha = 0.22f).compositeOver(BrandPalette.groundDark),
    outlineVariant = Color.White.copy(alpha = 0.12f).compositeOver(BrandPalette.groundDark),
    surfaceBright = Color.White.copy(alpha = 0.14f).compositeOver(BrandPalette.groundDark),
    surfaceDim = BrandPalette.groundDark,
    surfaceContainerLowest = BrandPalette.groundDark,
    surfaceContainerLow = Color.White.copy(alpha = 0.04f).compositeOver(BrandPalette.groundDark),
    surfaceContainer = Color.White.copy(alpha = 0.055f).compositeOver(BrandPalette.groundDark),
    surfaceContainerHigh = Color.White.copy(alpha = 0.07f).compositeOver(BrandPalette.groundDark),
    surfaceContainerHighest = Color.White.copy(alpha = 0.09f).compositeOver(BrandPalette.groundDark),
)

/**
 * The `tabs` treatment: the locked Internet Pro tokens, one role each.
 * surface2 -> surfaceVariant, muted -> onSurfaceVariant, dim -> outline,
 * accent -> primary. Light uses accentStrong as primary because white on the
 * light accent is 3.74:1, under AA for button text.
 */
private val TabsDarkColorScheme = darkColorScheme(
    primary = TabsDark.accent,
    onPrimary = TabsDark.onAccent,
    primaryContainer = TabsDark.accent.copy(alpha = 0.14f).compositeOver(TabsDark.surface),
    onPrimaryContainer = TabsDark.accentStrong,
    secondary = TabsDark.accent2,
    onSecondary = TabsDark.onAccent,
    tertiary = TabsDark.accentStrong,
    onTertiary = TabsDark.onAccent,
    background = TabsDark.bg,
    onBackground = TabsDark.text,
    surface = TabsDark.surface,
    onSurface = TabsDark.text,
    surfaceVariant = TabsDark.surface2,
    onSurfaceVariant = TabsDark.muted,
    error = Color(0xFFF87171),
    onError = TabsDark.bg,
    outline = TabsDark.dim,
    outlineVariant = TabsDark.border.compositeOver(TabsDark.bg),
    surfaceBright = TabsDark.surface2,
    surfaceDim = TabsDark.bg,
    surfaceContainerLowest = TabsDark.bg,
    surfaceContainerLow = TabsDark.bg2,
    surfaceContainer = TabsDark.surface,
    surfaceContainerHigh = TabsDark.surface2,
    surfaceContainerHighest = TabsDark.surface2,
)

private val TabsLightColorScheme = lightColorScheme(
    primary = TabsLight.accentStrong,
    onPrimary = TabsLight.onAccent,
    primaryContainer = TabsLight.accent.copy(alpha = 0.12f).compositeOver(TabsLight.surface),
    onPrimaryContainer = TabsLight.accentStrong,
    secondary = TabsLight.accent2,
    onSecondary = TabsLight.onAccent,
    tertiary = TabsLight.accent,
    onTertiary = TabsLight.onAccent,
    background = TabsLight.bg,
    onBackground = TabsLight.text,
    surface = TabsLight.surface,
    onSurface = TabsLight.text,
    surfaceVariant = TabsLight.surface2,
    onSurfaceVariant = TabsLight.muted,
    error = Danger,
    onError = Color.White,
    outline = TabsLight.dim,
    outlineVariant = TabsLight.border.compositeOver(TabsLight.bg),
    surfaceBright = TabsLight.surface,
    surfaceDim = TabsLight.bg2,
    surfaceContainerLowest = TabsLight.surface,
    surfaceContainerLow = TabsLight.bg,
    surfaceContainer = TabsLight.bg2,
    surfaceContainerHigh = TabsLight.surface2,
    surfaceContainerHighest = TabsLight.surface2,
)

@Composable
fun RostamTheme(
    themeMode: String = "auto",
    content: @Composable () -> Unit
) {
    // A DARK-GROUNDED treatment is the brand's DEFAULT, not an override of the
    // user. It applies when nobody has asked for anything, which is why a rings
    // brand looks dark in both system modes. An explicit choice in Settings
    // still wins: it previously did not, and a control that silently does
    // nothing is worse than one that is absent.
    val style = rememberBrandStyle()
    // ORDER MATTERS. An explicit user choice outranks the brand default, or the
    // theme setting is a control that does nothing. `forcesDark` sets what the
    // brand looks like when nobody has asked for anything; it does not get to
    // overrule someone who has.
    val light = when {
        themeMode == "dark" -> false
        themeMode == "light" -> true
        style.forcesDark -> false
        else -> !isSystemInDarkTheme()
    }
    val colorScheme = when {
        style == BrandStyle.TABS -> if (light) TabsLightColorScheme else TabsDarkColorScheme
        light -> if (style.forcesDark) RingsLightColorScheme else RostamLightColorScheme
        else -> if (style.forcesDark) RingsDarkColorScheme else RostamDarkColorScheme
    }

    // ── The system bars follow THIS scheme, not the phone's night setting ──
    //
    // MainActivity calls the no-argument enableEdgeToEdge(), whose SystemBarStyle
    // .auto picks the bar ICON colour from the resource configuration's night
    // mode. That is the phone's setting, and it is not what the app painted: the
    // scheme above is decided by the user's Theme choice and the brand default.
    // The two disagree exactly when someone overrides, and then the clock, the
    // wifi glyph and the signal bars are drawn in the wrong colour on a ground
    // that cannot hold them.
    //
    // Seen on R5CY13EDP7A (night mode on) with RedVPN set to Light: white status
    // icons on a #FCFCFC ground, the clock barely readable and the signal bars
    // gone. The mirror case, Dark on a light-mode phone, is dark-on-dark.
    //
    // Driving both flags off `light` makes the bars a property of what is
    // rendered instead of a property of the device.
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

/**
 * Paint the system bar ICONS for a full-screen surface that owns its GROUND.
 *
 * [RostamTheme] sets the bars from the app's colour scheme, which is right for
 * everything that sits on `scheme.background`. The paywall does not: it paints
 * its own gradient, and on a brand whose theme is light while the paywall is
 * dark the two disagree and the clock goes dark-on-dark. Seen on RedVPN in
 * Light: the paywall ground measures 33,24,40 and the bar icons were black.
 *
 * Restores whatever was set before on dispose, so closing the surface hands the
 * bars back to the theme rather than leaving them on the last screen's setting.
 */
@Composable
fun SystemBarsFor(ground: Color) {
    // Not luminance > 0.5: a mid-tone ground sits either side of that line
    // without saying which icon colour actually reads on it. Ask the same
    // question the rest of the palette asks.
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

/** Compose hands out a ContextWrapper, not the Activity. Unwrap to reach the window. */
private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

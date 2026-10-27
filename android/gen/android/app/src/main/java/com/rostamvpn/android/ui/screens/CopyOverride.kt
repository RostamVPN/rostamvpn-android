package com.rostamvpn.android.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rostamvpn.android.ConfigManager

/**
 * `stringResource`, with a server-supplied override.
 *
 * The paywall is the screen whose wording is worth tuning, and every tune used
 * to cost a release on five listings. `paywall.strings` in the OTA config now
 * overrides any string on it, keyed by RESOURCE NAME so a line added later is
 * tunable without touching this file.
 *
 * 🚨 THE LOCALE RULE IS THE WHOLE DESIGN. An override applies only to the
 * language it was written for. There is no cross-locale fallback, so supplying
 * `en` tunes English and leaves the other 37 translations alone. Falling back
 * to a supplied `en` would push English onto every Persian, Arabic, Russian and
 * Chinese device — §17, which is precisely how `app_name` made RedVPN display
 * "RostamVPN" to five languages.
 *
 * Resolution: exact tag ("pt-br") -> language ("pt") -> the baked resource.
 */
@Composable
private fun overrideFor(@StringRes id: Int): String? {
    val overrides = ConfigManager.copyOverrides
    if (overrides.isEmpty()) return null
    val ctx = LocalContext.current
    val name = runCatching { ctx.resources.getResourceEntryName(id) }.getOrNull() ?: return null
    val byLang = overrides[name] ?: return null

    val locale = LocalConfiguration.current.locales[0] ?: return null
    val tag = locale.toLanguageTag().lowercase()
    byLang[tag]?.let { return it }
    return byLang[locale.language.lowercase()]
}

/** [stringResource] with a config override for the CURRENT locale only. */
@Composable
fun cfgString(@StringRes id: Int): String =
    overrideFor(id) ?: stringResource(id)

/**
 * Format-arg variant.
 *
 * ⚠️ An override MUST carry the same placeholders as the resource it replaces.
 * A missing `%1$s` is not a crash — `format` ignores extra arguments — but an
 * EXTRA one throws `IllegalFormatException` on that language only, which is the
 * failure `tools/i18n/validate-translations.py` exists to catch and which the
 * config path bypasses entirely. Formatting is guarded so a bad override
 * degrades to the translated resource instead of taking the screen down.
 */
@Composable
fun cfgString(@StringRes id: Int, vararg args: Any): String {
    val raw = overrideFor(id) ?: return stringResource(id, *args)
    return runCatching { String.format(raw, *args) }.getOrElse { stringResource(id, *args) }
}

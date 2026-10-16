// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rostamvpn.android.ConfigManager

/** `stringResource`, with a server-supplied override. */
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

/** Format-arg variant. */
@Composable
fun cfgString(@StringRes id: Int, vararg args: Any): String {
    val raw = overrideFor(id) ?: return stringResource(id, *args)
    return runCatching { String.format(raw, *args) }.getOrElse { stringResource(id, *args) }
}

package com.rostamvpn.android

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * Play build: no sign-in field of any kind. Play rejected 453 under App access
 * because a reviewer reads a credential field as a login wall (CLAUDE.md, "IN-APP
 * SIGN-IN IS GONE"), so the email form lives only in src/direct and is not even
 * compiled here. Account links (`/me`, `/login`, `/account`) open the paywall,
 * which carries the account code and Restore.
 */
object WebAccount {
    @Suppress("UNUSED_PARAMETER")
    fun available(ctx: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Screen(vm: VpnViewModel, onBack: () -> Unit) {}

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun AccountRows(vm: VpnViewModel) {}
}

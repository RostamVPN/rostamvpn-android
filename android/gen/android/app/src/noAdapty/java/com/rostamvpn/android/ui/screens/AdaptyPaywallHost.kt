package com.rostamvpn.android.ui.screens

import androidx.compose.runtime.Composable
import com.rostamvpn.android.AdaptyService
import com.rostamvpn.android.VpnViewModel

/**
 * The no-SDK twin of `src/adapty/.../AdaptyPaywallHost.kt`, for a brand with no
 * `adaptyPublicKey`.
 *
 * This is not a degraded path. Read the real host: on a keyless brand
 * `flowFor()` returns null and `productsFor()` is empty, `BuildConfig.ADAPTY_ONLY`
 * is false, and every branch -- entitled or not -- therefore ends at
 * `PaywallScreen`. So does this one. The remote-flow machinery in between was
 * unreachable code carrying a linked SDK behind it.
 *
 * `placement` is kept in the signature, unused, because MainActivity passes it
 * and the two source sets must present the same API.
 */
@Composable
fun AdaptyPaywallHost(
    viewModel: VpnViewModel,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") placement: String = AdaptyService.PLACEMENT_MAIN,
) {
    PaywallScreen(viewModel = viewModel, onBack = onBack)
}

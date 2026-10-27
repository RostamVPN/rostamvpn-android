package com.rostamvpn.android.ui.screens

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.adapty.errors.AdaptyError
import com.adapty.models.AdaptyPaywallProduct
import com.adapty.models.AdaptyPurchaseResult
import com.adapty.ui.AdaptyFlowScreen
import com.adapty.ui.AdaptyUI
import com.adapty.ui.listeners.AdaptyFlowDefaultEventListener
import com.rostamvpn.android.AdaptyService
import com.rostamvpn.android.VpnViewModel

/**
 * The remote paywall when Adapty served one, ours otherwise.
 *
 * 🚨 THE FALLBACK IS THE POINT, not a safety net. [AdaptyService.config] is null
 * whenever api.adapty.io could not be reached, or the flow returned no priced
 * products — and on a censored network BEFORE the tunnel is up, unreachable is
 * the ordinary case. A remote paywall is an upgrade; it is never allowed to
 * become the reason somebody cannot pay.
 */
@Composable
fun AdaptyPaywallHost(
    viewModel: VpnViewModel,
    onBack: () -> Unit,
    placement: String = AdaptyService.PLACEMENT_MAIN,
) {
    val cfg = AdaptyService.flowFor(placement)
    val products = AdaptyService.productsFor(placement)

    // 🚨 A SUBSCRIBER MUST NEVER BE SOLD WHAT THEY ALREADY OWN.
    //
    // The Adapty flow is static marketing: it has no entitled state, so a paid
    // user tapping the drawer's Premium row was shown the plan ladder and a
    // "Premium NOW" button for a subscription already active on their account.
    // Observed on R5CY13EDP7A, whose cid is genuinely `tier=paid` with a live
    // Play token -- regions unlocked, ads off, and the paywall still selling.
    //
    // `PaywallScreen` renders a real entitled state -- hero, tagline, perks and
    // the Play cancel/change disclosure, with no ladder, no CTA and no
    // disclosure to sign -- so an entitled user is routed there, the same way an
    // unreachable Adapty is. Deliberately NOT a change to the flow: the
    // remote paywall is content we do not control, and the entitled state is not
    // something a marketing screen should be trusted to get right.
    val isPaid by viewModel.isPaid.collectAsState()

    // The ENTITLED state is always ours. Adapty has no such state, so this is
    // not the "old paywall" -- it is the subscription status screen, and an
    // adapty-only brand still needs one.
    if (isPaid) {
        PaywallScreen(viewModel = viewModel, onBack = onBack)
        return
    }

    // 🚨 ADAPTY-ONLY BRANDS DO NOT FALL BACK TO THE BUILT-IN OFFER.
    // Owner's call, 2026-09-26: redvpn and dollarvpn sell through Adapty and
    // nothing else, so the legacy selling screen must never appear on them.
    //
    // What replaces it is a RETRY, not a blank: `AdaptyService.preload()` now
    // fetches cache-first, so the common case is that a previously loaded flow
    // renders with no network at all. This branch is the genuinely-never-loaded
    // case -- first run behind a block -- and a button that can try again is
    // the only honest thing to show, because the alternative is a screen that
    // asks for money and cannot take it.
    if (cfg == null || products.isEmpty()) {
        if (BuildConfig.ADAPTY_ONLY) {
            AdaptyUnavailable(onRetry = { AdaptyService.reload() }, onBack = onBack)
        } else {
            PaywallScreen(viewModel = viewModel, onBack = onBack)
        }
        return
    }

    val listener = remember(viewModel, onBack) {
        object : AdaptyFlowDefaultEventListener() {
            override fun onPurchaseFinished(
                result: AdaptyPurchaseResult,
                product: AdaptyPaywallProduct,
                context: Context,
            ) {
                // 🚨 THE CASHIER REMAINS THE ENTITLEMENT AUTHORITY. An
                // Adapty-rendered paywall buys through its OWN billing wrapper,
                // so PlayBilling never runs and /iap/verify is never called --
                // the user pays Google and the cashier keeps serving the free
                // tier. Restoring re-verifies what Play currently considers
                // active against the cashier, which is the tested path.
                AdaptyService.reconcileAfterPurchase(viewModel)
                onBack()
            }

            override fun onPurchaseFailure(
                error: AdaptyError,
                product: AdaptyPaywallProduct,
                context: Context,
            ) {
                Log.w("AdaptyPaywall", "purchase failed: ${error.message}")
            }

            override fun onError(error: AdaptyError, context: Context) {
                // A render or script fault. Close rather than strand the user on
                // a broken screen; the next open falls back to ours.
                Log.w("AdaptyPaywall", "render error: ${error.message}")
                onBack()
            }

            override fun onFlowClosed() = onBack()

            // 🚨 THE FLOW'S OWN CLOSE BUTTON. AdaptyFlowScreen is a COMPOSABLE
            // rendered as an overlay, not an Activity, so the default
            // Close handling -- which finishes a host Activity -- does nothing
            // here. Without this the X on the paywall is inert.
            override fun onActionPerformed(action: AdaptyUI.Action, context: Context) {
                when (action) {
                    is AdaptyUI.Action.Close -> onBack()
                    else -> super.onActionPerformed(action, context)
                }
            }

            override fun onBackPressed(context: Context): Boolean {
                onBack()
                return true
            }
        }
    }

    // 🚨 SYSTEM BACK. Without this the press is not consumed by the overlay,
    // propagates past the Compose tree, and CLOSES THE WHOLE APP -- observed on
    // R5CY13EDP7A: pressing back on the paywall dropped the user to the
    // launcher. PaywallScreen has its own handling; routing through this host
    // bypassed it, so the host has to supply it.
    BackHandler { onBack() }

    AdaptyFlowScreen(cfg, products, listener)
}

/**
 * What an adapty-only brand shows when the remote flow has never loaded.
 *
 * ZERO new strings: `plans_unavailable` already reads "Plans aren't available
 * right now. Please try again later." and is in all 38 locales. A new key here
 * would ship English to Persian, Arabic, Russian, Turkish and Chinese devices
 * -- the trap this project has hit repeatedly -- to say something already said.
 *
 * It retries ON APPEAR rather than only on a tap, because the likeliest reason
 * a user is looking at this is a network that was down a moment ago.
 */
@Composable
private fun AdaptyUnavailable(onRetry: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { onRetry() }
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.plans_unavailable),
                color = scheme.onBackground,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onBack) {
                Text(stringResource(android.R.string.ok), color = scheme.primary)
            }
        }
    }
}

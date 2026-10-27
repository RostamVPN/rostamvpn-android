package com.rostamvpn.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The no-SDK twin of `src/adapty/.../AdaptyService.kt`, compiled into a brand
 * whose manifest carries no `adaptyPublicKey`. The real service and the Adapty
 * libraries themselves are source-set and dependency gated on that key, the
 * same way `cardSheet` gates the Stripe sheet.
 *
 * WHY A STUB AND NOT A `BuildConfig` BRANCH. A runtime switch still links the
 * SDK: ~2 MB of third-party billing and analytics code, its manifest entries,
 * its `AdvertisingIdClient` reference and its network stack, all inside an app
 * that never bills through it. It also leaves the vendor's class names in the
 * dex of an app whose privacy page says it carries no analytics code. Not
 * linking it is the only version of "off" that a decompiler agrees with.
 *
 * BEHAVIOUR IS UNCHANGED, not merely similar. On a keyless brand the real
 * service's `activateIfConfigured` returned immediately, `ready` stayed empty
 * forever and `sendSignals` had no profile to write to. Every value below is
 * what that code already produced at runtime -- this file removes the SDK, not
 * a feature.
 *
 * 🚨 DO NOT "simplify" this by deleting the members. They are the exact surface
 * MainActivity and VpnViewModel reference, and the two source sets must stay
 * API-compatible or the OTHER brands stop compiling.
 */
object AdaptyService {
    const val PLACEMENT_MAIN = "main_paywall"
    const val PLACEMENT_ONBOARDING = "onboarding"

    private val _ready = MutableStateFlow<Set<String>>(emptySet())

    /** Always empty: nothing can become ready without the SDK. MainActivity
     *  gates the onboarding paywall on this, so it simply never opens one --
     *  which is what a keyless brand already did. */
    val ready: StateFlow<Set<String>> = _ready.asStateFlow()

    /** No key, nothing to activate. */
    fun activateIfConfigured(ctx: Context) = Unit

    /** Region/entitlement signals exist to segment remote paywalls. With no
     *  remote paywall there is nothing to segment, and the values are not sent
     *  anywhere else. */
    fun sendSignals(ctx: Context, region: String?, freedom: Boolean, isPaid: Boolean) = Unit

    /** Called after a Play purchase settles on brands that render a remote
     *  flow. Ours renders the built-in PaywallScreen, which reads the cashier
     *  directly, so there is nothing to reconcile. */
    fun reconcileAfterPurchase(vm: VpnViewModel) = Unit

    /** The retry button only exists on the adapty-only brands. */
    fun reload() = Unit
}

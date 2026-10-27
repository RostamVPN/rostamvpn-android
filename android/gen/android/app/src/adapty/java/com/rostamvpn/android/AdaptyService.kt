package com.rostamvpn.android

import android.app.Activity
import android.content.Context
import android.util.Log
import com.adapty.Adapty
import com.adapty.models.AdaptyConfig
import com.adapty.models.AdaptyFlow
import com.adapty.models.AdaptyPaywallProduct
import com.adapty.models.AdaptyProfileParameters
import com.adapty.ui.AdaptyUI
import com.adapty.utils.AdaptyResult
import com.adapty.utils.TimeInterval
import com.rostamvpn.android.billing.CashierIdentity

/**
 * Adapty, activated only for a brand that carries a key, and never load-bearing.
 *
 * Mirrors iOS's `AdaptyService`. The cashier stays the entitlement authority on
 * both platforms; Adapty is presentation and analytics.
 *
 * 🚨 EVERY FAILURE PATH LEAVES [paywall] NULL AND THE APP USES ITS OWN
 * `PaywallScreen`. That is the design, not a safety net: these users are behind
 * censorship BEFORE they connect, so `api.adapty.io` being unreachable is the
 * ordinary case, not an error. A remote paywall is an upgrade; it is never
 * allowed to become the reason somebody cannot pay.
 */
object AdaptyService {
    private const val TAG = "AdaptyService"

    /**
     * The placements the app asks for. One set of strings for every brand: each
     * brand is a SEPARATE Adapty app with its own key, so an id carries no brand
     * and shared code stays free of `if (brand == …)`.
     *
     * Swapping which flow renders — or A/B testing two — happens in the Adapty
     * dashboard against a placement with no release. That is the whole point of
     * a placement, and why the code must never name a flow.
     *
     * Adapty's own guidance is at most five, following the user journey:
     * onboarding, then in-app, then a promo for the repeat non-converter. Two
     * are wired here; a third is a config edit plus a screen, not a refactor.
     */
    const val PLACEMENT_MAIN = "main_paywall"

    /**
     * The first-run placement, shown once between the welcome screen and home.
     *
     * 🚨 A BRAND WITH NO SUCH PLACEMENT IN ITS DASHBOARD IS UNAFFECTED, and that
     * is the whole brand gate. `getFlow` errors, nothing is cached under this
     * key, and [flowFor] returns null forever, so the screen never renders. No
     * `if (brand == …)`, no build flag: a brand opts in by creating the
     * placement, and rostam — which has no Adapty key at all — never even
     * activates the SDK.
     */
    const val PLACEMENT_ONBOARDING = "onboarding"

    private val ALL_PLACEMENTS = listOf(PLACEMENT_MAIN, PLACEMENT_ONBOARDING)

    /** A placement that loaded AND priced itself. Anything less is not cached. */
    private data class Flow(
        val config: AdaptyUI.FlowConfiguration,
        val products: List<AdaptyPaywallProduct>,
    )

    @Volatile private var active = false

    /**
     * Per placement, because two surfaces must not share one slot.
     *
     * This used to be a single `config`/`products` pair. With a second
     * placement that is not a widening, it is a CORRECTNESS bug waiting: the
     * onboarding flow finishing after the main one would have overwritten it,
     * and the upgrade tap would have rendered the onboarding offer, non
     * deterministically, depending on which network round trip came back first.
     */
    private val flows = java.util.concurrent.ConcurrentHashMap<String, Flow>()

    /**
     * Which placements have a renderable flow right now.
     *
     * 🚨 A `ConcurrentHashMap` IS NOT COMPOSE STATE, and reading one during
     * composition does not recompose when it changes. `OnboardingOffer` did
     * exactly that, so the flow arriving could never wake it: whether the
     * first-run offer appeared depended on some unrelated recomposition
     * happening to land after the Adapty round trip. On a cold install or a
     * blocked link it simply never showed, and "a later launch will" is the
     * same race again.
     *
     * A StateFlow so a composable can collect it and the map stays the source
     * of truth for the heavy values.
     */
    private val _ready = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val ready: kotlinx.coroutines.flow.StateFlow<Set<String>> = _ready

    private fun publishReady() { _ready.value = flows.keys.toSet() }

    fun flowFor(placement: String): AdaptyUI.FlowConfiguration? = flows[placement]?.config
    fun productsFor(placement: String): List<AdaptyPaywallProduct> =
        flows[placement]?.products.orEmpty()

    /** True once a placement has been asked for and answered, either way. */
    private val settled = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    fun isSettled(placement: String): Boolean = settled.contains(placement)

    fun activateIfConfigured(ctx: Context) {
        if (active) return
        val key = BuildConfig.ADAPTY_PUBLIC_KEY.trim()
        if (key.isEmpty()) return
        try {
            // customerUserId is the cid, so Adapty's profile and the cashier's
            // identity are ONE user rather than two anonymous ones that can
            // never be reconciled afterwards.
            val cid = CashierIdentity.ensure(ctx)
            Adapty.activate(
                ctx,
                AdaptyConfig.Builder(key)
                    .apply { if (cid.isNotEmpty()) withCustomerUserId(cid) }
                    // brands/<id>.json android.advertisingId false: never read the ad id.
                    .withAdIdCollectionDisabled(!BuildConfig.ADVERTISING_ID)
                    .build()
            )
            active = true
            // 🚨 ADAPTY'S OWN FALLBACK, and on an adapty-only brand it is the
            // difference between a paywall and a dead end.
            //
            // `AdaptyUnavailableView`/`AdaptyUnavailable` can only offer a
            // retry: with the built-in offer gone there is nothing else to
            // draw. This file is a snapshot of the flow, downloaded from the
            // Adapty dashboard and shipped in assets, which the SDK renders
            // when api.adapty.io cannot be reached -- the ordinary case here,
            // BEFORE the tunnel is up.
            //
            // Absent asset = absent call, so a brand without one is exactly as
            // it was. The SDK reads it lazily, so a missing file costs nothing
            // and cannot fail activation.
            fallbackAsset(ctx)?.let { asset ->
                try {
                    Adapty.setFallback(com.adapty.utils.FileLocation.fromAsset(asset))
                    Log.i(TAG, "fallback paywall registered: $asset")
                } catch (t: Throwable) {
                    Log.w(TAG, "setFallback failed: ${t.message}")
                }
            }
            preload()
        } catch (t: Throwable) {
            // Swallowed deliberately — see the class note.
            Log.w(TAG, "activate failed: ${t.message}")
        }
    }

    /**
     * Re-attempt the fetch. Exposed because an adapty-only brand has no
     * built-in offer to fall back to, so the one screen a user can reach when
     * the flow never loaded must be able to try again rather than only apologise.
     */
    fun reload() {
        if (!active) return
        // Clear settled first: a retry that left the flags set would let a
        // caller read "settled, no offer" for the whole round trip and render
        // the built-in paywall over a retry that is about to succeed.
        settled.clear()
        preload()
    }

    /**
     * The bundled fallback, or null when this brand ships none.
     *
     * Named per brand so one asset cannot be served to another: an Adapty
     * fallback is a snapshot of ONE app's flow, with that app's products in it,
     * and rendering redvpn's to a dollarvpn user would offer SKUs that do not
     * exist on that listing.
     */
    private fun fallbackAsset(ctx: Context): String? {
        val name = "adapty-fallback-${BuildConfig.CASHIER_BRAND}.json"
        return try {
            ctx.assets.open(name).close()
            name
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Our own identity and policy signals, written onto the Adapty profile.
     *
     * The cid already goes up as `customerUserId` at activation, and that is
     * what lets an Adapty webhook map a purchase back to a cashier identity.
     * This puts it somewhere it is also QUERYABLE: custom attributes are what
     * Adapty filters a placement AUDIENCE on, so a different flow can be aimed
     * at "free user in a paying region" from the dashboard, with no release.
     *
     * `paid` is the one signal Adapty cannot work out for itself. Premium
     * granted by the cashier — an account code, a Paymentwall order, a restored
     * ixroute-era subscription — never passes through Play Billing, so Adapty's
     * own access levels do not see it and would show the paywall to somebody
     * who has paid.
     *
     * ⚠️ `region` and `freedom` are SUPPLIED BY THE CALLER, deliberately.
     * `VpnViewModel.freedomRegion()` is the single choke point that honours
     * `freedom_tier`; reading `RegionDetector` here would report a freedom
     * region for a brand that has no free tier at all (dollarvpn), which is
     * exactly the disagreement that choke point exists to prevent.
     *
     * Silent on every failure, like the rest of this file: a profile attribute
     * that did not land is a worse analytics row, never a broken paywall.
     */
    fun sendSignals(ctx: Context, region: String?, freedom: Boolean, isPaid: Boolean) {
        if (!active) return
        try {
            val b = AdaptyProfileParameters.Builder()
                .withCustomAttribute("brand", BuildConfig.CASHIER_BRAND)
                .withCustomAttribute("freedom", if (freedom) "yes" else "no")
                .withCustomAttribute("paid", if (isPaid) "yes" else "no")
            // Adapty rejects an empty attribute value, so an absent signal is
            // omitted rather than sent as "".
            CashierIdentity.ensure(ctx).takeIf { it.isNotEmpty() }
                ?.let { b.withCustomAttribute("cid", it) }
            region?.takeIf { it.isNotEmpty() }?.let { b.withCustomAttribute("region", it) }
            Adapty.updateProfile(b.build()) { err ->
                if (err != null) Log.w(TAG, "updateProfile failed: ${err.message}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sendSignals failed: ${t.message}")
        }
    }

    private fun preload() {
        for (p in ALL_PLACEMENTS) load(p)
    }

    /**
     * Fetch ONE placement. Every failure path leaves its slot empty and marks it
     * settled, so a caller can tell "no offer here" from "not asked yet" —
     * which is the difference between showing the built-in paywall and showing
     * a spinner forever on a censored link.
     */
    private fun load(placement: String) {
        try {
            Adapty.getFlow(
                placement,
                // 🚨 CACHE FIRST, and it is load-bearing for an adapty-only
                // brand. `Default` goes to the network every time, so a user
                // whose api.adapty.io is blocked -- the ordinary case here,
                // BEFORE the tunnel is up -- gets nothing. Returning the cached
                // flow means one successful fetch keeps the offer renderable
                // offline for the life of the install.
                com.adapty.models.AdaptyPlacementFetchPolicy.ReturnCacheDataElseLoad,
                // Short on purpose: this runs on a censored network where a hung
                // request is the likely outcome and the user is waiting to be
                // sold to. Falling back fast beats a spinner.
                TimeInterval.seconds(8),
            ) { res ->
                when (res) {
                    is AdaptyResult.Success -> {
                        val pw = res.value
                        // 🚨 A PAYWALL THAT CANNOT PRICE ITSELF MUST NOT RENDER.
                        // Prices are filled from Play Billing on the device; if
                        // that returns nothing the flow still draws, with every
                        // price at 0 — a product that looks free and cannot be
                        // bought, which is strictly worse than no remote paywall.
                        Adapty.getPaywallProducts(pw) { p ->
                            if (p is AdaptyResult.Success && p.value.isNotEmpty()) {
                                val prods = p.value
                                AdaptyUI.getFlowConfiguration(pw) { c ->
                                    if (c is AdaptyResult.Success) {
                                        flows[placement] = Flow(c.value, prods)
                                        publishReady()
                                        Log.i(TAG, "$placement ready (${prods.size} products)")
                                    } else {
                                        flows.remove(placement)
                                        publishReady()
                                        Log.i(TAG, "$placement: flow config unavailable")
                                    }
                                    settled.add(placement)
                                }
                            } else {
                                flows.remove(placement)
                                publishReady()
                                settled.add(placement)
                                Log.i(TAG, "$placement: no priced products")
                            }
                        }
                    }
                    is AdaptyResult.Error -> {
                        // Expected for a placement this brand has not created.
                        flows.remove(placement)
                        publishReady()
                        settled.add(placement)
                        Log.i(TAG, "$placement unavailable: ${res.error.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            flows.remove(placement)
            publishReady()
            settled.add(placement)
            Log.w(TAG, "$placement load failed: ${t.message}")
        }
    }

    /**
     * 🚨 THE CASHIER REMAINS THE ENTITLEMENT AUTHORITY.
     *
     * An Adapty-rendered paywall purchases through its OWN billing wrapper, so
     * [PlayBilling] never runs and `/iap/verify` is never called — the cashier
     * would not learn the user had paid and `/profile` would keep serving the
     * free tier. Restoring re-verifies what Play currently considers active
     * against the cashier, which is exactly the reconciliation needed and is
     * already the tested path.
     */
    fun reconcileAfterPurchase(vm: VpnViewModel) {
        try {
            vm.restoreEntitlement(notify = false)
        } catch (t: Throwable) {
            Log.w(TAG, "reconcile failed: ${t.message}")
        }
    }
}

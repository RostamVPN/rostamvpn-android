package com.rostamvpn.android.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.rostamvpn.android.R

/**
 * WHICH HOME SCREEN A BRAND RENDERS.
 *
 * Five brands shipped ONE composition in five colours — same dial, same
 * upgrade banner, same meter, same region card, same footer, in the same
 * order. Recolouring a layout does not make it a different product, and the
 * five apps read as one app skinned five times because that is precisely what
 * they were.
 *
 * ── WHY THIS IS NOT AN `if (brand == …)` ──────────────────────────────────
 *
 * CLAUDE.md §6b bans branching on the brand, and the ban is not a style rule:
 * a brand check makes every new brand a Kotlin change, and it puts the roster
 * of live products into the shipped binary. This does neither. The manifest
 * names a COMPOSITION and the code branches on the composition, exactly as
 * `brand_has_mascot`, `brand_mark_scale` and `brand_disc_fills_on_connect`
 * already do for the dial.
 *
 * The test of whether that distinction is real rather than a disguise is
 * simple: CAN TWO BRANDS SELECT THE SAME STYLE? They can, and three do —
 * rostam, internetpro and goglobe all resolve to [CLASSIC]. A sixth brand that
 * wants RedVPN's band stack sets `"homeStyle": "rings"` in its manifest and
 * gets it, with no Kotlin change and no mention of its name anywhere in this
 * tree. That is the property a brand check cannot have.
 *
 * ── WHY A NAMED VARIANT AND NOT A BAG OF BOOLEANS ────────────────────────
 *
 * The variants differ in the ORDER and KIND of their elements, not in a few
 * independent switches. Decomposing that into `showsGradientPill`,
 * `usesBandStack`, `collapsesLowerStack`… would let a manifest select a
 * combination nobody has ever rendered — which is how this project shipped
 * invisible controls and a logo that spilled its container. A named variant is
 * a composition a person has looked at on a device.
 *
 * ── FAILURE MODE ─────────────────────────────────────────────────────────
 *
 * [CLASSIC] is the default in three places that must agree: the manifest
 * default in `gen-branding.py`, `brand_home_style` in `brand_defaults.xml`,
 * and [from] below. Forgetting any of them yields TODAY'S SHIPPING LAYOUT,
 * never a half-configured one. The generator additionally *rejects* an
 * unrecognised value outright, so a typo fails the build rather than silently
 * shipping classic to a brand that asked for something else — the two
 * behaviours differ on purpose: the generator can afford to be strict because
 * a human is watching it, and the runtime cannot because a resource overlay
 * someone hand-edited must still boot.
 */
enum class BrandStyle(
    /**
     * Is this treatment DARK-GROUNDED in every theme?
     *
     * A property of the composition, not a second token, because it is not
     * independently choosable: RedVPN's ground, its card ramp, its launch
     * colours and its paywall are one decision. Splitting it out would allow a
     * light `rings` that nobody has drawn.
     *
     * gen-branding.py keeps the SAME set in `DARK_STYLES`, because the system
     * splash colour is a generated resource and has to agree with the scheme or
     * the app flashes white before its first frame.
     */
    val forcesDark: Boolean = false,
) {
    /** Today's screen, unchanged. rostam, internetpro, goglobe. */
    CLASSIC,

    /** RedVPN. Concentric band stack, gradient pill, from the brand's own
     *  design package. See [RingsHome]. */
    RINGS(forcesDark = true),

    /** DollarVPN. Open arc gauge and a single ruled ledger card. See
     *  [ValueHome]. */
    VALUE,

    /** Three tabs (Connect, Passes, Account) with the ring dial, from the
     *  locked Internet Pro design. Replaces the drawer and the home stack
     *  entirely. Dark by default, with its own light token set; see
     *  [TabsRoot] and `branding.themeDark/themeLight`. */
    TABS(forcesDark = true),
    ;

    companion object {
        /** Unknown -> [CLASSIC]. A resource overlay is editable by hand and a
         *  crash here would be a black home screen, so this cannot throw. */
        fun from(tag: String): BrandStyle = when (tag.trim().lowercase()) {
            "rings" -> RINGS
            "value" -> VALUE
            "tabs" -> TABS
            else -> CLASSIC
        }
    }
}

/**
 * Read this brand's style once. `brand_home_style` is generated into
 * `src/<brand>/res/values/brand_layout.xml`; `src/main` carries the CLASSIC
 * fallback, and a flavour resource outranks it.
 *
 * It is a `<string translatable="false">` deliberately kept OUT of
 * strings.xml: `tools/i18n/validate-translations.py` globs the values/strings.xml files
 * only, so this neither demands 37 translations of the word "classic" nor
 * offers a translator a way to break the dispatch.
 */
@Composable
fun rememberBrandStyle(): BrandStyle {
    val tag = stringResource(R.string.brand_style)
    return remember(tag) { BrandStyle.from(tag) }
}

/**
 * The entitlement and metering gates, RESOLVED ONCE and handed to whichever
 * composition renders.
 *
 * These used to be computed inside the one layout's own `Column`. With more
 * than one layout that is a correctness problem rather than a tidiness one:
 * each variant would re-derive quota state, and the FREEDOM-REGION COLLAPSE —
 * Iran and Russia show connect + region and nothing else — would be
 * re-implemented per brand and could drift. `VpnViewModel.asFreedom()` forces
 * `unlimited` before the flow is ever read, so [showMeter] and [regionLocked]
 * are both false there; every variant must honour that by construction, and
 * the only way to guarantee it is to resolve it above them.
 *
 * @param regionLocked `premiumAvailable && !isPaid` — the single predicate for
 *   "this user is being sold to". It gates the upgrade offer AND the region
 *   lock together so the two can never disagree.
 * @param showMeter the `!unlimited && !paid` gate. False in freedom regions.
 * @param freeSpeed the free tier's real ceiling as text, or null when
 *   throttling is off in config — in which case NO screen may claim a limit.
 */
@Immutable
data class HomeGates(
    val regionLocked: Boolean,
    /**
     * This user is being SOLD to: premium exists for them and they have not
     * bought it. Split from [regionLocked], which used to double as this.
     *
     * The two diverged the moment an `until_quota` offering let a free user
     * pick a region: `regionLocked` went false and took the Upgrade button with
     * it, leaving a free user no way to buy at all. One is "can they choose a
     * location", the other is "is there an offer to make".
     */
    val sellingPremium: Boolean,
    /**
     * Does the free allowance come back? Server-owned (`period_days`), per brand.
     *
     * The label under the meter is `data_free_one_time` — a key whose NAME says
     * one-time and whose VALUE says "Monthly allowance". It was right when every
     * brand refilled monthly; on a one-time brand it promises a refill that
     * never comes. Defaults true, so nothing changes for a refilling brand.
     */
    val quotaRenews: Boolean,
    val showMeter: Boolean,
    val fraction: Float,
    val remainingMb: Long,
    val overQuota: Boolean,
    val reducedSpeed: Boolean,
    val freeSpeed: String?,
    /**
     * `throttle.hard_stop`: past the allowance this brand has no free tier at
     * all — `connectAuto()` REFUSES the connect rather than slowing it.
     *
     * Needed because [freeSpeed] being null is ambiguous: it is also null when
     * throttling is simply off, and those two states want opposite sentences.
     * "Want full speed back?" is right for a user who still has a tunnel and
     * wrong for one whose tunnel will not start.
     */
    val quotaStops: Boolean,
)

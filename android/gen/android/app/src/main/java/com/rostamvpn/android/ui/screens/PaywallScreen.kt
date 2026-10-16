// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.R
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.billing.CashierApi
import com.rostamvpn.android.billing.PayRail
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.rostamvpn.android.ui.theme.BrandPalette
import com.rostamvpn.android.ui.theme.SystemBarsFor
import com.rostamvpn.android.ui.theme.tone

@Immutable
private data class PaywallPalette(
    val ground: Color,
    val groundTop: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val gold: Color,
    val goldLite: Color,
    val cta: Color,
    val ctaLite: Color,
    val ctaInk: Color,
    val line: Color,
    val badgeInk: Color,
)

private val ClassicPaywall = PaywallPalette(

    ground = tone(BrandPalette.success, 0.51f, 0.096f),
    groundTop = tone(BrandPalette.success, 0.53f, 0.165f),
    card = tone(BrandPalette.success, 0.52f, 0.130f),
    muted = tone(BrandPalette.success, 0.13f, 0.660f),

    cta = tone(BrandPalette.success, 0.57f, 0.427f),
    ctaLite = tone(BrandPalette.success, 0.57f, 0.494f),
    ctaInk = tone(BrandPalette.success, 0.70f, 0.070f),

    gold = BrandPalette.warning,
    goldLite = tone(BrandPalette.warning, 0.82f, 0.660f),
    badgeInk = tone(BrandPalette.warning, 0.72f, 0.090f),

    ink = tone(BrandPalette.warning, 0.48f, 0.910f),
    line = tone(BrandPalette.warning, 0.48f, 0.910f).copy(alpha = 0.10f),
)

private val LocalPaywall = staticCompositionLocalOf { ClassicPaywall }

private val Card: Color @Composable get() = LocalPaywall.current.card
private val Ink: Color @Composable get() = LocalPaywall.current.ink
private val Muted: Color @Composable get() = LocalPaywall.current.muted
private val Gold: Color @Composable get() = LocalPaywall.current.gold
private val GoldLite: Color @Composable get() = LocalPaywall.current.goldLite
private val Teal: Color @Composable get() = LocalPaywall.current.cta
private val Line: Color @Composable get() = LocalPaywall.current.line
private val BadgeInk: Color @Composable get() = LocalPaywall.current.badgeInk

/** Below this, a savings badge argues AGAINST the plan it is attached to. */
private const val MIN_SAVINGS_BADGE = 30

private val CardRadius = 16.dp
private val CardPad = 16.dp

private val BadgeSize = 10.sp
private val BadgeLine = 14.sp
private val BadgeVPad = 3.dp
/**
 * Deepest the chip may dip below the card's top border. Fixed in dp, while the
 * chip's height scales with the system font: that is what keeps the clearance
 * between the chip and the card's content constant at every font scale.
 */
private val BadgeMaxDip = 10.dp

/** Months in a billing period; 0 when unknown. */
private fun periodMonths(billingPeriod: String?): Int = when (billingPeriod) {
    "P1W" -> 0
    "P1M" -> 1
    "P2M" -> 2
    "P3M" -> 3
    "P6M" -> 6
    "P1Y" -> 12
    else -> 0
}

/** The plan row's title, from Play's ISO-8601 `billingPeriod`. */
@Composable
private fun periodTitle(billingPeriod: String?): String? = when (billingPeriod) {
    "P1W" -> cfgString(R.string.plan_period_weekly)
    "P1M" -> cfgString(R.string.plan_period_monthly)
    "P3M" -> cfgString(R.string.plan_period_3month)
    "P6M" -> cfgString(R.string.plan_period_6month)
    "P1Y" -> cfgString(R.string.plan_period_yearly)
    else -> null
}

private const val FSI = '\u2068'
private const val PDI = '\u2069'

/**
 * Wrap a run we did not author in FSI…PDI so it cannot reorder the text around
 * it.
 */
private fun bidi(s: String): String = if (s.isEmpty()) s else "$FSI$s$PDI"

@Composable
fun PaywallScreen(viewModel: VpnViewModel, onBack: () -> Unit) {
    val rail by viewModel.payRail.collectAsState()
    val products by viewModel.subProducts.collectAsState()
    val webPlans by viewModel.webPlans.collectAsState()
    val isPaid by viewModel.isPaid.collectAsState()
    val plansLoading by viewModel.plansLoading.collectAsState()

    val throttled by viewModel.reducedSpeed.collectAsState()
    val usage by viewModel.usage.collectAsState()

    val freeSpeed =
        if (viewModel.quotaStops || viewModel.paidOnly) null else formatSpeed(viewModel.freeTierKbps)

    val throttledSpeed = if (throttled) freeSpeed else null
    val throttledNow = throttledSpeed != null

    val pw = ClassicPaywall

    SystemBarsFor(pw.groundTop)
    CompositionLocalProvider(LocalPaywall provides pw) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(0f to pw.groundTop, 0.28f to pw.ground, 1f to pw.ground)
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()

                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = cfgString(R.string.cd_close),
                        tint = Muted,
                    )
                }
            }

            val markSize = 104.dp
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(markSize + 16.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.brand_mascot),

                    contentDescription = cfgString(R.string.app_name),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(markSize),
                )
            }

            val tracking = booleanResource(R.bool.paywall_letter_tracking)

            Spacer(Modifier.height(14.dp))
            Text(
                cfgString(R.string.paywall_eyebrow, bidi(cfgString(R.string.app_name))),
                color = Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = if (tracking) 3.sp else 0.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))

            Text(
                cfgString(

                    if (isPaid) R.string.premium_active
                    else if (throttledNow) R.string.premium_title_throttled
                    else R.string.premium_title_default
                ),
                fontFamily = if (tracking) FontFamily.Serif else FontFamily.Default,
                color = Ink,

                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 38.sp,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                if (isPaid) cfgString(R.string.premium_tagline)
                else if (throttledSpeed != null)
                    cfgString(R.string.paywall_subhead_throttled, throttledSpeed)
                else if (viewModel.paidOnly) cfgString(R.string.paywall_subhead)
                else cfgString(R.string.paywall_subhead_offer),
                color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
                modifier = Modifier.fillMaxWidth(0.86f),
            )

            val perksBlock: @Composable () -> Unit = {
                Spacer(Modifier.height(24.dp))

                val quotaRenews = usage?.renews ?: true

                if (!viewModel.paidOnly) Perk(
                    R.drawable.ic_perk_infinite,
                    cfgString(R.string.paywall_perk_data_title),
                    cfgString(
                        if (quotaRenews) R.string.paywall_perk_data_body
                        else R.string.paywall_perk_data_body_once
                    ),
                )
                Perk(
                    R.drawable.ic_perk_bolt,
                    cfgString(R.string.paywall_perk_speed_title),

                    if (freeSpeed != null)
                        cfgString(R.string.paywall_perk_speed_body_limit, freeSpeed)
                    else cfgString(R.string.paywall_perk_speed_body),
                )

                Perk(
                    R.drawable.ic_perk_globe,
                    cfgString(R.string.paywall_perk_regions_title),
                    cfgString(R.string.paywall_perk_regions_body),
                )

                Perk(
                    R.drawable.ic_perk_exchange,
                    cfgString(R.string.paywall_perk_ixp_title),
                    cfgString(R.string.paywall_perk_ixp_body),
                )
                ProofRow()
                Spacer(Modifier.height(20.dp))
            }
            val plansBlock: @Composable () -> Unit = {
                when (rail) {
                    PayRail.GOOGLE_PLAY -> {
                        fun phase(pd: com.android.billingclient.api.ProductDetails) =
                            pd.subscriptionOfferDetails?.firstOrNull()
                                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                        fun months(pd: com.android.billingclient.api.ProductDetails) =
                            periodMonths(phase(pd)?.billingPeriod)

                        val anchor = products.maxByOrNull { months(it) }
                        var selected by remember { mutableStateOf("") }

                        LaunchedEffect(products) {
                            if (products.isNotEmpty() && products.none { it.productId == selected }) {
                                selected = anchor?.productId ?: products.first().productId
                            }
                        }

                        fun perMonth(pd: com.android.billingclient.api.ProductDetails): Double {
                            val mo = months(pd); val micros = phase(pd)?.priceAmountMicros ?: 0L
                            return if (mo > 0 && micros > 0) micros.toDouble() / mo else 0.0
                        }
                        /** The value anchor: this plan's price expressed PER MONTH. */
                        fun perMonthLabel(
                            pd: com.android.billingclient.api.ProductDetails,
                        ): String? {
                            val mo = months(pd)
                            val ph = phase(pd) ?: return null
                            val micros = ph.priceAmountMicros
                            val code = ph.priceCurrencyCode
                            if (mo <= 1 || micros <= 0L || code.isNullOrEmpty()) return null
                            return runCatching {
                                val cur = java.util.Currency.getInstance(code)
                                val perMo = micros.toDouble() / mo / 1_000_000.0

                                val digits = cur.defaultFractionDigits.coerceAtLeast(0)
                                val scale = Math.pow(10.0, digits.toDouble())
                                val floored = Math.floor(perMo * scale) / scale
                                java.text.NumberFormat.getCurrencyInstance().apply {
                                    currency = cur
                                }.format(floored)
                            }.getOrNull()
                        }
                        val baseline = products.filter { months(it) == 1 }
                            .mapNotNull { perMonth(it).takeIf { v -> v > 0 } }.minOrNull() ?: 0.0
                        val anchorPm = anchor?.let { perMonth(it) } ?: 0.0
                        val savings = if (baseline > 0 && anchorPm > 0 && anchorPm < baseline)
                            Math.round(100.0 - (anchorPm * 100.0 / baseline)).toInt() else 0

                        val perPeriod = products.groupingBy { phase(it)?.billingPeriod }.eachCount()

                        if (products.isEmpty()) {
                            if (plansLoading) Loading() else Unavailable()
                        } else {
                            products.sortedByDescending { months(it) }.forEach { pd ->
                                val bp = phase(pd)?.billingPeriod
                                val noun = periodTitle(bp)?.takeIf { (perPeriod[bp] ?: 0) <= 1 }
                                val isAnchor = pd.productId == anchor?.productId && savings >= MIN_SAVINGS_BADGE
                                PlanRow(
                                    title = noun ?: bidi(pd.name.ifEmpty { pd.productId }),

                                    subtitle = perMonthLabel(pd)?.let {
                                        cfgString(R.string.plan_per_month, bidi(it))
                                    },
                                    price = bidi(phase(pd)?.formattedPrice ?: ""),
                                    badge = if (isAnchor)
                                        cfgString(R.string.plan_save_badge, savings) else null,
                                    selected = pd.productId == selected,
                                ) { selected = pd.productId }
                            }
                            Spacer(Modifier.height(18.dp))
                            Reassure()

                            val hasTrial = products.firstOrNull { it.productId == selected }
                                ?.subscriptionOfferDetails?.any { o ->
                                    o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
                                } ?: false
                            Cta(
                                cfgString(
                                    if (hasTrial) R.string.start_free_trial else R.string.go_premium
                                )
                            ) {
                                if (selected.isNotEmpty()) viewModel.purchaseSubscription(selected)
                            }
                            Disclosure(cfgString(R.string.paywall_disclosure_play))
                        }
                    }
                    PayRail.STRIPE -> WebPlans(
                        webPlans, plansLoading,
                        R.string.pay_with_card, R.string.paywall_disclosure_stripe,
                    ) { viewModel.startStripePayment(it) }

                    PayRail.CRYPTO -> WebPlans(
                        webPlans, plansLoading,
                        R.string.pay_with_crypto, R.string.paywall_disclosure_crypto,
                    ) { viewModel.startCryptoPayment(it) }
                }
            }

            perksBlock()
            if (!isPaid) plansBlock()

            if (isPaid) {
                if (rail == PayRail.GOOGLE_PLAY) {
                    Spacer(Modifier.height(8.dp))
                    Disclosure(cfgString(R.string.paywall_disclosure_play))
                }
            } else {
                Spacer(Modifier.height(18.dp))

                TextButton(onClick = { viewModel.restoreEntitlement(notify = true) }) {
                    Text(cfgString(R.string.restore), color = Gold)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    }
}

/**
 * Stripe and crypto are one screen with a different CTA and disclosure: the
 * same cashier `Plan` list and the same one-CTA-acts-on-the-selection shape.
 */
@Composable
private fun WebPlans(
    plans: List<CashierApi.Plan>,
    loading: Boolean,
    @StringRes ctaRes: Int,
    @StringRes disclosureRes: Int,
    onPay: (String) -> Unit,
) {
    var selected by remember(plans) { mutableStateOf(plans.firstOrNull()?.slug ?: "") }
    if (plans.isEmpty()) {
        if (loading) Loading() else Unavailable()
    } else {
        plans.forEach { p ->
            PlanRow(
                title = bidi(p.label.ifEmpty { p.slug }),
                subtitle = null,
                price = bidi(p.price),
                badge = null,
                selected = p.slug == selected,
            ) { selected = p.slug }
        }
        Spacer(Modifier.height(18.dp))
        Reassure()
        Cta(cfgString(ctaRes)) { if (selected.isNotEmpty()) onPay(selected) }
        Disclosure(cfgString(disclosureRes))
    }
}

/** The trust row — real numbers from THIS brand's Play listing, or nothing. */
@Composable
private fun ProofRow() {
    val downloads = integerResource(R.integer.brand_proof_downloads)
    val ratingX10 = integerResource(R.integer.brand_proof_rating_x10)
    val reviews = integerResource(R.integer.brand_proof_reviews)

    if (downloads <= 0 && (ratingX10 <= 0 || reviews <= 0)) return

    Spacer(Modifier.height(18.dp))

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (ratingX10 > 0 && reviews > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Star, contentDescription = null, tint = Gold,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    cfgString(
                        R.string.proof_rating,
                        formatRating(ratingX10),
                        formatCount(reviews),
                    ),
                    color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (downloads > 0) {
            Spacer(Modifier.height(3.dp))
            Text(
                cfgString(R.string.proof_downloads, formatCount(downloads)),
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

/** The risk reversal, immediately above the button. */
@Composable
private fun Reassure() = Text(
    cfgString(R.string.paywall_reassure),
    color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
)

@Composable
private fun Perk(@DrawableRes icon: Int, title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Gold.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon), contentDescription = null, tint = Gold,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
}

/**
 * One plan. Four slots became three, because two of them said the same thing:
 * the row printed the period under the title AND under the price, beside a
 * Play product name that already meant "yearly".
 */
@Composable
private fun PlanRow(
    title: String,
    subtitle: String?,
    price: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val chipH = with(LocalDensity.current) { BadgeLine.toDp() } + BadgeVPad * 2
    val dip = (chipH / 2).coerceAtMost(BadgeMaxDip)
    val reserve = chipH - dip

    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = reserve)
                .clip(RoundedCornerShape(CardRadius))
                .background(Card)

                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) LocalPaywall.current.cta else Line,
                    shape = RoundedCornerShape(CardRadius),
                )
                .clickable(onClick = onClick)
                .padding(CardPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (!subtitle.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                }
            }

            if (price.isNotEmpty()) {
                Text(price, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)

                    .padding(end = 18.dp)

                    .height(chipH)

                    .clip(RoundedCornerShape(percent = 50))
                    .background(GoldLite)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badge,
                    color = BadgeInk,
                    fontSize = BadgeSize, lineHeight = BadgeLine,
                    fontWeight = FontWeight.Black, maxLines = 1,
                    letterSpacing =
                        if (booleanResource(R.bool.paywall_letter_tracking)) 0.8.sp else 0.sp,
                )
            }
        }
    }
}

@Composable
private fun Cta(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = LocalPaywall.current.ctaInk, fontWeight = FontWeight.Black, fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(LocalPaywall.current.ctaLite, Teal)))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
    )
}

@Composable
private fun Loading() = Text(cfgString(R.string.loading_plans), color = Muted, fontSize = 13.sp)

@Composable
private fun Unavailable() = Text(

    cfgString(R.string.plans_unavailable),
    color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
)

@Composable
private fun Disclosure(text: String) = Text(
    text, color = Muted, fontSize = 11.5.sp, textAlign = TextAlign.Center,
    lineHeight = 16.sp, modifier = Modifier.padding(top = 11.dp),
)


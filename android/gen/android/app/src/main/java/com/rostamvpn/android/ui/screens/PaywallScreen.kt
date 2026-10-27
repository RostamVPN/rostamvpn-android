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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.R
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.billing.CashierApi
import com.rostamvpn.android.billing.PayRail
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.compositeOver
import com.rostamvpn.android.ui.theme.Accent3
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.BrandPalette
import com.rostamvpn.android.ui.theme.TabsDark
import com.rostamvpn.android.ui.theme.mostLegibleOn
import com.rostamvpn.android.ui.theme.SystemBarsFor
import com.rostamvpn.android.ui.theme.tone

/**
 * THE PAYWALL'S PALETTE — and it was ROSTAM'S, on every brand.
 *
 * These nine values were top-level `private val`s: a deep-teal ground, a cream
 * ink and a gold accent, hardcoded, with no path from any brand manifest. So
 * RedVPN's paywall rendered its RED SHIELD on Rostam's dark green, under a gold
 * eyebrow, with a teal buy button — a complete second brand's identity worn by
 * all five apps at the exact moment the user is asked for money. It compiled,
 * it shipped, and only a screenshot shows it.
 *
 * I flagged this earlier and set it aside on the grounds that wiring it would
 * change Rostam's paywall and I could not verify that by eye. That reasoning was
 * wrong in one specific way, and it is worth naming because it is a trap: the
 * fix does not have to be "derive these from the manifest for everybody". It has
 * to be "let the composition choose", exactly as `BrandStyle` already does for
 * the home screen. CLASSIC returns THESE NINE LITERALS, unchanged, so Rostam
 * evaluates the same colours it always has and cannot move; the other treatments
 * derive theirs.
 *
 * ── WHY RAW BrandPalette AND NOT COLOUR ROLES ───────────────────────────
 *
 * Because `scheme.primary` FLIPS in the dark theme (Theme.kt maps it to Brand2)
 * and `scheme.secondary` is Accent2 — a hardcoded brown shared by all five
 * brands. Both traps were hit and measured on the home screen this session. The
 * paywall is also a fixed dark surface in every theme, so it must not follow the
 * light/dark scheme at all: it needs the brand's own two colours, and those are
 * `BrandPalette.primary` and `.accent`, reached through `Brand1` / `Accent3`.
 */
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
    /** Does the mark need a light disc behind it? A brand whose mark IS its
     *  ground colour vanishes on it — RedVPN's shield measures 1.04-1.47:1 on
     *  its own red and 5.49:1 on white, which is why the designer's own paywall
     *  puts the shield on a white field rather than on the gradient. */
    val markOnDisc: Boolean,
    /** The offer above the perks, the mark small, no eyebrow. */
    val focused: Boolean,
    /**
     * Lead with the PER-MONTH figure and demote the billed total to a caption.
     *
     * Only for a brand selling ONE plan: the card exists to be *selected*, and
     * with nothing to choose between it is furniture. It is a separate flag from
     * [focused] because RINGS is focused too — reusing that one would hand
     * redvpn's 63.5K installs a layout built for a single-SKU brand.
     */
    val priceHero: Boolean,
)

/**
 * CLASSIC, and it is TWO BRAND HUES, not the thirteen literals it used to be.
 *
 * The comment here read "the only one that is literals, do not derive these",
 * which was wrong twice. CLASSIC is not rostam: it is rostam, internetpro and
 * goglobe (`BrandStyle`), so internetpro shipped a teal-and-gold paywall that
 * belongs to a different brand than the rest of its app. And the values were
 * derivable all along. Measured against the thirteen they replace, this lands
 * every one within 12/255 on its worst channel and most within 3, so rostam's
 * screen is the screen it already was.
 *
 * The system is `success` for the field and `warning` for the metal, each
 * re-lit through [tone]. That is the whole palette: a dark teal room with one
 * gold in it. A brand supplies two colours and gets its own room.
 */
private val ClassicPaywall = PaywallPalette(
    // ── THE FIELD: the brand's `success` hue, re-lit dark ──
    ground = tone(BrandPalette.success, 0.51f, 0.096f),
    groundTop = tone(BrandPalette.success, 0.53f, 0.165f),
    card = tone(BrandPalette.success, 0.52f, 0.130f),
    muted = tone(BrandPalette.success, 0.13f, 0.660f),
    // The CTA is the same hue lit to mid, so the button reads as the room's own
    // light rather than as a sticker applied to it.
    cta = tone(BrandPalette.success, 0.57f, 0.427f),
    ctaLite = tone(BrandPalette.success, 0.57f, 0.494f),
    ctaInk = tone(BrandPalette.success, 0.70f, 0.070f),
    // ── THE METAL: `warning`, which is the gold at full strength ──
    gold = BrandPalette.warning,
    goldLite = tone(BrandPalette.warning, 0.82f, 0.660f),
    badgeInk = tone(BrandPalette.warning, 0.72f, 0.090f),
    // Body text is the metal at near-white: a warm off-white on a cold ground,
    // which is what keeps the screen from reading as grey-on-grey. Not pure
    // white, deliberately.
    ink = tone(BrandPalette.warning, 0.48f, 0.910f),
    line = tone(BrandPalette.warning, 0.48f, 0.910f).copy(alpha = 0.10f),
    markOnDisc = false,
    focused = false,
    priceHero = false,
)

private val LocalPaywall = staticCompositionLocalOf { ClassicPaywall }

/**
 * The palette for this brand's treatment.
 *
 * RINGS is sampled from RedVPN's own `6 Upgrade Paywall.png`: a purple-to-red
 * vertical field running #701187 at the top of the panel to #B41D5A at the
 * bottom of the visible area. Both are points on the SAME accent -> primary
 * gradient the home screen's pill and status word already use, so this is one
 * derived system rather than a third set of hexes, and a sixth brand selecting
 * `rings` gets its own.
 *
 * VALUE has no design file, so it takes the brand's own darkest tone as ground
 * and its gold as the accent — the same green-carries-state / gold-carries-the-
 * offer split the home screen uses, kept consistent so the two screens read as
 * one product.
 */
@Composable
private fun paywallPaletteFor(style: BrandStyle): PaywallPalette = when (style) {
    BrandStyle.CLASSIC -> ClassicPaywall
    BrandStyle.RINGS -> PaywallPalette(
        // ── GROUNDED DARK, and this is the owner's own words fixing itself ──
        //
        // This was a full-bleed purple-to-red gradient, taken from the brand's
        // design file. On device his reaction was "the red paywall for redvpn is
        // hurting my eyes" and "background should not be so red". That is a
        // PHYSICAL complaint, not a taste one, and the measurement I had already
        // taken says exactly why: white body text gets 4.66:1 on the gradient's
        // red end against 18.48:1 here. The paywall is nearly all field and very
        // little content, so that weak ratio was spread over most of the screen,
        // and a paywall is read in a dark room more often than anything else in
        // the app.
        //
        // Worth admitting: the design file is NOT a red-ground screen. It is one
        // third white and two thirds gradient panel. The full-bleed version was
        // MY simplification of its two-zone layout, so this is my error being
        // corrected rather than the designer's being overruled.
        //
        // The ground is the SAME `groundDark` the home screen and the launch now
        // use, not a paywall-only value, so the three surfaces are one product.
        // It is derived from the brand's own accent (10% of #69108C over a
        // near-black), which is what keeps it a violet-black rather than the OS
        // grey, and gives a sixth brand its own.
        ground = BrandPalette.groundDark,
        // A whisper, not a field: 4% white at the top so the screen is not dead
        // flat under the mark. It was #69108C.
        groundTop = Color.White.copy(alpha = 0.04f).compositeOver(BrandPalette.groundDark),
        card = BrandPalette.cardDark,
        ink = Color(0xFFF4F1F6),
        muted = Color(0xFFA9A2B4),
        // The eyebrow is cut on a focused paywall, so this is only the badge
        // wash and the "premium active" line. White, because the accent is now
        // rationed to two objects and this is not one of them.
        gold = Color(0xFFF4F1F6),
        goldLite = Color(0xFFF4F1F6),
        // ── THE ACCENT, SPENT TWICE ──
        //
        // The gradient survives, at full saturation, on the CTA (ctaLite ->
        // cta, purple to red) and on the selected plan card's border. Nowhere
        // else. Reduced AREA rather than reduced saturation: a desaturated
        // brand red is a different brand, whereas a small one at full strength
        // is the same brand used with discipline. It also stops the red
        // vibrating, which is an area effect on a near-black ground, not a
        // saturation one.
        cta = Brand1,
        ctaLite = Accent3,
        // White on the gradient: 10.23:1 at the purple end, 4.66:1 at the red.
        // Clears 4.5 across the whole sweep rather than only where it was
        // checked.
        ctaInk = Color.White,
        line = Color(0x2EFFFFFF),
        badgeInk = Color.White,
        markOnDisc = true,
        focused = true,
        priceHero = false,
    )
    BrandStyle.VALUE -> PaywallPalette(
        ground = BrandPalette.primaryDeep,
        groundTop = Brand1,
        card = Color.White.copy(alpha = 0.09f).compositeOver(BrandPalette.primaryDeep),
        // Derived, not literal (§23). `ink` is the HOME screen's own ground,
        // so the two screens are the same product by construction.
        ink = BrandPalette.background,
        muted = tone(BrandPalette.background, 0.13f, 0.72f),
        gold = Accent3,
        goldLite = Accent3,
        cta = Accent3,
        ctaLite = Accent3,
        ctaInk = mostLegibleOn(Accent3, Color.White, BrandPalette.ink),
        line = BrandPalette.background.copy(alpha = 0.10f),
        badgeInk = mostLegibleOn(Accent3, Color.White, Color(0xFF1F1A12)),
        markOnDisc = false,
        focused = true,
        priceHero = true,
    )
    // The locked Internet Pro tokens: a dark navy room with one teal in it,
    // the same object as the Connect tab. Always the dark set, like every
    // other treatment's paywall.
    BrandStyle.TABS -> PaywallPalette(
        ground = TabsDark.bg,
        groundTop = TabsDark.bg2,
        card = TabsDark.surface,
        ink = TabsDark.text,
        muted = TabsDark.muted,
        gold = TabsDark.accent,
        goldLite = TabsDark.accentStrong,
        cta = TabsDark.accent,
        ctaLite = TabsDark.accentStrong,
        ctaInk = TabsDark.onAccent,
        line = TabsDark.text.copy(alpha = 0.08f),
        badgeInk = TabsDark.onAccent,
        markOnDisc = false,
        focused = true,
        priceHero = true,
    )
}

// Every existing call site below reads these names unchanged; only their SOURCE
// moved. A `@Composable get()` on a top-level val is the whole trick, and it is
// what let a nine-colour rebrand land without touching ~40 usage sites.
private val Ground: Color @Composable get() = LocalPaywall.current.ground
private val Card: Color @Composable get() = LocalPaywall.current.card
private val Ink: Color @Composable get() = LocalPaywall.current.ink
private val Muted: Color @Composable get() = LocalPaywall.current.muted
private val Gold: Color @Composable get() = LocalPaywall.current.gold
private val GoldLite: Color @Composable get() = LocalPaywall.current.goldLite
private val Teal: Color @Composable get() = LocalPaywall.current.cta
private val Line: Color @Composable get() = LocalPaywall.current.line
private val BadgeInk: Color @Composable get() = LocalPaywall.current.badgeInk

/**
 * Below this, a savings badge argues AGAINST the plan it is attached to.
 *
 * It was `> 0`, so any advantage at all earned a badge. Rostam's annual is
 * 16% cheaper per month than its monthly, and "SAVE 16%" in a gold pill is a
 * reader's cue to check whether the discount is worth committing a year — at
 * which point the badge has spent screen space making the case against the
 * plan the screen wants them to choose. Above ~30% the number carries itself.
 *
 * ⚠️ This CHANGES ROSTAM: its 16% badge disappears. That is deliberate and
 * requested, and it is the ONLY intentional change to rostam in this work --
 * its home screen and launch are pixel-identical. RedVPN's annual is 16.5%
 * on the same maths, so it loses the badge too until pricing moves.
 */
private const val MIN_SAVINGS_BADGE = 30

private val CardRadius = 16.dp
private val CardPad = 16.dp

// The savings chip's metrics are declared here because [PlanRow] computes the
// space it reserves for the chip from them. Its height must be predictable, so
// lineHeight is set explicitly rather than left to the default text style.
private val BadgeSize = 10.sp
private val BadgeLine = 14.sp
private val BadgeVPad = 3.dp
/** Deepest the chip may dip below the card's top border. Fixed in dp, while the
 *  chip's height scales with the system font: that is what keeps the clearance
 *  between the chip and the card's content constant at every font scale. */
private val BadgeMaxDip = 10.dp

/** Months in a billing period; 0 when unknown. Used to rank plans + price them. */
private fun periodMonths(billingPeriod: String?): Int = when (billingPeriod) {
    "P1W" -> 0        // sub-month: never the "best value" anchor
    "P1M" -> 1
    "P2M" -> 2        // Play has never sold this; ranked anyway, it costs nothing
    "P3M" -> 3
    "P6M" -> 6
    "P1Y" -> 12
    else -> 0
}

/**
 * The plan row's title, from Play's ISO-8601 `billingPeriod`.
 *
 * NOT from the product id: ids are per-brand (rostam p1m/p1a, redvpn
 * redvpn_premium/redvpn_yearly, and "redvpn_yearly" does not even end in
 * "year"), so matching them silently produced blank labels.
 *
 * NOT from `ProductDetails.name` either, which is what this row used to show.
 * Play localizes that by the **account's country**, never by the app's language,
 * so an English UI rendered Persian plan titles next to English everything else
 * and no amount of strings.xml work could fix it — it cannot follow the Android
 * 13+ per-app language picker. Returns null for a period we have no noun for;
 * the caller falls back to Play's name only there.
 */
@Composable
private fun periodTitle(billingPeriod: String?): String? = when (billingPeriod) {
    "P1W" -> cfgString(R.string.plan_period_weekly)
    "P1M" -> cfgString(R.string.plan_period_monthly)
    "P3M" -> cfgString(R.string.plan_period_3month)
    "P6M" -> cfgString(R.string.plan_period_6month)
    "P1Y" -> cfgString(R.string.plan_period_yearly)
    else -> null
}

private const val FSI = '\u2068'   // FIRST STRONG ISOLATE
private const val PDI = '\u2069'   // POP DIRECTIONAL ISOLATE

/**
 * Wrap a run we did not author in FSI…PDI so it cannot reorder the text around
 * it.
 *
 * Play's `formattedPrice` and the cashier's plan labels arrive in whatever
 * script and direction the store chose, and get composed beside UI text that may
 * be RTL. Without isolation the bidi algorithm pulls neighbouring neutrals into
 * the run and the line reads scrambled — the same mechanism that threw the
 * trailing full stops to the visual left of every Persian sentence on this
 * screen.
 */
private fun bidi(s: String): String = if (s.isEmpty()) s else "$FSI$s$PDI"

@Composable
fun PaywallScreen(viewModel: VpnViewModel, onBack: () -> Unit) {
    val rail by viewModel.payRail.collectAsState()
    val products by viewModel.subProducts.collectAsState()
    val cnPlans by viewModel.cnPlans.collectAsState()
    val webPlans by viewModel.webPlans.collectAsState()
    val isPaid by viewModel.isPaid.collectAsState()
    val plansLoading by viewModel.plansLoading.collectAsState()
    // The free tier's real ceiling, and whether this user is under it RIGHT NOW.
    // This is the only urgency on the screen and it is the user's own true
    // state — no countdown, no expiring discount. A censorship tool asking
    // someone to hurry is asking them to trust a sales tactic with traffic that
    // can get them arrested, and that trade is not worth a conversion point.
    val throttled by viewModel.reducedSpeed.collectAsState()
    val usage by viewModel.usage.collectAsState()
    // A hard-stop brand has no slow lane to fall back to, so there is no speed
    // to name. `free_kbps` is still set on those brands, and naming it would
    // describe a tier the user will never be given.
    val freeSpeed =
        if (viewModel.quotaStops || viewModel.paidOnly) null else formatSpeed(viewModel.freeTierKbps)
    // Non-null means BOTH: the core is really rate-limiting this user right now,
    // and we have a figure to name. Carried as a nullable rather than a boolean
    // so the two facts cannot drift apart at the call sites below — the screen
    // can never say "you are limited" without being able to say to what.
    val throttledSpeed = if (throttled) freeSpeed else null
    val throttledNow = throttledSpeed != null


    val style = rememberBrandStyle()
    val pw = paywallPaletteFor(style)
    // The paywall paints its OWN ground, so the system bars must follow this
    // gradient and not the app's colour scheme. On RedVPN in Light the scheme is
    // light and this screen is #211828, and the clock came out black on it.
    // Restored to the theme's setting when the paywall closes.
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
                // safeDrawing is the UNION of the status bar, the navigation bar,
                // the display cutout and the IME. The navigation bar used to clip
                // the footer — half of the Restore button's 48dp target sat under
                // a 51dp 3-button bar — and this is the only screen in the app
                // with a text field, so the IME has to be part of the same inset:
                // adding the bottom bar inset alone would push the account-code
                // field further out of reach. The gradient stays on the uninset
                // Box above, so colour still bleeds behind the bars and only the
                // scroll viewport shrinks.
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

            // 104dp -> 44dp on a focused paywall. The mark is IDENTIFICATION,
            // not an argument: the user is already inside this app and every
            // pixel it takes is a pixel the price does not get. It is also the
            // single largest item in the ~560dp that used to sit above the first
            // plan row.
            //
            // markOnDisc is a CONTRAST requirement rather than a flourish. On a
            // purple-to-red field RedVPN's own shield measures 1.04-1.47:1 —
            // the same failure the connect dial had — and on white it measures
            // 5.49:1. The brand's designer solved it the same way, putting the
            // shield on a white field rather than on the gradient.
            val markSize = if (pw.focused) 44.dp else 104.dp
            Box(
                contentAlignment = Alignment.Center,
                modifier = if (pw.markOnDisc)
                    Modifier.size(markSize + 16.dp).clip(CircleShape).background(Color.White)
                else Modifier.size(markSize + 16.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.brand_mascot),
                    // app_name, never a literal: this file is shared by five brands.
                    contentDescription = cfgString(R.string.app_name),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(markSize),
                )
            }

            // Arabic-script locales get no letter tracking and no serif face.
            // Tracking pulls joined letterforms apart into what reads as a broken
            // word, and Android's fallback stack ships no serif Arabic face, so
            // FontFamily.Serif silently degrades to the default sans there anyway
            // — better to substitute it deliberately. Selected by a resource
            // (res/values*/bools.xml), never by a locale test in Kotlin.
            val tracking = booleanResource(R.bool.paywall_letter_tracking)

            // CUT on a focused paywall. "REDVPN PREMIUM" over RedVPN's own mark,
            // inside RedVPN, tells the reader nothing they did not know before
            // they opened it — it is a label on a room they are standing in.
            if (!pw.focused) {
                Spacer(Modifier.height(14.dp))
                Text(
                    // The brand is isolated because it is a Latin wordmark dropped
                    // into whatever sentence the translator writes around it.
                    cfgString(R.string.paywall_eyebrow, bidi(cfgString(R.string.app_name))),
                    color = Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = if (tracking) 3.sp else 0.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))
            // The hero states the OFFER, and only swaps to the user's situation
            // when that situation is real. "The fast lane out. / Nothing in your
            // way." is atmosphere: it is beautiful and it tells a reader nothing
            // they could act on. The two things a free user does not know at this
            // moment are what they get and what it costs them not to have it, and
            // those are exactly what these two lines now say.
            Text(
                cfgString(
                    // 🚨 A SUBSCRIBER IS NOT A PROSPECT. The hero states what they
                    // HAVE. `premium_active` / `premium_tagline` are the same pair
                    // the purchase celebration uses, so both are already in 38
                    // locales and this costs no translation work.
                    if (isPaid) R.string.premium_active
                    else if (throttledNow) R.string.premium_title_throttled
                    // The offer headline SELLS "No ads.", which is not something
                    // to sell to someone who already has none — an ad-free brand
                    // or a donor region with ads off. The fallback says the same
                    // two real benefits without the third, and is already in 38
                    // locales, so no brand ships an English-only headline.
                    else if (!viewModel.adsEverShown) R.string.premium_title_default
                    else R.string.paywall_headline_offer
                ),
                fontFamily = if (tracking) FontFamily.Serif else FontFamily.Default,
                color = Ink,
                // 30sp -> 22sp when focused, with lineHeight following it. The
                // headline is the benefit list, so it keeps its full text and
                // its two lines; it just stops being a poster.
                fontSize = if (pw.priceHero) 18.sp else if (pw.focused) 22.sp else 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = if (pw.priceHero) 24.sp else if (pw.focused) 28.sp else 38.sp,
            )
            // The default subhead is DROPPED on a focused paywall: the headline
            // above it has already named what Premium gives. The THROTTLED one
            // stays in every treatment, because that one is not a restatement —
            // it names the speed this user is being held at right now, which is
            // the only true urgency on the screen and the one fact the headline
            // cannot carry.
            if (isPaid || throttledSpeed != null || !pw.focused) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (isPaid) cfgString(R.string.premium_tagline)
                    else if (throttledSpeed != null)
                        cfgString(R.string.paywall_subhead_throttled, throttledSpeed)
                    // paid_only: there is no free allowance to compare against.
                    else if (viewModel.paidOnly) cfgString(R.string.paywall_subhead)
                    else cfgString(R.string.paywall_subhead_offer),
                    color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth(0.86f),
                )
            }

            // ── ORDER IS THE OWNER'S COMPLAINT, AND IT IS A REAL ONE ──
            //
            // "they are long, the offer is not even visble at once
            // glance". Measured: close, a 104dp mark, an eyebrow, a
            // two-line 30sp serif headline, a subhead, then FOUR perk
            // rows each carrying an icon, a bold title AND a full
            // sentence — roughly 560dp before the first price, on a
            // viewport of about 640-700dp after the system bars. The
            // price was off-screen on the screen whose only job is the
            // price.
            //
            // The fix is NOT to compress the perks. The headline ALREADY
            // IS the benefit list — paywall_headline_offer names unlimited,
            // full speed and no ads — so the four rows below it were
            // always a restatement, and a compressed chip strip would be
            // the same restatement wrapping badly in German and Persian.
            // They move BELOW the fold intact, where they answer the
            // visitor who came to read rather than the one the app just
            // interrupted. Most views are involuntary: VpnViewModel opens
            // this screen itself when the cashier reports over-quota, and
            // that user already knows why they are here.
            //
            // CLASSIC keeps today's order, so rostam does not move. The
            // same fix is one token away for it when the owner wants it.
            val perksBlock: @Composable () -> Unit = {
                Spacer(Modifier.height(24.dp))
                // FOUR perks, ordered by what the user actually feels, and two of
                // them were missing entirely. The screen used to sell "every region"
                // and "full speed" while saying nothing about the data cap that is
                // the reason most people arrive here, and nothing about ads — even
                // though a free user is shown a full-screen interstitial on EVERY
                // connect and Premium removes it. An unstated benefit converts nobody.
                // "Renews every month" is TRUE on rostam/redvpn (verified against
                // the production cashier: a 45-day-old period refills on the next
                // usage report) and FALSE on a one-time brand. The server owns the
                // answer, so read it rather than assuming either.
                val quotaRenews = usage?.renews ?: true
                // paid_only: both data bodies describe a free allowance this
                // brand does not have; the speed perk below already says
                // "No cap, and never slowed down".
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
                    // Names the actual ceiling when we know it. "No cap, and never
                    // slowed down" asks the reader to imagine the alternative; "Free
                    // drops to 2 Mbps once it runs out" tells them what it is, and it
                    // comes from the config the core enforces, so it cannot go stale.
                    if (freeSpeed != null)
                        cfgString(R.string.paywall_perk_speed_body_limit, freeSpeed)
                    else cfgString(R.string.paywall_perk_speed_body),
                )
                // Only a benefit where ads actually exist. A no-ads brand and a
                // donor region with ads off both already have this, and selling
                // someone something they have is a claim a store page carries.
                if (viewModel.adsEverShown) Perk(
                    R.drawable.ic_perk_noads,
                    cfgString(R.string.paywall_perk_ads_title),
                    cfgString(R.string.paywall_perk_ads_body),
                )
                // The `tabs` treatment has no region picker (its route is
                // read-only until the server has a region contract) and no
                // globes, so this perk would sell something it does not have.
                if (style != BrandStyle.TABS) Perk(
                    R.drawable.ic_perk_globe,
                    cfgString(R.string.paywall_perk_regions_title),
                    cfgString(R.string.paywall_perk_regions_body),
                )
                // "Every region" was KEPT rather than replaced by this one, as
                // the brief's list implied: the region picker is premium-gated,
                // so dropping it would remove a benefit the user actually buys.
                // Its body says "Most VPNs", and `tabs` copy never says VPN.
                if (style != BrandStyle.TABS) Perk(
                    R.drawable.ic_perk_exchange,
                    cfgString(R.string.paywall_perk_ixp_title),
                    cfgString(R.string.paywall_perk_ixp_body),
                )
                ProofRow()
                Spacer(Modifier.height(20.dp))

                // ── Plan selection — annual anchored. One CTA acts on the selection. ──
                // `rail` decides which BUTTONS exist. It never decides which language
                // the screen is in: that is the resource system's job, and it is the
                // only thing that can serve 38 locales. A payment rail is not a
                // locale — gating copy on PayRail.CHINA gave a Chinese reader outside
                // China an English screen and a German on the China rail a Chinese one.
            }
            val plansBlock: @Composable () -> Unit = {
                when (rail) {
                    PayRail.GOOGLE_PLAY -> {
                        // Everything below is derived from Play's own data, so it works
                        // for ANY brand's SKU ids (p1m/p1a, redvpn_premium/redvpn_yearly, …).
                        fun phase(pd: com.android.billingclient.api.ProductDetails) =
                            pd.subscriptionOfferDetails?.firstOrNull()
                                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                        fun months(pd: com.android.billingclient.api.ProductDetails) =
                            periodMonths(phase(pd)?.billingPeriod)

                        // Longest billing period = the value anchor (usually annual).
                        val anchor = products.maxByOrNull { months(it) }
                        var selected by remember { mutableStateOf("") }
                        // Re-pick only if the current choice vanished, so a billing
                        // reconnect doesn't reset the user's tap.
                        LaunchedEffect(products) {
                            if (products.isNotEmpty() && products.none { it.productId == selected }) {
                                selected = anchor?.productId ?: products.first().productId
                            }
                        }
                        // Savings = anchor's per-month cost vs the cheapest per-month plan.
                        fun perMonth(pd: com.android.billingclient.api.ProductDetails): Double {
                            val mo = months(pd); val micros = phase(pd)?.priceAmountMicros ?: 0L
                            return if (mo > 0 && micros > 0) micros.toDouble() / mo else 0.0
                        }
                        /**
                         * The value anchor: this plan's price expressed PER MONTH.
                         *
                         * "$39.99 / year" beside "$3.99 / month" makes the reader do
                         * division before they can compare, and most do not — they
                         * compare 39.99 against 3.99 and pick the small number. This
                         * puts both plans in the same unit so the annual row's
                         * advantage is visible instead of inferred.
                         *
                         * Derived from Play's own `priceAmountMicros` +
                         * `priceCurrencyCode` and the ISO-8601 billing period, so it
                         * works for any brand's SKUs (CLAUDE.md 6b — never match on a
                         * product id) and can never invent a currency. Null for a
                         * one-month or unknown period, where it would say nothing the
                         * price does not already say.
                         */
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
                                // FLOOR, not round. $11.99/12 = $0.9992, which the
                                // default formatter rounds UP to $1.00 — a figure
                                // fractionally ABOVE what a month actually costs, on
                                // a brand whose whole pitch is the dollar. Flooring
                                // says $0.99: never more than the true per-month
                                // share, and the real annual charge is stated
                                // directly beneath it.
                                //
                                // Scaled by the CURRENCY's own fraction digits, not a
                                // hardcoded 100 — JPY has 0 and BHD has 3, and
                                // flooring those to 2 places invents precision the
                                // currency does not have.
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
                        // Two SKUs sharing one billing period cannot both be titled by
                        // that period. Then — and only then — Play's own name is the
                        // sole thing that tells them apart.
                        val perPeriod = products.groupingBy { phase(it)?.billingPeriod }.eachCount()

                        if (products.isEmpty()) {
                            if (plansLoading) Loading() else Unavailable()
                        } else {
                            // One plan, a real per-month figure and a period noun:
                            // then the card has nothing to compare against and the
                            // hero says it better. Any clause failing falls through
                            // to the rows below, which is what every other brand does.
                            val solo = products.singleOrNull()
                            val soloPm = solo?.let { perMonthLabel(it) }
                            val soloNoun = solo?.let { periodTitle(phase(it)?.billingPeriod) }
                            if (pw.priceHero && soloPm != null && soloNoun != null) {
                                LaunchedEffect(solo) { selected = solo.productId }
                                PriceHero(
                                    perMonth = bidi(soloPm),
                                    periodNoun = soloNoun,
                                    total = bidi(phase(solo)?.formattedPrice ?: ""),
                                )
                            } else products.sortedByDescending { months(it) }.forEach { pd ->
                                val bp = phase(pd)?.billingPeriod
                                val noun = periodTitle(bp)?.takeIf { (perPeriod[bp] ?: 0) <= 1 }
                                val isAnchor = pd.productId == anchor?.productId && savings >= MIN_SAVINGS_BADGE
                                PlanRow(
                                    title = noun ?: bidi(pd.name.ifEmpty { pd.productId }),
                                    // The per-month equivalent, NOT "Save 16% vs
                                    // monthly": the badge already carries the
                                    // percentage, and printing it twice on one row
                                    // spent the subtitle on a number the reader had
                                    // just been given. The per-month figure is the one
                                    // thing the row could not otherwise say.
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
                            // Free-trial label if the selected plan offers a zero-price phase.
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

                    PayRail.PAYMENTWALL -> WebPlans(
                        webPlans, plansLoading,
                        R.string.pay_with_card, R.string.paywall_disclosure_paymentwall,
                    ) { viewModel.startPaymentwallPayment(it) }

                    PayRail.CHINA -> {
                        if (cnPlans.isEmpty()) {
                            if (plansLoading) Loading() else Unavailable()
                        } else {
                            cnPlans.forEach { p ->
                                PlanRow(
                                    title = bidi(p.label.ifEmpty { p.slug }),
                                    subtitle = null,
                                    price = bidi(p.price),
                                    badge = null,
                                    selected = false,
                                ) {}
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    // Wallet marks come from resources: values/ carries
                                    // the Latin names and values-zh-rCN the Chinese
                                    // ones, so a German on this rail is not shown
                                    // Chinese buttons.
                                    Cta(cfgString(R.string.pay_alipay), Modifier.weight(1f)) {
                                        viewModel.startCnPayment(p.slug, "alipay")
                                    }
                                    Cta(cfgString(R.string.pay_wechat), Modifier.weight(1f)) {
                                        viewModel.startCnPayment(p.slug, "wxpay")
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                            }
                            Reassure()
                            Disclosure(cfgString(R.string.paywall_disclosure_china))
                        }
                    }
                }
            }

            // 🚨 NO LADDER AND NO CTA FOR SOMEONE WHO ALREADY PAID.
            //
            // Routing an entitled user here from the Adapty flow was only half
            // the fix: `premium_active` was APPENDED below `plansBlock()`, so a
            // subscriber still got the plan rows, a live "Go Premium" button
            // wired to `purchaseSubscription`, and the billing disclosure --
            // with "Premium is active" underneath them. Observed on
            // R5CY13EDP7A, `tier=paid` with a live Play token.
            //
            // The perks stay: on this branch they read as what the user HAS,
            // and they are the only thing on the screen that says what the
            // subscription is for.
            if (isPaid) {
                perksBlock()
            } else if (pw.focused) {
                Spacer(Modifier.height(18.dp))
                plansBlock()
                Spacer(Modifier.height(if (pw.priceHero) 8.dp else 30.dp))
                perksBlock()
            } else {
                perksBlock()
                plansBlock()
            }

            if (isPaid) {
                // 🚨 THE ACCOUNT CODE IS GONE. Entitlement is the native store
                // plus Adapty; there has been nowhere to ENTER a code since
                // sign-in came out, so showing one described a transfer the
                // product no longer performs -- and it cost a cashier round
                // trip on every open to render it.
                //
                // What a subscriber needs here is where to cancel or change
                // plan. `paywall_disclosure_play` already says exactly that,
                // in 38 locales, so this needs no new string.
                if (rail == PayRail.GOOGLE_PLAY) {
                    Spacer(Modifier.height(8.dp))
                    Disclosure(cfgString(R.string.paywall_disclosure_play))
                }
            } else {
                Spacer(Modifier.height(18.dp))
                // Restore is the STORE's job now. There is no account and no
                // code to paste: a subscription lives on the Play account that
                // bought it, so restoring is re-reading queryPurchases. This
                // also runs unprompted at launch (MainScreen), so the button is
                // for the user who does not know that and taps something.
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
 * Stripe / crypto / Paymentwall are one screen with a different CTA and
 * disclosure: the same cashier `Plan` list and the same
 * one-CTA-acts-on-the-selection shape.
 *
 * They were three copy-pasted blocks, which is how `periodLabel(p.slug)`
 * survived in all three — cashier slugs are `cn-monthly` / `usd-monthly`, never
 * ISO-8601, so that label has always rendered empty and no rail here has ever
 * shown a period. `CashierApi.Plan` carries no period field at all, so the row
 * shows the server's own label and the price, and nothing it cannot know.
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

/**
 * The trust row — real numbers from THIS brand's Play listing, or nothing.
 *
 * It sits between the perks and the prices because that is where the reader
 * stops believing the seller and starts looking for someone else's evidence.
 * Everything on it is a figure a person can go and check on the store page; the
 * app makes no claim of its own here, invents no award, quotes no user. Quoting
 * a testimonial from someone who uses a censorship tool would be dishonest even
 * if it were real, and identifying enough to be dangerous if it were.
 *
 * The numbers are per-flavor integer resources, generated from brands/<id>.json
 * (see gen_android_proof). A brand with none draws nothing at all — rostam's
 * million downloads are rostam's, and four of the five brands in this tree would
 * be lying if a shared literal spoke for them.
 */
/**
 * The single-plan price lockup: per-month figure, unit, rule, billed total.
 *
 * Replaces [PlanRow] when there is exactly one product. A row exists to be
 * SELECTED; with nothing to choose between, its border, fill and tap target are
 * furniture, and they push the perks under the fold. Leading with the per-month
 * figure is the owner's call — "emphasise the monthly cost while showing annual
 * pricing" — and it is also the honest way to read an annual charge.
 *
 * Both numbers come from Play (`perMonth` is `priceAmountMicros / months`), so
 * this can never invent a currency and never states a figure the store will not
 * charge. There is no hardcoded "$1.00" anywhere: that string is wrong in every
 * market that is not the US.
 */
@Composable
private fun PriceHero(perMonth: String, periodNoun: String, total: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            perMonth,
            // Explicitly Default: Noto Serif has no Black weight, so inheriting
            // the brand's serif here makes Compose synthesize one.
            fontFamily = FontFamily.Default,
            fontSize = 56.sp,
            fontWeight = FontWeight.Black,
            color = Ink,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            cfgString(R.string.plan_hero_unit),
            fontSize = 15.sp,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .width(96.dp)
                .height(1.dp)
                .background(Gold.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(periodNoun, fontSize = 14.sp, color = Muted)
            Text("\u00B7", fontSize = 14.sp, color = Muted)
            Text(total, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
    }
}

@Composable
private fun ProofRow() {
    val downloads = integerResource(R.integer.brand_proof_downloads)
    val ratingX10 = integerResource(R.integer.brand_proof_rating_x10)
    val reviews = integerResource(R.integer.brand_proof_reviews)
    // Each half is drawn only if its own numbers exist, so a brand that knows
    // its install count but has no rating yet still gets the half it can prove.
    if (downloads <= 0 && (ratingX10 <= 0 || reviews <= 0)) return
    // The leading gap belongs to the row, not to the caller: a brand with no
    // numbers must leave no hole where the row would have been.
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
                // A vector star, not "★". U+2605 is drawn by whichever fallback
                // font the OEM ships, at whatever weight, and cannot be tinted —
                // the same failure the perk icons were converted away from.
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

/**
 * The risk reversal, immediately above the button.
 *
 * There is no money-back guarantee to promise and none is implied here. What
 * this product can honestly offer instead is better suited to its audience
 * anyway: buying it requires no account, no email and no name. For someone
 * paying for a tool that gets them past a national filter, "we never learn who
 * you are" answers a bigger fear than a refund does.
 */
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
            // Vectors, not "◉"/"⚡": U+25C9 defaults to text presentation and
            // U+26A1 to emoji presentation, so the two perks rendered in two
            // different visual languages — a gold glyph above the OEM's colour
            // emoji — and the result was at the mercy of the device's font.
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
 * the row printed the period under the title AND under the price, beside a Play
 * product name that already meant "yearly".
 *
 *     [Yearly]                                  [$39.99]
 *     [Save 16% vs monthly]
 *
 * The savings chip is **not** an overlay. It is a sibling in the Box and the card
 * is pushed down by `reserve`, so the chip straddles the card's top border in
 * real, laid-out space:
 *
 *     chipH     = the chip's height, FIXED by Modifier.height so it is known
 *                 rather than estimated from text metrics
 *     dip       = min(chipH / 2, BadgeMaxDip)   how far it sinks past the border
 *     reserve   = chipH - dip                   empty band above the card
 *     clearance = CardPad - dip                 chip bottom -> card content
 *
 * `chipH` cancels out of `clearance`, so the gap between the chip and the card's
 * content is `CardPad - dip >= CardPad - BadgeMaxDip` = 6dp **whatever the font
 * scale, the price string or the locale** — the chip rises further at 2x text
 * instead of reaching further in. That is why this is structurally impossible
 * rather than merely nudged out of the way: the old chip was drawn on top of the
 * price and ate 62% of the annual price's glyph height.
 *
 * No `offset` anywhere. `Alignment.TopEnd` and `padding(end = …)` are
 * direction-aware, so RTL mirrors for free — this is the Compose face of the
 * SwiftUI `.offset(x:)` trap that iOS had to hand-mirror, and the right move is
 * to not need the offset at all.
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
    // chipH is IMPOSED on the chip below (Modifier.height), not estimated from
    // its text metrics — an estimate that came out a couple of dp short would
    // silently eat the clearance the layout is built on. It scales with the
    // system font because the label inside it does; the dip past the card's
    // border is capped in dp, which is what keeps the clearance constant.
    val chipH = with(LocalDensity.current) { BadgeLine.toDp() } + BadgeVPad * 2
    val dip = (chipH / 2).coerceAtMost(BadgeMaxDip)
    val reserve = chipH - dip

    // Reserved on every row, badged or not, so the cards keep one rhythm and
    // nothing shifts when the anchor moves between them.
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = reserve)
                .clip(RoundedCornerShape(CardRadius))
                .background(Card)
                // THE SECOND (and last) place the accent is spent on a dark
                // paywall: the selected plan's border. `selectedEdge` is the
                // palette's own CTA colour, so the row the user has chosen and
                // the button that acts on it are visibly the same decision.
                // CLASSIC still resolves this to Gold, unchanged.
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
            // ONE price, vertically centred. The second period label that used to
            // sit under it is gone: it was the same string as the row's own title.
            if (price.isNotEmpty()) {
                Text(price, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    // Clear of the card's corner arc, so the chip's pill and the
                    // card's radius never meet as a double corner.
                    .padding(end = 18.dp)
                    // Fixes the chip's height at exactly chipH, which is what the
                    // reserve above is computed from. BadgeVPad * 2 of headroom
                    // over the label's own line height, so nothing clips.
                    .height(chipH)
                    // GoldLite, not Gold: the chip used to be the exact colour of
                    // the selected card's border and the two merged into one blob.
                    // This is the colour iOS gives it, which is why the chip reads
                    // there as a separate object.
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
    // A wrong SKU id makes this the ONLY thing on the screen, so it is the last
    // string that should have been English-only.
    cfgString(R.string.plans_unavailable),
    color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
)

@Composable
private fun Disclosure(text: String) = Text(
    text, color = Muted, fontSize = 11.5.sp, textAlign = TextAlign.Center,
    lineHeight = 16.sp, modifier = Modifier.padding(top = 11.dp),
)


package com.rostamvpn.android.billing

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.TimeZone

/**
 * Which payment rail to offer. China has no Google Play, so mainland-CN devices
 * get the cashier-brokered Alipay/WeChat rail; everyone else uses Play Billing.
 */
/**
 * Pay rails the app can render. Which one is used is decided at runtime from
 * `/pay/methods` — never hardcoded — so a rail can be switched off server-side
 * without an app release.
 *
 * PayPal was removed (2026-08-07). PAYMENTWALL is retained only as a last-resort
 * card fallback so the direct build is never left with no card rail; turn it off in
 * `/pay/methods` once STRIPE handles subscriptions and it disappears by itself.
 */
enum class PayRail { GOOGLE_PLAY, CHINA, STRIPE, CRYPTO, PAYMENTWALL }

/** Result of region detection, including which signal decided it (for debugging). */
data class RegionInfo(val country: String, val source: String, val timezone: String)

/**
 * Region detection for choosing the pay rail. Cascade, most→least authoritative,
 * first non-empty wins:
 *   1. Play Store billing country  (authoritative "store data" — where they pay)
 *   2. SIM home / network country  (TelephonyManager, no permission, hard to spoof)
 *   3. Device timezone             (CN zone set)
 *   4. Locale country              (weak last resort)
 *
 * IP geolocation is deliberately NOT used: this is a VPN, so once connected the
 * apparent IP is the exit node and would lie about the user's region.
 *
 * `storeCountry` is supplied by the caller (from
 * BillingClient.getBillingConfigAsync().countryCode) because it's async; pass null
 * if unavailable and the cascade falls through to the device signals.
 */
object RegionDetector {

    // Mainland-China timezone ids (Play unavailable → cashier rail).
    private val CN_TIMEZONES = setOf(
        "Asia/Shanghai", "Asia/Urumqi", "Asia/Chongqing", "Asia/Harbin",
        "Asia/Kashgar", "PRC"
    )

    // Arabic-speaking-region timezone ids (for the tz→UI-language override).
    private val AR_TIMEZONES = setOf(
        "Asia/Dubai", "Asia/Riyadh", "Asia/Baghdad", "Asia/Qatar", "Asia/Kuwait",
        "Asia/Bahrain", "Asia/Muscat", "Asia/Aden", "Asia/Amman", "Asia/Beirut",
        "Asia/Damascus", "Asia/Gaza", "Asia/Hebron", "Africa/Cairo"
    )

    /**
     * tz → UI language (Option B: timezone OVERRIDES the device locale). Returns
     * a region-qualified locale matching our resource bundles (fa-rIR, zh-rCN, ru,
     * ar), or null for timezones we don't localize (caller keeps device/English).
     */
    fun localeForTimezone(tz: String): Locale? = when {
        tz == "Asia/Tehran" || tz == "Iran" -> Locale("fa", "IR")
        tz in CN_TIMEZONES -> Locale("zh", "CN")
        tz == "Europe/Moscow" || tz == "W-SU" -> Locale("ru")
        tz in AR_TIMEZONES -> Locale("ar")
        else -> null
    }

    /** Debug-only region override (set from the Dev Tools screen). When non-null,
     *  it wins over every real signal so testers can flip IR/US/CN without changing
     *  the device timezone. Only ever set in debug builds. */
    @Volatile var devOverride: String? = null

    /** Full detection with the deciding signal named — used for the debug overlay. */
    fun detect(context: Context, storeCountry: String?): RegionInfo {
        val tz = TimeZone.getDefault().id
        devOverride?.takeIf { it.isNotEmpty() }?.let { return RegionInfo(it.uppercase(), "dev", tz) }
        storeCountry?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            ?.let { return RegionInfo(it, "store", tz) }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
            ?.let { return RegionInfo(it, "sim", tz) }
        tm?.networkCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
            ?.let { return RegionInfo(it, "network", tz) }

        if (tz in CN_TIMEZONES) return RegionInfo("CN", "timezone", tz)
        if (tz == "Asia/Tehran" || tz == "Iran") return RegionInfo("IR", "timezone", tz)

        return RegionInfo(Locale.getDefault().country.uppercase().ifEmpty { "US" }, "locale", tz)
    }

    fun detectCountry(context: Context, storeCountry: String?): String =
        detect(context, storeCountry).country

    /**
     * ISO region for a freedom-region timezone, else null. Generous tz match:
     * exact, or the config value as a prefix, so "Asia/Tehran" also covers any
     * sub-zone a vendor ROM invents.
     *
     * Config-driven — the table is `ConfigManager.freedomRegions`. Nothing here
     * knows a region by name.
     */
    /**
     * Enabled IME subtype locales plus the system locale list, as language codes
     * ("fa", "ckb", "ru"). One implementation, because this signal decides two
     * very different things — whether to make the device a proxy exit node, and
     * whether the person gets the product free — and they must never disagree.
     */
    fun keyboardLangs(context: Context): Set<String> {
        val langs = mutableSetOf<String>()
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.enabledInputMethodList?.forEach { imi ->
                imm.getEnabledInputMethodSubtypeList(imi, true)?.forEach { st ->
                    val loc = st.languageTag.ifEmpty { @Suppress("DEPRECATION") st.locale }
                    if (loc.isNotEmpty()) {
                        langs.add(loc.substringBefore('-').substringBefore('_').lowercase())
                    }
                }
            }
        } catch (_: Throwable) {}
        // The system locale list as a second reading of the same intent: a person
        // who added Persian to their device languages read Persian.
        try {
            val locales = context.resources.configuration.locales
            for (i in 0 until locales.size()) langs.add(locales[i].language.lowercase())
        } catch (_: Throwable) {}
        return langs
    }

    /** Which freedom region, if any, this device's keyboards claim. */
    fun freedomRegionForKeyboards(context: Context): String? {
        val table = com.rostamvpn.android.ConfigManager.freedomKeyboards
        if (table.isEmpty()) return null
        val langs = keyboardLangs(context)
        if (langs.isEmpty()) return null
        for ((region, wanted) in table) {
            if (wanted.any { it.lowercase() in langs }) return region
        }
        return null
    }

    fun freedomRegionForTz(tz: String): String? {
        val t = tz.trim()
        if (t.isEmpty()) return null
        for ((region, zones) in com.rostamvpn.android.ConfigManager.freedomRegions) {
            for (z in zones) {
                if (t.equals(z, ignoreCase = true) || t.startsWith(z, ignoreCase = true)) return region
            }
        }
        return null
    }

    /**
     * The freedom region (donor-funded, 100% free) this device belongs to, or
     * null when it belongs to none.
     *
     * THE SINGLE SOURCE for the freedom decision. Every gate — metering,
     * throttle, CDN, paywall, ad policy, AdMob init — must read this one
     * function, so the UI can never disagree with the policy and the ad-policy
     * region can never be re-derived from a different signal than the one that
     * granted the free tier. `AdMobInitializer.allowedHere` reads it too, which
     * is why it lives here and not in the ViewModel: it has to be answerable in
     * onCreate, before any ViewModel exists.
     *
     * BOTH signals count, SIM/network FIRST and then the timezone:
     *   - SIM first, so an Iranian SIM roaming abroad keeps the free tier and
     *     the returned CODE is the one the SIM says (that code picks ad policy).
     *   - The timezone counts too, not merely as a fallback. It used to be a
     *     fallback guarded by "if the country is empty", which `detect()` can
     *     never produce — it ends at the locale country, defaulting to "US" —
     *     so the config's timezone lists were unreachable code and two real
     *     cohorts were metered, throttled, paywalled and served commercial ads:
     *     a foreign SIM inside Iran, and a SIM-less Russian tablet on a non-RU
     *     locale (the config carries 27 RU zones that could not influence
     *     anything).
     *
     * Erring toward free is the safe direction: a false positive costs one
     * subscription, a false negative charges someone the project decided to give
     * it to. The clock is user-writable and the SIM is not — but the cashier
     * already decides freedom from the reported timezone ALONE
     * (`region.is_freedom_region_tz`), so a spoofed clock is granted unlimited
     * quota server-side whatever this returns. SIM-only strictness here bought
     * no revenue; it only put a paywall and an ad request in front of the two
     * cohorts above.
     */
    /** Which signal decided the last [freedomRegion] call. Diagnostics only. */
    @Volatile var lastFreedomSignal: String = "none"
        private set

    /**
     * Which freedom region this device belongs to, or null.
     *
     * Precedence, and the asymmetry behind it is deliberate:
     *
     *  1. Dev override, alone.
     *  2. **Location** — SIM country, network country, timezone. Hardware and
     *     clock. Anyone actually IN Iran trips at least one of these, so this
     *     step is what protects the Iranian experience, and it is never gated
     *     on anything else. Russia needs nothing more: an RU SIM lands here.
     *  3. **Keyboard** — proves the person READS the language, not where they
     *     are. It rescues what location cannot see: an Iranian on a foreign
     *     SIM, roaming, dual-SIM, or a wifi tablet with no SIM at all.
     *
     * Every false positive lives in step 3 and they are one population — the
     * diaspora: Persian keyboard, foreign SIM, foreign clock. [strictKeyboard]
     * is the knob for exactly them, and it CANNOT reach anyone in Iran: to be
     * refused you need a non-freedom SIM *and* a non-freedom network *and* a
     * non-freedom timezone at once, which is a description of being elsewhere.
     *
     * The Play store country is deliberately NOT consulted. Play does not serve
     * Iran, so Iranian users routinely hold US store accounts — reading that as
     * location would charge the very people this mechanism exists to protect.
     */
    fun freedomRegion(context: Context, storeCountry: String? = null): String? {
        val table = com.rostamvpn.android.ConfigManager.freedomRegions

        // The Dev Tools override decides ALONE. An OR with the other signals
        // could not force NOT-freedom on a device whose real tz is a freedom
        // zone, which is exactly what a tester needs it for.
        devOverride?.takeIf { it.isNotEmpty() }?.let { o ->
            lastFreedomSignal = "dev"
            return table.keys.firstOrNull { it.equals(o, ignoreCase = true) }
        }

        // ── 2. Location. Never gated, never second-guessed. ────────────────
        //
        // BOTH the SIM and the serving network are tested, not just whichever
        // detect() happens to return first. detect() stops at the SIM, and a
        // foreign SIM ROAMING INSIDE IRAN reports simCountryIso=<foreign> while
        // networkCountryIso is "ir" — the serving network is the Iranian one.
        // Reading only the SIM would refuse a person standing in Tehran holding
        // a Qatari SIM. The network is also the harder signal to arrange: you
        // have to physically be there.
        val info = detect(context, storeCountry)
        simAndNetworkCountries(context).forEach { c ->
            table.keys.firstOrNull { it.equals(c, ignoreCase = true) }?.let {
                lastFreedomSignal = "hardware:$c"
                return it
            }
        }
        table.keys.firstOrNull { it.equals(info.country, ignoreCase = true) }?.let {
            lastFreedomSignal = info.source              // store | locale
            return it
        }
        freedomRegionForTz(info.timezone)?.let {
            lastFreedomSignal = "timezone"
            return it
        }

        // ── 3. Keyboard. The rescue, and the only home of false positives. ──
        val byKeyboard = freedomRegionForKeyboards(context)
        if (byKeyboard == null) {
            lastFreedomSignal = "none"
            return null
        }
        if (!com.rostamvpn.android.ConfigManager.strictKeyboard) {
            lastFreedomSignal = "keyboard"
            return byKeyboard
        }

        // Strict mode. The keyboard still wins unless HARDWARE contradicts it: a
        // SIM or serving network is issued by a carrier, not typed by a user, so
        // a foreign one — reached only after the timezone also declined above —
        // is the diaspora signature.
        //
        // Absent hardware is NOT a contradiction. A wifi-only tablet has nothing
        // to contradict with, and refusing it would break the exact case the
        // keyboard signal was added to rescue.
        val hardware = simOrNetworkCountry(context)
        if (hardware.isNullOrEmpty()) {
            lastFreedomSignal = "keyboard:no-hardware"
            return byKeyboard
        }
        lastFreedomSignal = "keyboard:refused-by-$hardware"
        return null
    }

    /**
     * The SIM's country, else the serving network's; null when the device has
     * neither — a wifi-only tablet, or a phone with the radio off.
     *
     * Excludes the timezone (user-writable) and the store country (see
     * [freedomRegion]) on purpose: this is the hardware-only reading.
     */
    private fun simAndNetworkCountries(context: Context): List<String> = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        listOfNotNull(
            tm?.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() },
            tm?.networkCountryIso?.uppercase()?.takeIf { it.isNotEmpty() },
        ).distinct()
    } catch (_: Throwable) { emptyList() }

    /** First hardware country reported, SIM before serving network; null if neither. */
    private fun simOrNetworkCountry(context: Context): String? =
        simAndNetworkCountries(context).firstOrNull()

    /**
     * Convenience rail picker. **Compliant by construction:** the non-Play rails
     * (CHINA/Shepherd) are only ever returned in the `direct` build
     * (`WEB_PAY_ENABLED`). In the `play` build this always returns `GOOGLE_PLAY`,
     * because routing any user to an alternative payment from a Play-distributed
     * app violates Google Play's Payments policy. See docs/PLAY-POLICY.md §0.
     * (Note: the live rail selection is `VpnViewModel.openPaywallAndLoad()`; this
     * helper exists for callers that just need the region→rail mapping.)
     */
    fun rail(context: Context, storeCountry: String?): PayRail =
        if (com.rostamvpn.android.BuildConfig.WEB_PAY_ENABLED &&
            detectCountry(context, storeCountry) == "CN"
        ) PayRail.CHINA else PayRail.GOOGLE_PLAY
}

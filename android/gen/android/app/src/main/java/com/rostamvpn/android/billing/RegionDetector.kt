// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.billing

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.TimeZone

/**
 * Which payment rail to offer. China has no Google Play, so mainland-CN
 * devices get the cashier-brokered Alipay/WeChat rail; everyone else uses Play
 * Billing.
 */
/**
 * Pay rails the app can render. Which one is used is decided at runtime from
 * `/pay/methods` — never hardcoded — so a rail can be switched off server-side
 * without an app release.
 */
enum class PayRail { GOOGLE_PLAY, STRIPE, CRYPTO }

/**
 * Result of region detection, including which signal decided it (for
 * debugging).
 */
data class RegionInfo(val country: String, val source: String, val timezone: String)

/**
 * Region detection for choosing the pay rail. Cascade, most→least
 * authoritative, first non-empty wins: 1. Play Store billing country
 * (authoritative "store data" — where they pay) 2.
 */
object RegionDetector {
    private val CN_TIMEZONES = setOf(
        "Asia/Shanghai", "Asia/Urumqi", "Asia/Chongqing", "Asia/Harbin",
        "Asia/Kashgar", "PRC"
    )

    /**
     * Debug-only region override (set from the Dev Tools screen). When non-null,
     * it wins over every real signal so testers can flip IR/US/CN without changing
     * the device timezone. Only ever set in debug builds.
     */
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
     */
    /**
     * Enabled IME subtype locales plus the system locale list, as language codes
     * ("fa", "ckb", "ru").
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
     * The freedom region (donor-funded, 100% free) this device belongs to, or null
     * when it belongs to none.
     */
    /** Which signal decided the last [freedomRegion] call. Diagnostics only. */
    @Volatile var lastFreedomSignal: String = "none"
        private set

    /** Which freedom region this device belongs to, or null. */
    fun freedomRegion(context: Context, storeCountry: String? = null): String? {
        val table = com.rostamvpn.android.ConfigManager.freedomRegions

        devOverride?.takeIf { it.isNotEmpty() }?.let { o ->
            lastFreedomSignal = "dev"
            return table.keys.firstOrNull { it.equals(o, ignoreCase = true) }
        }

        val info = detect(context, storeCountry)
        simAndNetworkCountries(context).forEach { c ->
            table.keys.firstOrNull { it.equals(c, ignoreCase = true) }?.let {
                lastFreedomSignal = "hardware:$c"
                return it
            }
        }
        table.keys.firstOrNull { it.equals(info.country, ignoreCase = true) }?.let {
            lastFreedomSignal = info.source
            return it
        }
        freedomRegionForTz(info.timezone)?.let {
            lastFreedomSignal = "timezone"
            return it
        }

        val byKeyboard = freedomRegionForKeyboards(context)
        if (byKeyboard == null) {
            lastFreedomSignal = "none"
            return null
        }
        if (!com.rostamvpn.android.ConfigManager.strictKeyboard) {
            lastFreedomSignal = "keyboard"
            return byKeyboard
        }

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
     */
    private fun simAndNetworkCountries(context: Context): List<String> = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        listOfNotNull(
            tm?.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() },
            tm?.networkCountryIso?.uppercase()?.takeIf { it.isNotEmpty() },
        ).distinct()
    } catch (_: Throwable) { emptyList() }

    /**
     * First hardware country reported, SIM before serving network; null if
     * neither.
     */
    private fun simOrNetworkCountry(context: Context): String? =
        simAndNetworkCountries(context).firstOrNull()
}

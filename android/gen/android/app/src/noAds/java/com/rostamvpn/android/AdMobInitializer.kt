package com.rostamvpn.android

import android.content.Context

/**
 * No-adtech build: nothing to initialise.
 *
 * `allowedHere` is false, so `MainActivity` never calls `init`, and no ad SDK
 * is started — no network request, no consent lookup, no identifier read.
 */
object AdMobInitializer {
    fun allowedHere(ctx: Context): Boolean = false
    fun mayRequestNow(ctx: Context): Boolean = false
    fun init(context: Context) { /* no adtech in this variant */ }
}

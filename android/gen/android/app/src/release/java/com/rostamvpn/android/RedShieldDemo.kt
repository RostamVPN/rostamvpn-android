package com.rostamvpn.android

import android.content.Context
import android.content.Intent

/**
 * Release twin of src/debug's RedShieldDemo: no sample data exists in a
 * release build, so every accessor is null and the launch extra is ignored.
 * Same signatures, so shared code never branches on the build type.
 */
object RedShieldDemo {
    val active: Boolean get() = false
    var openOn: String?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") v) {}

    @Suppress("UNUSED_PARAMETER") fun enableFrom(intent: Intent?) {}
    @Suppress("UNUSED_PARAMETER") fun uidFor(ctx: Context, host: String): Int? = null
    @Suppress("UNUSED_PARAMETER") fun flows(ctx: Context): ShieldFlows? = null
    fun clear() {}
    fun scores(): ShieldScoreCache? = null
    fun report(): ShieldScanReport? = null
    fun profiles(): List<ShieldProfile>? = null
    fun blockedRows(): List<GuardStore.Row>? = null
}

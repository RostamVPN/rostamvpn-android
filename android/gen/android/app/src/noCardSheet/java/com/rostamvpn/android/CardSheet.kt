package com.rostamvpn.android

import androidx.compose.runtime.Composable

/**
 * The card rail's stand-in for brands without the native eSIM store
 * (build.gradle.kts `cardSheetBrands`): no Stripe SDK is linked, and the
 * checkout that would call this is never shown for them.
 */
object CardSheet {
    const val available = false

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun rememberPresenter(onResult: (CardResult) -> Unit): (clientSecret: String, publishableKey: String, merchant: String) -> Unit =
        { _, _, _ -> onResult(CardResult.Failed(null)) }
}

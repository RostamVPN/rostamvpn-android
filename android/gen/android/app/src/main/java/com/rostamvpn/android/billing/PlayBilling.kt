// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Google Play Billing v7 wrapper for auto-renewing subscriptions. */
class PlayBilling(context: Context) {
    private val TAG = "PlayBilling"

    /** Set by the caller; invoked for each freshly PURCHASED item. */
    var onPurchase: ((Purchase) -> Unit)? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .forEach { onPurchase?.invoke(it) }
        } else {
            Log.i(TAG, "purchases update: code=${result.responseCode}")
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .setListener(purchasesListener)
        .build()

    private suspend fun ensureReady(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    /** Play Store billing country (ISO), or null. Authoritative pay-rail signal. */
    suspend fun billingCountry(): String? {
        if (!ensureReady()) return null
        return suspendCancellableCoroutine { cont ->
            client.getBillingConfigAsync(GetBillingConfigParams.newBuilder().build()) { result, config ->
                if (cont.isActive) {
                    cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) config?.countryCode else null)
                }
            }
        }
    }

    suspend fun queryProducts(productIds: List<String>): List<ProductDetails> {
        if (productIds.isEmpty()) return emptyList()
        if (!ensureReady()) return emptyList()
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            productIds.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            }
        ).build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, details ->
                if (cont.isActive) {
                    cont.resume(
                        if (result.responseCode == BillingClient.BillingResponseCode.OK)
                            details.productDetailsList
                        else emptyList()
                    )
                }
            }
        }
    }

    /** Active subscription purchases — the restore path. */
    /**
     * Like [queryActivePurchases], but null when Play could not answer (not ready,
     * or any non-OK response): "unknown", never "no subscription".
     */
    suspend fun queryActivePurchasesOrNull(): List<Purchase>? {
        if (!ensureReady()) return null
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS).build()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (cont.isActive) {
                    cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases else null)
                }
            }
        }
    }

    suspend fun queryActivePurchases(): List<Purchase> {
        if (!ensureReady()) return emptyList()
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS).build()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (cont.isActive) {
                    cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases else emptyList())
                }
            }
        }
    }

    /** Launch the purchase UI for a subscription. Must be on the main thread. */
    fun launchPurchase(
        activity: Activity,
        product: ProductDetails,
        oldPurchaseToken: String? = null,
        accountId: String? = null,
    ): Boolean {
        val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: run {
            Log.w(TAG, "no offer token for ${product.productId}"); return false
        }
        val b = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(product)
                    .setOfferToken(offerToken)
                    .build()
            )
        )
        if (!accountId.isNullOrEmpty()) b.setObfuscatedAccountId(accountId)
        if (!oldPurchaseToken.isNullOrEmpty()) {
            b.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(oldPurchaseToken)
                    .setSubscriptionReplacementMode(
                        BillingFlowParams.SubscriptionUpdateParams
                            .ReplacementMode.WITH_TIME_PRORATION
                    )
                    .build()
            )
            Log.i(TAG, "plan change → ${product.productId} (proration: with-time)")
        }
        return client.launchBillingFlow(activity, b.build()).responseCode == BillingClient.BillingResponseCode.OK
    }

    suspend fun acknowledge(purchaseToken: String): Boolean {
        if (!ensureReady()) return false
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        return suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(params) { result ->
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
        }
    }

    fun close() {
        try { client.endConnection() } catch (_: Throwable) {}
    }
}

package com.rostamvpn.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet

/**
 * The native eSIM checkout's card rail: Stripe PaymentSheet over the
 * PaymentIntent that `POST /v1/orders` creates (its `client_secret`, with the
 * order's own `publishable_key`). The iOS twin is ESimPayments.swift
 * (`ESimCardPayment.present`).
 *
 * Compiled only into brands whose manifest has `android.navigation: tabs` and
 * `features.esim` (build.gradle.kts `cardSheetBrands`); every other brand gets
 * src/noCardSheet, so neither Stripe's code nor its size reaches them.
 *
 * Google Pay is offered inside the sheet (US merchant, USD). Stripe hides the
 * button by itself where the device or the account cannot pay with it, so
 * nothing here decides that. Test keys (`pk_test_`) use Google's test wallet.
 */
object CardSheet {
    const val available = true

    @Composable
    fun rememberPresenter(onResult: (CardResult) -> Unit): (clientSecret: String, publishableKey: String, merchant: String) -> Unit {
        val ctx = LocalContext.current
        val sheet = rememberPaymentSheet { r: PaymentSheetResult ->
            onResult(
                when (r) {
                    is PaymentSheetResult.Completed -> CardResult.Completed
                    is PaymentSheetResult.Canceled -> CardResult.Cancelled
                    is PaymentSheetResult.Failed -> CardResult.Failed(r.error.localizedMessage)
                }
            )
        }
        return remember(sheet) {
            { secret, key, merchant ->
                PaymentConfiguration.init(ctx.applicationContext, key)
                val gp = PaymentSheet.GooglePayConfiguration(
                    environment = if (key.startsWith("pk_test")) PaymentSheet.GooglePayConfiguration.Environment.Test
                    else PaymentSheet.GooglePayConfiguration.Environment.Production,
                    countryCode = "US",
                    currencyCode = "USD",
                )
                sheet.presentWithPaymentIntent(
                    secret,
                    PaymentSheet.Configuration.Builder(merchant).googlePay(gp).build(),
                )
            }
        }
    }
}

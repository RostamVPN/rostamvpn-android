package com.rostamvpn.android.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts the Paymentwall checkout widget in a WebView. The user pays here (cards /
 * local methods); the cashier confirms server-side via the pingback and the
 * ViewModel polls `pay/status` — so we do NOT trust the WebView's own redirect as
 * proof of payment. On confirmed payment the ViewModel clears the URL and this
 * dismisses itself; the close control lets the user back out.
 *
 * Direct-APK / web build only — never reachable in the Play build.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPayScreen(url: String, onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadUrl(url)
                }
            }
        )
        Text(
            text = "✕ Close",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { onClose() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

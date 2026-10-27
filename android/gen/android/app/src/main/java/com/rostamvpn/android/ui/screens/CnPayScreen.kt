package com.rostamvpn.android.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.rostamvpn.android.billing.CashierApi
import com.rostamvpn.android.ui.theme.Brand1

/** Render a string to a QR Bitmap (ZXing — already a project dependency). */
internal fun qrBitmap(content: String, size: Int = 640): Bitmap? = try {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until size) for (y in 0 until size) {
            setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
} catch (_: Throwable) { null }

/**
 * China rail payment: show the Alipay/WeChat order as a QR (scan from another
 * device) plus an "open in app" button, while the ViewModel polls pay status.
 * Dismissing clears the order, which stops the poll.
 */
@Composable
fun CnPayScreen(order: CashierApi.PayOrder, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(order.payUrl) { qrBitmap(order.payUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "扫码支付 / Scan to pay",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Brand1
        )
        Spacer(Modifier.height(24.dp))

        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Payment QR", modifier = Modifier.size(240.dp))
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(order.payUrl)))
        }) {
            Text("打开应用支付 / Open to pay")
        }

        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Brand1)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "等待支付确认… / Waiting for confirmation…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onDismiss) { Text("取消 / Cancel") }
    }
}

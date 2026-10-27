package com.rostamvpn.android.ui.screens

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.euicc.DownloadableSubscription
import android.telephony.euicc.EuiccManager
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.Brand1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "My eSIMs", native: the device's eSIMs from `GET /v1/esims?device_id=&usage=1`,
 * an on-device install where the phone supports it, and the QR plus the SM-DP+
 * address and activation code as the fallback that always works.
 *
 * Install is `EuiccManager.downloadSubscription` with the LPA string. This app
 * holds no carrier privileges, so the platform answers RESOLVABLE_ERROR first
 * and `startResolutionActivity` shows the system's own consent sheet; the
 * second result is the real one. API 28+ only (minSdk is 23), and only where
 * `EuiccManager.isEnabled`; everything else gets the manual path.
 *
 * The device id is a bearer credential for these activation codes (e-sim.net
 * ANDROID-PLAN S2). It goes only to e-sim.net over https, and the QR is drawn
 * here from the LPA, so no image URL carries it.
 */
internal data class Esim(
    val iccid: String,
    val title: String,
    val status: String,
    val lpa: String,
    val smdp: String,
    val matchingId: String,
    val remainingMb: Long?,
    val totalMb: Long?,
    val usageStatus: String,
    val destinationId: String = "",
    val planSlug: String = "",
    val line: String = "",
    val apn: String = "",
    val orderId: String = "",
    /** Airalo's "YYYY-MM-DD HH:MM:SS" (UTC); null before first attach. */
    val expiredAt: String? = null,
    /** Server-computed expired_at + topup_grace_days, same format. */
    val graceUntil: String? = null,
    val recycled: Boolean = false,
    val remainingVoice: Int? = null,
    val totalVoice: Int? = null,
    val remainingText: Int? = null,
    val totalText: Int? = null,
    /** Row's plan level (`data` | `data-voice-text`) when the server sends it. */
    val level: String? = null,
    /** Row's topup_grace_days when the server sends it. */
    val topupGraceDays: Int? = null,
    /** The plan's validity in days (row field, server 2026-10-15); 0 or null when unknown. */
    val days: Int? = null,
    /** The line's phone number, digits only; "" on data-only plans (API-NATIVE.md). */
    val msisdn: String = "",
    /** e-sim.net's install guide page for this order, "" when absent. */
    val installGuideUrl: String = "",
    val flag: String = "",
    val destination: String = "",
    val rechargeable: Boolean? = null,
) {
    /** A number: the row says so, or usage proves minutes or SMS. */
    val looksLikeNumber get() = level == "data-voice-text" || (totalVoice ?: 0) > 0 || (totalText ?: 0) > 0
    val active get() = status == "active" || usageStatus == "ACTIVE"
    val finished get() = usageStatus == "FINISHED" || usageStatus == "EXPIRED"
    val installable get() = !active && !finished && lpa.startsWith("LPA:")
}

/** Pure, so it can be checked against a captured response. */
internal fun parseEsims(body: String): List<Esim> {
    val arr = JSONObject(body).getJSONArray("esims")
    return (0 until arr.length()).map { i ->
        val e = arr.getJSONObject(i)
        val u = e.optJSONObject("usage")
        Esim(
            iccid = e.optString("iccid"),
            title = listOf(e.optString("flag"), e.optString("destination"))
                .filter { it.isNotBlank() }.joinToString(" "),
            status = e.optString("status"),
            lpa = e.optString("lpa"),
            smdp = e.optString("smdp"),
            matchingId = e.optString("matching_id"),
            remainingMb = u?.takeIf { it.has("remaining") && !it.isNull("remaining") }?.optLong("remaining"),
            totalMb = u?.takeIf { it.has("total") && !it.isNull("total") }?.optLong("total"),
            usageStatus = u?.optString("status").orEmpty(),
            destinationId = e.optString("destination_id"),
            planSlug = e.optString("plan_slug"),
            line = e.optString("line"),
            apn = e.optString("apn"),
            orderId = e.optString("order_id"),
            expiredAt = u?.optStr("expired_at"),
            graceUntil = u?.optStr("grace_until"),
            recycled = u?.optBoolean("recycled") == true || u?.optString("status") == "RECYCLED",
            remainingVoice = u?.optIntOrNull("remaining_voice"),
            totalVoice = u?.optIntOrNull("total_voice"),
            remainingText = u?.optIntOrNull("remaining_text"),
            totalText = u?.optIntOrNull("total_text"),
            level = e.optStr("level"),
            topupGraceDays = e.optIntOrNull("topup_grace_days"),
            days = e.optIntOrNull("days"),
            msisdn = e.optString("msisdn"),
            installGuideUrl = e.optString("install_guide_url"),
            flag = e.optString("flag"),
            destination = e.optString("destination"),
            rechargeable = if (e.has("rechargeable") && !e.isNull("rechargeable")) e.optBoolean("rechargeable") else null,
        )
    }
}

private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null
private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null

/** null = the request failed; empty = the device has none. */
internal suspend fun fetchEsims(deviceId: String): List<Esim>? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("$PASS_API/esims?device_id=$deviceId&usage=1")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        // Signed in (internetpro's Account tab only): the email's eSIMs too.
        // No token on any other brand, so nothing is added there.
        EsimSession.attach(c)
        try {
            if (c.responseCode != 200) null
            else parseEsims(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.w("EsimMine", "esims fetch failed: ${it.javaClass.simpleName}") }.getOrNull()
}

private fun euicc(ctx: Context): EuiccManager? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        (ctx.getSystemService(Context.EUICC_SERVICE) as? EuiccManager)?.takeIf { it.isEnabled }
    else null

private fun gb(mb: Long) = "%.1f GB".format(mb / 1024.0)

/** A fresh mutable PendingIntent per request: the platform writes the result extras into it. */
private fun downloadCallback(ctx: Context, action: String): PendingIntent =
    PendingIntent.getBroadcast(
        ctx, 0,
        Intent(action).setPackage(ctx.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    )

@Composable
internal fun EsimMine(deviceId: String, onAccount: () -> Unit) {
    val ctx = LocalContext.current
    var esims by remember { mutableStateOf<List<Esim>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val canInstall = remember { euicc(ctx) != null }

    LaunchedEffect(reload) {
        failed = false
        val r = fetchEsims(deviceId)
        if (r == null) failed = true else esims = r
    }

    val installer = rememberEsimInstaller(R.string.esim_installed, R.string.esim_install_failed) { reload++ }
    val installing = installer.busyKey

    fun install(e: Esim) = installer.install(e.lpa, e.iccid)

    fun copy(label: String, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, ctx.getString(R.string.esim_copied), Toast.LENGTH_SHORT).show()
    }

    val list = esims
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            list == null && !failed ->
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Brand1)
            list == null -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.esim_load_failed), textAlign = TextAlign.Center)
                TextButton(onClick = { reload++ }) { Text(stringResource(R.string.esim_retry)) }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                if (list.isEmpty()) item {
                    Text(stringResource(R.string.esim_none), Modifier.padding(vertical = 24.dp))
                }
                items(list, key = { it.iccid }) { e ->
                    EsimCard(e, canInstall, installing == e.iccid, { install(e) }, ::copy)
                }
                item {
                    TextButton(onClick = onAccount, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.esim_account))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun EsimCard(
    e: Esim,
    canInstall: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                stringResource(
                    when {
                        e.usageStatus == "FINISHED" -> R.string.esim_status_used
                        e.usageStatus == "EXPIRED" -> R.string.esim_status_expired
                        e.active -> R.string.esim_status_active
                        else -> R.string.esim_status_ready
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Brand1
            )
        }
        if (e.remainingMb != null && e.totalMb != null && e.totalMb > 0) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.esim_left_of, gb(e.remainingMb), gb(e.totalMb)))
        }
        if (!e.installable) return@Column
        Spacer(Modifier.height(12.dp))
        if (canInstall) {
            Button(
                onClick = onInstall, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand1)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(stringResource(R.string.esim_install))
            }
            Spacer(Modifier.height(12.dp))
        }
        // The manual path, always shown: another phone, a phone whose eUICC
        // this API cannot reach, or an install the platform refused.
        val qr = remember(e.lpa) { qrBitmap(e.lpa, 480) }
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(), contentDescription = null,
                modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally).background(Color.White)
            )
        }
        CodeRow(stringResource(R.string.esim_smdp_label), e.smdp, onCopy)
        CodeRow(stringResource(R.string.esim_activation_code), e.matchingId, onCopy)
    }
}

@Composable
private fun CodeRow(label: String, value: String, onCopy: (String, String) -> Unit) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { onCopy(label, value) }) { Text(stringResource(R.string.esim_copy)) }
    }
}

/**
 * On-device install, shared by "My eSIMs" and the Passes tab. Holds the one
 * download receiver for the screen. `busyKey` is the key of the install in
 * flight. Messages are parameters so each surface keeps its own vocabulary.
 *
 * Without carrier privileges the platform answers RESOLVABLE_ERROR first and
 * `startResolutionActivity` shows the system consent sheet; the second result
 * is the real one. API 28+ and `EuiccManager.isEnabled` only.
 */
internal class EsimInstaller(
    val canInstall: Boolean,
    private val busy: androidx.compose.runtime.MutableState<String?>,
    private val start: (lpa: String, key: String) -> Unit,
) {
    val busyKey: String? get() = busy.value
    fun install(lpa: String, key: String) = start(lpa, key)
}

@Composable
internal fun rememberEsimInstaller(installedMsg: Int, failedMsg: Int, onInstalled: () -> Unit): EsimInstaller {
    val ctx = LocalContext.current
    val busy = remember { mutableStateOf<String?>(null) }
    val canInstall = remember { euicc(ctx) != null }
    val toast = { id: Int -> Toast.makeText(ctx, ctx.getString(id), Toast.LENGTH_LONG).show() }
    val action = remember { "${ctx.packageName}.ESIM_DOWNLOAD" }
    // One receiver for the screen. The PendingIntent is explicit to our own
    // package and is sent with our identity, so NOT_EXPORTED is correct.
    DisposableEffect(action) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@DisposableEffect onDispose {}
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val mgr = euicc(c) ?: return
                when (resultCode) {
                    EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK -> {
                        busy.value = null
                        toast(installedMsg)
                        onInstalled()
                    }
                    EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR -> {
                        val act = ctx.findActivity()
                        val ok = act != null && runCatching {
                            mgr.startResolutionActivity(act, 0, intent, downloadCallback(c, action))
                        }.isSuccess
                        if (!ok) { busy.value = null; toast(failedMsg) }
                    }
                    else -> {
                        busy.value = null
                        Log.w("EsimMine", "download result $resultCode")
                        toast(failedMsg)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(rx) } }
    }
    return remember(canInstall) {
        EsimInstaller(canInstall, busy) { lpa, key ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@EsimInstaller
            val mgr = euicc(ctx) ?: return@EsimInstaller
            busy.value = key
            runCatching {
                mgr.downloadSubscription(DownloadableSubscription.forActivationCode(lpa), true, downloadCallback(ctx, action))
            }.onFailure { busy.value = null; toast(failedMsg) }
        }
    }
}

/** The Activity behind a Compose context, through any ContextWrapper chain
 *  (a ContextThemeWrapper is not an Activity, so a bare cast fails and the
 *  system consent screen for a resolvable install error never shows). */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

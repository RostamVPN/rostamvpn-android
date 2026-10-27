package com.rostamvpn.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import app.netcore.NetCoreBridge
import com.rostamvpn.android.RedShield
import com.rostamvpn.android.RedShieldEngine
import com.rostamvpn.android.RedShieldProfiles
import com.rostamvpn.android.ShieldScore
import com.rostamvpn.android.ShieldScoreCache
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.ui.theme.Brand1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The protection card on the RedVPN home, under the dial (iOS GuardianHomeCard).
 * Left: the shield, the feature's name and state, and the master switch (the
 * same one as Rules). Right: today's refused count. Below, while on and
 * connected: the latest notices (a refusal, a score flag, an Unusual
 * destination), each opening the destination with Trust / Block, and "See all"
 * into the sheet's Traffic segment. Shown only where BuildConfig.PROTECTION_NAME is set.
 */
@Composable
internal fun RedShieldCard(viewModel: VpnViewModel, connected: Boolean, onSeeAll: () -> Unit, onAsk: () -> Unit = onSeeAll) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val on by viewModel.guardianOn.collectAsState()
    val live = connected || ShieldData.demo()
    val active = live && on && RedShield.enabledByConfig
    var notices by remember { mutableStateOf(emptyList<ShieldNotice>()) }
    var scores by remember { mutableStateOf(ShieldScoreCache()) }
    var today by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<ShieldNotice?>(null) }
    val rev by RedShieldEngine.revision.collectAsState()

    // Android 13+: RedShield's flag notifications need POST_NOTIFICATIONS.
    // Asked once, the first time the card is active (ShieldNotifier posts nothing without it).
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(active) {
        if (!active || ShieldData.demo() || android.os.Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val p = RedShield.prefs(ctx)
        if (p.getBoolean("notif_asked", false)) return@LaunchedEffect
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        p.edit().putBoolean("notif_asked", true).apply()
        notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(live, rev) {
        today = withContext(Dispatchers.IO) { ShieldData.blockedToday(ctx) }
        if (!live) { notices = emptyList(); return@LaunchedEffect }
        // Enabled block profiles: revalidated once a day (If-None-Match), through the tunnel.
        if (!ShieldData.demo()) withContext(Dispatchers.IO) {
            if (RedShieldProfiles.refreshDaily(ctx)) RedShieldEngine.resync(ctx)
        }
        while (true) {
            withContext(Dispatchers.IO) {
                val s = ShieldData.scores(ctx)
                val n = shieldNotices(ctx, ShieldData.flows(ctx)?.flows.orEmpty(), s, ShieldData.blocked(ctx))
                val t = ShieldData.blockedToday(ctx)
                withContext(Dispatchers.Main) { scores = s; notices = n; today = t }
            }
            delay(5_000)
        }
    }

    // Up to three; one when nothing is newer than an hour, so the region card
    // stays on the first screen.
    val recent = notices.firstOrNull()?.let { System.currentTimeMillis() - it.at < 3_600_000 } ?: false
    val visible = notices.take(if (recent) 3 else 1)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShieldTile(on = active, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    RedShield.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface, modifier = Modifier.semantics { heading() },
                )
                Text(
                    when {
                        !live -> stringResource(R.string.rs_connect_to_turn_on, RedShield.name)
                        !on || !RedShield.enabledByConfig -> stringResource(R.string.rs_off_own_rules)
                        else -> stringResource(R.string.rs_on)
                    },
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2,
                )
            }
            if (active) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(java.text.NumberFormat.getInstance().format(today), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                    Text(stringResource(R.string.rs_blocked_today_label), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
            Switch(
                checked = on,
                enabled = live,
                onCheckedChange = { v ->
                    viewModel.setGuardianOn(v)
                    RedShieldEngine.resync(ctx)
                    if (v) RedShieldEngine.scoreNow(ctx)
                },
                colors = shieldSwitchColors(),
            )
        }

        if (active) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.rs_ask_button, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = ShopColors.accentOnCard(), maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onAsk)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
                Spacer(Modifier.weight(1f))
                if (visible.isNotEmpty()) Text(
                    stringResource(R.string.rs_see_all),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = ShopColors.accentOnCard(),
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onSeeAll)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            if (visible.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.rs_latest), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    visible.forEach { n -> NoticeRow(n) { detail = n } }
                }
            }
        }
    }

    detail?.let { n -> NoticeDetail(n, scores.score(n.host)) { detail = null } }
}

@Composable
private fun NoticeRow(n: ShieldNotice, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val app = rememberApp(n.uid)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShieldIcon(noticeGlyph(n.kind), liftText(scheme.surface, noticeColor(n.kind), 3f), 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            noticeWord(ctx, n.kind) + " · ",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1,
        )
        // The host in its own LTR run, so an RTL ellipsis never eats its first letter.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
        ) {
            Text(
                n.host,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            " · " + n.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f, fill = false),
        )
        Spacer(Modifier.weight(0.01f))
        if (app != null) { AppIcon(app, 18.dp); Spacer(Modifier.width(6.dp)) }
        Text(ShieldWords.ago(n.at), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

/** One destination: what is known, and Trust / Block. */
@Composable
internal fun NoticeDetail(n: ShieldNotice, score: ShieldScore?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var tick by remember { mutableIntStateOf(0) }
    val (allow, deny) = remember(tick) { readRules() }
    val app = rememberApp(n.uid)
    fun rule(v: String) {
        if (NetCoreBridge.isLoaded) runCatching { NetCoreBridge.setUserRule(n.host, v) }
        RedShieldEngine.resync(ctx)
        tick++
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(RedShield.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(isolate(n.host), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                    if (score != null) { Spacer(Modifier.width(6.dp)); ShieldPill(ShieldWords.verdict(ctx, score.verdict), ShieldWords.verdictColor(score.verdict)) }
                }
                Text(n.detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                // Whose address this is, so Trust / Block is a decision, not a guess.
                val who = n.ctx ?: ShieldContext.of(score, null)
                if (ShieldContext.line(who) != null || ShieldContext.rdns(who, n.host) != null)
                    ShieldContextLines(who, n.host, scheme.onSurfaceVariant)
                else score?.let { ShieldWords.spaceFacts(ctx, it) }?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                if (app != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, 20.dp); Spacer(Modifier.width(6.dp))
                    Text(app.name, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Text(ShieldWords.ago(n.at), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.rs_detail_footer), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            val blocked = n.host in deny
            TextButton(onClick = { rule(if (blocked) "clear" else "deny") }) {
                Text(stringResource(if (blocked) R.string.rs_kind_blocked else R.string.rs_block), color = ShopColors.accentOnCard())
            }
        },
        dismissButton = {
            val trusted = n.host in allow
            Row {
                TextButton(onClick = { rule(if (trusted) "clear" else "allow") }) {
                    Text(stringResource(if (trusted) R.string.rs_trusted else R.string.rs_trust))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.rs_done)) }
            }
        },
    )
}

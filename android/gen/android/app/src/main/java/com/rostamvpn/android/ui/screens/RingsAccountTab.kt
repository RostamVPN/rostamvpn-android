package com.rostamvpn.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.BuildConfig
import com.rostamvpn.android.R
import com.rostamvpn.android.ReviewManager
import com.rostamvpn.android.VpnViewModel

/**
 * The Account tab, iOS AccountTabView: the drawer's rows as one grouped list.
 *
 *   Account        (direct only) sign in / the email, Manage on vpn.red
 *   Subscription   Premium (Active | Upgrade), Manage subscription, Usage stats
 *   Connection     Split tunnelling and Share debug log (Android's own)
 *   Settings       Appearance, Language (pushed pickers)
 *   Support        Rate, About, Privacy Policy, Open source; the version
 *
 * Not ported, on purpose: "Scan config code" (Android removed the ML Kit
 * scanner with its whole runtime, CLAUDE.md §19; a .rostam file still opens
 * the app) and "Terms of Use" (this brand has no terms page and no site URL
 * recorded in its manifest to point one at).
 */
@Composable
internal fun RingsAccountTab(viewModel: VpnViewModel) {
    var page by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = page.isNotEmpty()) { page = "" }
    when (page) {
        "appearance" -> PickerPage(
            stringResource(R.string.acct_appearance),
            listOf("auto" to stringResource(R.string.acct_auto), "light" to stringResource(R.string.acct_light), "dark" to stringResource(R.string.acct_dark)),
            viewModel.themeSetting.collectAsState().value,
            onPick = { viewModel.setTheme(it) }, onBack = { page = "" },
        )
        "language" -> PickerPage(
            stringResource(R.string.acct_language),
            LANGUAGES.map { (code, label) -> code to (label ?: stringResource(R.string.acct_automatic)) },
            viewModel.languageSetting.collectAsState().value,
            onPick = { viewModel.setUiLanguage(it) }, onBack = { page = "" },
        )
        else -> AccountList(viewModel, onPage = { page = it })
    }
}

/** The same six the drawer's settings offer, each in its own script. */
private val LANGUAGES = listOf("auto" to null, "en" to "English", "zh" to "中文", "fa" to "فارسی", "ar" to "العربية", "ru" to "Русский")

@Composable
private fun AccountList(viewModel: VpnViewModel, onPage: (String) -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val isPaid by viewModel.isPaid.collectAsState()
    val premiumAvailable by viewModel.premiumAvailable.collectAsState()
    val theme by viewModel.themeSetting.collectAsState()
    val language by viewModel.languageSetting.collectAsState()
    val appName = stringResource(R.string.app_name)

    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.acct_title),
            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = scheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
        )

        com.rostamvpn.android.WebAccount.AccountRows(viewModel)

        if (isPaid || premiumAvailable) {
            ShopSection(stringResource(R.string.acct_subscription)) {
                AccountRow(
                    rememberVectorPainter(Icons.Filled.Star), stringResource(R.string.acct_premium),
                    value = stringResource(if (isPaid) R.string.acct_active else R.string.acct_upgrade),
                    iconTint = ShopColors.accentOnCard(),
                ) { viewModel.openPaywallAndLoad() }
                if (isPaid) {
                    // Play keeps the subscription; this is its own management page.
                    AccountRow(painterResource(R.drawable.ic_rv_card), stringResource(R.string.acct_manage_sub)) {
                        openWeb(ctx, "https://play.google.com/store/account/subscriptions?package=${BuildConfig.APPLICATION_ID}")
                    }
                }
                if (!viewModel.paidOnly) {
                    AccountRow(painterResource(R.drawable.ic_rv_chart), stringResource(R.string.acct_usage), divider = false) {
                        viewModel.openDataUsage(); viewModel.refreshUsage()
                    }
                }
            }
        }

        ShopSection(stringResource(R.string.acct_connection)) {
            AccountRow(painterResource(R.drawable.ic_rv_apps), stringResource(R.string.protected_apps)) { viewModel.openAppSelection() }
            AccountRow(rememberVectorPainter(Icons.Filled.Share), stringResource(R.string.share_debug_log), divider = false) { viewModel.shareDebugLog() }
        }

        ShopSection(stringResource(R.string.acct_settings)) {
            AccountRow(painterResource(R.drawable.ic_rv_palette), stringResource(R.string.acct_appearance),
                value = stringResource(when (theme) { "light" -> R.string.acct_light; "dark" -> R.string.acct_dark; else -> R.string.acct_auto })) { onPage("appearance") }
            AccountRow(painterResource(R.drawable.ic_perk_globe), stringResource(R.string.acct_language),
                value = LANGUAGES.firstOrNull { it.first == language }?.second ?: stringResource(R.string.acct_automatic), divider = false) { onPage("language") }
        }

        ShopSection(stringResource(R.string.acct_support)) {
            AccountRow(rememberVectorPainter(Icons.Filled.Star), stringResource(R.string.acct_rate, appName)) {
                ctx.findActivity()?.let { ReviewManager.askNow(it) }
            }
            AccountRow(rememberVectorPainter(Icons.Filled.Info), stringResource(R.string.acct_about, appName)) { viewModel.openAbout() }
            AccountRow(rememberVectorPainter(Icons.Filled.Lock), stringResource(R.string.acct_privacy)) { viewModel.openPrivacyPolicy() }
            AccountRow(rememberVectorPainter(Icons.AutoMirrored.Filled.List), stringResource(R.string.open_source_libraries), divider = false) { viewModel.openOpenSource() }
        }
        var confirm1 by rememberSaveable { mutableStateOf(false) }
        var confirm2 by rememberSaveable { mutableStateOf(false) }
        var deleting by rememberSaveable { mutableStateOf(false) }
        ShopSection(footer = stringResource(R.string.acct_delete_footer)) {
            AccountRow(rememberVectorPainter(Icons.Filled.Delete), stringResource(R.string.acct_delete),
                iconTint = scheme.error, divider = false) { if (!deleting) confirm1 = true }
        }
        // Step one: what it does. Step two: an explicit last confirmation.
        if (confirm1) AlertDialog(
            onDismissRequest = { confirm1 = false },
            title = { Text(stringResource(R.string.acct_delete_title)) },
            text = { Text(stringResource(R.string.acct_delete_footer)) },
            confirmButton = { TextButton(onClick = { confirm1 = false; confirm2 = true }) { Text(stringResource(R.string.continue_label), color = scheme.error) } },
            dismissButton = { TextButton(onClick = { confirm1 = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
        if (confirm2) AlertDialog(
            onDismissRequest = { confirm2 = false },
            title = { Text(stringResource(R.string.acct_delete)) },
            text = { Text(stringResource(R.string.acct_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirm2 = false; deleting = true; viewModel.deleteAccount { deleting = false } }) {
                    Text(stringResource(R.string.acct_delete), color = scheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm2 = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
        Text(
            "$appName ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp),
        )
    }
}

@Composable
private fun AccountRow(
    icon: Painter,
    label: String,
    value: String? = null,
    iconTint: Color? = null,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ShopRow(onClick = onClick, divider = divider) {
        Icon(icon, null, tint = iconTint ?: scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        if (value != null) {
            Text(value, color = scheme.onSurfaceVariant, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(4.dp))
        }
        Chevron()
    }
}

@Composable
private fun PickerPage(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding()) {
        ShopTopBar(title, onBack = onBack)
        ShopSection {
            options.forEachIndexed { i, (code, label) ->
                ShopRow(onClick = { onPick(code) }, divider = i < options.lastIndex) {
                    Text(label, Modifier.weight(1f), color = scheme.onSurface)
                    if (code == selected) Icon(Icons.Filled.Check, null, tint = ShopColors.accentOnCard())
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

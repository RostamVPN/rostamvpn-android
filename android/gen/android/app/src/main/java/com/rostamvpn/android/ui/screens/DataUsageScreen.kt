// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import com.rostamvpn.android.VpnViewModel
import com.rostamvpn.android.ui.theme.Brand1

private fun fmtBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    val mb = bytes / 1_000_000.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        else -> "%d KB".format(bytes / 1000)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataUsageScreen(viewModel: VpnViewModel, onBack: () -> Unit) {
    val total by viewModel.dataTotal.collectAsState()
    val traffic by viewModel.trafficInfo.collectAsState()
    val usage by viewModel.usage.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(stringResource(R.string.usage_stats), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = Brand1)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Brand1)
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
        )

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))

            StatCard(stringResource(R.string.usage_total_used), fmtBytes(total))

            if (traffic.totalRx.isNotEmpty() || traffic.totalTx.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                StatCard(stringResource(R.string.usage_this_session),
                    "↓ ${traffic.totalRx.ifEmpty { "0" }}   ↑ ${traffic.totalTx.ifEmpty { "0" }}")
            }

            usage?.takeIf { !it.unlimited && !it.paid }?.let { u ->
                Spacer(Modifier.height(12.dp))

                val usedBytes = (u.usedMb * 1_000_000L).coerceAtLeast(0L)
                val quotaBytes = (u.freeQuotaMb * 1_000_000L).coerceAtLeast(0L)
                StatCard(
                    label = stringResource(R.string.usage_free_allowance),
                    value = "${fmtBytes(usedBytes)} / ${fmtBytes(quotaBytes)}",
                    sub = if (u.overQuota) stringResource(R.string.usage_used_up)
                          else stringResource(R.string.data_free_remaining,
                                              fmtBytes((quotaBytes - usedBytes).coerceAtLeast(0L))),
                    alert = u.overQuota
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.usage_device_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, sub: String? = null, alert: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = if (alert) MaterialTheme.colorScheme.error else Brand1)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = if (alert) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

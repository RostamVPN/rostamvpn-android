package com.rostamvpn.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.Brand1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.open_source_libraries),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Brand1
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = Brand1
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Transparent
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.open_source_intro, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth()
            )

            // Kept in step with the crates actually linked into libnetcore
            // and libwgtcp_core. This screen is an attribution notice, so listing a
            // library we do NOT ship is as wrong as omitting one we do -- it was
            // previously naming AmneziaWG, rustls, ring and webpki-roots, none of
            // which survive in this app (the http carrier is plaintext HTTP Upgrade
            // wrapped in Noise, so there is no TLS stack to attribute).
            LibrarySectionHeader("Transport & Networking")

            LibraryItem(
                name = "hev-socks5-tunnel",
                license = "GPL-3.0",
                description = "High-performance SOCKS5 TUN/TAP tunnel"
            )
            LibraryItem(
                name = "BoringTun",
                license = "BSD-3-Clause",
                description = "WireGuard implementation in Rust"
            )
            LibraryItem(
                name = "yamux",
                license = "MIT / Apache-2.0",
                description = "Stream multiplexer"
            )
            LibraryItem(
                name = "socket2",
                license = "MIT / Apache-2.0",
                description = "Low-level socket configuration"
            )

            LibrarySectionHeader("Cryptography")

            LibraryItem(
                name = "snow",
                license = "MIT / Apache-2.0",
                description = "Noise protocol framework"
            )
            LibraryItem(
                name = "chacha20poly1305",
                license = "MIT / Apache-2.0",
                description = "Authenticated encryption"
            )
            LibraryItem(
                name = "ed25519-dalek",
                license = "BSD-3-Clause",
                description = "Ed25519 signature verification"
            )
            LibraryItem(
                name = "blake2",
                license = "MIT / Apache-2.0",
                description = "BLAKE2 hash function"
            )
            LibraryItem(
                name = "rand",
                license = "MIT / Apache-2.0",
                description = "Random number generation"
            )

            LibrarySectionHeader("Async Runtime & Serialization")

            LibraryItem(
                name = "tokio",
                license = "MIT",
                description = "Asynchronous runtime"
            )
            LibraryItem(
                name = "futures",
                license = "MIT / Apache-2.0",
                description = "Async programming primitives"
            )
            LibraryItem(
                name = "serde_json",
                license = "MIT / Apache-2.0",
                description = "JSON serialization"
            )
            LibraryItem(
                name = "base64",
                license = "MIT / Apache-2.0",
                description = "Base64 encoding"
            )
            LibraryItem(
                name = "jni",
                license = "MIT / Apache-2.0",
                description = "Java Native Interface bindings"
            )

            LibrarySectionHeader("Android & UI")

            LibraryItem(
                name = "Jetpack Compose",
                license = "Apache-2.0",
                description = "Modern Android UI toolkit"
            )
            LibraryItem(
                name = "Material Design 3",
                license = "Apache-2.0",
                description = "Material Design components for Compose"
            )
            LibraryItem(
                name = "AndroidX",
                license = "Apache-2.0",
                description = "Android support libraries"
            )
            LibraryItem(
                name = "kotlinx.coroutines",
                license = "Apache-2.0",
                description = "Asynchronous programming for Kotlin"
            )
            LibraryItem(
                name = "Google Play Billing Library",
                license = "Apache-2.0",
                description = "In-app subscription purchases"
            )
            LibraryItem(
                name = "ZXing",
                license = "Apache-2.0",
                description = "QR code generation"
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LibrarySectionHeader(text: String) {
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Brand1,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun LibraryItem(name: String, license: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = license,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = Brand1
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

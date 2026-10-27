package com.rostamvpn.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.rostamvpn.android.ui.theme.BrandPalette
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.Brand1

/**
 * Sidebar drawer menu matching Figma menu design.
 * Features:
 * - Dark teal gradient background (Brand-Gradient-4)
 * - Back arrow + RostamVPN header
 * - Menu items with Material icons
 * - Rostam mascot image at bottom center
 */
@Composable
fun DrawerMenu(
    onClose: () -> Unit,
    onAbout: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onOpenSource: () -> Unit,
    onPremium: () -> Unit = {},
    showPremium: Boolean = true,
    onUsageStats: () -> Unit = {},
    showUsageStats: Boolean = true,
    onRate: () -> Unit = {},
    showEsim: Boolean = false,
    onEsim: () -> Unit = {},
    showAccount: Boolean = false,
    onAccount: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The drawer ground, DERIVED from the flavor's palette.
    //
    // Two of these three stops used to be the literals #1A3A3D and #2D4B4E —
    // Rostam's dark teal, hard-coded — with only the middle stop reading the
    // brand. Every other brand therefore got a teal drawer with a single band
    // of its own colour through the middle: RedVPN's menu was teal-navy-red.
    // That is the `if (brand == …)` failure in its silent form (CLAUDE.md §6b),
    // so the answer is the same one used everywhere else here — compute it from
    // BrandPalette instead of naming a colour.
    //
    // The two stops are `primaryDeep` pulled toward `primary`. The fractions
    // are not invented: they are the ones that REPRODUCE the literals they
    // replace, so Rostam's drawer does not change. Measured, not eyeballed:
    //   0.33 → #1E373D  vs the old #1A3A3D   (Δ 4, 3, 0)
    //   0.62 → #2B4E53  vs the old #2D4B4E   (Δ 2, 3, 5)
    // Both are under half a step of 8-bit quantisation noise on a gradient that
    // is itself interpolating between them.
    //
    // compositeOver, NOT lerp — Theme.kt:41 explains why: Color.lerp goes
    // through OKLAB and would not return the sRGB values measured above.
    fun deepen(t: Float) =
        BrandPalette.primary.copy(alpha = t).compositeOver(BrandPalette.primaryDeep)
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(deepen(0.33f), Brand1, deepen(0.62f))
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(gradientBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 16.dp)
        ) {
            // Header with back arrow + clean logo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.close_menu),
                        tint = Color.White
                    )
                }
                Image(
                    painter = painterResource(id = R.drawable.brand_splash),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .height(50.dp)
                        .fillMaxWidth(0.55f),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Menu items

            if (showPremium) {
                DrawerMenuItem(
                    icon = Icons.Outlined.Star,
                    label = stringResource(R.string.premium),
                    onClick = {
                        onPremium()
                        onClose()
                    }
                )
            }

            // eSIM store. Only brands whose manifest sets features.esim; for
            // every other brand this row does not exist.
            if (showEsim) {
                DrawerMenuItem(
                    icon = Icons.Outlined.Phone,
                    label = stringResource(R.string.esim_title),
                    onClick = {
                        onEsim()
                        onClose()
                    }
                )
            }

            // Web account (sign-in, manage on the web). Direct builds of a brand
            // with `deepLinkHosts` only; never on Play, where an account row is
            // what got 453 rejected under App access.
            if (showAccount) {
                DrawerMenuItem(
                    icon = Icons.Outlined.AccountCircle,
                    label = stringResource(R.string.account),
                    onClick = {
                        onAccount()
                        onClose()
                    }
                )
            }

            if (showUsageStats) DrawerMenuItem(
                icon = Icons.AutoMirrored.Filled.List,
                label = stringResource(R.string.usage_stats),
                onClick = {
                    onUsageStats()
                    onClose()
                }
            )

            // Rate. Opens the Play LISTING, not the in-app card: Google's guide
            // says not to put a button in front of the API, because inside the
            // quota the flow succeeds and draws nothing. See ReviewManager.askNow.
            DrawerMenuItem(
                icon = Icons.Outlined.Star,
                label = stringResource(R.string.rate_app, stringResource(R.string.app_name)),
                onClick = {
                    onRate()
                    onClose()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Info,
                label = stringResource(R.string.about_screen_title, stringResource(R.string.app_name)),
                onClick = {
                    onAbout()
                    onClose()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Lock,
                label = stringResource(R.string.privacy_policy),
                onClick = {
                    onPrivacyPolicy()
                    onClose()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Build,
                label = stringResource(R.string.open_source_libraries),
                onClick = {
                    onOpenSource()
                    onClose()
                }
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}

package com.rostamvpn.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.Brand1
import com.rostamvpn.android.ui.theme.liftOnto

/**
 * The tabs treatment's grouped-list vocabulary (iOS `esimListStyle`,
 * `esimRow`, `ESimCopyRow`, `ESimPrimaryButtonStyle`): rows on the card
 * colour, the page ground behind them, section headers in muted ink, one
 * filled brand button per screen. Colour roles only, so light and dark are
 * the same design with different tokens.
 */
/**
 * liftOnto for text on EITHER ground. liftOnto blends toward white by default,
 * which is right on a dark page and wrong on a light one: brand red is 4.16:1
 * on white, so "lifting" it toward white made the selected tab label and every
 * red link vanish in light mode. On a light ground, deepen toward black.
 */
@Composable
internal fun liftText(bg: Color, c: Color, min: Float = 4.5f): Color =
    liftOnto(bg, c, min, toward = if (bg.luminance() > 0.5f) Color.Black else Color.White)

internal object ShopColors {
    /** Brand red as a TEXT/graphic colour on the page: lifted to 4.5:1. */
    @Composable fun accent(): Color = liftText(MaterialTheme.colorScheme.background, Brand1, min = 4.5f)
    /** On a card (the surface the rows sit on). */
    @Composable fun accentOnCard(): Color = liftText(MaterialTheme.colorScheme.surface, Brand1, min = 4.5f)
    @Composable fun dark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val success = Color(0xFF1FA97C)
    @Composable fun successText(): Color = liftText(MaterialTheme.colorScheme.surface, success, min = 4.5f)
}

/** A titled group of rows on one card, with optional header and footer lines. */
@Composable
internal fun ShopSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).semantics { heading() },
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surface),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
            )
        }
    }
}

/** One row of a section. [onClick] null = static. A hairline separates rows ([divider]). */
@Composable
internal fun ShopRow(
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    minHeight: Dp = 52.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(20.dp),
    )
}

/** A label and a value with Copy, for codes, addresses and amounts. */
@Composable
internal fun CopyValueRow(label: String, value: String, mono: Boolean = true, divider: Boolean = true) {
    val ctx = LocalContext.current
    val copied = stringResource(R.string.esim_copied)
    ShopRow(onClick = { copyText(ctx, label, value, copied) }, divider = divider) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.shop_copy), style = MaterialTheme.typography.labelLarge, color = ShopColors.accentOnCard(), fontWeight = FontWeight.SemiBold)
    }
}

internal fun copyText(ctx: Context, label: String, value: String, toast: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(label, value))
    // Android 13+ shows its own clipboard confirmation; below it, say so.
    if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(ctx, toast, android.widget.Toast.LENGTH_SHORT).show()
}

/** The one filled button per screen: brand red, white label (4.54:1). */
@Composable
internal fun ShopPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(50))
            .background(Brand1.copy(alpha = if (enabled) 1f else 0.45f))
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Color.White,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, color = Color.White),
        ) { content() }
    }
}

/** A screen's bar: back arrow, centred-ish title, optional trailing action. */
@Composable
internal fun ShopTopBar(title: String, onBack: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.shop_close), tint = MaterialTheme.colorScheme.onBackground)
            }
        } else Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
        )
        trailing()
    }
}

/** A flag emoji in a rounded tile (or the region's globe tile). */
@Composable
internal fun FlagTile(flag: String, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) { Text(flag, fontSize = (size.value * 0.55f).sp) }
}

/** A region's tile: a globe glyph on the region's own colour (iOS ESimRegionTile). */
@Composable
internal fun RegionTile(id: String, size: Dp = 36.dp) {
    val c = regionColour(id)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_perk_globe), contentDescription = null, tint = c, modifier = Modifier.size(size * 0.55f)) }
}

private fun regionColour(id: String): Color = when (id) {
    "europe", "eu-plus-uk" -> Color(64, 120, 220)
    "asia" -> Color(226, 120, 40)
    "oceania" -> Color(24, 160, 170)
    "africa", "africa-safari" -> Color(52, 160, 90)
    "north-america", "latam" -> Color(150, 90, 210)
    "caribbean" -> Color(20, 150, 200)
    "middle-east" -> Color(200, 150, 30)
    else -> Brand1
}

/** A placeholder row while the catalogue loads: two soft bars, pulsing. */
@Composable
internal fun SkeletonRow(divider: Boolean = true) {
    val a by rememberInfiniteTransition(label = "sk").animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    ShopRow(divider = divider) {
        Box(Modifier.size(36.dp).alpha(a).clip(RoundedCornerShape(10.dp)).background(bar))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(a)) {
            Box(Modifier.width(140.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(bar))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(80.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(bar))
        }
    }
}

@Composable
internal fun StatusPill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** What is left, as a bar: green, amber under a quarter, red under a tenth. */
@Composable
internal fun UsageBar(fractionLeft: Float, height: Dp = 6.dp) {
    val f = fractionLeft.coerceIn(0f, 1f)
    val c = when {
        f < 0.10f -> MaterialTheme.colorScheme.error
        f < 0.25f -> Color(0xFFE08A2E)
        else -> ShopColors.success
    }
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(RoundedCornerShape(50)).background(c))
    }
}

/** A QR of [payload], drawn here (ZXing core): no image URL carries the code. */
@Composable
internal fun QrImage(payload: String, side: Dp, contentDescription: String) {
    val bmp = remember(payload) { qrBitmap(payload, 600)?.asImageBitmap() }
    Box(Modifier.size(side + 20.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
        if (bmp != null) Image(bmp, contentDescription, filterQuality = FilterQuality.None, modifier = Modifier.size(side))
    }
}

/** A choice marker: filled check when chosen, an empty ring otherwise. */
@Composable
internal fun RadioMark(selected: Boolean) {
    if (selected) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ShopColors.accentOnCard(), modifier = Modifier.size(24.dp))
    } else {
        Box(Modifier.size(22.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape))
    }
}

/** A row's leading icon in a small tinted tile. */
@Composable
internal fun IconTile(icon: ImageVector, bg: Color, fg: Color, size: Dp = 34.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.55f))
    }
}

/** The statusbar inset for full-screen pages pushed over the tab content. */
internal fun Modifier.pageTop(): Modifier = this.statusBarsPadding()

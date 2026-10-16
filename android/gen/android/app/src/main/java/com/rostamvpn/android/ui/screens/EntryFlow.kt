// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.screens

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.Anchor
import com.rostamvpn.android.ui.theme.ChakraPetch

/** The dot-grid texture behind the brand screens. */
@Composable
private fun DotGrid(
    modifier: Modifier = Modifier,
    color: Color,
    accent: Color,
    spacing: Int = 48,
    alpha: Float = 0.10f,
) {
    Canvas(modifier = modifier) {
        val step = with(density) { spacing.dp.toPx() }
        if (step <= 0f) return@Canvas
        var y = 0f
        while (y <= size.height + step) {
            var x = 0f
            while (x <= size.width + step) {
                drawCircle(color, radius = step * 0.045f, center = Offset(x + step / 2, y + step / 2), alpha = alpha * 1.5f)
                drawCircle(accent, radius = step * 0.03f, center = Offset(x, y), alpha = alpha)
                x += step
            }
            y += step
        }
    }
}

/** The Rostam hero on a disc. */
@Composable
private fun RostamHero(size: Int, onDark: Boolean = false) {
    Box(
        modifier = Modifier.size(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    if (onDark) Brush.radialGradient(
                        listOf(Color.White, Anchor.Surface, Color(0xFFE4E9EC))
                    ) else Brush.linearGradient(listOf(Anchor.SurfaceAlt, Anchor.SurfaceAlt))
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding((size * 0.055f).dp)
                .clip(CircleShape)
                .background(Color.Transparent)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Anchor.Success,
                    radius = this.size.minDimension / 2,
                    style = Stroke(width = 1.dp.toPx()),
                    alpha = 0.4f,
                )
            }
        }
        Image(

            painter = painterResource(R.drawable.brand_splash),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size((size * 0.6f).dp),
        )
    }
}

/** The brand wordmark: the name, with a trailing "VPN" in the accent colour. */
@Composable
private fun Wordmark(sizeSp: Int, color: Color, dimAccent: Boolean = false) {
    val name = stringResource(R.string.app_name)
    val head = if (name.endsWith("VPN", ignoreCase = true)) name.dropLast(3) else name
    val tail = name.drop(head.length)
    Text(
        text = buildAnnotatedString {
            append(head)
            if (tail.isNotEmpty()) {
                withStyle(SpanStyle(color = if (dimAccent) color else Anchor.Success)) { append(tail) }
            }
        },
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.Bold,
        fontSize = sizeSp.sp,
        color = color,
    )
}

/** A rule with a small node at its centre — the brand's divider. */
@Composable
private fun Ornament(width: Int = 80, color: Color = Anchor.Success) {
    Canvas(modifier = Modifier.size(width.dp, 8.dp)) {
        val w = size.width
        val mid = size.height / 2
        val gap = w * 0.075f
        drawLine(color, Offset(0f, mid), Offset(w * 0.425f - gap, mid), strokeWidth = 1.dp.toPx(), alpha = 0.5f)
        drawLine(color, Offset(w * 0.575f + gap, mid), Offset(w, mid), strokeWidth = 1.dp.toPx(), alpha = 0.5f)
        drawRoundRect(
            color,
            topLeft = Offset(w / 2 - 2.dp.toPx(), mid - 2.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(4.dp.toPx(), 4.dp.toPx()),
        )
    }
}

/** Colour one word of a title without breaking translation. */
@Composable
private fun accentedTitle(full: String, accent: String, accentColor: Color) = buildAnnotatedString {
    val i = if (accent.isNotBlank()) full.indexOf(accent) else -1
    if (i < 0) {
        append(full)
    } else {
        append(full.substring(0, i))
        withStyle(SpanStyle(color = accentColor, fontStyle = FontStyle.Italic)) { append(accent) }
        append(full.substring(i + accent.length))
    }
}

/** A single welcome slide. */
@Composable
fun WelcomeScreen(
    showPlans: Boolean = true,
    onContinue: () -> Unit,
) {
    val pages = remember(showPlans) {
        buildList {
            add(0)
            add(1)
            if (showPlans) add(2)
        }
    }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Anchor.Surface)
    ) {
        DotGrid(
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp),
            color = Anchor.Primary,
            accent = Anchor.Saffron,
            spacing = 48,
            alpha = 0.10f,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Wordmark(sizeSp = 16, color = Anchor.Ink)
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(0.3f))
                    RostamHero(size = 190)
                    Spacer(Modifier.height(20.dp))
                    Ornament()
                    Spacer(Modifier.height(14.dp))
                    when (pages[index]) {
                        0 -> {
                            Text(
                                text = accentedTitle(
                                    full = stringResource(R.string.welcome_title),
                                    accent = stringResource(R.string.welcome_title_accent),
                                    accentColor = Anchor.AccentOnLight,
                                ),
                                fontFamily = ChakraPetch,
                                fontWeight = FontWeight.SemiBold,
                                fontStyle = FontStyle.Italic,
                                fontSize = 30.sp,
                                lineHeight = 36.sp,
                                color = Anchor.Ink,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(18.dp))
                            SlideBody(stringResource(R.string.welcome_body, stringResource(R.string.app_name)))
                        }

                        1 -> {
                            SlideTitle(stringResource(R.string.paywall_perk_ixp_title))
                            Spacer(Modifier.height(18.dp))
                            SlideBody(stringResource(R.string.paywall_perk_ixp_body))
                        }
                        else -> {
                            SlideTitle(stringResource(R.string.onboarding_plans_title))
                            Spacer(Modifier.height(18.dp))

                            SlideBody(stringResource(R.string.paywall_perk_data_body))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            if (pages.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 14.dp),
                ) {
                    repeat(pages.size) { i ->
                        val on = i == pager.currentPage
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(width = if (on) 18.dp else 6.dp, height = 6.dp)
                                .background(
                                    if (on) Anchor.Primary else Anchor.InkDim.copy(alpha = 0.35f),
                                    RoundedCornerShape(3.dp),
                                )
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (last) onContinue()
                    else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Anchor.Primary, contentColor = Color.White),
            ) {
                Text(
                    stringResource(if (last) R.string.get_started else R.string.continue_label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            TextButton(
                onClick = onContinue,
                modifier = Modifier.height(44.dp),
            ) {
                Text(
                    stringResource(R.string.skip_label),
                    fontSize = 14.sp,
                    color = if (last) Color.Transparent else Anchor.InkDim,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SlideTitle(text: String) {
    Text(
        text = text,
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.SemiBold,
        fontStyle = FontStyle.Italic,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        color = Anchor.Ink,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SlideBody(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = Anchor.InkDim,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}


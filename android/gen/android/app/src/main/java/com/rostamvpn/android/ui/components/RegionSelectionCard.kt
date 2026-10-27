package com.rostamvpn.android.ui.components

import androidx.compose.ui.res.stringResource
import com.rostamvpn.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Connection-mode selector — the user picks a REGION (Automatic / Frankfurt /
 * Los Angeles). The transport/protocol is intentionally NOT exposed: netcore
 * decides which carrier to use within the chosen region.
 *
 * This is the ONE region row in the app, in both entitlement states. The locked
 * variant used to be a hand-copied Row inside MainScreen's upgrade card, which
 * is how a control ended up sharing a container with a promotion and a status
 * readout. `locked` keeps them a single component, so the row cannot change
 * typography, padding or identity when a user pays — and there is no second
 * copy to keep in step.
 *
 * Locked is still genuinely operable: it opens the paywall, which is the only
 * thing that would actually unlock it. A control that looks tappable must be
 * tappable; a lock icon on a dead card is a control that lies.
 *
 * @param locked        true when the user is being sold region choice rather
 *                      than given it. Caller owns the predicate.
 * @param onLockedClick what tapping does while locked (the paywall).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionSelectionCard(
    selectedRegion: String,
    regions: List<String>,
    regionLabel: (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onLockedClick: () -> Unit = {},
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (locked) Modifier.clickable(
                    // The card IS the button. Naming the action for TalkBack is
                    // what lets the trailing icon go back to being decorative
                    // instead of the only thing that announces the state.
                    onClickLabel = stringResource(R.string.upgrade),
                    role = Role.Button,
                ) { onLockedClick() }
                else Modifier.clickable { showSheet = true }
            ),
        shape = RoundedCornerShape(16.dp),
        // WHITE, explicitly, for both entitlement states.
        //
        // The page is #F5F5F5 and `surface` is #FFFFFF (Theme.kt). That gap is
        // the whole reason the background is off-white rather than white: it is
        // what lets a card lift without a border or a rule. Taking Material's
        // default here filled the card with a grey-lavender tint, so a grey card
        // sat on a grey page with almost no separation, and the structure had to
        // be faked with dividers instead.
        //
        // Set on BOTH states deliberately. It was once forced to `surface` here
        // while the locked twin in MainScreen took the default, so the same
        // Region row changed colour with entitlement: white for paid and freedom
        // users, lavender for metered ones. One component, one fill.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.region_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    // Locked says the whole thing on the value line
                    // ("Automatic — choose a region with Premium") rather than
                    // stuttering "Automatic" above a hint that repeats it. The
                    // string is already translated into all 38 locales, so the
                    // lock explains itself in every one of them — an icon on
                    // its own explains nothing, least of all to TalkBack.
                    text = when {
                        locked -> stringResource(R.string.region_locked_hint)
                        selectedRegion == "auto" -> stringResource(R.string.region_automatic)
                        else -> regionLabel(selectedRegion)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    // The colour ROLE, not the raw brand val. They are the same in
                    // light; the dark scheme swaps primary to the brand's lighter
                    // tone, where the raw val measured 1.88:1 on a dark card. This
                    // takes it to 4.49:1 and costs nothing in light mode.
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!locked) {
                    Text(
                        text = if (selectedRegion == "auto") stringResource(R.string.region_auto_hint)
                               else stringResource(R.string.region_location_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                // Chevron-UP because a bottom sheet comes up; a padlock because
                // nothing comes up until you pay. Both are vector Icons that
                // obey the theme — the locked row used to draw a "🔒" emoji,
                // which the device's own emoji font paints in its own colours,
                // ignores `tint`, and announces in English in all 38 locales.
                imageVector = if (locked) Icons.Default.Lock else Icons.Default.KeyboardArrowUp,
                // Unlocked: the icon is the only affordance, so it carries the
                // name (was the literal "Change region" — TalkBack announced
                // English everywhere; `region_label` is already translated).
                // Locked: the card's own onClickLabel says "Upgrade" and the
                // value line says why, so a third announcement is noise.
                contentDescription = if (locked) null else stringResource(R.string.region_label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (locked) 20.dp else 22.dp),
            )
        }
    }

    if (showSheet && !locked) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.region_label),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                regions.forEach { region ->
                    val selected = region == selectedRegion
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(region)
                                showSheet = false
                            }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = regionLabel(region),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

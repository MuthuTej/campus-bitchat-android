package com.bitchat.android.campus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitchat.android.campus.CampusIdentityRegistry

/**
 * Renders who someone is.
 *
 * The distinction this component draws is the whole point of the identity system: a verified user
 * gets their institutional name and role, and an unverified one is labelled as unverified no matter
 * what nickname they chose. A self-chosen "Professor Priya" must never render like the real thing.
 */
@Composable
fun CampusIdentityLabel(
    identity: CampusIdentityRegistry.Identity,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    when (identity) {
        is CampusIdentityRegistry.Identity.Verified -> Column(modifier = modifier) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = identity.displayName,
                    style = if (compact) MaterialTheme.typography.labelLarge
                    else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = "Verified campus identity",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = identity.role.displayLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is CampusIdentityRegistry.Identity.Unverified -> Column(modifier = modifier) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    // The claimed nickname is shown so conversations remain followable, but it is
                    // never paired with a role and never styled as trusted.
                    text = identity.claimedNickname ?: "Unknown campus user",
                    style = if (compact) MaterialTheme.typography.labelLarge
                    else MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "Unverified user",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = "Unverified user",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

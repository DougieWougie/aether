package com.aether.nextcloud.ui.dashboard

import com.aether.nextcloud.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.ui.components.MorphingShapeBadge
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Header(
    account: AetherCredentials?,
    avatar: ImageBitmap?,
    accounts: List<AetherCredentials>,
    onSelect: (String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: (String) -> Unit,
    directShareEnabled: Boolean,
    onDirectShareChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MorphingShapeBadge(MaterialShapes.Cookie6Sided, size = 52.dp, container = MaterialTheme.colorScheme.tertiaryContainer) {
            if (avatar != null) {
                Image(
                    avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(5.dp).clip(CircleShape),
                )
            } else {
                Text(
                    (account?.displayName ?: "A").take(1).uppercase(),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(account?.displayName ?: "Aether", style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            account?.let { Text(it.serverUrl.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        AccountMenu(accounts, account, onSelect, onAddAccount, onSignOut, directShareEnabled, onDirectShareChange)
    }
}

@Composable
internal fun AccountMenu(
    accounts: List<AetherCredentials>,
    selected: AetherCredentials?,
    onSelect: (String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: (String) -> Unit,
    directShareEnabled: Boolean,
    onDirectShareChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.accounts_description)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (accounts.size > 1) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.accountName) },
                        onClick = { expanded = false; onSelect(account.accountName) },
                        enabled = account != selected,
                    )
                }
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.account_add)) }, onClick = { expanded = false; onAddAccount() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.account_direct_share)) },
                trailingIcon = { Switch(checked = directShareEnabled, onCheckedChange = null) },
                onClick = { onDirectShareChange(!directShareEnabled) },
            )
            selected?.let { account ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_sign_out, account.accountName)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                    onClick = { expanded = false; onSignOut(account.accountName) },
                )
            }
        }
    }
}

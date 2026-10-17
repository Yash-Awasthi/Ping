package com.ping.app.ui.contacts

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.ping.app.ui.components.SectionLabel
import com.ping.app.ui.theme.BrandBrush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.ping.app.R
import com.ping.app.model.Contact
import com.ping.app.ui.components.Avatar
import com.ping.app.ui.components.PingCard
import com.ping.app.ui.theme.PingCyan

@Composable
fun ContactsScreen(
    onContactClick: (String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importVCards)
    }
    val backupWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::backUp)
    }
    val sealedWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.backUpSealed(uri) else viewModel.setBackupPassphrase(null)
    }
    val backupReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::restore)
    }
    var menuOpen by remember { mutableStateOf(false) }
    var askBackupPassphrase by remember { mutableStateOf(false) }
    var lockedBackup by remember { mutableStateOf<Pair<android.net.Uri, Boolean>?>(null) }
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        launch { viewModel.locked.collect { lockedBackup = it } }
        launch {
            viewModel.imported.collect { n ->
                toast(if (n < 0) resources.getString(R.string.contacts_import_failed)
                else resources.getQuantityString(R.plurals.contacts_imported, n, n))
            }
        }
        launch {
            viewModel.backedUp.collect { ok ->
                toast(resources.getString(if (ok) R.string.contacts_backup_saved else R.string.contacts_backup_failed))
            }
        }
        viewModel.restored.collect { n ->
            toast(if (n < 0) resources.getString(R.string.contacts_restore_failed)
            else resources.getQuantityString(R.plurals.contacts_restored, n, n))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.contacts_count, contacts.size, contacts.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { importer.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory")) }) {
                Text(stringResource(R.string.contacts_import), color = PingCyan)
            }
            if (contacts.isNotEmpty()) {
                TextButton(onClick = { shareVCards(context, contacts) }) {
                    Text(pluralStringResource(R.plurals.contacts_export_all, contacts.size, contacts.size), color = PingCyan)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.contacts_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contacts_backup)) },
                        onClick = { menuOpen = false; askBackupPassphrase = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contacts_restore)) },
                        onClick = { menuOpen = false; backupReader.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                    )
                }
            }
        }
        ContactList(contacts, query, onContactClick)
    }

    if (askBackupPassphrase) {
        PassphraseDialog(
            title = R.string.backup_passphrase_title,
            message = R.string.backup_passphrase_message,
            wrong = false,
            onConfirm = { pass ->
                askBackupPassphrase = false
                viewModel.setBackupPassphrase(pass.takeIf { it.isNotEmpty() })
                if (pass.isEmpty()) backupWriter.launch("Ping-backup.json") else sealedWriter.launch("Ping-backup.pingseal")
            },
            onDismiss = { askBackupPassphrase = false },
        )
    }
    lockedBackup?.let { (uri, wrong) ->
        PassphraseDialog(
            title = R.string.restore_passphrase_title,
            message = R.string.restore_passphrase_message,
            wrong = wrong,
            onConfirm = { pass -> lockedBackup = null; viewModel.restore(uri, pass) },
            onDismiss = { lockedBackup = null },
        )
    }
}

/** Asks for a backup passphrase; [onConfirm] gets it trimmed, empty when left blank. */
@Composable
private fun PassphraseDialog(
    title: Int,
    message: Int,
    wrong: Boolean,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_passphrase_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = wrong,
                    supportingText = if (wrong) {
                        { Text(stringResource(R.string.restore_passphrase_wrong)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pass.trim().toCharArray()) }) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ContactList(
    contacts: List<Contact>,
    query: String,
    onContactClick: (String) -> Unit,
) {
    if (contacts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(BrandBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        if (query.isBlank()) R.string.empty_contacts else R.string.contacts_no_results,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        val favourites = remember(contacts) { contacts.filter { it.isFavorite } }
        val rest = remember(contacts, query) { if (query.isBlank()) contacts.filterNot { it.isFavorite } else contacts }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (query.isBlank() && favourites.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.contacts_favourites)) }
                items(favourites, key = { "f-" + it.id }) { contact ->
                    ContactItem(contact = contact, onClick = { onContactClick(contact.id) })
                }
                item { SectionLabel(stringResource(R.string.contacts_everyone)) }
            }
            items(rest, key = { it.id }) { contact ->
                ContactItem(contact = contact, onClick = { onContactClick(contact.id) })
            }
        }
    }
}

@Composable
private fun ContactItem(
    contact: Contact,
    onClick: () -> Unit,
) {
    PingCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = contact.displayName)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName.ifBlank { stringResource(R.string.contact_unknown_name) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                val sub = contact.email.ifBlank { contact.phone }.ifBlank { contact.social }
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (contact.memo.isNotBlank()) {
                    Text(
                        text = contact.memo,
                        style = MaterialTheme.typography.labelSmall,
                        color = PingCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (contact.isFavorite) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        contact.receivedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

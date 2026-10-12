package com.ping.app.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.model.Contact
import com.ping.app.ui.components.Avatar
import com.ping.app.ui.components.PingCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    contactId: String,
    onBack: () -> Unit,
    viewModel: ContactDetailViewModel =
        hiltViewModel<ContactDetailViewModel, ContactDetailViewModel.Factory> { it.create(contactId) },
) {
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }

    contact?.let { c ->
        if (editing) {
            EditContactDialog(
                contact = c,
                onSave = { viewModel.save(it); editing = false },
                onDismiss = { editing = false },
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(c.displayName.ifBlank { stringResource(R.string.contact_unknown_name) })
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(name = c.displayName, size = 88.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = c.displayName.ifBlank { stringResource(R.string.contact_unknown_name) },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(
                            R.string.contact_swapped_on,
                            DateUtils.formatDateTime(
                                context, c.receivedAt,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                val details = buildList {
                    if (c.phone.isNotBlank()) add(stringResource(R.string.contact_label_phone) to c.phone)
                    if (c.email.isNotBlank()) add(stringResource(R.string.contact_label_email) to c.email)
                    if (c.social.isNotBlank()) add(stringResource(R.string.contact_label_social) to c.social)
                    if (c.note.isNotBlank()) add(stringResource(R.string.contact_label_note) to c.note)
                    if (c.memo.isNotBlank()) add(stringResource(R.string.contact_label_memo) to c.memo)
                }
                PingCard {
                    details.forEachIndexed { i, (label, value) ->
                        if (i > 0) Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = label.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")))
                        },
                        enabled = c.phone.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null)
                        Text(" " + stringResource(R.string.action_call))
                    }
                    Spacer(modifier = Modifier.weight(0.1f))
                    Button(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${c.email}")))
                        },
                        enabled = c.email.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Email, contentDescription = null)
                        Text(" " + stringResource(R.string.action_email_verb))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val text = listOf(c.displayName, c.phone, c.email, c.social, c.note)
                                .filter { it.isNotBlank() }.joinToString("\n")
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Contact", text))
                            Toast.makeText(context, R.string.contact_copied, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_copy))
                    }
                    Spacer(modifier = Modifier.weight(0.1f))
                    IconButton(
                        onClick = { viewModel.toggleFavorite(c) },
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = stringResource(R.string.contact_toggle_favourite_initial),
                            tint = if (c.isFavorite) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val insert = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                                type = ContactsContract.RawContacts.CONTENT_TYPE
                                putExtra(ContactsContract.Intents.Insert.NAME, c.displayName)
                                putExtra(ContactsContract.Intents.Insert.PHONE, c.phone)
                                putExtra(ContactsContract.Intents.Insert.EMAIL, c.email)
                                putExtra(ContactsContract.Intents.Insert.NOTES, c.note)
                            }
                            runCatching { context.startActivity(insert) }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_add_to_phone))
                    }
                    Spacer(modifier = Modifier.weight(0.1f))
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/x-vcard"
                                putExtra(Intent.EXTRA_TEXT, c.toVCard())
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, null)) }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_share_vcard))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        viewModel.deleteContact(c)
                        onBack()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text(" " + stringResource(R.string.action_delete))
                }
            }
        }
    }
}

/** Edits every card field plus the private memo; blank cards cannot be saved. */
@Composable
private fun EditContactDialog(contact: Contact, onSave: (Contact) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(contact.displayName) }
    var phone by remember { mutableStateOf(contact.phone) }
    var email by remember { mutableStateOf(contact.email) }
    var social by remember { mutableStateOf(contact.social) }
    var note by remember { mutableStateOf(contact.note) }
    var memo by remember { mutableStateOf(contact.memo) }
    val updated = contact.copy(
        displayName = name.trim(), phone = phone.trim(), email = email.trim(),
        social = social.trim(), note = note.trim(), memo = memo.trim(),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    Triple(R.string.contact_label_name, name) { v: String -> name = v },
                    Triple(R.string.contact_label_phone, phone) { v: String -> phone = v },
                    Triple(R.string.contact_label_email, email) { v: String -> email = v },
                    Triple(R.string.contact_label_social, social) { v: String -> social = v },
                    Triple(R.string.contact_label_note, note) { v: String -> note = v },
                    Triple(R.string.contact_label_memo, memo) { v: String -> memo = v },
                ).forEach { (label, value, set) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = set,
                        label = { Text(stringResource(label)) },
                        singleLine = label != R.string.contact_label_note && label != R.string.contact_label_memo,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = stringResource(R.string.contact_memo_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(updated) }, enabled = !updated.isEmptyCard) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

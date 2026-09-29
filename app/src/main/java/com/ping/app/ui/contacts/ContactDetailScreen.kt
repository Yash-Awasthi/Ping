package com.ping.app.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.ui.components.Avatar
import com.ping.app.ui.components.PingCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    onBack: () -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    val context = LocalContext.current

    contact?.let { c ->
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
                }

                Spacer(modifier = Modifier.height(20.dp))

                val details = buildList {
                    if (c.phone.isNotBlank()) add(stringResource(R.string.contact_label_phone) to c.phone)
                    if (c.email.isNotBlank()) add(stringResource(R.string.contact_label_email) to c.email)
                    if (c.social.isNotBlank()) add(stringResource(R.string.contact_label_social) to c.social)
                    if (c.note.isNotBlank()) add(stringResource(R.string.contact_label_note) to c.note)
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

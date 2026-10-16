package com.ping.app.ui.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import com.ping.app.ui.contacts.shareVCards
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.ui.components.Avatar
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import com.ping.app.ui.components.PingCard
import com.ping.app.ui.theme.BrandBrush

@Composable
fun ProfileScreen(
    shareOnOpen: Boolean = false,
    onShareHandled: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Shares the saved card, not unsaved edits, so the file matches what a swap sends.
    suspend fun shareMyCard() {
        val card = viewModel.myCard()
        if (card == null) Toast.makeText(context, R.string.profile_share_empty, Toast.LENGTH_SHORT).show()
        else shareVCards(context, listOf(card), "my-card.vcf")
    }
    LaunchedEffect(shareOnOpen) {
        if (shareOnOpen) {
            onShareHandled()
            shareMyCard()
        }
    }

    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var social by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }

    // Prefill from saved profile once.
    LaunchedEffect(profile) {
        if (!loaded && profile != null) {
            profile?.let {
                name = it.displayName
                phone = it.phone
                email = it.email
                social = it.social
                note = it.note
            }
            loaded = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // What the other phone receives, updated as you type.
        Text(
            text = stringResource(R.string.profile_preview_label).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(BrandBrush)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = name, size = 56.dp)
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = name.ifBlank { stringResource(R.string.profile_title) },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )
                    Text(
                        text = listOf(phone, email).filter { it.isNotBlank() }.joinToString(" · ")
                            .ifBlank { stringResource(R.string.profile_subtitle) },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            if (social.isNotBlank() || note.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                if (social.isNotBlank()) Text(social, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        PingCard {
            ProfileField(name, { name = it }, R.string.hint_name, Icons.Default.Person, KeyboardType.Text)
            ProfileField(phone, { phone = it }, R.string.hint_phone, Icons.Default.Phone, KeyboardType.Phone)
            ProfileField(email, { email = it }, R.string.hint_email, Icons.Default.Email, KeyboardType.Email)
            ProfileField(social, { social = it }, R.string.hint_social, Icons.Default.Share, KeyboardType.Uri)
            ProfileField(note, { note = it }, R.string.hint_note, Icons.Default.Info, KeyboardType.Text, singleLine = false)
        }
        Text(
            text = stringResource(R.string.profile_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (name.isBlank()) {
                    Toast.makeText(context, R.string.profile_name_required, Toast.LENGTH_SHORT).show()
                    return@Button
                }
                viewModel.save(
                    name = name.trim(),
                    phone = phone.trim(),
                    email = email.trim(),
                    social = social.trim(),
                    note = note.trim(),
                )
                Toast.makeText(context, R.string.profile_saved, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text(stringResource(R.string.profile_save))
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { scope.launch { shareMyCard() } },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.profile_share_card))
        }
    }
}

@Composable
private fun ProfileField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    icon: ImageVector,
    keyboard: KeyboardType,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 3,
    )
}

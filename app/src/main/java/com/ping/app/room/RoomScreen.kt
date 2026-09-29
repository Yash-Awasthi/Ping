package com.ping.app.room

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.auth.GestureCamera
import com.ping.app.room.RoomProtocol.RoomFile
import com.ping.app.ui.components.GestureGuideButton
import com.ping.app.ui.components.PingCard
import com.ping.app.ui.components.SectionLabel
import com.ping.app.utils.RequiredPermissions
import com.ping.app.utils.vibrateDouble
import com.ping.app.utils.vibrateShort
import java.io.File

/**
 * The file room: pick host or join, lock the room's gesture, then share file names and
 * pull files on demand. Leaving the screen closes the room.
 */
@Composable
fun RoomScreen(
    onBack: () -> Unit,
    viewModel: RoomViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<RoomRole?>(null) }

    BackHandler {
        viewModel.leave()
        onBack()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.role != null -> RoomContent(state, viewModel, onLeave = { viewModel.leave(); onBack() })
            chosen == null -> Chooser(onPick = { chosen = it }, onBack = onBack)
            else -> GestureLock(
                viewModel = viewModel,
                onLocked = { viewModel.open(chosen!!, it) },
                onBack = { chosen = null },
            )
        }
    }
}

@Composable
private fun Chooser(onPick: (RoomRole) -> Unit, onBack: () -> Unit) {
    Text(stringResource(R.string.room_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.room_intro), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = { onPick(RoomRole.HOST) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.room_host))
    }
    OutlinedButton(onClick = { onPick(RoomRole.GUEST) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.room_join))
    }
    TextButton(onClick = onBack) { Text(stringResource(R.string.action_cancel)) }
}

@Composable
private fun GestureLock(viewModel: RoomViewModel, onLocked: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    var granted by remember { mutableStateOf(RequiredPermissions.missing(context).isEmpty()) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = RequiredPermissions.missing(context).isEmpty()
    }
    val camera by viewModel.cameraState.collectAsStateWithLifecycle()
    val previewDescription = stringResource(R.string.gesture_camera_preview_desc)

    LaunchedEffect(granted) { if (!granted) launcher.launch(RequiredPermissions.all) }
    LaunchedEffect(granted, previewView) {
        val preview = previewView
        if (granted && preview != null && lifecycleOwner != null) viewModel.startCamera(lifecycleOwner, preview)
    }
    LaunchedEffect(camera) {
        val locked = camera as? GestureCamera.State.Locked ?: return@LaunchedEffect
        runCatching { context.vibrateShort() }
        viewModel.stopCamera()
        onLocked(locked.fingerprint.code)
    }

    Text(stringResource(R.string.room_lock_prompt), style = MaterialTheme.typography.titleMedium)
    GestureGuideButton()
    Text(
        text = when (val c = camera) {
            is GestureCamera.State.NoHand -> stringResource(R.string.gesture_no_hand)
            is GestureCamera.State.Detecting ->
                stringResource(R.string.gesture_detecting, c.fingerprint.label, (c.stability * 100).toInt())
            is GestureCamera.State.Locked -> stringResource(R.string.exchange_gesture_locked, c.fingerprint.label)
            is GestureCamera.State.ModelError -> stringResource(R.string.gesture_model_error)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (granted) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .border(2.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraLarge)
                .semantics { contentDescription = previewDescription },
            factory = { ctx ->
                PreviewView(ctx).also {
                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = it
                }
            },
        )
    } else {
        Text(stringResource(R.string.perm_rationale_subtitle), textAlign = TextAlign.Center)
    }
    OutlinedButton(onClick = {
        viewModel.stopCamera()
        onBack()
    }) { Text(stringResource(R.string.action_cancel)) }
}

@Composable
private fun ColumnScope.RoomContent(state: RoomState, viewModel: RoomViewModel, onLeave: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.addFiles(it)
    }

    LaunchedEffect(state.phase) {
        if (state.phase == RoomPhase.IN_ROOM) runCatching { context.vibrateDouble() }
    }

    Text(
        text = stringResource(
            when (state.phase) {
                RoomPhase.OPEN -> R.string.room_open
                RoomPhase.SEARCHING -> R.string.room_searching
                RoomPhase.WAITING_APPROVAL -> R.string.room_waiting_approval
                RoomPhase.IN_ROOM -> R.string.room_in
                RoomPhase.DENIED -> R.string.room_denied
                RoomPhase.NO_ROOM -> R.string.room_none
                RoomPhase.CLOSED -> R.string.room_closed
                RoomPhase.IDLE -> R.string.room_searching
            },
        ),
        style = MaterialTheme.typography.titleMedium,
    )

    if (state.phase == RoomPhase.WAITING_APPROVAL && state.linkDigits.isNotBlank()) {
        Text(
            stringResource(R.string.room_link_code, state.linkDigits),
            style = MaterialTheme.typography.titleLarge,
        )
    }

    if (state.manifest.members.isNotEmpty()) {
        Text(
            stringResource(R.string.room_members, state.manifest.members.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    state.pending.forEach { guest ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.room_wants_in, guest.name) +
                    if (guest.digits.isNotBlank()) stringResource(R.string.room_wants_in_code, guest.digits) else "",
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.decide(guest.endpointId, true) }) { Text(stringResource(R.string.room_allow)) }
            TextButton(onClick = { viewModel.decide(guest.endpointId, false) }) { Text(stringResource(R.string.room_deny)) }
        }
    }

    val inRoom = state.phase == RoomPhase.OPEN || state.phase == RoomPhase.IN_ROOM
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (inRoom) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.room_add_files)) }
        }
        OutlinedButton(onClick = onLeave) { Text(stringResource(R.string.room_leave)) }
    }

    if (inRoom) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (state.shared.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.room_you_share)) }
                items(state.shared, key = { "s" + it.id }) { f ->
                    PingCard(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.titleMedium)
                                Text(Formatter.formatShortFileSize(context, f.size), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { viewModel.removeShared(f.id) }) {
                                Text(stringResource(R.string.room_stop_sharing))
                            }
                        }
                    }
                }
            }
            val others = state.manifest.files.filter { it.ownerKey != state.myKey }
            item { SectionLabel(stringResource(R.string.room_in_room)) }
            if (others.isEmpty()) {
                item { Text(stringResource(R.string.room_no_files), style = MaterialTheme.typography.bodyMedium) }
            }
            items(others, key = { it.id }) { file ->
                PingCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    RoomFileRow(file, state.transfers[file.id], onDownload = { viewModel.download(file.id) })
                }
            }
        }
    }
}

@Composable
private fun RoomFileRow(file: RoomFile, transfer: Transfer?, onDownload: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.room_file_meta, Formatter.formatShortFileSize(context, file.size), file.ownerName),
                style = MaterialTheme.typography.bodySmall,
            )
            if (transfer?.status == Transfer.Status.RECEIVING && transfer.total > 0) {
                LinearProgressIndicator(
                    progress = { transfer.bytes.toFloat() / transfer.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        when (transfer?.status) {
            Transfer.Status.QUEUED, Transfer.Status.RECEIVING ->
                Text(stringResource(R.string.room_receiving), style = MaterialTheme.typography.bodySmall)
            Transfer.Status.DONE -> TextButton(onClick = { openDownloaded(context, transfer.path) }) {
                Text(stringResource(R.string.room_open_file))
            }
            Transfer.Status.FAILED, null -> TextButton(onClick = onDownload) {
                Text(stringResource(if (transfer == null) R.string.room_download else R.string.room_retry))
            }
        }
    }
}

private fun openDownloaded(context: Context, path: String?) {
    val file = path?.let(::File) ?: return
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, null))
    }
}

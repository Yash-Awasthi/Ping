package com.ping.app.room

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.auth.GestureCamera
import com.ping.app.room.RoomProtocol.Note
import com.ping.app.room.RoomProtocol.RoomFile
import com.ping.app.service.NearbyExchangeService
import com.ping.app.ui.components.FileBadge
import com.ping.app.ui.components.GestureGuideButton
import com.ping.app.ui.components.GesturePreview
import com.ping.app.ui.components.HeroBanner
import com.ping.app.ui.components.ModeTile
import com.ping.app.ui.components.SavedGestureChips
import com.ping.app.ui.theme.PingCyan
import com.ping.app.ui.theme.PingMagenta
import com.ping.app.ui.components.PingCard
import com.ping.app.ui.components.SectionLabel
import com.ping.app.utils.RequiredPermissions
import com.ping.app.utils.vibrateDouble
import com.ping.app.utils.vibrateShort
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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
    val context = LocalContext.current

    // The room and the swap share one radio; a swap still running would fight the room for it.
    var radioFree by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        stopSwap(context)
        radioFree = true
    }

    BackHandler {
        viewModel.leave()
        onBack()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.role != null -> RoomContent(state, viewModel, onLeave = { viewModel.leave(); onBack() })
            chosen == null -> Chooser(onPick = { chosen = it }, onBack = onBack)
            !radioFree -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else -> {
                LaunchedEffect(chosen) { if (chosen == RoomRole.GUEST) viewModel.prewarm() }
                GestureLock(
                    viewModel = viewModel,
                    onLocked = { viewModel.open(chosen!!, it) },
                    onBack = { viewModel.cancelPrewarm(); chosen = null },
                )
            }
        }
    }
}

/** Stops a running swap and returns once its teardown, which clears the shared radio, should have run. */
private suspend fun stopSwap(context: Context) {
    val running = context.stopService(Intent(context, NearbyExchangeService::class.java))
    NearbyExchangeService.clearSession()
    if (!running) return
    val am = context.getSystemService(ActivityManager::class.java)
    val name = NearbyExchangeService::class.java.name
    withTimeoutOrNull(3_000) {
        @Suppress("DEPRECATION") // Still lists the caller's own services.
        while (am.getRunningServices(Int.MAX_VALUE).any { it.service.className == name }) delay(50)
    }
    // ponytail: the record goes just before onDestroy runs on main, so a fixed grace covers the gap; a swap "gone" signal if it races.
    delay(300)
}

@Composable
private fun Chooser(onPick: (RoomRole) -> Unit, onBack: () -> Unit) {
    HeroBanner(stringResource(R.string.room_title), stringResource(R.string.room_intro))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeTile(
            title = stringResource(R.string.room_host),
            hint = stringResource(R.string.room_host_hint),
            icon = Icons.Default.Home,
            accent = PingCyan,
            onClick = { onPick(RoomRole.HOST) },
            modifier = Modifier.weight(1f),
        )
        ModeTile(
            title = stringResource(R.string.room_join),
            hint = stringResource(R.string.room_join_hint),
            icon = Icons.Default.Search,
            accent = PingMagenta,
            onClick = { onPick(RoomRole.GUEST) },
            modifier = Modifier.weight(1f),
        )
    }
    TextButton(onClick = onBack) { Text(stringResource(R.string.action_cancel)) }
}

@Composable
private fun GestureLock(viewModel: RoomViewModel, onLocked: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    var granted by remember { mutableStateOf(RequiredPermissions.missing(context).isEmpty()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = RequiredPermissions.missing(context).isEmpty()
    }
    val camera by viewModel.cameraState.collectAsStateWithLifecycle()
    val surfaceRequest by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val previewDescription = stringResource(R.string.gesture_camera_preview_desc)

    LaunchedEffect(granted) { if (!granted) launcher.launch(RequiredPermissions.all) }
    LaunchedEffect(granted) {
        if (granted && lifecycleOwner != null) viewModel.startCamera(lifecycleOwner)
    }
    LaunchedEffect(camera) {
        val locked = camera as? GestureCamera.State.Locked ?: return@LaunchedEffect
        runCatching { context.vibrateShort() }
        viewModel.stopCamera()
        onLocked(locked.fingerprint.code)
    }

    Text(stringResource(R.string.room_lock_prompt), style = MaterialTheme.typography.titleMedium)
    GestureGuideButton()
    SavedGestureChips(onPick = {
        viewModel.stopCamera()
        onLocked(it)
    })
    Text(
        text = when (val c = camera) {
            is GestureCamera.State.NoHand -> stringResource(R.string.gesture_no_hand)
            is GestureCamera.State.Detecting ->
                stringResource(R.string.gesture_detecting, c.fingerprint.label(LocalResources.current), (c.stability * 100).toInt())
            is GestureCamera.State.Locked -> stringResource(R.string.exchange_gesture_locked, c.fingerprint.label(LocalResources.current))
            is GestureCamera.State.ModelError -> stringResource(R.string.gesture_model_error)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (granted) {
        GesturePreview(
            surfaceRequest,
            Modifier.semantics { contentDescription = previewDescription },
        )
    } else {
        Text(stringResource(R.string.perm_rationale_subtitle), textAlign = TextAlign.Center)
        Button(onClick = { runCatching { context.startActivity(openAppSettings(context)) } }) {
            Text(stringResource(R.string.perm_rationale_open_settings))
        }
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
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let(viewModel::addFolder)
    }

    LaunchedEffect(state.phase) {
        when (state.phase) {
            RoomPhase.IN_ROOM -> runCatching { context.vibrateDouble() }
            RoomPhase.DENIED, RoomPhase.NO_ROOM -> runCatching { context.vibrateShort() }
            else -> Unit
        }
    }

    val inRoom = state.phase == RoomPhase.OPEN || state.phase == RoomPhase.IN_ROOM
    HeroBanner(
        title = stringResource(if (state.role == RoomRole.HOST) R.string.room_you_host else R.string.room_you_guest),
        subtitle = stringResource(
            when (state.phase) {
                RoomPhase.OPEN -> R.string.room_open
                RoomPhase.SEARCHING -> R.string.room_searching
                RoomPhase.WAITING_APPROVAL -> R.string.room_waiting_approval
                RoomPhase.IN_ROOM -> R.string.room_in
                RoomPhase.DENIED -> R.string.room_denied
                RoomPhase.NO_ROOM -> if (state.gestureMismatch) R.string.room_gesture_mismatch else R.string.room_none
                RoomPhase.CLOSED -> R.string.room_closed
                RoomPhase.IDLE -> R.string.room_searching
            },
        ),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (state.manifest.members.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.room_member_count, state.manifest.members.size, state.manifest.members.size) +
                    " · " + state.manifest.members.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (inRoom) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.room_add_files)) }
                OutlinedButton(onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(R.string.room_add_folder), color = Color.White)
                }
            }
        }
    }

    if (state.guessing) {
        Text(
            stringResource(R.string.room_guessing_warning),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.pending.size > 1) {
        Button(onClick = viewModel::approveAll, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.room_allow_all, state.pending.size))
        }
    }
    val others = state.manifest.files.filter { it.ownerKey != state.myKey }
    val fetchable = others.count { state.transfers[it.id]?.status.let { s -> s == null || s == Transfer.Status.FAILED } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (inRoom && fetchable > 1) {
            Button(onClick = viewModel::downloadAll) { Text(stringResource(R.string.room_download_all, fetchable)) }
        }
        OutlinedButton(onClick = onLeave) { Text(stringResource(R.string.room_leave)) }
    }

    if (inRoom) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.pending, key = { "p" + it.endpointId }) { guest ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.room_wants_in, guest.name),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { viewModel.decide(guest.endpointId, true) }) { Text(stringResource(R.string.room_allow)) }
                    TextButton(onClick = { viewModel.decide(guest.endpointId, false) }) { Text(stringResource(R.string.room_deny)) }
                }
            }
            if (state.shared.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.room_you_share)) }
                items(state.shared, key = { "s" + it.id }) { f ->
                    PingCard(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            FileBadge(f.name)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(shownName(f.path, f.name), style = MaterialTheme.typography.titleMedium)
                                Text(Formatter.formatShortFileSize(context, f.size), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { viewModel.removeShared(f.id) }) {
                                Text(stringResource(R.string.room_stop_sharing))
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) { SectionLabel(stringResource(R.string.room_notes)) }
                    TextButton(onClick = { pasteNote(context, viewModel::postNote) }) {
                        Text(stringResource(R.string.room_note_paste))
                    }
                }
            }
            items(state.notes) { note -> NoteRow(note) }
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
        FileBadge(file.name)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                shownName(file.path, file.name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.room_file_meta, Formatter.formatShortFileSize(context, file.size), file.ownerName),
                style = MaterialTheme.typography.bodySmall,
            )
            if (transfer?.status == Transfer.Status.RECEIVING && transfer.total > 0) {
                LinearProgressIndicator(
                    progress = { transfer.bytes.toFloat() / transfer.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                transferRate(transfer)?.let { (perSecond, secondsLeft) ->
                    Text(
                        stringResource(
                            R.string.room_speed,
                            Formatter.formatShortFileSize(context, perSecond),
                            DateUtils.formatElapsedTime(secondsLeft),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        when (transfer?.status) {
            Transfer.Status.QUEUED, Transfer.Status.RECEIVING ->
                Text(stringResource(R.string.room_receiving), style = MaterialTheme.typography.bodySmall)
            Transfer.Status.DONE -> TextButton(onClick = { openDownloaded(context, transfer.path) }) {
                Text(stringResource(R.string.room_open_file))
            }
            Transfer.Status.FAILED, null -> TextButton(onClick = onDownload) {
                Text(
                    stringResource(
                        when {
                            transfer == null -> R.string.room_download
                            transfer.bytes > 0 -> R.string.room_resume
                            else -> R.string.room_retry
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun NoteRow(note: Note) {
    val context = LocalContext.current
    val copy = stringResource(R.string.action_copy)
    PingCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClickLabel = copy) {
                    clipboard(context).setPrimaryClip(ClipData.newPlainText(note.from, note.text))
                    Toast.makeText(context, R.string.contact_copied, Toast.LENGTH_SHORT).show()
                }
                .padding(vertical = 6.dp),
        ) {
            Text(note.from, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(note.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun clipboard(context: Context) = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

private fun pasteNote(context: Context, post: (String) -> Unit) {
    val text = clipboard(context).primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    if (RoomProtocol.noteFits(text)) post(text)
    else Toast.makeText(context, R.string.room_note_unusable, Toast.LENGTH_SHORT).show()
}

private fun openAppSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

private fun shownName(path: String, name: String) = if (path.isEmpty()) name else "$path/$name"

/** Bytes per second and seconds left, once enough has arrived to say. */
private fun transferRate(t: Transfer): Pair<Long, Long>? {
    val elapsed = (System.currentTimeMillis() - t.startedAt) / 1000.0
    if (t.startedAt == 0L || elapsed < 1 || t.bytes <= t.from) return null
    val perSecond = ((t.bytes - t.from) / elapsed).toLong().coerceAtLeast(1)
    return perSecond to (t.total - t.bytes) / perSecond
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

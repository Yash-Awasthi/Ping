package com.ping.app.ui.exchange

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Color
import com.ping.app.ui.components.Avatar
import com.ping.app.ui.theme.BrandBrush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.auth.GestureCamera
import com.ping.app.auth.GestureFingerprint
import com.ping.app.auth.GestureLock
import com.ping.app.model.ExchangeSession
import com.ping.app.model.Profile
import com.ping.app.service.NearbyExchangeService
import com.ping.app.ui.ConnectionState
import com.ping.app.ui.components.GestureGuideButton
import com.ping.app.ui.components.GesturePreview
import com.ping.app.ui.components.SavedGestureChips
import com.ping.app.ui.ConnectionStatusIndicator
import com.ping.app.utils.RequiredPermissions
import com.ping.app.utils.vibrateDouble
import com.ping.app.utils.vibrateError
import com.ping.app.utils.vibrateShort
import com.ping.app.utils.vibrateTick
import kotlinx.coroutines.delay

/**
 * The swap screen: lock a gesture, then wait for a peer holding the same one.
 *
 * The camera is released the moment a code locks, so the pose cannot drift into a
 * different code while the service searches and the lock cannot silently change.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExchangeScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: ExchangeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner

    var permissionsGranted by remember {
        mutableStateOf(RequiredPermissions.missing(context).isEmpty())
    }
    // Saveable so a rotation mid-search neither restarts the camera nor forgets the first gesture.
    var searchStarted by rememberSaveable { mutableStateOf(false) }
    var twoStep by rememberSaveable { mutableStateOf(false) }
    var firstCode by rememberSaveable { mutableStateOf<String?>(null) }
    var fields by remember { mutableStateOf(loadFields(context)) }
    var lastStability by remember { mutableFloatStateOf(0f) }
    var restartKey by remember { mutableStateOf(0) }
    var remaining by remember { mutableStateOf(NearbyExchangeService.WINDOW_SECONDS) }
    var memo by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionsGranted = RequiredPermissions.missing(context).isEmpty() }

    val cameraState by viewModel.cameraState.collectAsStateWithLifecycle()
    val surfaceRequest by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val previewDescription = stringResource(R.string.gesture_camera_preview_desc)

    // Session state is process-wide, so an earlier visit's result would render as this one's.
    // A search already running belongs to this screen, as after a rotation, so it is kept.
    DisposableEffect(Unit) {
        if (!searchStarted) NearbyExchangeService.clearSession()
        onDispose {
            viewModel.stopCamera()
            // A scan that never locked has nothing to finish, so it must not outlive the screen.
            if (!searchStarted) {
                NearbyExchangeService.stop(context)
                NearbyExchangeService.clearSession()
            }
        }
    }

    LaunchedEffect(permissionsGranted) {
        if (!permissionsGranted) permissionLauncher.launch(RequiredPermissions.all)
    }

    LaunchedEffect(permissionsGranted, restartKey) {
        if (permissionsGranted) NearbyExchangeService.prewarm(context)
    }

    LaunchedEffect(permissionsGranted, restartKey) {
        if (permissionsGranted && lifecycleOwner != null && !searchStarted) viewModel.startCamera(lifecycleOwner)
    }

    LaunchedEffect(cameraState) {
        val steady = (cameraState as? GestureCamera.State.Detecting)?.stability ?: 0f
        if (GestureLock.stabilityStep(lastStability, steady)) runCatching { context.vibrateTick() }
        lastStability = steady
        val locked = cameraState as? GestureCamera.State.Locked ?: return@LaunchedEffect
        if (searchStarted) return@LaunchedEffect
        val code = locked.fingerprint.code
        val first = firstCode
        if (twoStep && first == null) {
            firstCode = code
            runCatching { context.vibrateShort() }
            return@LaunchedEffect
        }
        // The first pose is still held when it locks; the second must be a different one.
        if (first == code) return@LaunchedEffect
        searchStarted = true
        runCatching { context.vibrateShort() }
        viewModel.stopCamera()
        NearbyExchangeService.start(
            context,
            GestureFingerprint.sequenceCode(listOfNotNull(first, code)),
            fields,
        )
    }

    LaunchedEffect(session?.state) {
        val s = session?.state
        if (s == ExchangeSession.State.COMPLETED) {
            runCatching { context.vibrateDouble() }
        }
        if (s == ExchangeSession.State.NO_MATCH || s == ExchangeSession.State.ERROR) {
            runCatching { context.vibrateError() }
        }
        if (session?.state != ExchangeSession.State.SEARCHING) return@LaunchedEffect
        remaining = NearbyExchangeService.WINDOW_SECONDS
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
    }

    val gestureText = if (twoStep && firstCode != null && cameraState !is GestureCamera.State.ModelError) {
        stringResource(R.string.exchange_second_gesture)
    } else when (val state = cameraState) {
        is GestureCamera.State.NoHand -> stringResource(R.string.gesture_no_hand)
        is GestureCamera.State.Detecting -> stringResource(
            R.string.gesture_detecting,
            state.fingerprint.label,
            (state.stability * 100).toInt(),
        )
        is GestureCamera.State.Locked -> stringResource(
            R.string.exchange_gesture_locked,
            state.fingerprint.label,
        )
        is GestureCamera.State.ModelError -> stringResource(R.string.gesture_model_error)
    }

    val statusText = when (val state = session?.state) {
        null -> gestureText
        ExchangeSession.State.SEARCHING ->
            stringResource(R.string.exchange_waiting_peer_countdown, remaining)
        ExchangeSession.State.CONNECTING -> stringResource(R.string.status_connecting)
        ExchangeSession.State.EXCHANGING -> stringResource(R.string.status_exchanging)
        ExchangeSession.State.COMPLETED -> stringResource(
            R.string.exchange_completed,
            session?.receivedContact?.displayName?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.someone),
        )
        ExchangeSession.State.NO_MATCH -> stringResource(
            if (session?.reason == ExchangeSession.Reason.GESTURE_MISMATCH) R.string.exchange_no_match_gesture
            else R.string.exchange_no_match,
        )
        ExchangeSession.State.CANCELLED -> stringResource(R.string.exchange_cancelled)
        ExchangeSession.State.ERROR -> stringResource(session?.errorRes ?: R.string.exchange_error_generic)
    }
    // The percentage ticks every frame, so a screen reader hears only the pose it names.
    val spokenStatus = (cameraState as? GestureCamera.State.Detecting)
        ?.takeIf { session == null && !(twoStep && firstCode != null) }
        ?.fingerprint?.label ?: statusText

    val connectionState = when (session?.state) {
        ExchangeSession.State.SEARCHING -> ConnectionState.SCANNING
        ExchangeSession.State.CONNECTING,
        ExchangeSession.State.EXCHANGING,
        -> ConnectionState.CONNECTING
        ExchangeSession.State.COMPLETED -> ConnectionState.PAIRED
        ExchangeSession.State.NO_MATCH,
        ExchangeSession.State.ERROR,
        -> ConnectionState.ERROR
        else -> ConnectionState.IDLE
    }

    val busy = session?.state == ExchangeSession.State.CONNECTING ||
        session?.state == ExchangeSession.State.EXCHANGING
    val retryable = session?.state == ExchangeSession.State.NO_MATCH ||
        session?.state == ExchangeSession.State.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionStatusIndicator(state = connectionState, modifier = Modifier.padding(bottom = 8.dp))

        StageRow(session?.state)

        if (session == null && firstCode == null) {
            FilterChip(
                selected = twoStep,
                onClick = { twoStep = !twoStep },
                label = { Text(stringResource(R.string.exchange_two_gestures)) },
            )
            SavedGestureChips(onPick = { code ->
                if (!searchStarted) {
                    searchStarted = true
                    viewModel.stopCamera()
                    NearbyExchangeService.start(context, code, fields)
                }
            })
            Text(stringResource(R.string.exchange_share_fields), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, enabled = false, onClick = {}, label = { Text(stringResource(R.string.contact_label_name)) })
                SHARE_TOGGLES.forEach { (key, label) ->
                    FilterChip(
                        selected = key in fields,
                        onClick = {
                            fields = if (key in fields) fields - key else fields + key
                            saveFields(context, fields)
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }

        if (session == null) GestureGuideButton()

        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spokenStatus
            },
        )

        val others = session?.othersNearby ?: 0
        if (others > 0 && (session?.state == ExchangeSession.State.SEARCHING || session?.state == ExchangeSession.State.NO_MATCH)) {
            Text(
                text = pluralStringResource(R.plurals.exchange_others_nearby, others, others),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        val legacy = session?.legacyNearby ?: 0
        if (legacy > 0 && (session?.state == ExchangeSession.State.SEARCHING || session?.state == ExchangeSession.State.NO_MATCH)) {
            Text(
                text = stringResource(R.string.exchange_peer_outdated),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        if (busy) {
            CircularProgressIndicator()
        }

        val received = session?.receivedContact

        if (session?.state == ExchangeSession.State.COMPLETED && received != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(BrandBrush)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(name = received.displayName, size = 72.dp)
                Text(
                    received.displayName.ifBlank { stringResource(R.string.someone) },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    listOf(received.phone, received.email).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        if (session?.state == ExchangeSession.State.COMPLETED && received != null) {
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text(stringResource(R.string.contact_label_memo)) },
                placeholder = { Text(stringResource(R.string.exchange_memo_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (permissionsGranted && !searchStarted) {
            val steady = (cameraState as? GestureCamera.State.Detecting)?.stability
                ?: if (cameraState is GestureCamera.State.Locked) 1f else 0f
            LinearProgressIndicator(
                progress = { steady },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.secondary,
            )
            GesturePreview(
                surfaceRequest,
                Modifier.semantics { contentDescription = previewDescription },
            )
        } else if (!permissionsGranted) {
            Text(
                text = stringResource(R.string.perm_rationale_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.perm_rationale_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { context.startActivity(openAppSettings(context)) }) {
                Text(stringResource(R.string.perm_rationale_open_settings))
            }
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.perm_rationale_not_now))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                NearbyExchangeService.stop(context)
                onBack()
            }) {
                Text(stringResource(R.string.action_cancel))
            }

            if (permissionsGranted && retryable) {
                Button(onClick = {
                    NearbyExchangeService.stop(context)
                    NearbyExchangeService.clearSession()
                    viewModel.resetCamera()
                    searchStarted = false
                    firstCode = null
                    restartKey++
                }) {
                    Text(stringResource(R.string.exchange_retry))
                }
            }

            if (session?.state == ExchangeSession.State.COMPLETED) {
                Button(onClick = {
                    session?.receivedContact?.let { viewModel.saveMemo(it, memo) }
                    onDone()
                }) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}

/** Gesture, find, check, swap: which step of the swap is running now. */
@Composable
private fun StageRow(state: ExchangeSession.State?) {
    val current = when (state) {
        null -> 0
        ExchangeSession.State.SEARCHING -> 1
        ExchangeSession.State.CONNECTING -> 2
        else -> 3
    }
    val labels = listOf(
        R.string.stage_gesture, R.string.stage_find, R.string.stage_check, R.string.stage_swap,
    )
    val description = stringResource(R.string.exchange_step_desc, current + 1, labels.size, stringResource(labels[current]))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        labels.forEachIndexed { i, label ->
            val done = i < current
            val now = i == current
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics {}) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                now -> MaterialTheme.colorScheme.primary
                                done -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    else Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                    color = if (now || done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (i < labels.lastIndex) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .padding(bottom = 18.dp)
                        .height(2.dp)
                        .background(if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

/** Optional card fields, wire key to label; the name always goes. */
private val SHARE_TOGGLES = listOf(
    "phone" to R.string.contact_label_phone,
    "email" to R.string.contact_label_email,
    "social" to R.string.contact_label_social,
    "note" to R.string.contact_label_note,
)

private const val PREFS = "swap"
private const val KEY_FIELDS = "fields"

private fun loadFields(context: Context): Set<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FIELDS, null)?.plus("name")
        ?: Profile.FIELDS

private fun saveFields(context: Context, fields: Set<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FIELDS, fields).apply()
}

private fun openAppSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

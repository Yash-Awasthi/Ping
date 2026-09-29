package com.ping.app.ui.exchange

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.auth.GestureCamera
import com.ping.app.auth.GestureFingerprint
import com.ping.app.model.ExchangeSession
import com.ping.app.service.NearbyExchangeService
import com.ping.app.ui.ConnectionState
import com.ping.app.ui.components.GestureGuideButton
import com.ping.app.ui.ConnectionStatusIndicator
import com.ping.app.utils.RequiredPermissions
import com.ping.app.utils.vibrateDouble
import com.ping.app.utils.vibrateShort
import kotlinx.coroutines.delay

/**
 * The swap screen: lock a gesture, then wait for a peer holding the same one.
 *
 * The camera is released the moment a code locks, so the pose cannot drift into a
 * different code while the service searches and the lock cannot silently change.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var searchStarted by remember { mutableStateOf(false) }
    var twoStep by remember { mutableStateOf(false) }
    var firstCode by remember { mutableStateOf<String?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var restartKey by remember { mutableStateOf(0) }
    var remaining by remember { mutableStateOf(NearbyExchangeService.WINDOW_SECONDS) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionsGranted = RequiredPermissions.missing(context).isEmpty() }

    val cameraState by viewModel.cameraState.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val previewDescription = stringResource(R.string.gesture_camera_preview_desc)

    // Session state is process-wide, so an earlier visit's result would render as this one's.
    DisposableEffect(Unit) {
        NearbyExchangeService.clearSession()
        onDispose {
            viewModel.stopCamera()
            NearbyExchangeService.clearSession()
        }
    }

    LaunchedEffect(permissionsGranted) {
        if (!permissionsGranted) permissionLauncher.launch(RequiredPermissions.all)
    }

    LaunchedEffect(permissionsGranted, previewView, restartKey) {
        val preview = previewView
        if (permissionsGranted && preview != null && lifecycleOwner != null && !searchStarted) {
            viewModel.startCamera(lifecycleOwner, preview)
        }
    }

    LaunchedEffect(cameraState) {
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
        )
    }

    LaunchedEffect(session?.state) {
        val s = session?.state
        if (s == ExchangeSession.State.AWAITING_CONFIRM || s == ExchangeSession.State.COMPLETED) {
            runCatching { context.vibrateDouble() }
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
        ExchangeSession.State.AWAITING_CONFIRM ->
            stringResource(R.string.exchange_confirm_prompt, session?.sas.orEmpty())
        ExchangeSession.State.EXCHANGING -> stringResource(R.string.status_exchanging)
        ExchangeSession.State.COMPLETED -> stringResource(
            R.string.exchange_completed,
            session?.receivedContact?.displayName?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.someone),
        )
        ExchangeSession.State.NO_MATCH -> stringResource(R.string.exchange_no_match)
        ExchangeSession.State.CANCELLED -> stringResource(R.string.exchange_cancelled)
        ExchangeSession.State.ERROR -> session?.errorMessage ?: stringResource(R.string.exchange_error_generic)
    }

    val connectionState = when (session?.state) {
        ExchangeSession.State.SEARCHING -> ConnectionState.SCANNING
        ExchangeSession.State.CONNECTING,
        ExchangeSession.State.AWAITING_CONFIRM,
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
        ConnectionStatusIndicator(state = connectionState, modifier = Modifier.padding(bottom = 24.dp))

        if (session == null && firstCode == null) {
            FilterChip(
                selected = twoStep,
                onClick = { twoStep = !twoStep },
                label = { Text(stringResource(R.string.exchange_two_gestures)) },
            )
        }

        if (session == null) GestureGuideButton()

        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        if (busy) {
            CircularProgressIndicator()
        }

        if (permissionsGranted) {
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

            if (session?.state == ExchangeSession.State.AWAITING_CONFIRM) {
                Button(onClick = { NearbyExchangeService.confirm(context) }) {
                    Text(stringResource(R.string.exchange_confirm_match))
                }
                OutlinedButton(onClick = { NearbyExchangeService.reject(context) }) {
                    Text(stringResource(R.string.exchange_confirm_mismatch))
                }
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
                Button(onClick = onDone) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}

private fun openAppSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

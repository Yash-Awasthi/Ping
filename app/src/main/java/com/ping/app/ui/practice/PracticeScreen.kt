package com.ping.app.ui.practice

import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.auth.GestureCamera
import com.ping.app.ui.components.GestureGuideButton
import com.ping.app.ui.components.PingCard
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PracticeViewModel @Inject constructor(
    private val camera: GestureCamera,
) : ViewModel() {
    val state = camera.state
    val metrics = kotlinx.coroutines.flow.MutableStateFlow("")

    init {
        camera.onLandmarks = { pts ->
            val flat = FloatArray(pts.size * 3)
            pts.forEachIndexed { i, p -> flat[i * 3] = p.first; flat[i * 3 + 1] = p.second; flat[i * 3 + 2] = p.third }
            metrics.value = com.ping.app.auth.GestureFingerprint.metrics(flat)
        }
    }

    override fun onCleared() { camera.onLandmarks = null }
    fun start(owner: LifecycleOwner, preview: PreviewView) = camera.start(owner, preview)
    fun stop() = camera.stop()
}

/**
 * Shows what Ping sees your hand doing, live, and never locks or connects to anything. Use it
 * to learn a gesture before agreeing on it with someone.
 */
@Composable
fun PracticeScreen(
    onBack: () -> Unit,
    viewModel: PracticeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val owner = context as? LifecycleOwner
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()

    LaunchedEffect(preview) {
        val p = preview
        if (p != null && owner != null) viewModel.start(owner, p)
    }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    val fingerprint = when (val s = state) {
        is GestureCamera.State.Detecting -> s.fingerprint
        is GestureCamera.State.Locked -> s.fingerprint
        else -> null
    }
    LaunchedEffect(fingerprint?.code) {
        fingerprint?.let { Timber.d("Practice %s | %s | %s", it.code, it.label, metrics) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.practice_title), style = MaterialTheme.typography.headlineSmall)
        PingCard {
            Text(
                text = fingerprint?.label ?: stringResource(R.string.gesture_no_hand),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = listOf(
                    fingerprint?.code.orEmpty(),
                    fingerprint?.let { "open ${it.openFingers} · closed ${it.closedFingers}" }.orEmpty(),
                    metrics,
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .border(2.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraLarge),
            factory = { ctx ->
                PreviewView(ctx).also {
                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    preview = it
                }
            },
        )
        GestureGuideButton()
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_done)) }
    }
}

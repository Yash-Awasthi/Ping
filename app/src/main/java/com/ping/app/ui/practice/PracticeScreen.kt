package com.ping.app.ui.practice

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.auth.CustomGesture
import com.ping.app.auth.GestureCamera
import com.ping.app.auth.GestureFingerprint
import com.ping.app.ui.components.GestureGuideButton
import com.ping.app.ui.components.GesturePreview
import com.ping.app.ui.components.PingCard
import com.ping.app.ui.components.SectionLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PracticeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val camera: GestureCamera,
) : ViewModel() {
    val state = camera.state
    val surfaceRequest = camera.surfaceRequest
    val metrics = MutableStateFlow("")
    val saved = MutableStateFlow(CustomGesture.load(context))

    init {
        camera.onLandmarks = { pts ->
            val flat = FloatArray(pts.size * 3)
            pts.forEachIndexed { i, p -> flat[i * 3] = p.first; flat[i * 3 + 1] = p.second; flat[i * 3 + 2] = p.third }
            metrics.value = com.ping.app.auth.GestureFingerprint.metrics(flat)
        }
    }

    /** Saving under an existing name replaces that gesture. */
    fun save(gesture: CustomGesture) = update(saved.value.filter { it.name != gesture.name } + gesture)

    fun delete(gesture: CustomGesture) = update(saved.value - gesture)

    private fun update(gestures: List<CustomGesture>) {
        saved.value = gestures
        CustomGesture.save(context, gestures)
    }

    override fun onCleared() { camera.onLandmarks = null }
    fun start(owner: LifecycleOwner) = camera.start(owner)
    fun stop() = camera.stop()
}

/** Metrics change every frame, so only this line reads them. */
@Composable
private fun LiveMetrics(fingerprint: GestureFingerprint?, metricsFlow: StateFlow<String>) {
    val metrics by metricsFlow.collectAsStateWithLifecycle()
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

/**
 * Shows what Ping sees your hand doing, live, and never connects to anything. Use it to learn a
 * gesture before agreeing on it with someone, or to record one of your own.
 */
@Composable
fun PracticeScreen(
    onBack: () -> Unit,
    viewModel: PracticeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val owner = context as? LifecycleOwner
    val state = viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    val surfaceRequest by viewModel.surfaceRequest.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { if (owner != null) viewModel.start(owner) }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    // State changes every frame (stability ticks); the screen only needs to redraw when the pose does.
    val fingerprint by remember {
        derivedStateOf {
            when (val s = state.value) {
                is GestureCamera.State.Detecting -> s.fingerprint
                is GestureCamera.State.Locked -> s.fingerprint
                else -> null
            }
        }
    }
    val locked by remember { derivedStateOf { (state.value as? GestureCamera.State.Locked)?.fingerprint } }
    LaunchedEffect(fingerprint?.code) {
        fingerprint?.let { Timber.d("Practice %s | %s | %s", it.code, it.label, viewModel.metrics.value) }
    }

    val steps = remember { mutableStateListOf<String>() }
    val labels = remember { mutableStateListOf<String>() }
    var name by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            LiveMetrics(fingerprint, viewModel.metrics)
        }
        GesturePreview(surfaceRequest)
        GestureGuideButton()

        PingCard {
            SectionLabel(stringResource(R.string.custom_gestures_title))
            Text(
                stringResource(R.string.custom_gestures_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (steps.isNotEmpty()) Text(labels.joinToString("  →  "), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A second step equal to the first could never be made live: the swap screen waits for a change.
                OutlinedButton(
                    enabled = locked.let { it != null && steps.size < CustomGesture.MAX_STEPS && steps.lastOrNull() != it.code },
                    onClick = { locked?.let { steps += it.code; labels += it.label } },
                ) { Text(stringResource(R.string.custom_gesture_add_step)) }
                if (steps.isNotEmpty()) {
                    TextButton(onClick = { steps.clear(); labels.clear() }) { Text(stringResource(R.string.custom_gesture_clear)) }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.custom_gesture_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = name.isNotBlank() && steps.isNotEmpty(),
                onClick = {
                    viewModel.save(CustomGesture(name.trim(), steps.toList(), labels.toList()))
                    steps.clear(); labels.clear(); name = ""
                },
            ) { Text(stringResource(R.string.action_save)) }
            saved.forEach { gesture ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(gesture.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            gesture.labels.joinToString("  →  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { viewModel.delete(gesture) }) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_done)) }
    }
}

package com.ping.app.auth

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Front-camera hand pipeline for Ping.
 *
 * Runs MediaPipe [HandLandmarker] in LIVE_STREAM mode and, for each frame with
 * a hand, computes a [GestureFingerprint]. When the same fingerprint code holds
 * steady for [COMMIT_FRAMES] frames it emits [State.Locked] — that locked code
 * is the pairing secret handed to the exchange service.
 *
 * There is no per-person enrollment: the fingerprint is a pure function of the
 * hand pose, so two strangers doing the same agreed gesture lock the same code.
 */
@Singleton
class GestureCamera @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed class State {
        /** No hand in frame. */
        object NoHand : State()
        /** A hand is visible; [stability] is 0..1 progress toward locking. */
        data class Detecting(val fingerprint: GestureFingerprint, val stability: Float) : State()
        /** The fingerprint held steady long enough — [fingerprint].code is the pairing key. */
        data class Locked(val fingerprint: GestureFingerprint) : State()
        /** MediaPipe failed to initialise. */
        data class ModelError(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.NoHand)
    val state: StateFlow<State> = _state

    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null

    private var lastCode: String? = null
    private var streak = 0

    fun start(owner: LifecycleOwner, preview: PreviewView) {
        executor = Executors.newSingleThreadExecutor()
        initLandmarker()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bind(owner, preview)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll(); cameraProvider = null
        landmarker?.close(); landmarker = null
        executor?.shutdown(); executor = null
        reset()
        _state.value = State.NoHand
    }

    /** Forget the current streak so the next lock is a fresh capture. */
    fun reset() {
        lastCode = null
        streak = 0
        if (_state.value !is State.ModelError) _state.value = State.NoHand
    }

    private fun initLandmarker() {
        try {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .setResultListener(::onResult)
                .setErrorListener { e -> Timber.e(e, "HandLandmarker error") }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            val msg = "Failed to load hand model: ${e.message}"
            Timber.e(e, msg)
            _state.value = State.ModelError(msg)
        }
    }

    private fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { ia -> ia.setAnalyzer(executor!!) { proxy -> process(proxy) } }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        } catch (e: Exception) {
            Timber.e(e, "Camera bind failed")
        }
    }

    private fun process(proxy: ImageProxy) {
        val lm = landmarker ?: run { proxy.close(); return }
        val bitmap = proxy.toBitmap()
        proxy.close()
        try {
            lm.detectAsync(BitmapImageBuilder(bitmap).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Timber.w(e, "detectAsync failed")
        }
    }

    private fun onResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") image: com.google.mediapipe.framework.image.MPImage) {
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            reset()
            return
        }
        // Flatten the 21 landmarks to [x,y,z, …].
        val pts = hands[0]
        val xyz = FloatArray(pts.size * 3)
        for (i in pts.indices) {
            xyz[i * 3] = pts[i].x()
            xyz[i * 3 + 1] = pts[i].y()
            xyz[i * 3 + 2] = pts[i].z()
        }
        val fp = GestureFingerprint.fromLandmarks(xyz) ?: run { reset(); return }

        if (fp.code == lastCode) streak++ else { lastCode = fp.code; streak = 1 }
        val stability = (streak.toFloat() / COMMIT_FRAMES).coerceAtMost(1f)
        _state.value = if (streak >= COMMIT_FRAMES) State.Locked(fp)
        else State.Detecting(fp, stability)
    }

    companion object {
        private const val MODEL_ASSET = "hand_landmarker.task"
        /** Frames the same code must persist before we lock it. ~0.5s at 20fps. */
        private const val COMMIT_FRAMES = 10
    }
}

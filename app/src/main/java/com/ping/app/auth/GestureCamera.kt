package com.ping.app.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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

    /** Optional callback for raw landmark data (used by air-drawing overlay) */
    var onLandmarks: ((List<Triple<Float, Float, Float>>) -> Unit)? = null

    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null

    private var lastCode: String? = null
    private var streak = 0
    private var lastMask = -1
    private var maskStreak = 0
    private var lastMotion: GestureFingerprint.Motion? = null
    private var motionStreak = 0
    private val tracker = MotionTracker()
    private val palmVotes = ArrayDeque<GestureFingerprint.Palm>()
    private val handVotes = ArrayDeque<Boolean>()

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
        lastMask = -1
        maskStreak = 0
        lastMotion = null
        motionStreak = 0
        palmVotes.clear()
        handVotes.clear()
        tracker.clear()
        if (_state.value !is State.ModelError) _state.value = State.NoHand
    }

    private fun initLandmarker() {
        try {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .setResultListener(::onResult)
                .setErrorListener { e -> Timber.e(e, "HandLandmarker error") }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Throwable) {
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
        val raw = proxy.toBitmap()
        val rotation = proxy.imageInfo.rotationDegrees
        proxy.close()
        // Turn the sensor frame upright and mirror it like the preview, so "up" and "right"
        // mean what the user sees on screen on any phone.
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        try {
            lm.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
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
        fun flatten(i: Int): FloatArray {
            val pts = hands[i]
            return FloatArray(pts.size * 3).also { out ->
                for (k in pts.indices) {
                    out[k * 3] = pts[k].x(); out[k * 3 + 1] = pts[k].y(); out[k * 3 + 2] = pts[k].z()
                }
            }
        }
        fun isRight(i: Int): Boolean? =
            result.handedness().getOrNull(i)?.firstOrNull()?.categoryName()?.let { it == "Right" }

        onLandmarks?.invoke(hands[0].map { Triple(it.x(), it.y(), it.z()) })

        val first = flatten(0)
        var fp = GestureFingerprint.fromLandmarks(first, isRight(0)) ?: run { reset(); return }
        var pointerSource = first

        // Two hands held together make one gesture; each phone must pick the same "main" hand.
        if (hands.size > 1) {
            val second = flatten(1)
            val other = GestureFingerprint.fromLandmarks(second, isRight(1))
            if (other != null && GestureFingerprint.handsTogether(first, second)) {
                val firstIsMain = fp.code <= other.code
                val main = if (firstIsMain) fp else other
                val partner = if (firstIsMain) other else fp
                fp = main.copy(partner = partner.fingerMask)
                pointerSource = if (firstIsMain) first else second
            }
        }

        // Which hand: only trusted once the last frames agree.
        // The detector sees the mirrored frame, so its "Right" is the user's left hand.
        isRight(0)?.let { handVotes.addLast(!it) }
        while (handVotes.size > HAND_VOTES) handVotes.removeFirst()
        if (handVotes.size >= HAND_VOTES) {
            val share = handVotes.count { it }.toDouble() / handVotes.size
            when {
                share >= HAND_AGREE -> fp = fp.copy(hand = GestureFingerprint.Hand.RIGHT)
                share <= 1 - HAND_AGREE -> fp = fp.copy(hand = GestureFingerprint.Hand.LEFT)
            }
        }

        // The left/right call can flicker, so the palm is the majority of the last frames.
        if (fp.palm == GestureFingerprint.Palm.FACING || fp.palm == GestureFingerprint.Palm.AWAY) {
            palmVotes.addLast(fp.palm)
            while (palmVotes.size > PALM_VOTES) palmVotes.removeFirst()
            val facing = palmVotes.count { it == GestureFingerprint.Palm.FACING }
            fp = fp.copy(palm = if (facing * 2 >= palmVotes.size) GestureFingerprint.Palm.FACING else GestureFingerprint.Palm.AWAY)
        }

        val (px, py) = GestureFingerprint.pointer(pointerSource, fp.fingerMask)
        tracker.add(px, py)
        val motion = tracker.classify()

        if (fp.code == lastCode) streak++ else { lastCode = fp.code; streak = 1 }
        if (fp.fingerMask == lastMask) maskStreak++ else { lastMask = fp.fingerMask; maskStreak = 1 }
        if (motion != null && motion == lastMotion) motionStreak++ else { lastMotion = motion; motionStreak = if (motion != null) 1 else 0 }

        // A moving hand locks on its motion; a still hand locks on its pose. Direction and flags
        // wobble while a hand moves, so a motion code keeps only the finger mask.
        val motionLocked = motion != null && maskStreak >= MOTION_POSE_FRAMES && motionStreak >= MOTION_FRAMES
        val stillLocked = motion == null && streak >= COMMIT_FRAMES && tracker.isStill(COMMIT_FRAMES)
        _state.value = when {
            motionLocked -> State.Locked(fp.withMotion(motion!!)).also { Timber.d("Locked gesture %s | %s", it.fingerprint.code, it.fingerprint.label) }
            stillLocked -> State.Locked(fp).also { Timber.d("Locked gesture %s | %s", fp.code, fp.label) }
            else -> State.Detecting(fp, (streak.toFloat() / COMMIT_FRAMES).coerceAtMost(1f))
        }
    }

    companion object {
        private const val MODEL_ASSET = "hand_landmarker.task"
        /** Frames the same code must persist before we lock it. ~0.5s at 20fps. */
        private const val COMMIT_FRAMES = 10
        /** A motion locks after the fingers held one shape this long and the motion read the same this many frames. */
        private const val MOTION_POSE_FRAMES = 10
        private const val MOTION_FRAMES = 4
        private const val PALM_VOTES = 9
        private const val HAND_VOTES = 9
        private const val HAND_AGREE = 0.8
    }
}

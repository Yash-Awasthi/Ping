package com.ping.app.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
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
import kotlin.math.roundToInt

/**
 * Front-camera hand pipeline for Ping.
 *
 * Runs MediaPipe [HandLandmarker] in LIVE_STREAM mode and, for each frame with
 * a hand, computes a [GestureFingerprint]. When the same fingerprint code holds
 * steady for [GestureLock.COMMIT_FRAMES] frames it emits [State.Locked] — that locked code
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

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    /** Where the preview wants to draw; null while the camera is stopped. */
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest

    /** Optional callback for raw landmark data (used by air-drawing overlay) */
    var onLandmarks: ((List<Triple<Float, Float, Float>>) -> Unit)? = null

    @Volatile private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null

    private val lock = GestureLock()
    private val tracker = MotionTracker()
    private val palmVotes = ArrayDeque<GestureFingerprint.Palm>()
    private val handedness = Handedness()

    fun start(owner: LifecycleOwner) {
        // Off main: warmUp() is synchronized, so it would block on a load still running from app start.
        executor = Executors.newSingleThreadExecutor().also { it.execute(::warmUp) }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bind(owner)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        // The landmarker stays loaded: building it costs a noticeable pause on every open.
        cameraProvider?.unbindAll(); cameraProvider = null
        _surfaceRequest.value = null
        executor?.shutdown(); executor = null
        reset()
        _state.value = State.NoHand
    }

    /** Forget the current streak so the next lock is a fresh capture. */
    fun reset() {
        lock.clear()
        palmVotes.clear()
        handedness.clear()
        tracker.clear()
        if (_state.value !is State.ModelError) _state.value = State.NoHand
    }

    /** Load the hand model once; safe to call from a background thread at app start. */
    @Synchronized
    fun warmUp() {
        if (landmarker != null) return
        // Starts CameraX's camera enumeration now so the first bind does not pay for it.
        ProcessCameraProvider.getInstance(context)
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

    private fun bind(owner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider { request -> _surfaceRequest.value = request }
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
        val upright = try { upright(proxy) } finally { proxy.close() }
        try {
            lm.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Timber.w(e, "detectAsync failed")
        }
    }

    private var rawFrame: Bitmap? = null
    private var uprightFrame: Bitmap? = null
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * The frame turned upright and mirrored like the preview, so "up" and "right" mean what the
     * user sees on any phone. Both bitmaps are reused: detectAsync copies the pixels before returning.
     */
    private fun upright(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val raw = if (plane.pixelStride == 4 && plane.rowStride == proxy.width * 4) {
            reuse(rawFrame, proxy.width, proxy.height).also {
                rawFrame = it
                it.copyPixelsFromBuffer(plane.buffer.rewind())
            }
        } else proxy.toBitmap()
        val matrix = Matrix().apply {
            postRotate(proxy.imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f)
        }
        val bounds = RectF(0f, 0f, raw.width.toFloat(), raw.height.toFloat()).also(matrix::mapRect)
        matrix.postTranslate(-bounds.left, -bounds.top)
        val out = reuse(uprightFrame, bounds.width().roundToInt(), bounds.height().roundToInt())
        uprightFrame = out
        Canvas(out).drawBitmap(raw, matrix, framePaint)
        return out
    }

    private fun reuse(bitmap: Bitmap?, width: Int, height: Int): Bitmap =
        bitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

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
        // The detector sees the mirrored frame, so its "Right" is the user's left hand.
        val hand = handedness.update(isRight(0)?.let { !it }, GestureFingerprint.shapeIsRight(first))
        val detectorRight = hand?.let { it == GestureFingerprint.Hand.LEFT } ?: isRight(0)
        var fp = GestureFingerprint.fromLandmarks(first, detectorRight) ?: run { reset(); return }
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

        fp = fp.copy(hand = hand)

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

        val locked = lock.update(fp, motion, tracker.isStill(GestureLock.COMMIT_FRAMES))
        _state.value = if (locked != null) {
            Timber.d("Locked gesture %s | %s", locked.code, locked.label)
            State.Locked(locked)
        } else State.Detecting(fp, lock.stability)
    }

    companion object {
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val PALM_VOTES = 9
    }
}

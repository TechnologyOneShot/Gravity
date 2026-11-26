package com.gravity.ide.capture

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gravity.ide.agent.TaskForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/**
 * Real screen capture through MediaProjection, started only after the user accepts the
 * system consent dialog. While a session is active, any app in the foreground — including
 * an app Gravity just built and launched — can be captured.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var width = 0
    private var height = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            ScreenCaptureController.setState(CaptureState.Error("Screen capture permission was not granted"))
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+: the foreground service must be running before getMediaProjection().
        TaskForegroundService.ensureChannel(this)
        val notification = NotificationCompat.Builder(this, TaskForegroundService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Yamini screen capture active")
            .setContentText("Used for app testing screenshots. Stop it from the Testing Center.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            stopCapture() // a new consent token replaces any previous session
            startCapture(resultCode, data)
            ScreenCaptureController.attach(this)
            ScreenCaptureController.setState(CaptureState.Active(width, height))
        } catch (e: Exception) {
            ScreenCaptureController.setState(CaptureState.Error("Could not start capture: ${e.message}"))
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("MediaProjection unavailable")
        projection = mp
        val handlerThread = HandlerThread("gravity-capture").also { it.start() }
        thread = handlerThread
        val handler = Handler(handlerThread.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
                ScreenCaptureController.setState(CaptureState.Idle)
                stopSelf()
            }
        }, handler)

        val metrics = DisplayMetrics()
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        display = mp.createVirtualDisplay(
            "gravity-capture", width, height, metrics.densityDpi.takeIf { it > 0 } ?: resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler,
        )
    }

    /** Grabs the most recent frame. Waits briefly for a frame if none is buffered yet. */
    suspend fun grab(): Bitmap {
        val r = reader ?: throw IllegalStateException("Capture is not active")
        var image = r.acquireLatestImage()
        if (image == null) {
            val ready = CompletableDeferred<Unit>()
            r.setOnImageAvailableListener({ ready.complete(Unit) }, thread?.let { Handler(it.looper) })
            withTimeout(3000) { ready.await() }
            r.setOnImageAvailableListener(null, null)
            image = r.acquireLatestImage() ?: throw IllegalStateException("No frame available")
        }
        image.use { img ->
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * img.width
            val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
            if (cropped !== padded) padded.recycle()
            return cropped
        }
    }

    private fun stopCapture() {
        ScreenCaptureController.detach(this)
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        thread?.quitSafely()
        display = null
        reader = null
        projection = null
        thread = null
    }

    override fun onDestroy() {
        stopCapture()
        if (ScreenCaptureController.state.value is CaptureState.Active) ScreenCaptureController.setState(CaptureState.Idle)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4301
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "com.gravity.ide.capture.STOP"

        fun start(context: Context, resultCode: Int, data: Intent) {
            // Only start as a foreground service with real consent: the service cannot call
            // startForeground(mediaProjection) without it, and Android crashes the app if a
            // foreground-service start is not followed by startForeground().
            if (resultCode != Activity.RESULT_OK) {
                ScreenCaptureController.setState(CaptureState.Error("Screen capture permission was not granted"))
                return
            }
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Active(val width: Int, val height: Int) : CaptureState
    data class Error(val message: String) : CaptureState
}

object ScreenCaptureController {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    @Volatile private var service: ScreenCaptureService? = null

    internal fun attach(s: ScreenCaptureService) { service = s }
    internal fun detach(s: ScreenCaptureService) { if (service === s) service = null }
    internal fun setState(s: CaptureState) { _state.value = s }

    val isActive: Boolean get() = service != null

    suspend fun capture(): Bitmap = (service ?: throw IllegalStateException("Start a screen capture session first (Testing Center → Start capture)")).grab()
}

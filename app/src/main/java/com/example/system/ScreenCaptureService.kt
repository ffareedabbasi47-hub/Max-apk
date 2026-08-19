package com.example.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume

// BUGFIX (bug #2 — API compatibility audit): getParcelableExtra(String) is
// deprecated on API 33+ in favor of getParcelableExtra(String, Class) --
// the old single-arg form still works correctly on every API level
// (including this project's minSdk 24), it's a deprecation warning, not a
// functional bug, but this version-gated helper follows the officially
// recommended pattern and removes the warning cleanly.
private fun <T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String, clazz: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}

/**
 * PHASE 10 — REAL screen capture via Android's MediaProjection API.
 *
 * Consent flow (cannot be bypassed, by Android design):
 *   MainActivity launches MediaProjectionManager.createScreenCaptureIntent()
 *   -> the SYSTEM shows the "Start recording or casting?" dialog
 *   -> only on a positive result does MainActivity start this service,
 *      passing the resultCode + result Intent data it got back.
 *
 * This service holds the MediaProjection alive only while a persistent,
 * visible foreground notification is showing (Android enforces this --
 * there is no way to capture the screen silently in the background). The
 * notification has a STOP action that immediately tears everything down.
 *
 * It captures single frames on demand (captureFrame) rather than a
 * continuous stream, which is what "MAX, screen par kya hai?" / vision
 * analysis actually needs. A continuous encode/stream pipeline (for a live
 * remote "screen share to someone") is a materially bigger feature
 * (video encoding, transport) and is intentionally NOT claimed here.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = intent?.getParcelableExtraCompat(EXTRA_RESULT_DATA, Intent::class.java)
        screenWidth = intent?.getIntExtra(EXTRA_WIDTH, 1080) ?: 1080
        screenHeight = intent?.getIntExtra(EXTRA_HEIGHT, 1920) ?: 1920
        screenDensity = intent?.getIntExtra(EXTRA_DENSITY, 420) ?: 420

        if (resultData == null) {
            Log.e(TAG, "ScreenCaptureService started without valid MediaProjection consent data -- refusing to capture.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null despite a result -- consent may have been revoked.")
            stopCapture()
            return START_NOT_STICKY
        }

        // Android 14+ requires registering a callback before creating the
        // VirtualDisplay, or the system kills the projection.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by the system/user.")
                stopCapture()
            }
        }, null)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "MaxScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isActive = true
        instance = this
        Log.i(TAG, "Screen capture active: ${screenWidth}x${screenHeight}")
        return START_NOT_STICKY
    }

    /**
     * Captures exactly one frame as a Bitmap. Returns null if capture isn't
     * active or the frame couldn't be read in time -- callers (the
     * ViewModel) must report that honestly rather than fabricating a
     * result, per the "no fake features" rule.
     */
    suspend fun captureFrame(): Bitmap? {
        val reader = imageReader ?: return null
        return suspendCancellableCoroutine { cont ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val task = Runnable {
                val image: Image? = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (image == null) {
                    if (cont.isActive) cont.resume(null)
                    return@Runnable
                }
                try {
                    val planes = image.planes
                    val buffer: ByteBuffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    bitmap.recycle()
                    if (cont.isActive) cont.resume(cropped)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode captured frame: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                } finally {
                    image.close()
                }
            }
            try {
                // Give the display a brief moment to actually paint into the
                // reader's surface before we try to acquire a frame.
                handler.postDelayed(task, 120)
                // BUGFIX (lifecycle/coroutine safety): if the calling
                // coroutine is cancelled (e.g. the ViewModel scope is torn
                // down, or the user backs out) before this fires, cancel the
                // pending Handler callback instead of letting it run against
                // a possibly-closed ImageReader/dead service.
                cont.invokeOnCancellation { handler.removeCallbacks(task) }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun stopCapture() {
        isActive = false
        instance = null
        try { virtualDisplay?.release() } catch (e: Exception) { /* ignore */ }
        try { imageReader?.close() } catch (e: Exception) { /* ignore */ }
        try { mediaProjection?.stop() } catch (e: Exception) { /* ignore */ }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "MAX Screen Capture", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shown whenever MAX is actively capturing your screen."
            }
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAX is viewing your screen")
            .setContentText("Tap Stop to end screen capture immediately.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "max_screen_capture_channel"
        private const val NOTIFICATION_ID = 3001

        const val ACTION_STOP = "com.example.SCREEN_CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DENSITY = "extra_density"

        // Observed by the UI (Phase 10 requirement: clearly show when active)
        // and by SystemControlManager to know whether a capture is possible.
        // (Not `private set`: the outer service instance methods assign these
        // directly, and Kotlin's private-setter scoping on companion objects
        // is easy to get wrong across class/companion boundaries -- keeping
        // it a plain var here avoids that footgun. Nothing outside this file
        // has any reason to write these.)
        @Volatile
        var isActive: Boolean = false

        @Volatile
        var instance: ScreenCaptureService? = null
    }
}

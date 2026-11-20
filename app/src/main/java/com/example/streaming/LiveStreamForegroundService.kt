package com.example.streaming

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.R

/**
 * Android 14+ compliant Foreground Service managing Camera Live and MediaProjection Screen Live
 * alongside the RTMP/RTMPS hardware publisher.
 */
class LiveStreamForegroundService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Tech Live Broadcast"
        val isScreen = intent?.getBooleanExtra(EXTRA_IS_SCREEN, false) ?: false

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                title = title,
                status = if (isScreen) "Screen Live + Audio active" else "Camera Live active"
            )
        )

        if (isScreen) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            val rtmpUrl = intent?.getStringExtra(EXTRA_RTMP_URL).orEmpty()
            val width = intent?.getIntExtra(EXTRA_WIDTH, 1280) ?: 1280
            val height = intent?.getIntExtra(EXTRA_HEIGHT, 720) ?: 720
            val fps = intent?.getIntExtra(EXTRA_FPS, 30) ?: 30
            val bitrateKbps = intent?.getIntExtra(EXTRA_BITRATE_KBPS, 4500) ?: 4500
            val audioBitrateKbps = intent?.getIntExtra(EXTRA_AUDIO_BITRATE_KBPS, 128) ?: 128
            val micOn = intent?.getBooleanExtra(EXTRA_MIC_ON, true) ?: true
            val devAudioOn = intent?.getBooleanExtra(EXTRA_DEVICE_AUDIO_ON, false) ?: false

            if (resultCode == Activity.RESULT_OK && resultData != null && rtmpUrl.isNotBlank()) {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, resultData)?.also { proj ->
                    val surface = sharedPublisher.prepareVideoSurface(width, height, fps, bitrateKbps)
                    if (surface != null) {
                        virtualDisplay = proj.createVirtualDisplay(
                            "TechLiveScreenCapture",
                            width,
                            height,
                            resources.displayMetrics.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            surface,
                            null,
                            null
                        )
                        sharedPublisher.startPublishing(
                            rtmpTargetUrl = rtmpUrl,
                            audioBitrateKbps = audioBitrateKbps,
                            targetFps = fps,
                            targetBitrateKbps = bitrateKbps,
                            captureMic = micOn,
                            captureDeviceAudio = devAudioOn,
                            mediaProjection = proj
                        )
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sharedPublisher.stopPublishing()
        virtualDisplay?.release()
        mediaProjection?.stop()
        virtualDisplay = null
        mediaProjection = null
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Live Broadcast",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun buildNotification(title: String, status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(true)
            .build()

    companion object {
        val sharedPublisher = RtmpStreamPublisher()

        const val CHANNEL_ID = "techlive_broadcast_channel"
        const val NOTIFICATION_ID = 4101
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IS_SCREEN = "extra_is_screen"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_RTMP_URL = "extra_rtmp_url"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE_KBPS = "extra_bitrate_kbps"
        const val EXTRA_AUDIO_BITRATE_KBPS = "extra_audio_bitrate_kbps"
        const val EXTRA_MIC_ON = "extra_mic_on"
        const val EXTRA_DEVICE_AUDIO_ON = "extra_device_audio_on"
    }
}

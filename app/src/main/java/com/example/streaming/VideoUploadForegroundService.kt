package com.example.streaming

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import com.example.R

data class VideoFileMetadata(
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val durationSeconds: Long,
    val resolution: String,
    val fps: Long
)

object VideoMetadataInspector {
    const val MAX_UPLOAD_BYTES = 1_073_741_824L // 1 GB

    fun inspect(context: Context, uri: Uri): Result<VideoFileMetadata> {
        return try {
            val cr = context.contentResolver
            val mime = cr.getType(uri) ?: "video/mp4"
            if (mime != "video/mp4" && mime != "video/quicktime") {
                return Result.failure(IllegalArgumentException("Only MP4 and MOV videos are supported for cloud streaming."))
            }

            var name = "video_${System.currentTimeMillis()}.mp4"
            var size = 0L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                }
            }

            if (size <= 0L) {
                return Result.failure(IllegalArgumentException("Unable to verify video file size."))
            }
            if (size > MAX_UPLOAD_BYTES) {
                return Result.failure(IllegalArgumentException("File exceeds the 1 GB maximum upload limit."))
            }

            val retriever = MediaMetadataRetriever()
            var durationSec = 0L
            var width = 1920
            var height = 1080
            try {
                retriever.setDataSource(context, uri)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                durationSec = (durMs / 1000L).coerceAtLeast(1L)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            } finally {
                retriever.release()
            }

            Result.success(
                VideoFileMetadata(
                    fileName = name,
                    mimeType = mime,
                    fileSizeBytes = size,
                    durationSeconds = durationSec,
                    resolution = "${width}x${height}",
                    fps = 30L
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class VideoUploadForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Video Uploads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Uploading Prerecorded Video")
            .setContentText("Resumable cloud upload in progress...")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL_ID = "techlive_upload_channel"
        const val NOTIFICATION_ID = 4102
    }
}

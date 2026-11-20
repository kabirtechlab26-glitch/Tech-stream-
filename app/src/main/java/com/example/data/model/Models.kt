package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class PlanType(val displayName: String, val defaultPriceBdt: Long) {
    FREE("Free", 0L),
    MONTHLY("Monthly", 100L),
    LIFETIME("Lifetime", 1500L);

    companion object {
        fun safeFrom(raw: String?): PlanType =
            entries.firstOrNull { it.name == raw } ?: FREE
    }
}

enum class UserRole {
    USER, ADMIN, SUPER_ADMIN;

    companion object {
        fun safeFrom(raw: String?): UserRole =
            entries.firstOrNull { it.name == raw } ?: USER
    }
}

enum class AccountStatus {
    ACTIVE, SUSPENDED, BANNED;

    companion object {
        fun safeFrom(raw: String?): AccountStatus =
            entries.firstOrNull { it.name == raw } ?: ACTIVE
    }
}

enum class DestinationPlatform(
    val displayName: String,
    val maxResolution: String,
    val allowedFps: List<Int>,
    val defaultUrl: String,
    val oauthRequired: Boolean
) {
    YOUTUBE(
        displayName = "YouTube",
        maxResolution = "2160p",
        allowedFps = listOf(24, 30, 60),
        defaultUrl = "rtmps://a.rtmp.youtube.com/live2",
        oauthRequired = true
    ),
    FACEBOOK(
        displayName = "Facebook Live",
        maxResolution = "1080p",
        allowedFps = listOf(24, 30, 60),
        defaultUrl = "rtmps://live-api-s.facebook.com:443/rtmp/",
        oauthRequired = true
    ),
    TIKTOK(
        displayName = "TikTok Live",
        maxResolution = "1080p",
        allowedFps = listOf(24, 30, 60),
        defaultUrl = "",
        oauthRequired = true
    ),
    TELEGRAM(
        displayName = "Telegram Live",
        maxResolution = "1080p",
        allowedFps = listOf(24, 30),
        defaultUrl = "rtmps://dc4-1.rtmp.t.me/s/",
        oauthRequired = false
    ),
    CUSTOM_RTMP(
        displayName = "Custom RTMP",
        maxResolution = "2160p",
        allowedFps = listOf(24, 30, 60),
        defaultUrl = "rtmp://",
        oauthRequired = false
    ),
    CUSTOM_RTMPS(
        displayName = "Custom RTMPS",
        maxResolution = "2160p",
        allowedFps = listOf(24, 30, 60),
        defaultUrl = "rtmps://",
        oauthRequired = false
    );

    companion object {
        fun safeFrom(raw: String?): DestinationPlatform =
            entries.firstOrNull { it.name == raw } ?: CUSTOM_RTMP
    }
}

enum class OrientationMode(val label: String, val ratioLabel: String) {
    LANDSCAPE_16_9("Landscape", "16:9"),
    PORTRAIT_9_16("Portrait", "9:16");

    companion object {
        fun safeFrom(raw: String?): OrientationMode =
            entries.firstOrNull { it.name == raw } ?: LANDSCAPE_16_9
    }
}

enum class AspectFitMode(val label: String) {
    FIT("Fit"),
    CROP("Crop"),
    STRETCH("Stretch"),
    BLUR_BACKGROUND("Blur Background");

    companion object {
        fun safeFrom(raw: String?): AspectFitMode =
            entries.firstOrNull { it.name == raw } ?: FIT
    }
}

enum class LoopMode(val label: String) {
    ONCE("Play Once"),
    LOOP_2("Loop 2 times"),
    LOOP_5("Loop 5 times"),
    LOOP_10("Loop 10 times"),
    UNLIMITED("Unlimited Loop");

    companion object {
        fun safeFrom(raw: String?): LoopMode =
            entries.firstOrNull { it.name == raw } ?: ONCE
    }
}

enum class StreamSourceType(val label: String) {
    CAMERA("Camera Live"),
    SCREEN("Screen Live"),
    PRERECORDED_CLOUD("Cloud Video");

    companion object {
        fun safeFrom(raw: String?): StreamSourceType =
            entries.firstOrNull { it.name == raw } ?: CAMERA
    }
}

enum class StreamStatus {
    PREPARING, RUNNING, RECONNECTING, STOPPED, COMPLETED, FAILED, SCHEDULED;

    companion object {
        fun safeFrom(raw: String?): StreamStatus =
            entries.firstOrNull { it.name == raw } ?: STOPPED
    }
}

enum class VideoStatus(val label: String) {
    UPLOADING("Uploading..."),
    PROCESSING("Processing..."),
    VALIDATING("Validating Video..."),
    GENERATING_THUMBNAIL("Generating Thumbnail..."),
    PREPARING_STREAM("Preparing Stream..."),
    READY("Ready to stream"),
    FAILED("Processing failed");

    companion object {
        fun safeFrom(raw: String?): VideoStatus =
            entries.firstOrNull { it.name == raw } ?: UPLOADING
    }
}

enum class PaymentStatus {
    PENDING, PAID, FAILED, REJECTED, REFUNDED;

    companion object {
        fun safeFrom(raw: String?): PaymentStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class PaymentMethod(val displayName: String) {
    BKASH("bKash"),
    NAGAD("Nagad"),
    CARD("Card");

    companion object {
        fun safeFrom(raw: String?): PaymentMethod =
            entries.firstOrNull { it.name == raw } ?: BKASH
    }
}

@Keep
@IgnoreExtraProperties
data class SystemSettings(
    @DocumentId val id: String = "",
    val freeDailyLimitSeconds: Long = 10800L, // 3 hours
    val monthlyPriceBdt: Long = 100L,
    val lifetimePriceBdt: Long = 1500L,
    val maxUploadSizeMb: Long = 1024L, // 1 GB
    val freeStorageLimitMb: Long = 500L,
    val monthlyStorageLimitMb: Long = 10240L, // 10 GB
    val lifetimeStorageLimitMb: Long = 51200L, // 50 GB
    val maxStreamDurationHours: Long = 168L, // 7 days
    val maxConcurrentStreams: Long = 3L,
    val allowedResolutions: List<String> = listOf("360p", "480p", "720p", "1080p", "1440p", "2160p"),
    val allowedFps: List<Long> = listOf(24L, 30L, 60L),
    val maintenanceMode: Boolean = false,
    val announcementMessage: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val role: String = "USER",
    val accountStatus: String = "ACTIVE",
    val plan: String = "FREE",
    val subscriptionStatus: String = "ACTIVE",
    val subscriptionStartAt: Timestamp? = null,
    val subscriptionExpiryAt: Timestamp? = null,
    val dailyLiveUsedSeconds: Long = 0L,
    val dailyUsageDate: String = "",
    val storageUsedBytes: Long = 0L,
    val storageLimitBytes: Long = 524288000L,
    val termsAccepted: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class StreamDestination(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val platform: String = "YOUTUBE",
    val protocol: String = "RTMPS",
    val rtmpUrl: String = "",
    val maskedStreamKey: String = "",
    val encryptedKeyRef: String? = null,
    val channelName: String? = null,
    val connectionStatus: String = "CONNECTED",
    val maxResolution: String = "1080p",
    val maxFps: Long = 60L,
    val supports60Fps: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class StreamPreset(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val resolution: String = "1080p",
    val fps: Long = 60L,
    val bitrateKbps: Long = 6000L,
    val audioBitrateKbps: Long = 160L,
    val orientation: String = "LANDSCAPE_16_9",
    val aspectFitMode: String = "FIT",
    val destinationNames: List<String> = emptyList(),
    val microphoneEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val encoderProfile: String = "H264_HIGH",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VideoAsset(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val fileName: String = "",
    val mimeType: String = "video/mp4",
    val fileSizeBytes: Long = 0L,
    val durationSeconds: Long = 0L,
    val resolution: String = "1920x1080",
    val fps: Long = 30L,
    val codec: String = "h264",
    val audioCodec: String = "aac",
    val status: String = "UPLOADING",
    val uploadProgress: Long = 0L,
    val resumableSessionUri: String? = null,
    val storagePath: String? = null,
    val thumbnailUrl: String? = null,
    val errorMessage: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class StreamSession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val description: String? = null,
    val sourceType: String = "CAMERA",
    val videoId: String? = null,
    val videoTitle: String? = null,
    val destinations: List<String> = emptyList(),
    val orientation: String = "LANDSCAPE_16_9",
    val aspectFitMode: String = "FIT",
    val resolution: String = "1080p",
    val fps: Long = 30L,
    val bitrateKbps: Long = 4500L,
    val audioBitrateKbps: Long = 128L,
    val status: String = "RUNNING",
    val cloudStream: Boolean = false,
    val durationLimitSeconds: Long = 10800L,
    val loopMode: String = "ONCE",
    val loopCount: Long = 0L,
    val scheduledStartAt: Timestamp? = null,
    val recurringRule: String? = null,
    val startedAt: Timestamp? = null,
    val endedAt: Timestamp? = null,
    val elapsedSeconds: Long = 0L,
    val currentBitrateKbps: Long = 0L,
    val currentFps: Long = 0L,
    val droppedFramesPct: Double = 0.0,
    val connectionQuality: String? = null,
    val workerCpuPct: Long = 0L,
    val workerRamPct: Long = 0L,
    val reconnectAttempts: Long = 0L,
    val bytesTransferred: Long = 0L,
    val errorMessage: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UserNotification(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val message: String = "",
    val category: String = "STREAM",
    val read: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PaymentTransaction(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val userName: String = "",
    val plan: String = "MONTHLY",
    val amountBdt: Long = 100L,
    val currency: String = "BDT",
    val paymentMethod: String = "BKASH",
    val transactionReference: String = "",
    val gatewayMode: String = "MANUAL",
    val status: String = "PENDING",
    val adminNote: String? = null,
    val verifiedBy: String? = null,
    val verifiedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class WithdrawalRequest(
    @DocumentId val id: String = "",
    val adminId: String = "",
    val amountBdt: Long = 0L,
    val method: String = "",
    val destination: String = "",
    val status: String = "PENDING",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val actorId: String = "",
    val actorEmail: String? = null,
    val action: String = "",
    val targetId: String? = null,
    val severity: String = "INFO",
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AbuseReport(
    @DocumentId val id: String = "",
    val reporterId: String = "",
    val targetStreamId: String = "",
    val reason: String = "",
    val details: String = "",
    val status: String = "OPEN",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class TutorialQuestion(
    @DocumentId val id: String = "",
    val userId: String = "",
    val authorName: String = "",
    val streamId: String? = null,
    val streamTitle: String? = null,
    val topicTag: String = "Kotlin",
    val questionText: String = "",
    val upvotes: Long = 0L,
    val answered: Boolean = false,
    val answerText: String? = null,
    val answeredBy: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

object CapabilityValidator {
    private val resOrder = listOf("360p", "480p", "720p", "1080p", "1440p", "2160p")

    fun maxSupportedResolution(destinations: List<StreamDestination>, globalAllowed: List<String>): String {
        val allowedIdx = globalAllowed.mapNotNull { resOrder.indexOf(it).takeIf { i -> i >= 0 } }.maxOrNull() ?: 3
        if (destinations.isEmpty()) return resOrder[allowedIdx]
        val destMaxIdx = destinations.minOf { dest ->
            val idx = resOrder.indexOf(dest.maxResolution)
            if (idx >= 0) idx else 3
        }
        return resOrder[minOf(allowedIdx, destMaxIdx)]
    }

    fun allowedFpsFor(destinations: List<StreamDestination>, globalAllowed: List<Long>): List<Int> {
        val base = globalAllowed.map { it.toInt() }.ifEmpty { listOf(24, 30, 60) }
        if (destinations.isEmpty()) return base
        val supports60 = destinations.all { it.supports60Fps && it.maxFps >= 60L }
        return if (supports60) base else base.filter { it <= 30 }
    }

    fun isResolutionSupported(res: String, maxAllowed: String): Boolean {
        val rIdx = resOrder.indexOf(res)
        val mIdx = resOrder.indexOf(maxAllowed)
        return rIdx in 0..mIdx
    }
}

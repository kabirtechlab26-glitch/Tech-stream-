package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.*
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TechLiveRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // --- USER PROFILE & DAILY QUOTA ---

    suspend fun ensureUserProfileExists(): Result<UserProfile> {
        val user = auth.currentUser
            ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/${user.uid}"
        return try {
            val docRef = db.collection("users").document(user.uid)
            val snap = docRef.get().await()
            val today = todayKey()
            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to user.uid,
                    "name" to (user.displayName?.takeIf { it.isNotBlank() } ?: "Tech Live Creator"),
                    "email" to (user.email ?: "creator@techlive.local"),
                    "photoUrl" to user.photoUrl?.toString(),
                    "role" to "USER",
                    "accountStatus" to "ACTIVE",
                    "plan" to "FREE",
                    "subscriptionStatus" to "ACTIVE",
                    "dailyLiveUsedSeconds" to 0L,
                    "dailyUsageDate" to today,
                    "storageUsedBytes" to 0L,
                    "storageLimitBytes" to 524288000L, // 500 MB free
                    "termsAccepted" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                docRef.set(payload).await()
                val created = docRef.get().await().toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: UserProfile(userId = user.uid, name = user.displayName ?: "Creator", email = user.email ?: "")
                Result.success(created)
            } else {
                val profile = snap.toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: UserProfile(userId = user.uid)
                // Automatic daily free quota reset on new calendar day
                if (profile.dailyUsageDate != today) {
                    docRef.update(
                        mapOf(
                            "dailyLiveUsedSeconds" to 0L,
                            "dailyUsageDate" to today,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                Result.success(profile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user profile observation" }
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { it.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
        )
    }

    fun observeSystemSettings(): Flow<SystemSettings> =
        db.collection("system_settings").document("global")
            .snapshots()
            .map { snap ->
                snap.toObject(SystemSettings::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    ?: SystemSettings(id = "global")
            }

    suspend fun recordDailyLiveUsage(userId: String, additionalSeconds: Long): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val docRef = db.collection("users").document(userId)
            val snap = docRef.get().await()
            val today = todayKey()
            val currentDate = snap.getString("dailyUsageDate") ?: today
            val currentUsed = if (currentDate == today) (snap.getLong("dailyLiveUsedSeconds") ?: 0L) else 0L
            val nextUsed = (currentUsed + additionalSeconds).coerceIn(0L, 86400L)
            docRef.update(
                mapOf(
                    "dailyLiveUsedSeconds" to nextUsed,
                    "dailyUsageDate" to today,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteOwnAccountData(userId: String): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).delete().await()
            auth.currentUser?.delete()?.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- DESTINATIONS ---

    fun observeDestinations(userId: String): Flow<List<StreamDestination>> =
        db.collection("users").document(userId).collection("destinations")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { it.toObjects(StreamDestination::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    suspend fun addDestination(
        userId: String,
        destId: String,
        name: String,
        platform: DestinationPlatform,
        protocol: String,
        rtmpUrl: String,
        maskedStreamKey: String,
        encryptedKeyRef: String?,
        channelName: String?
    ): Result<Unit> {
        val path = "users/$userId/destinations/$destId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val status = if (platform == DestinationPlatform.TIKTOK) "UNAVAILABLE" else "CONNECTED"
            val payload = mapOf(
                "userId" to userId,
                "name" to name.trim().take(80),
                "platform" to platform.name,
                "protocol" to protocol,
                "rtmpUrl" to rtmpUrl.trim().take(400),
                "maskedStreamKey" to maskedStreamKey.take(64),
                "encryptedKeyRef" to encryptedKeyRef,
                "channelName" to channelName?.take(120),
                "connectionStatus" to status,
                "maxResolution" to platform.maxResolution,
                "maxFps" to (platform.allowedFps.maxOrNull() ?: 30).toLong(),
                "supports60Fps" to platform.allowedFps.contains(60),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            db.collection("users").document(userId).collection("destinations").document(destId)
                .set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteDestination(userId: String, destinationId: String): Result<Unit> {
        val path = "users/$userId/destinations/$destinationId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).collection("destinations").document(destinationId)
                .delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- PRESETS / STREAM TEMPLATES ---

    fun observePresets(userId: String): Flow<List<StreamPreset>> =
        db.collection("users").document(userId).collection("presets")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { it.toObjects(StreamPreset::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    suspend fun savePreset(userId: String, preset: StreamPreset): Result<Unit> {
        val isNew = preset.id.isBlank()
        val docRef = if (isNew) {
            db.collection("users").document(userId).collection("presets").document()
        } else {
            db.collection("users").document(userId).collection("presets").document(preset.id)
        }
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val payload = mutableMapOf<String, Any>(
                "userId" to userId,
                "name" to preset.name.trim().take(80),
                "resolution" to preset.resolution,
                "fps" to preset.fps,
                "bitrateKbps" to preset.bitrateKbps,
                "audioBitrateKbps" to preset.audioBitrateKbps,
                "orientation" to preset.orientation,
                "aspectFitMode" to preset.aspectFitMode,
                "destinationNames" to preset.destinationNames.take(10),
                "microphoneEnabled" to preset.microphoneEnabled,
                "cameraEnabled" to preset.cameraEnabled,
                "encoderProfile" to preset.encoderProfile,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (isNew) {
                payload["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(payload).await()
            } else {
                docRef.update(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deletePreset(userId: String, presetId: String): Result<Unit> {
        val path = "users/$userId/presets/$presetId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).collection("presets").document(presetId)
                .delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- VIDEOS ---

    fun observeVideos(userId: String): Flow<List<VideoAsset>> =
        db.collection("users").document(userId).collection("videos")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { it.toObjects(VideoAsset::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    suspend fun createOrUpdateVideo(userId: String, videoId: String, video: VideoAsset, isNew: Boolean): Result<Unit> {
        val docRef = db.collection("users").document(userId).collection("videos").document(videoId)
        return try {
            val uid = requireUserId()
            check(uid == userId)
            if (isNew) {
                val payload = mapOf(
                    "userId" to userId,
                    "title" to video.title.take(120),
                    "fileName" to video.fileName.take(200),
                    "mimeType" to video.mimeType.take(80),
                    "fileSizeBytes" to video.fileSizeBytes,
                    "durationSeconds" to video.durationSeconds,
                    "resolution" to video.resolution.take(24),
                    "fps" to video.fps,
                    "codec" to video.codec.take(40),
                    "audioCodec" to video.audioCodec.take(40),
                    "status" to video.status,
                    "uploadProgress" to video.uploadProgress,
                    "resumableSessionUri" to video.resumableSessionUri,
                    "storagePath" to video.storagePath,
                    "thumbnailUrl" to video.thumbnailUrl,
                    "errorMessage" to video.errorMessage,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                docRef.set(payload).await()
            } else {
                val updateMap = mapOf(
                    "title" to video.title.take(120),
                    "durationSeconds" to video.durationSeconds,
                    "resolution" to video.resolution.take(24),
                    "fps" to video.fps,
                    "codec" to video.codec.take(40),
                    "audioCodec" to video.audioCodec.take(40),
                    "status" to video.status,
                    "uploadProgress" to video.uploadProgress,
                    "resumableSessionUri" to video.resumableSessionUri,
                    "storagePath" to video.storagePath,
                    "thumbnailUrl" to video.thumbnailUrl,
                    "errorMessage" to video.errorMessage,
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                docRef.update(updateMap).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun renameVideo(userId: String, videoId: String, newTitle: String): Result<Unit> {
        val path = "users/$userId/videos/$videoId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).collection("videos").document(videoId)
                .update(
                    mapOf(
                        "title" to newTitle.trim().take(120),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteVideo(userId: String, video: VideoAsset): Result<Unit> {
        val path = "users/$userId/videos/${video.id}"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).collection("videos").document(video.id)
                .delete().await()
            val userRef = db.collection("users").document(userId)
            val snap = userRef.get().await()
            val currentUsed = snap.getLong("storageUsedBytes") ?: 0L
            userRef.update(
                mapOf(
                    "storageUsedBytes" to (currentUsed - video.fileSizeBytes).coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun adjustUserStorageBytes(userId: String, deltaBytes: Long): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val userRef = db.collection("users").document(userId)
            val current = userRef.get().await().getLong("storageUsedBytes") ?: 0L
            userRef.update(
                mapOf(
                    "storageUsedBytes" to (current + deltaBytes).coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- STREAMS & SCHEDULES ---

    fun observeStreams(userId: String): Flow<List<StreamSession>> =
        db.collection("users").document(userId).collection("streams")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { it.toObjects(StreamSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    suspend fun createStreamSession(userId: String, streamId: String, session: StreamSession): Result<Unit> {
        val docRef = db.collection("users").document(userId).collection("streams").document(streamId)
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val payload = mapOf(
                "userId" to userId,
                "title" to session.title.trim().take(140),
                "description" to session.description?.take(600),
                "sourceType" to session.sourceType,
                "videoId" to session.videoId,
                "videoTitle" to session.videoTitle,
                "destinations" to session.destinations.ifEmpty { listOf("Custom RTMP") }.take(10),
                "orientation" to session.orientation,
                "aspectFitMode" to session.aspectFitMode,
                "resolution" to session.resolution,
                "fps" to session.fps,
                "bitrateKbps" to session.bitrateKbps,
                "audioBitrateKbps" to session.audioBitrateKbps,
                "status" to session.status,
                "cloudStream" to session.cloudStream,
                "durationLimitSeconds" to session.durationLimitSeconds.coerceIn(60L, 604800L),
                "loopMode" to session.loopMode,
                "loopCount" to session.loopCount,
                "scheduledStartAt" to session.scheduledStartAt,
                "recurringRule" to session.recurringRule,
                "startedAt" to if (session.status == "RUNNING") FieldValue.serverTimestamp() else null,
                "elapsedSeconds" to session.elapsedSeconds,
                "currentBitrateKbps" to session.currentBitrateKbps,
                "currentFps" to session.currentFps,
                "droppedFramesPct" to session.droppedFramesPct,
                "connectionQuality" to session.connectionQuality,
                "workerCpuPct" to session.workerCpuPct,
                "workerRamPct" to session.workerRamPct,
                "reconnectAttempts" to session.reconnectAttempts,
                "bytesTransferred" to session.bytesTransferred,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateStreamTelemetry(
        userId: String,
        streamId: String,
        status: String,
        elapsedSeconds: Long,
        currentBitrateKbps: Long,
        currentFps: Long,
        droppedFramesPct: Double,
        connectionQuality: String,
        reconnectAttempts: Long,
        bytesTransferred: Long,
        errorMessage: String? = null
    ): Result<Unit> {
        val docRef = db.collection("users").document(userId).collection("streams").document(streamId)
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val updates = mutableMapOf<String, Any>(
                "status" to status,
                "elapsedSeconds" to elapsedSeconds,
                "currentBitrateKbps" to currentBitrateKbps,
                "currentFps" to currentFps,
                "droppedFramesPct" to droppedFramesPct,
                "connectionQuality" to connectionQuality,
                "reconnectAttempts" to reconnectAttempts,
                "bytesTransferred" to bytesTransferred,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (status in listOf("STOPPED", "COMPLETED", "FAILED")) {
                updates["endedAt"] = FieldValue.serverTimestamp()
            }
            if (!errorMessage.isNullOrBlank()) {
                updates["errorMessage"] = errorMessage.take(300)
            }
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteScheduledStream(userId: String, streamId: String): Result<Unit> {
        val path = "users/$userId/streams/$streamId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).collection("streams").document(streamId)
                .delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- NOTIFICATIONS ---

    fun observeNotifications(userId: String): Flow<List<UserNotification>> =
        db.collection("users").document(userId).collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .snapshots()
            .map { it.toObjects(UserNotification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    suspend fun pushNotification(userId: String, title: String, message: String, category: String): Result<Unit> {
        val docRef = db.collection("users").document(userId).collection("notifications").document()
        return try {
            val payload = mapOf(
                "userId" to userId,
                "title" to title.take(120),
                "message" to message.take(400),
                "category" to category.take(40),
                "read" to false,
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun markNotificationRead(userId: String, notificationId: String): Result<Unit> {
        val path = "users/$userId/notifications/$notificationId"
        return try {
            db.document(path).update("read", true).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- PAYMENTS (NEVER SELF-APPROVED) ---

    fun observeUserPayments(userId: String): Flow<List<PaymentTransaction>> =
        db.collection("payments")
            .whereEqualTo("userId", userId)
            .snapshots()
            .map { snap ->
                snap.toObjects(PaymentTransaction::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }

    suspend fun submitManualOrGatewayPayment(
        userId: String,
        userEmail: String,
        userName: String,
        plan: PlanType,
        amountBdt: Long,
        method: PaymentMethod,
        trxReference: String,
        gatewayMode: String
    ): Result<Unit> {
        val docRef = db.collection("payments").document()
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val payload = mapOf(
                "userId" to userId,
                "userEmail" to userEmail.take(200),
                "userName" to userName.take(120),
                "plan" to plan.name,
                "amountBdt" to amountBdt,
                "currency" to "BDT",
                "paymentMethod" to method.name,
                "transactionReference" to trxReference.trim().take(100),
                "gatewayMode" to gatewayMode,
                "status" to "PENDING",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            pushNotification(
                userId = userId,
                title = "Payment Verification Pending",
                message = "Your ${plan.displayName} payment (${method.displayName}, TRX: ${trxReference.trim()}) is awaiting backend/admin verification.",
                category = "PAYMENT"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    // --- COMMUNITY TUTORIAL Q&A ---

    fun observeTutorialQuestions(): Flow<List<TutorialQuestion>> =
        db.collection("tutorial_qa")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(60)
            .snapshots()
            .map { it.toObjects(TutorialQuestion::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    suspend fun postTutorialQuestion(
        userId: String,
        authorName: String,
        topicTag: String,
        questionText: String,
        streamId: String? = null,
        streamTitle: String? = null
    ): Result<Unit> {
        val docRef = db.collection("tutorial_qa").document()
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val payload = mapOf(
                "userId" to userId,
                "authorName" to authorName.take(120),
                "streamId" to streamId,
                "streamTitle" to streamTitle?.take(140),
                "topicTag" to topicTag.take(40),
                "questionText" to questionText.trim().take(500),
                "upvotes" to 0L,
                "answered" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upvoteTutorialQuestion(questionId: String): Result<Unit> {
        val path = "tutorial_qa/$questionId"
        return try {
            requireUserId()
            db.document(path).update(
                mapOf(
                    "upvotes" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun answerTutorialQuestion(questionId: String, answerText: String, answeredBy: String): Result<Unit> {
        val path = "tutorial_qa/$questionId"
        return try {
            requireUserId()
            db.document(path).update(
                mapOf(
                    "answered" to true,
                    "answerText" to answerText.trim().take(800),
                    "answeredBy" to answeredBy.take(120),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- ABUSE REPORT ---

    suspend fun submitAbuseReport(userId: String, targetStreamId: String, reason: String, details: String): Result<Unit> {
        val docRef = db.collection("reports").document()
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val payload = mapOf(
                "reporterId" to userId,
                "targetStreamId" to targetStreamId.take(128),
                "reason" to reason.take(120),
                "details" to details.take(600),
                "status" to "OPEN",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    // --- ADMIN-ONLY OPERATIONS (PROTECTED BY FIRESTORE RULES) ---

    suspend fun checkIsAdmin(uid: String, email: String?): Boolean {
        if (email.equals("kabirtechlab26@gmail.com", ignoreCase = true) && auth.currentUser?.isEmailVerified == true) {
            return true
        }
        return try {
            db.collection("admins").document(uid).get().await().exists()
        } catch (_: Exception) {
            false
        }
    }

    fun observeAllUsersAdmin(): Flow<List<UserProfile>> =
        db.collection("users").snapshots()
            .map { it.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    fun observeAllPaymentsAdmin(): Flow<List<PaymentTransaction>> =
        db.collection("payments").snapshots()
            .map { snap ->
                snap.toObjects(PaymentTransaction::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }

    fun observeAllWithdrawalsAdmin(): Flow<List<WithdrawalRequest>> =
        db.collection("withdrawals").snapshots()
            .map { snap ->
                snap.toObjects(WithdrawalRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }

    fun observeAllReportsAdmin(): Flow<List<AbuseReport>> =
        db.collection("reports").snapshots()
            .map { it.toObjects(AbuseReport::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    fun observeAuditLogsAdmin(): Flow<List<AuditLog>> =
        db.collection("audit_logs")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(80)
            .snapshots()
            .map { it.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

    suspend fun adminVerifyPayment(payment: PaymentTransaction, approve: Boolean, note: String): Result<Unit> {
        val adminUid = requireUserId()
        val payRef = db.collection("payments").document(payment.id)
        return try {
            val nextStatus = if (approve) "PAID" else "REJECTED"
            payRef.update(
                mapOf(
                    "status" to nextStatus,
                    "adminNote" to note.take(300),
                    "verifiedBy" to adminUid,
                    "verifiedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            if (approve) {
                val storageBytes = if (payment.plan == "LIFETIME") 53687091200L else 10737418240L
                val userUpdates = mutableMapOf<String, Any>(
                    "plan" to payment.plan,
                    "subscriptionStatus" to "ACTIVE",
                    "subscriptionStartAt" to FieldValue.serverTimestamp(),
                    "storageLimitBytes" to storageBytes,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (payment.plan == "MONTHLY") {
                    val expiryMs = System.currentTimeMillis() + 30L * 86400_000L
                    userUpdates["subscriptionExpiryAt"] = Timestamp(Date(expiryMs))
                }
                db.collection("users").document(payment.userId).update(userUpdates).await()
                pushNotification(
                    userId = payment.userId,
                    title = "Subscription Activated",
                    message = "Your ${payment.plan} plan payment (${payment.amountBdt} BDT) has been verified!",
                    category = "SUBSCRIPTION"
                )
            } else {
                pushNotification(
                    userId = payment.userId,
                    title = "Payment Rejected",
                    message = "Your payment (${payment.transactionReference}) was rejected: $note",
                    category = "PAYMENT"
                )
            }
            logAuditEvent("PAYMENT_${nextStatus}", payment.id, "INFO", "Admin verified payment ${payment.transactionReference}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, payRef.path)
            Result.failure(e)
        }
    }

    suspend fun adminUpdateUserStatusAndPlan(
        targetUserId: String,
        accountStatus: AccountStatus,
        plan: PlanType
    ): Result<Unit> {
        val path = "users/$targetUserId"
        return try {
            val storageLimit = when (plan) {
                PlanType.FREE -> 524288000L
                PlanType.MONTHLY -> 10737418240L
                PlanType.LIFETIME -> 53687091200L
            }
            db.document(path).update(
                mapOf(
                    "accountStatus" to accountStatus.name,
                    "plan" to plan.name,
                    "storageLimitBytes" to storageLimit,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            logAuditEvent("ADMIN_USER_UPDATE", targetUserId, "WARNING", "Status=${accountStatus.name}, Plan=${plan.name}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminRequestWithdrawal(amountBdt: Long, method: String, destination: String): Result<Unit> {
        val adminUid = requireUserId()
        val docRef = db.collection("withdrawals").document()
        return try {
            docRef.set(
                mapOf(
                    "adminId" to adminUid,
                    "amountBdt" to amountBdt,
                    "method" to method.take(60),
                    "destination" to destination.take(200),
                    "status" to "PENDING",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            logAuditEvent("WITHDRAWAL_REQUEST", docRef.id, "INFO", "Requested $amountBdt BDT via $method")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun adminUpdateWithdrawalStatus(withdrawalId: String, nextStatus: String): Result<Unit> {
        val path = "withdrawals/$withdrawalId"
        return try {
            requireUserId()
            db.document(path).update(
                mapOf(
                    "status" to nextStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            logAuditEvent("WITHDRAWAL_${nextStatus}", withdrawalId, "INFO", "Updated withdrawal status to $nextStatus")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminResolveReport(reportId: String, nextStatus: String): Result<Unit> {
        val path = "reports/$reportId"
        return try {
            requireUserId()
            db.document(path).update(
                mapOf(
                    "status" to nextStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            logAuditEvent("REPORT_${nextStatus}", reportId, "INFO", "Marked abuse report $reportId as $nextStatus")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminSaveSystemSettings(settings: SystemSettings): Result<Unit> {
        val path = "system_settings/global"
        return try {
            requireUserId()
            db.document(path).set(
                mapOf(
                    "freeDailyLimitSeconds" to settings.freeDailyLimitSeconds,
                    "monthlyPriceBdt" to settings.monthlyPriceBdt,
                    "lifetimePriceBdt" to settings.lifetimePriceBdt,
                    "maxUploadSizeMb" to settings.maxUploadSizeMb,
                    "freeStorageLimitMb" to settings.freeStorageLimitMb,
                    "monthlyStorageLimitMb" to settings.monthlyStorageLimitMb,
                    "lifetimeStorageLimitMb" to settings.lifetimeStorageLimitMb,
                    "maxStreamDurationHours" to settings.maxStreamDurationHours,
                    "maxConcurrentStreams" to settings.maxConcurrentStreams,
                    "allowedResolutions" to settings.allowedResolutions,
                    "allowedFps" to settings.allowedFps,
                    "maintenanceMode" to settings.maintenanceMode,
                    "announcementMessage" to settings.announcementMessage.take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            logAuditEvent("SYSTEM_SETTINGS_UPDATED", "global", "WARNING", "Updated global SaaS pricing/limits")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun logAuditEvent(action: String, targetId: String?, severity: String, details: String) {
        val user = auth.currentUser ?: return
        val docRef = db.collection("audit_logs").document()
        try {
            docRef.set(
                mapOf(
                    "actorId" to user.uid,
                    "actorEmail" to user.email,
                    "action" to action.take(100),
                    "targetId" to targetId?.take(128),
                    "severity" to severity,
                    "details" to details.take(600),
                    "createdAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
            ).await()
        } catch (_: Exception) {
            // Do not crash UX if audit write fails offline
        }
    }
}

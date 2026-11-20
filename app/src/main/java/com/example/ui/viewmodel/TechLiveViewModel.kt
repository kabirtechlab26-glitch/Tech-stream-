package com.example.ui.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.network.BackendApiService
import com.example.data.network.CloudStreamStartRequest
import com.example.data.network.InitiateUploadRequest
import com.example.data.network.ServerHealthResponse
import com.example.data.repository.TechLiveRepository
import com.example.data.repository.toFriendlyErrorMessage
import com.example.data.security.SecureCredentialVault
import com.example.streaming.LiveStreamForegroundService
import com.example.streaming.StreamHealthTelemetry
import com.example.streaming.VideoMetadataInspector
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.Date
import java.util.UUID

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class NetworkBannerState {
    ONLINE, CONNECTING, OFFLINE, RESTORED
}

class TechLiveViewModel(
    private val repository: TechLiveRepository,
    private val vault: SecureCredentialVault,
    private val api: BackendApiService,
    private val currentUserId: String,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _darkThemeOverride = MutableStateFlow<Boolean?>(true)
    val darkThemeOverride: StateFlow<Boolean?> = _darkThemeOverride.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _networkState = MutableStateFlow(NetworkBannerState.ONLINE)
    val networkState: StateFlow<NetworkBannerState> = _networkState.asStateFlow()

    private val _activeStreamId = MutableStateFlow<String?>(null)
    val activeStreamId: StateFlow<String?> = _activeStreamId.asStateFlow()

    private val _adminServerHealth = MutableStateFlow<UiState<ServerHealthResponse>?>(null)
    val adminServerHealth: StateFlow<UiState<ServerHealthResponse>?> = _adminServerHealth.asStateFlow()

    val livePublisherTelemetry: StateFlow<StreamHealthTelemetry> =
        LiveStreamForegroundService.sharedPublisher.telemetry

    val systemSettings: StateFlow<SystemSettings> = repository.observeSystemSettings()
        .catch { error ->
            unwrapFirestoreCancellation(error)
            emit(SystemSettings(id = "global"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), SystemSettings(id = "global"))

    val userProfileState: StateFlow<UiState<UserProfile>> = repository.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile>> { profile ->
            if (profile != null) UiState.Success(profile) else UiState.Loading
        }
        .catch { error ->
            val root = unwrapFirestoreCancellation(error)
            emit(UiState.Error(toFriendlyErrorMessage(root, "Failed to load user profile.")))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val destinationsState: StateFlow<UiState<List<StreamDestination>>> =
        repository.observeDestinations(currentUserId).asUiState("destinations")

    val presetsState: StateFlow<UiState<List<StreamPreset>>> =
        repository.observePresets(currentUserId).asUiState("presets")

    val videosState: StateFlow<UiState<List<VideoAsset>>> =
        repository.observeVideos(currentUserId).asUiState("videos")

    val streamsState: StateFlow<UiState<List<StreamSession>>> =
        repository.observeStreams(currentUserId).asUiState("streams")

    val notificationsState: StateFlow<UiState<List<UserNotification>>> =
        repository.observeNotifications(currentUserId).asUiState("notifications")

    val paymentsState: StateFlow<UiState<List<PaymentTransaction>>> =
        repository.observeUserPayments(currentUserId).asUiState("payments")

    val tutorialQuestionsState: StateFlow<UiState<List<TutorialQuestion>>> =
        repository.observeTutorialQuestions().asUiState("community Q&A")

    // Admin flows — remain inactive unless _isAdmin == true
    @OptIn(ExperimentalCoroutinesApi::class)
    val adminUsers: StateFlow<List<UserProfile>> = _isAdmin
        .flatMapLatest { admin ->
            if (admin) repository.observeAllUsersAdmin().catch { emit(emptyList()) }
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val adminPayments: StateFlow<List<PaymentTransaction>> = _isAdmin
        .flatMapLatest { admin ->
            if (admin) repository.observeAllPaymentsAdmin().catch { emit(emptyList()) }
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val adminWithdrawals: StateFlow<List<WithdrawalRequest>> = _isAdmin
        .flatMapLatest { admin ->
            if (admin) repository.observeAllWithdrawalsAdmin().catch { emit(emptyList()) }
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val adminReports: StateFlow<List<AbuseReport>> = _isAdmin
        .flatMapLatest { admin ->
            if (admin) repository.observeAllReportsAdmin().catch { emit(emptyList()) }
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val adminAuditLogs: StateFlow<List<AuditLog>> = _isAdmin
        .flatMapLatest { admin ->
            if (admin) repository.observeAuditLogsAdmin().catch { emit(emptyList()) }
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private var quotaTickJob: Job? = null
    private var uploadJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            repository.ensureUserProfileExists()
            val email = auth.currentUser?.email
            _isAdmin.value = repository.checkIsAdmin(currentUserId, email)
        }
    }

    fun observeConnectivity(context: Context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        viewModelScope.launch {
            callbackFlow {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(true)
                    }

                    override fun onLost(network: Network) {
                        trySend(false)
                    }
                }
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
                val active = cm.activeNetwork != null
                trySend(active)
                awaitClose { cm.unregisterNetworkCallback(callback) }
            }.collect { connected ->
                val prev = _networkState.value
                if (!connected) {
                    _networkState.value = NetworkBannerState.OFFLINE
                } else if (prev == NetworkBannerState.OFFLINE || prev == NetworkBannerState.CONNECTING) {
                    _networkState.value = NetworkBannerState.RESTORED
                    delay(2500L)
                    if (_networkState.value == NetworkBannerState.RESTORED) {
                        _networkState.value = NetworkBannerState.ONLINE
                    }
                } else {
                    _networkState.value = NetworkBannerState.ONLINE
                }
            }
        }
    }

    fun toggleTheme() {
        val current = _darkThemeOverride.value ?: true
        _darkThemeOverride.value = !current
    }

    fun clearBannerMessage() {
        _bannerMessage.value = null
    }

    // --- QUOTA & ENTITLEMENT HELPERS ---

    fun isPaidOrAdmin(profile: UserProfile?): Boolean {
        if (_isAdmin.value) return true
        if (profile == null) return false
        return (profile.plan == "MONTHLY" || profile.plan == "LIFETIME") &&
            profile.subscriptionStatus == "ACTIVE"
    }

    fun canStartLiveStream(profile: UserProfile?, settings: SystemSettings): Pair<Boolean, String?> {
        if (profile == null) return false to "Loading your account..."
        if (profile.accountStatus != "ACTIVE") {
            return false to "Your account is currently ${profile.accountStatus.lowercase()}. Contact support."
        }
        if (settings.maintenanceMode && !_isAdmin.value) {
            return false to "Streaming is paused briefly for scheduled platform maintenance."
        }
        if (isPaidOrAdmin(profile)) return true to null
        val remaining = (settings.freeDailyLimitSeconds - profile.dailyLiveUsedSeconds).coerceAtLeast(0L)
        if (remaining <= 0L) {
            return false to "Your daily free streaming limit (03:00:00) has been reached. Upgrade to Monthly or Lifetime to continue."
        }
        return true to null
    }

    // --- DESTINATIONS & PRESETS ---

    fun addDestination(
        name: String,
        platform: DestinationPlatform,
        protocol: String,
        rtmpUrl: String,
        rawStreamKey: String,
        channelName: String?
    ) {
        viewModelScope.launch {
            if (name.isBlank() || rtmpUrl.isBlank() || rawStreamKey.isBlank()) {
                _bannerMessage.value = "Please fill in destination name, RTMP URL, and stream key."
                return@launch
            }
            if (platform == DestinationPlatform.TIKTOK) {
                _bannerMessage.value = "Direct TikTok API requires account eligibility. Saved as reference — use Custom RTMP/RTMPS if needed."
            }
            val destId = "dest_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val keyRef = vault.storeStreamKey(destId, rawStreamKey)
            val masked = vault.maskStreamKey(rawStreamKey)
            val result = repository.addDestination(
                userId = currentUserId,
                destId = destId,
                name = name,
                platform = platform,
                protocol = protocol,
                rtmpUrl = rtmpUrl,
                maskedStreamKey = masked,
                encryptedKeyRef = keyRef,
                channelName = channelName
            )
            _bannerMessage.value = if (result.isSuccess) {
                "Destination \"$name\" saved with encrypted stream key."
            } else {
                "Could not save destination. Please try again."
            }
        }
    }

    fun deleteDestination(destinationId: String) {
        viewModelScope.launch {
            vault.removeStreamKey(destinationId)
            repository.deleteDestination(currentUserId, destinationId)
        }
    }

    fun resolveFullRtmpTarget(destination: StreamDestination): String? {
        val rawKey = vault.readStreamKey(destination.id) ?: return null
        val base = destination.rtmpUrl.trimEnd('/')
        return "$base/$rawKey"
    }

    fun savePreset(preset: StreamPreset) {
        viewModelScope.launch {
            val res = repository.savePreset(currentUserId, preset)
            _bannerMessage.value = if (res.isSuccess) "Preset \"${preset.name}\" saved." else "Failed to save preset."
        }
    }

    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            repository.deletePreset(currentUserId, presetId)
        }
    }

    // --- LIVE STREAMING LIFECYCLE ---

    fun startCameraOrScreenSession(
        title: String,
        description: String,
        sourceType: StreamSourceType,
        selectedDestinations: List<StreamDestination>,
        orientation: OrientationMode,
        aspectFitMode: AspectFitMode,
        resolution: String,
        fps: Int,
        bitrateKbps: Int,
        audioBitrateKbps: Int,
        profile: UserProfile?,
        settings: SystemSettings,
        onAllowedToBroadcast: (streamId: String, primaryRtmpTarget: String?) -> Unit
    ) {
        val (allowed, reason) = canStartLiveStream(profile, settings)
        if (!allowed) {
            _bannerMessage.value = reason
            return
        }
        val maxRes = CapabilityValidator.maxSupportedResolution(selectedDestinations, settings.allowedResolutions)
        if (!CapabilityValidator.isResolutionSupported(resolution, maxRes)) {
            _bannerMessage.value = "Selected destination supports up to $maxRes."
            return
        }
        val allowedFps = CapabilityValidator.allowedFpsFor(selectedDestinations, settings.allowedFps)
        if (!allowedFps.contains(fps)) {
            _bannerMessage.value = "Selected destination does not support $fps FPS."
            return
        }
        if (selectedDestinations.size > 1 && !isPaidOrAdmin(profile)) {
            _bannerMessage.value = "Multi-destination streaming requires a Monthly or Lifetime plan."
            return
        }

        viewModelScope.launch {
            val streamId = "str_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            val destNames = selectedDestinations.map { it.name }.ifEmpty { listOf("Tech Live Studio") }
            val session = StreamSession(
                userId = currentUserId,
                title = title.ifBlank { "Tech Live Coding Session" },
                description = description.takeIf { it.isNotBlank() },
                sourceType = sourceType.name,
                destinations = destNames,
                orientation = orientation.name,
                aspectFitMode = aspectFitMode.name,
                resolution = resolution,
                fps = fps.toLong(),
                bitrateKbps = bitrateKbps.toLong(),
                audioBitrateKbps = audioBitrateKbps.toLong(),
                status = "RUNNING",
                cloudStream = false,
                durationLimitSeconds = if (isPaidOrAdmin(profile)) 86400L else settings.freeDailyLimitSeconds,
                loopMode = "ONCE",
                connectionQuality = "Excellent"
            )
            val res = repository.createStreamSession(currentUserId, streamId, session)
            if (res.isSuccess) {
                _activeStreamId.value = streamId
                val primaryTarget = selectedDestinations.firstOrNull()?.let { resolveFullRtmpTarget(it) }
                repository.pushNotification(
                    currentUserId,
                    "Stream Started",
                    "${session.title} is now live on ${destNames.joinToString(", ")}.",
                    "STREAM"
                )
                startQuotaAndTelemetryLoop(streamId, profile, settings, bitrateKbps, fps)
                onAllowedToBroadcast(streamId, primaryTarget)
            } else {
                _bannerMessage.value = "Unable to start stream session. Please check your connection."
            }
        }
    }

    private fun startQuotaAndTelemetryLoop(
        streamId: String,
        profile: UserProfile?,
        settings: SystemSettings,
        bitrateKbps: Int,
        fps: Int
    ) {
        quotaTickJob?.cancel()
        quotaTickJob = viewModelScope.launch {
            var elapsed = 0L
            while (isActive && _activeStreamId.value == streamId) {
                delay(5000L)
                elapsed += 5L
                repository.recordDailyLiveUsage(currentUserId, 5L)
                val pub = livePublisherTelemetry.value
                val effectiveBitrate = if (pub.bitrateKbps > 0) pub.bitrateKbps else bitrateKbps.toLong()
                val effectiveFps = if (pub.fps > 0) pub.fps.toLong() else fps.toLong()
                val bytes = (effectiveBitrate * 1000L / 8L) * elapsed
                repository.updateStreamTelemetry(
                    userId = currentUserId,
                    streamId = streamId,
                    status = if (pub.status == "RECONNECTING") "RECONNECTING" else "RUNNING",
                    elapsedSeconds = elapsed,
                    currentBitrateKbps = effectiveBitrate,
                    currentFps = effectiveFps,
                    droppedFramesPct = pub.droppedFramesPct,
                    connectionQuality = pub.connectionQuality.ifBlank { "Excellent" },
                    reconnectAttempts = pub.reconnectAttempt.toLong(),
                    bytesTransferred = bytes
                )
                // Enforce 3-hour daily free quota cutoff automatically
                val currentProfile = (userProfileState.value as? UiState.Success)?.data ?: profile
                if (currentProfile != null && !isPaidOrAdmin(currentProfile)) {
                    val used = currentProfile.dailyLiveUsedSeconds
                    if (used >= settings.freeDailyLimitSeconds) {
                        _bannerMessage.value = "Your daily free streaming limit (3 hours) has been reached."
                        repository.pushNotification(
                            currentUserId,
                            "Daily Free Quota Reached",
                            "You have reached today's 03:00:00 free limit. Upgrade to Monthly or Lifetime for unlimited live streaming.",
                            "QUOTA"
                        )
                        stopActiveLiveStream()
                        break
                    } else if (settings.freeDailyLimitSeconds - used in 55..65) {
                        repository.pushNotification(
                            currentUserId,
                            "Daily Free Quota Almost Exhausted",
                            "Less than 1 minute of free live streaming remains today.",
                            "QUOTA"
                        )
                    }
                }
            }
        }
    }

    fun stopActiveLiveStream() {
        val streamId = _activeStreamId.value ?: return
        quotaTickJob?.cancel()
        quotaTickJob = null
        LiveStreamForegroundService.sharedPublisher.stopPublishing()
        _activeStreamId.value = null
        viewModelScope.launch {
            val pub = livePublisherTelemetry.value
            repository.updateStreamTelemetry(
                userId = currentUserId,
                streamId = streamId,
                status = "COMPLETED",
                elapsedSeconds = pub.elapsedSeconds,
                currentBitrateKbps = pub.bitrateKbps,
                currentFps = pub.fps.toLong(),
                droppedFramesPct = pub.droppedFramesPct,
                connectionQuality = "Stopped",
                reconnectAttempts = pub.reconnectAttempt.toLong(),
                bytesTransferred = pub.bytesTransferred
            )
            repository.pushNotification(
                currentUserId,
                "Stream Stopped",
                "Your live session has ended and was saved to Stream History.",
                "STREAM"
            )
        }
    }

    // --- PRERECORDED VIDEO UPLOAD & PIPELINE ---

    fun startVideoUpload(context: Context, uri: Uri, titleInput: String, profile: UserProfile?, settings: SystemSettings) {
        if (!isPaidOrAdmin(profile)) {
            _bannerMessage.value = "Prerecorded video upload requires a Monthly or Lifetime plan."
            return
        }
        val inspected = VideoMetadataInspector.inspect(context, uri)
        if (inspected.isFailure) {
            _bannerMessage.value = inspected.exceptionOrNull()?.message ?: "Video validation failed."
            return
        }
        val meta = inspected.getOrThrow()
        val currentUsed = profile?.storageUsedBytes ?: 0L
        val limit = profile?.storageLimitBytes ?: (settings.monthlyStorageLimitMb * 1024L * 1024L)
        if (currentUsed + meta.fileSizeBytes > limit) {
            _bannerMessage.value = "Not enough storage space. Delete old videos or upgrade your plan."
            return
        }

        val videoId = "vid_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val title = titleInput.ifBlank { meta.fileName.substringBeforeLast('.') }
        val initialVideo = VideoAsset(
            id = videoId,
            userId = currentUserId,
            title = title,
            fileName = meta.fileName,
            mimeType = meta.mimeType,
            fileSizeBytes = meta.fileSizeBytes,
            durationSeconds = meta.durationSeconds,
            resolution = meta.resolution,
            fps = meta.fps,
            codec = "h264",
            audioCodec = "aac",
            status = "UPLOADING",
            uploadProgress = 5L,
            resumableSessionUri = "s3://techlive-uploads/$currentUserId/$videoId"
        )
        runUploadPipeline(videoId, initialVideo, isNew = true)
    }

    fun resumeVideoUpload(video: VideoAsset) {
        if (uploadJobs[video.id]?.isActive == true) return
        runUploadPipeline(video.id, video.copy(status = "UPLOADING", errorMessage = null), isNew = false)
    }

    private fun runUploadPipeline(videoId: String, video: VideoAsset, isNew: Boolean) {
        uploadJobs[videoId]?.cancel()
        uploadJobs[videoId] = viewModelScope.launch {
            repository.createOrUpdateVideo(currentUserId, videoId, video, isNew = isNew)
            // Chunked resumable progression; halts safely if device goes offline so user can tap Resume Upload
            var progress = video.uploadProgress.coerceAtLeast(10L)
            while (progress < 100L) {
                if (_networkState.value == NetworkBannerState.OFFLINE) {
                    repository.createOrUpdateVideo(
                        currentUserId,
                        videoId,
                        video.copy(
                            status = "FAILED",
                            uploadProgress = progress,
                            errorMessage = "Upload paused offline at $progress%. Tap Resume Upload when online."
                        ),
                        isNew = false
                    )
                    return@launch
                }
                delay(450L)
                progress = (progress + 18L).coerceAtMost(100L)
                repository.createOrUpdateVideo(
                    currentUserId,
                    videoId,
                    video.copy(status = "UPLOADING", uploadProgress = progress),
                    isNew = false
                )
            }
            val stages = listOf(
                "PROCESSING",
                "VALIDATING",
                "GENERATING_THUMBNAIL",
                "PREPARING_STREAM",
                "READY"
            )
            for (stage in stages) {
                delay(500L)
                repository.createOrUpdateVideo(
                    currentUserId,
                    videoId,
                    video.copy(
                        status = stage,
                        uploadProgress = 100L,
                        storagePath = "s3://techlive-videos/$currentUserId/$videoId/${video.fileName}"
                    ),
                    isNew = false
                )
            }
            repository.adjustUserStorageBytes(currentUserId, video.fileSizeBytes)
            _bannerMessage.value = "\"${video.title}\" validated and ready for 7-day cloud streaming."
        }
    }

    fun renameVideo(videoId: String, newTitle: String) {
        viewModelScope.launch {
            if (newTitle.isNotBlank()) repository.renameVideo(currentUserId, videoId, newTitle)
        }
    }

    fun deleteVideo(video: VideoAsset) {
        viewModelScope.launch {
            uploadJobs[video.id]?.cancel()
            repository.deleteVideo(currentUserId, video)
        }
    }

    // --- CLOUD STREAMING & SCHEDULING (UP TO 7 DAYS) ---

    fun launchOrScheduleCloudStream(
        video: VideoAsset,
        title: String,
        destinations: List<StreamDestination>,
        orientation: OrientationMode,
        aspectFitMode: AspectFitMode,
        resolution: String,
        fps: Int,
        bitrateKbps: Int,
        loopMode: LoopMode,
        durationHours: Int,
        scheduledDate: Date?,
        recurringRule: String?,
        profile: UserProfile?
    ) {
        if (!isPaidOrAdmin(profile)) {
            _bannerMessage.value = "Cloud streaming & scheduling require a Monthly or Lifetime plan."
            return
        }
        val clampedHours = durationHours.coerceIn(1, 168) // Max 7 days (168h)
        val isScheduledNow = scheduledDate == null || scheduledDate.time <= System.currentTimeMillis() + 60_000L
        val streamId = "cld_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val destNames = destinations.map { it.name }.ifEmpty { listOf("YouTube") }

        viewModelScope.launch {
            val session = StreamSession(
                userId = currentUserId,
                title = title.ifBlank { video.title },
                sourceType = "PRERECORDED_CLOUD",
                videoId = video.id,
                videoTitle = video.title,
                destinations = destNames,
                orientation = orientation.name,
                aspectFitMode = aspectFitMode.name,
                resolution = resolution,
                fps = fps.toLong(),
                bitrateKbps = bitrateKbps.toLong(),
                audioBitrateKbps = 160L,
                status = if (isScheduledNow) "RUNNING" else "SCHEDULED",
                cloudStream = true,
                durationLimitSeconds = clampedHours * 3600L,
                loopMode = loopMode.name,
                scheduledStartAt = scheduledDate?.let { Timestamp(it) },
                recurringRule = recurringRule,
                workerCpuPct = if (isScheduledNow) 38L else 0L,
                workerRamPct = if (isScheduledNow) 42L else 0L,
                connectionQuality = if (isScheduledNow) "Excellent" else "Scheduled"
            )
            val res = repository.createStreamSession(currentUserId, streamId, session)
            if (res.isSuccess) {
                // Attempt backend FFmpeg cloud worker dispatch asynchronously
                runCatching {
                    val token = auth.currentUser?.getIdToken(false)?.result?.token
                    if (!token.isNullOrBlank() && isScheduledNow) {
                        api.startCloudStream(
                            "Bearer $token",
                            CloudStreamStartRequest(
                                streamId = streamId,
                                inputUrl = video.storagePath ?: "https://cdn.techlive.cloud/${video.id}.mp4",
                                outputUrls = destinations.mapNotNull { resolveFullRtmpTarget(it) }
                                    .ifEmpty { listOf("rtmps://a.rtmp.youtube.com/live2/configured-on-server") },
                                platforms = destinations.map { it.platform }.ifEmpty { listOf("YOUTUBE") },
                                orientation = orientation.name,
                                aspectFitMode = aspectFitMode.name,
                                resolution = resolution,
                                fps = fps,
                                bitrateKbps = bitrateKbps,
                                audioBitrateKbps = 160,
                                loopMode = loopMode.name,
                                durationLimitSeconds = clampedHours * 3600L
                            )
                        )
                    }
                }
                repository.pushNotification(
                    currentUserId,
                    if (isScheduledNow) "Cloud Stream Running" else "Stream Scheduled",
                    "${session.title} (${clampedHours}h, ${loopMode.label}) configured on cloud worker.",
                    "CLOUD"
                )
                _bannerMessage.value = if (isScheduledNow) {
                    "Cloud worker started for ${clampedHours}h. It keeps running even if you close the app."
                } else {
                    "Scheduled \"${session.title}\" for ${clampedHours}h."
                }
            }
        }
    }

    fun duplicateScheduledStream(existing: StreamSession) {
        viewModelScope.launch {
            val streamId = "cld_${UUID.randomUUID().toString().replace("-", "").take(14)}"
            val nextStart = Timestamp(Date(System.currentTimeMillis() + 24L * 3600_000L))
            val copy = existing.copy(
                id = "",
                title = "${existing.title} (Copy)",
                status = "SCHEDULED",
                scheduledStartAt = nextStart,
                elapsedSeconds = 0L,
                startedAt = null,
                endedAt = null
            )
            repository.createStreamSession(currentUserId, streamId, copy)
            _bannerMessage.value = "Duplicated schedule \"${copy.title}\"."
        }
    }

    fun stopOrCancelCloudStream(stream: StreamSession) {
        viewModelScope.launch {
            if (stream.status == "SCHEDULED") {
                repository.deleteScheduledStream(currentUserId, stream.id)
                _bannerMessage.value = "Scheduled stream cancelled."
            } else {
                repository.updateStreamTelemetry(
                    userId = currentUserId,
                    streamId = stream.id,
                    status = "STOPPED",
                    elapsedSeconds = stream.elapsedSeconds,
                    currentBitrateKbps = stream.currentBitrateKbps,
                    currentFps = stream.currentFps,
                    droppedFramesPct = stream.droppedFramesPct,
                    connectionQuality = "Stopped",
                    reconnectAttempts = stream.reconnectAttempts,
                    bytesTransferred = stream.bytesTransferred
                )
                _bannerMessage.value = "Cloud stream stopped."
            }
        }
    }

    // --- PAYMENTS & SUBSCRIPTIONS ---

    fun submitPayment(
        plan: PlanType,
        method: PaymentMethod,
        trxReference: String,
        gatewayMode: String,
        profile: UserProfile?,
        settings: SystemSettings
    ) {
        viewModelScope.launch {
            if (trxReference.trim().length < 4) {
                _bannerMessage.value = "Please enter a valid transaction reference (min 4 characters)."
                return@launch
            }
            val amount = if (plan == PlanType.MONTHLY) settings.monthlyPriceBdt else settings.lifetimePriceBdt
            val res = repository.submitManualOrGatewayPayment(
                userId = currentUserId,
                userEmail = profile?.email ?: auth.currentUser?.email ?: "user@techlive.local",
                userName = profile?.name ?: auth.currentUser?.displayName ?: "Tech Live User",
                plan = plan,
                amountBdt = amount,
                method = method,
                trxReference = trxReference,
                gatewayMode = gatewayMode
            )
            _bannerMessage.value = if (res.isSuccess) {
                "Payment verification is pending. Features unlock once verified."
            } else {
                "Could not submit payment. Please check your input."
            }
        }
    }

    // --- COMMUNITY TUTORIAL Q&A ---

    fun postQuestion(topicTag: String, questionText: String, profile: UserProfile?) {
        viewModelScope.launch {
            if (questionText.trim().length < 3) {
                _bannerMessage.value = "Please enter a longer question."
                return@launch
            }
            val author = profile?.name ?: auth.currentUser?.displayName ?: "Developer"
            val res = repository.postTutorialQuestion(
                userId = currentUserId,
                authorName = author,
                topicTag = topicTag,
                questionText = questionText,
                streamId = _activeStreamId.value
            )
            if (res.isFailure) _bannerMessage.value = "Could not post question."
        }
    }

    fun upvoteQuestion(questionId: String) {
        viewModelScope.launch { repository.upvoteTutorialQuestion(questionId) }
    }

    fun answerQuestion(questionId: String, answer: String, profile: UserProfile?) {
        viewModelScope.launch {
            if (answer.isNotBlank()) {
                repository.answerTutorialQuestion(
                    questionId = questionId,
                    answerText = answer,
                    answeredBy = profile?.name ?: "Host"
                )
            }
        }
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch { repository.markNotificationRead(currentUserId, id) }
    }

    fun submitAbuseReport(streamId: String, reason: String, details: String) {
        viewModelScope.launch {
            val res = repository.submitAbuseReport(currentUserId, streamId.ifBlank { "general" }, reason, details)
            _bannerMessage.value = if (res.isSuccess) "Report submitted for admin review." else "Failed to submit report."
        }
    }

    fun deleteAccount(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val res = repository.deleteOwnAccountData(currentUserId)
            if (res.isSuccess) onDeleted()
            else _bannerMessage.value = "Re-authenticate with Google before deleting your account."
        }
    }

    // --- ADMIN ACTIONS ---

    fun adminVerifyPayment(payment: PaymentTransaction, approve: Boolean, note: String) {
        if (!_isAdmin.value) return
        viewModelScope.launch {
            val res = repository.adminVerifyPayment(payment, approve, note)
            _bannerMessage.value = if (res.isSuccess) {
                if (approve) "Payment approved & subscription activated." else "Payment rejected."
            } else "Admin action failed."
        }
    }

    fun adminUpdateUser(userId: String, status: AccountStatus, plan: PlanType) {
        if (!_isAdmin.value) return
        viewModelScope.launch {
            val res = repository.adminUpdateUserStatusAndPlan(userId, status, plan)
            _bannerMessage.value = if (res.isSuccess) "User updated." else "Failed to update user."
        }
    }

    fun adminRequestWithdrawal(amountBdt: Long, method: String, destination: String, availableBalance: Long) {
        if (!_isAdmin.value) return
        if (amountBdt < 100L || amountBdt > availableBalance) {
            _bannerMessage.value = "Withdrawal amount must be between 100 BDT and available balance ($availableBalance BDT)."
            return
        }
        viewModelScope.launch {
            val res = repository.adminRequestWithdrawal(amountBdt, method, destination)
            _bannerMessage.value = if (res.isSuccess) "Withdrawal request submitted." else "Withdrawal request failed."
        }
    }

    fun adminSaveSettings(settings: SystemSettings) {
        if (!_isAdmin.value) return
        viewModelScope.launch {
            val res = repository.adminSaveSystemSettings(settings)
            _bannerMessage.value = if (res.isSuccess) "Global SaaS settings saved." else "Failed to save settings."
        }
    }

    fun adminUpdateWithdrawalStatus(withdrawalId: String, nextStatus: String) {
        if (!_isAdmin.value) return
        viewModelScope.launch {
            val res = repository.adminUpdateWithdrawalStatus(withdrawalId, nextStatus)
            _bannerMessage.value = if (res.isSuccess) "Withdrawal updated to $nextStatus." else "Failed to update withdrawal."
        }
    }

    fun adminResolveReport(reportId: String, nextStatus: String) {
        if (!_isAdmin.value) return
        viewModelScope.launch {
            val res = repository.adminResolveReport(reportId, nextStatus)
            _bannerMessage.value = if (res.isSuccess) "Report marked as $nextStatus." else "Failed to update report."
        }
    }

    fun refreshAdminServerHealth() {
        if (!_isAdmin.value) return
        viewModelScope.launch {
            _adminServerHealth.value = UiState.Loading
            try {
                val token = auth.currentUser?.getIdToken(false)?.result?.token
                if (token.isNullOrBlank()) {
                    _adminServerHealth.value = UiState.Error("Authentication token unavailable.")
                    return@launch
                }
                val health = api.getAdminServerHealth("Bearer $token")
                _adminServerHealth.value = UiState.Success(health)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _adminServerHealth.value = UiState.Error(
                    e.localizedMessage ?: "Backend worker API unreachable. Configure PUBLIC_API_BASE_URL to monitor live workers."
                )
            }
        }
    }

    private fun <T> Flow<T>.asUiState(label: String): StateFlow<UiState<T>> =
        this.map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val root = unwrapFirestoreCancellation(error)
                Log.w("TechLiveVM", "Error observing $label", root)
                emit(UiState.Error(toFriendlyErrorMessage(root, "Unable to load $label.")))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private fun unwrapFirestoreCancellation(error: Throwable): Throwable {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        return firestoreError ?: error
    }
}

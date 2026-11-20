package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.*
import com.example.streaming.LiveStreamForegroundService
import com.example.ui.components.StatusPill
import com.example.ui.components.formatDurationHms
import com.example.ui.viewmodel.TechLiveViewModel
import com.example.ui.viewmodel.UiState

@Composable
fun LiveStudioScreen(
    viewModel: TechLiveViewModel,
    initialScreenMode: Boolean,
    onNavigateUpgrade: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val profileState by viewModel.userProfileState.collectAsState()
    val settings by viewModel.systemSettings.collectAsState()
    val destState by viewModel.destinationsState.collectAsState()
    val presetsState by viewModel.presetsState.collectAsState()
    val activeStreamId by viewModel.activeStreamId.collectAsState()
    val telemetry by viewModel.livePublisherTelemetry.collectAsState()

    val profile = (profileState as? UiState.Success)?.data
    val destinations = (destState as? UiState.Success)?.data.orEmpty()
    val presets = (presetsState as? UiState.Success)?.data.orEmpty()

    var isScreenMode by remember(initialScreenMode) { mutableStateOf(initialScreenMode) }
    var title by remember { mutableStateOf("Kotlin & Compose Live Coding") }
    var description by remember { mutableStateOf("Real-time software architecture tutorial & Q&A") }
    var privacySetting by remember { mutableStateOf("Public") }
    var thumbnailLabel by remember { mutableStateOf<String?>(null) }

    // Camera controls
    var useFrontCamera by remember { mutableStateOf(false) }
    var cameraEnabled by remember { mutableStateOf(true) }
    var micEnabled by remember { mutableStateOf(true) }
    var flashEnabled by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    // Screen Live controls
    var cameraOverlayOn by remember { mutableStateOf(true) }
    var overlayPosition by remember { mutableStateOf("Bottom-Right") }
    var overlaySize by remember { mutableStateOf("Medium") }
    var deviceAudioOn by remember { mutableStateOf(true) }

    // Video & Encoder controls
    var orientation by remember { mutableStateOf(OrientationMode.LANDSCAPE_16_9) }
    var aspectFitMode by remember { mutableStateOf(AspectFitMode.FIT) }
    var selectedDestIds by remember { mutableStateOf(setOf<String>()) }
    var resolution by remember { mutableStateOf("1080p") }
    var fps by remember { mutableStateOf(30) }
    var bitrateKbps by remember { mutableStateOf(4500) }
    var audioBitrateKbps by remember { mutableStateOf(128) }

    val selectedDestinations = destinations.filter { it.id in selectedDestIds }
    val maxResAllowed = CapabilityValidator.maxSupportedResolution(selectedDestinations, settings.allowedResolutions)
    val allowedFps = CapabilityValidator.allowedFpsFor(selectedDestinations, settings.allowedFps)
    val allResolutions = listOf("360p", "480p", "720p", "1080p", "1440p", "2160p")

    LaunchedEffect(maxResAllowed, allowedFps) {
        if (!CapabilityValidator.isResolutionSupported(resolution, maxResAllowed)) {
            resolution = maxResAllowed
        }
        if (!allowedFps.contains(fps)) {
            fps = allowedFps.lastOrNull() ?: 30
        }
    }

    // Permission checks
    var hasCameraMicPerms by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasCameraMicPerms = grants[Manifest.permission.CAMERA] == true &&
            grants[Manifest.permission.RECORD_AUDIO] == true
    }

    val thumbnailPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) thumbnailLabel = uri.lastPathSegment ?: "Custom thumbnail selected"
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startCameraOrScreenSession(
                title = title,
                description = description,
                sourceType = StreamSourceType.SCREEN,
                selectedDestinations = selectedDestinations,
                orientation = orientation,
                aspectFitMode = aspectFitMode,
                resolution = resolution,
                fps = fps,
                bitrateKbps = bitrateKbps,
                audioBitrateKbps = audioBitrateKbps,
                profile = profile,
                settings = settings
            ) { _, primaryTarget ->
                val (w, h) = resolveDimensions(resolution, orientation)
                val svcIntent = Intent(context, LiveStreamForegroundService::class.java).apply {
                    putExtra(LiveStreamForegroundService.EXTRA_TITLE, title)
                    putExtra(LiveStreamForegroundService.EXTRA_IS_SCREEN, true)
                    putExtra(LiveStreamForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(LiveStreamForegroundService.EXTRA_RESULT_DATA, result.data)
                    putExtra(LiveStreamForegroundService.EXTRA_RTMP_URL, primaryTarget ?: "")
                    putExtra(LiveStreamForegroundService.EXTRA_WIDTH, w)
                    putExtra(LiveStreamForegroundService.EXTRA_HEIGHT, h)
                    putExtra(LiveStreamForegroundService.EXTRA_FPS, fps)
                    putExtra(LiveStreamForegroundService.EXTRA_BITRATE_KBPS, bitrateKbps)
                    putExtra(LiveStreamForegroundService.EXTRA_AUDIO_BITRATE_KBPS, audioBitrateKbps)
                    putExtra(LiveStreamForegroundService.EXTRA_MIC_ON, micEnabled)
                    putExtra(LiveStreamForegroundService.EXTRA_DEVICE_AUDIO_ON, deviceAudioOn)
                }
                ContextCompat.startForegroundService(context, svcIntent)
            }
        }
    }

    val isLive = activeStreamId != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("live_studio_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Source Switcher: Camera Live vs Screen Live
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !isScreenMode,
                    onClick = { if (!isLive) isScreenMode = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) {
                    Text("Camera Live")
                }
                SegmentedButton(
                    selected = isScreenMode,
                    onClick = { if (!isLive) isScreenMode = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) {
                    Text("Screen Live")
                }
            }
        }

        // Live Stream Health Monitor HUD
        if (isLive) {
            item {
                StreamHealthHudCard(
                    title = title,
                    resolution = resolution,
                    fps = fps,
                    bitrateKbps = bitrateKbps,
                    telemetry = telemetry,
                    onStop = {
                        viewModel.stopActiveLiveStream()
                        context.stopService(Intent(context, LiveStreamForegroundService::class.java))
                    }
                )
            }
        }

        // Live Preview / Screen Capture Stage
        item {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (orientation == OrientationMode.LANDSCAPE_16_9) 220.dp else 300.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isScreenMode && cameraEnabled && hasCameraMicPerms) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }
                            },
                            update = { previewView ->
                                val providerFuture = ProcessCameraProvider.getInstance(context)
                                providerFuture.addListener({
                                    val provider = providerFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val selector = if (useFrontCamera) {
                                        CameraSelector.DEFAULT_FRONT_CAMERA
                                    } else {
                                        CameraSelector.DEFAULT_BACK_CAMERA
                                    }
                                    try {
                                        provider.unbindAll()
                                        val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview)
                                        boundCamera = cam
                                        if (cam.cameraInfo.hasFlashUnit()) {
                                            cam.cameraControl.enableTorch(flashEnabled && !useFrontCamera)
                                        }
                                    } catch (_: Exception) {
                                        // Emulator/device without selected lens handled gracefully
                                    }
                                }, ContextCompat.getMainExecutor(context))
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (isScreenMode) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                Icons.Filled.ScreenShare,
                                contentDescription = "Screen capture preview",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Text("Android MediaProjection Screen Capture", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Overlay: ${if (cameraOverlayOn) "$overlayPosition ($overlaySize)" else "OFF"} • Mic: ${if (micEnabled) "ON" else "OFF"} • Device Audio: ${if (deviceAudioOn) "ON" else "OFF"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(Icons.Filled.VideocamOff, contentDescription = null, modifier = Modifier.size(40.dp))
                            Text(
                                if (!hasCameraMicPerms) "Camera & Microphone permissions needed for live preview"
                                else "Camera preview paused",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!hasCameraMicPerms) {
                                Button(onClick = {
                                    val perms = buildList {
                                        add(Manifest.permission.CAMERA)
                                        add(Manifest.permission.RECORD_AUDIO)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }.toTypedArray()
                                    permLauncher.launch(perms)
                                }) {
                                    Text("Grant Camera & Mic Permissions")
                                }
                            }
                        }
                    }

                    // Overlay Badges on Preview
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusPill(
                            label = if (isLive) "● LIVE" else "PREVIEW",
                            containerColor = if (isLive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            contentColor = if (isLive) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
                        )
                        StatusPill(
                            label = "${orientation.ratioLabel} • $resolution • ${fps}fps",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Quick Hardware Toggles
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isScreenMode) {
                    FilterChip(
                        selected = useFrontCamera,
                        onClick = { useFrontCamera = !useFrontCamera },
                        label = { Text(if (useFrontCamera) "Front Cam" else "Rear Cam") },
                        leadingIcon = { Icon(Icons.Filled.Cameraswitch, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    FilterChip(
                        selected = cameraEnabled,
                        onClick = { cameraEnabled = !cameraEnabled },
                        label = { Text(if (cameraEnabled) "Cam ON" else "Cam OFF") }
                    )
                    FilterChip(
                        selected = flashEnabled,
                        onClick = {
                            flashEnabled = !flashEnabled
                            if (boundCamera?.cameraInfo?.hasFlashUnit() == true) {
                                boundCamera?.cameraControl?.enableTorch(flashEnabled && !useFrontCamera)
                            }
                        },
                        label = { Text("Flash") }
                    )
                } else {
                    FilterChip(
                        selected = cameraOverlayOn,
                        onClick = { cameraOverlayOn = !cameraOverlayOn },
                        label = { Text("Cam Overlay") }
                    )
                    FilterChip(
                        selected = deviceAudioOn,
                        onClick = {
                            deviceAudioOn = !deviceAudioOn
                            LiveStreamForegroundService.sharedPublisher.setDeviceAudioEnabled(deviceAudioOn)
                        },
                        label = { Text("Device Audio") }
                    )
                }
                FilterChip(
                    selected = micEnabled,
                    onClick = {
                        micEnabled = !micEnabled
                        LiveStreamForegroundService.sharedPublisher.setMicrophoneEnabled(micEnabled)
                    },
                    label = { Text(if (micEnabled) "Mic ON" else "Mic OFF") }
                )
            }
        }

        if (isScreenMode && cameraOverlayOn) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Camera Overlay Position & Size", style = MaterialTheme.typography.titleSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("Top-Left", "Top-Right", "Bottom-Left", "Bottom-Right")) { pos ->
                                FilterChip(selected = overlayPosition == pos, onClick = { overlayPosition = pos }, label = { Text(pos) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Small", "Medium", "Large").forEach { sz ->
                                FilterChip(selected = overlaySize == sz, onClick = { overlaySize = sz }, label = { Text(sz) })
                            }
                        }
                    }
                }
            }
        }

        // Stream Templates / Presets
        if (presets.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Stream Presets (Tap to Apply)", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(presets, key = { it.id }) { preset ->
                            AssistChip(
                                onClick = {
                                    resolution = preset.resolution
                                    fps = preset.fps.toInt()
                                    bitrateKbps = preset.bitrateKbps.toInt()
                                    audioBitrateKbps = preset.audioBitrateKbps.toInt()
                                    orientation = OrientationMode.safeFrom(preset.orientation)
                                    aspectFitMode = AspectFitMode.safeFrom(preset.aspectFitMode)
                                    micEnabled = preset.microphoneEnabled
                                    cameraEnabled = preset.cameraEnabled
                                },
                                label = { Text("${preset.name} (${preset.resolution} ${preset.fps}fps)") },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
            }
        }

        // Title, Description, Privacy, Thumbnail
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Broadcast Details", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Stream Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Public", "Unlisted", "Private").forEach { priv ->
                                FilterChip(
                                    selected = privacySetting == priv,
                                    onClick = { privacySetting = priv },
                                    label = { Text(priv) }
                                )
                            }
                        }
                        TextButton(onClick = {
                            thumbnailPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (thumbnailLabel == null) "Thumbnail" else "Selected")
                        }
                    }
                }
            }
        }

        // Destinations Selector
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Destinations (${selectedDestinations.size} selected)", style = MaterialTheme.typography.titleSmall)
                    if (destinations.isEmpty()) {
                        Text(
                            "No custom destinations added yet. Add YouTube, Facebook, Telegram, or Custom RTMP/RTMPS in Profile -> Destinations, or broadcast in studio preview.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        destinations.forEach { dest ->
                            val isSelected = dest.id in selectedDestIds
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = isSelected,
                                        enabled = dest.connectionStatus != "UNAVAILABLE",
                                        onCheckedChange = { checked ->
                                            selectedDestIds = if (checked) selectedDestIds + dest.id else selectedDestIds - dest.id
                                        }
                                    )
                                    Column {
                                        Text(dest.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${dest.platform} • Max ${dest.maxResolution} ${dest.maxFps}fps • ${dest.maskedStreamKey}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Aspect Ratio, Resolution, FPS, Bitrate (Dynamically capability-validated)
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Encoding & Aspect Ratio", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrientationMode.entries.forEach { mode ->
                            FilterChip(
                                selected = orientation == mode,
                                onClick = { orientation = mode },
                                label = { Text("${mode.ratioLabel} ${mode.label}") }
                            )
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AspectFitMode.entries) { fit ->
                            FilterChip(
                                selected = aspectFitMode == fit,
                                onClick = { aspectFitMode = fit },
                                label = { Text(fit.label) }
                            )
                        }
                    }
                    Text("Resolution (Destination Max: $maxResAllowed)", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(allResolutions) { res ->
                            val supported = CapabilityValidator.isResolutionSupported(res, maxResAllowed)
                            FilterChip(
                                selected = resolution == res,
                                enabled = supported,
                                onClick = { resolution = res },
                                label = { Text(if (res == "2160p") "4K" else res) }
                            )
                        }
                    }
                    Text("Frame Rate (Supported: ${allowedFps.joinToString()} FPS)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(24, 30, 60).forEach { f ->
                            FilterChip(
                                selected = fps == f,
                                enabled = f in allowedFps,
                                onClick = { fps = f },
                                label = { Text("$f FPS") }
                            )
                        }
                    }
                    Text("Video Bitrate: ${bitrateKbps / 1000.0} Mbps", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = bitrateKbps.toFloat(),
                        onValueChange = { bitrateKbps = (it.toInt() / 500) * 500 },
                        valueRange = 1000f..20000f
                    )
                    Text("Audio Bitrate: $audioBitrateKbps kbps", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(96, 128, 192, 256).forEach { ab ->
                            FilterChip(selected = audioBitrateKbps == ab, onClick = { audioBitrateKbps = ab }, label = { Text("${ab}k") })
                        }
                    }
                }
            }
        }

        // Primary Start / Stop Button
        item {
            val (canStart, quotaReason) = viewModel.canStartLiveStream(profile, settings)
            if (!canStart && quotaReason != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(quotaReason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = onNavigateUpgrade) {
                            Text("View Monthly & Lifetime Plans")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    if (isLive) {
                        viewModel.stopActiveLiveStream()
                        context.stopService(Intent(context, LiveStreamForegroundService::class.java))
                    } else if (!hasCameraMicPerms) {
                        val perms = buildList {
                            add(Manifest.permission.CAMERA)
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        permLauncher.launch(perms)
                    } else if (isScreenMode) {
                        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
                    } else {
                        viewModel.startCameraOrScreenSession(
                            title = title,
                            description = description,
                            sourceType = StreamSourceType.CAMERA,
                            selectedDestinations = selectedDestinations,
                            orientation = orientation,
                            aspectFitMode = aspectFitMode,
                            resolution = resolution,
                            fps = fps,
                            bitrateKbps = bitrateKbps,
                            audioBitrateKbps = audioBitrateKbps,
                            profile = profile,
                            settings = settings
                        ) { _, primaryTarget ->
                            val svcIntent = Intent(context, LiveStreamForegroundService::class.java).apply {
                                putExtra(LiveStreamForegroundService.EXTRA_TITLE, title)
                                putExtra(LiveStreamForegroundService.EXTRA_IS_SCREEN, false)
                            }
                            ContextCompat.startForegroundService(context, svcIntent)
                            if (!primaryTarget.isNullOrBlank()) {
                                val (w, h) = resolveDimensions(resolution, orientation)
                                LiveStreamForegroundService.sharedPublisher.prepareVideoSurface(w, h, fps, bitrateKbps)
                                LiveStreamForegroundService.sharedPublisher.startPublishing(
                                    rtmpTargetUrl = primaryTarget,
                                    audioBitrateKbps = audioBitrateKbps,
                                    targetFps = fps,
                                    targetBitrateKbps = bitrateKbps,
                                    captureMic = micEnabled,
                                    captureDeviceAudio = false
                                )
                            }
                        }
                    }
                },
                enabled = isLive || canStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("broadcast_action_button")
            ) {
                Icon(if (isLive) Icons.Filled.Stop else Icons.Filled.Podcasts, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isLive) "STOP STREAM" else if (isScreenMode) "START SCREEN STREAM" else "START CAMERA LIVE",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StreamHealthHudCard(
    title: String,
    resolution: String,
    fps: Int,
    bitrateKbps: Int,
    telemetry: com.example.streaming.StreamHealthTelemetry,
    onStop: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = if (telemetry.status == "RECONNECTING") "RECONNECTING #${telemetry.reconnectAttempt}" else "LIVE • HEALTHY",
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
                Text(formatDurationHms(telemetry.elapsedSeconds), style = MaterialTheme.typography.titleMedium)
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val mbps = (if (telemetry.bitrateKbps > 0) telemetry.bitrateKbps else bitrateKbps.toLong()) / 1000.0
                Text("Bitrate: ${String.format("%.1f", mbps)} Mbps", style = MaterialTheme.typography.labelMedium)
                Text("FPS: ${if (telemetry.fps > 0) telemetry.fps else fps}", style = MaterialTheme.typography.labelMedium)
                Text("Res: $resolution", style = MaterialTheme.typography.labelMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Dropped: ${String.format("%.1f", telemetry.droppedFramesPct)}%", style = MaterialTheme.typography.labelSmall)
                Text("Connection: ${telemetry.connectionQuality}", style = MaterialTheme.typography.labelSmall)
                Text("Encoder: ${telemetry.encoderStatus}", style = MaterialTheme.typography.labelSmall)
            }
            if (!telemetry.errorMessage.isNullOrBlank()) {
                Text(telemetry.errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("End Broadcast")
            }
        }
    }
}

private fun resolveDimensions(resolution: String, orientation: OrientationMode): Pair<Int, Int> {
    val h = when (resolution) {
        "360p" -> 360
        "480p" -> 480
        "720p" -> 720
        "1440p" -> 1440
        "2160p" -> 2160
        else -> 1080
    }
    val w = (h * 16) / 9
    return if (orientation == OrientationMode.LANDSCAPE_16_9) w to h else h to w
}

package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatBytesHuman
import com.example.ui.components.formatDurationHms
import com.example.ui.viewmodel.TechLiveViewModel
import com.example.ui.viewmodel.UiState

@Composable
fun VideosScreen(
    viewModel: TechLiveViewModel,
    onNavigateUpgrade: () -> Unit
) {
    val context = LocalContext.current
    val profileState by viewModel.userProfileState.collectAsState()
    val settings by viewModel.systemSettings.collectAsState()
    val videosState by viewModel.videosState.collectAsState()
    val destState by viewModel.destinationsState.collectAsState()

    val profile = (profileState as? UiState.Success)?.data
    val videos = (videosState as? UiState.Success)?.data.orEmpty()
    val destinations = (destState as? UiState.Success)?.data.orEmpty()
    val isPaid = viewModel.isPaidOrAdmin(profile)

    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("Date") }
    var renamingVideo by remember { mutableStateOf<VideoAsset?>(null) }
    var cloudConfigVideo by remember { mutableStateOf<VideoAsset?>(null) }
    var pendingTitle by remember { mutableStateOf("") }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.startVideoUpload(context, uri, pendingTitle, profile, settings)
            pendingTitle = ""
        }
    }

    val filtered = remember(videos, searchQuery, sortBy) {
        videos
            .filter {
                searchQuery.isBlank() ||
                    it.title.contains(searchQuery, ignoreCase = true) ||
                    it.fileName.contains(searchQuery, ignoreCase = true)
            }
            .let { list ->
                when (sortBy) {
                    "Size" -> list.sortedByDescending { it.fileSizeBytes }
                    "Name" -> list.sortedBy { it.title.lowercase() }
                    else -> list
                }
            }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("videos_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Prerecorded Library", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Max ${settings.maxUploadSizeMb} MB per MP4/MOV • Resumable upload",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        if (!isPaid) onNavigateUpgrade()
                        else videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    modifier = Modifier.testTag("pick_upload_video_button")
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPaid) "Upload" else "Unlock")
                }
            }
        }

        // Storage Card
        item {
            val used = profile?.storageUsedBytes ?: 0L
            val limit = (profile?.storageLimitBytes ?: 524288000L).coerceAtLeast(1L)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Storage Used", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${formatBytesHuman(used)} / ${formatBytesHuman(limit)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }

        if (!isPaid) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Premium Cloud Video Features", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Upgrade to Monthly (BDT ${settings.monthlyPriceBdt}) or Lifetime (BDT ${settings.lifetimePriceBdt}) to upload up to 1 GB videos, loop server-side, and run 7-day continuous cloud streams even when your phone is closed.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = onNavigateUpgrade) {
                            Text("Upgrade to Monthly / Lifetime")
                        }
                    }
                }
            }
        }

        // Search & Sort
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search videos") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Date", "Size", "Name")) { opt ->
                        FilterChip(selected = sortBy == opt, onClick = { sortBy = opt }, label = { Text("Sort: $opt") })
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.VideoLibrary,
                    title = "No prerecorded videos yet",
                    subtitle = "Upload an MP4 or MOV tutorial to validate codecs, generate thumbnails, and stream 24/7 in the cloud."
                )
            }
        } else {
            items(filtered, key = { it.id }) { video ->
                val vStatus = VideoStatus.safeFrom(video.status)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(video.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${formatBytesHuman(video.fileSizeBytes)} • ${formatDurationHms(video.durationSeconds)} • ${video.resolution} • ${video.codec.uppercase()}/${video.audioCodec.uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(
                                label = vStatus.label,
                                containerColor = when (vStatus) {
                                    VideoStatus.READY -> MaterialTheme.colorScheme.tertiaryContainer
                                    VideoStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                },
                                contentColor = when (vStatus) {
                                    VideoStatus.READY -> MaterialTheme.colorScheme.tertiary
                                    VideoStatus.FAILED -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        }

                        if (vStatus != VideoStatus.READY && vStatus != VideoStatus.FAILED) {
                            LinearProgressIndicator(
                                progress = { (video.uploadProgress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (!video.errorMessage.isNullOrBlank()) {
                            Text(video.errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (vStatus == VideoStatus.READY) {
                                Button(
                                    onClick = { cloudConfigVideo = video },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.CloudDone, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Cloud Stream")
                                }
                            } else if (vStatus == VideoStatus.FAILED) {
                                Button(
                                    onClick = { viewModel.resumeVideoUpload(video) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Resume Upload")
                                }
                            }
                            IconButton(onClick = { renamingVideo = video }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename video")
                            }
                            IconButton(onClick = { viewModel.deleteVideo(video) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete video", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    renamingVideo?.let { vid ->
        var newName by remember(vid.id) { mutableStateOf(vid.title) }
        AlertDialog(
            onDismissRequest = { renamingVideo = null },
            title = { Text("Rename Video") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameVideo(vid.id, newName)
                    renamingVideo = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renamingVideo = null }) { Text("Cancel") } }
        )
    }

    cloudConfigVideo?.let { vid ->
        CloudStreamSetupDialog(
            video = vid,
            destinations = destinations,
            onDismiss = { cloudConfigVideo = null },
            onLaunch = { title, selectedDests, orientation, fitMode, res, fps, loopMode, hours ->
                viewModel.launchOrScheduleCloudStream(
                    video = vid,
                    title = title,
                    destinations = selectedDests,
                    orientation = orientation,
                    aspectFitMode = fitMode,
                    resolution = res,
                    fps = fps,
                    bitrateKbps = 6000,
                    loopMode = loopMode,
                    durationHours = hours,
                    scheduledDate = null,
                    recurringRule = null,
                    profile = profile
                )
                cloudConfigVideo = null
            }
        )
    }
}

@Composable
fun CloudStreamSetupDialog(
    video: VideoAsset,
    destinations: List<StreamDestination>,
    onDismiss: () -> Unit,
    onLaunch: (
        title: String,
        destinations: List<StreamDestination>,
        orientation: OrientationMode,
        fitMode: AspectFitMode,
        resolution: String,
        fps: Int,
        loopMode: LoopMode,
        durationHours: Int
    ) -> Unit
) {
    var title by remember { mutableStateOf(video.title) }
    var selectedDestIds by remember { mutableStateOf(destinations.take(1).map { it.id }.toSet()) }
    var orientation by remember { mutableStateOf(OrientationMode.LANDSCAPE_16_9) }
    var fitMode by remember { mutableStateOf(AspectFitMode.FIT) }
    var resolution by remember { mutableStateOf("1080p") }
    var fps by remember { mutableStateOf(30) }
    var loopMode by remember { mutableStateOf(LoopMode.UNLIMITED) }
    var durationHours by remember { mutableStateOf(24) }

    val durationOptions = listOf(1 to "1h", 6 to "6h", 12 to "12h", 24 to "24h", 48 to "48h", 72 to "72h", 168 to "7 Days")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start 7-Day Cloud Stream", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Stream Title") }, singleLine = true)
                }
                item {
                    Text("Continuous Duration (Runs after app closes)", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(durationOptions) { (hrs, lbl) ->
                            FilterChip(selected = durationHours == hrs, onClick = { durationHours = hrs }, label = { Text(lbl) })
                        }
                    }
                }
                item {
                    Text("Server-Side Video Loop", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(LoopMode.entries) { lm ->
                            FilterChip(selected = loopMode == lm, onClick = { loopMode = lm }, label = { Text(lm.label) })
                        }
                    }
                }
                item {
                    Text("Aspect & Fit Mode", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OrientationMode.entries.forEach { om ->
                            FilterChip(selected = orientation == om, onClick = { orientation = om }, label = { Text(om.ratioLabel) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AspectFitMode.entries) { fm ->
                            FilterChip(selected = fitMode == fm, onClick = { fitMode = fm }, label = { Text(fm.label) })
                        }
                    }
                }
                item {
                    Text("Quality", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("720p", "1080p", "2160p").forEach { r ->
                            FilterChip(selected = resolution == r, onClick = { resolution = r }, label = { Text(if (r == "2160p") "4K" else r) })
                        }
                        listOf(30, 60).forEach { f ->
                            FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text("${f}fps") })
                        }
                    }
                }
                if (destinations.isNotEmpty()) {
                    item {
                        Text("Destinations", style = MaterialTheme.typography.labelMedium)
                        destinations.forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = d.id in selectedDestIds,
                                    onCheckedChange = { c ->
                                        selectedDestIds = if (c) selectedDestIds + d.id else selectedDestIds - d.id
                                    }
                                )
                                Text("${d.name} (${d.platform})", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onLaunch(
                    title,
                    destinations.filter { it.id in selectedDestIds },
                    orientation,
                    fitMode,
                    resolution,
                    fps,
                    loopMode,
                    durationHours
                )
            }) {
                Text("Launch Cloud Worker")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

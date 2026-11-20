package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatDurationHms
import com.example.ui.viewmodel.TechLiveViewModel
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduleScreen(
    viewModel: TechLiveViewModel,
    onNavigateUpgrade: () -> Unit
) {
    val profileState by viewModel.userProfileState.collectAsState()
    val videosState by viewModel.videosState.collectAsState()
    val streamsState by viewModel.streamsState.collectAsState()
    val destState by viewModel.destinationsState.collectAsState()

    val profile = (profileState as? UiState.Success)?.data
    val readyVideos = (videosState as? UiState.Success)?.data.orEmpty().filter { it.status == "READY" }
    val allStreams = (streamsState as? UiState.Success)?.data.orEmpty()
    val destinations = (destState as? UiState.Success)?.data.orEmpty()
    val isPaid = viewModel.isPaidOrAdmin(profile)

    val cloudRunning = allStreams.filter { it.cloudStream && (it.status == "RUNNING" || it.status == "RECONNECTING") }
    val scheduled = allStreams.filter { it.status == "SCHEDULED" }

    var showCreateSchedule by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("schedule_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cloud & Scheduled", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Server-side looping & 7-day continuous cloud workers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        if (!isPaid) onNavigateUpgrade()
                        else showCreateSchedule = true
                    },
                    modifier = Modifier.testTag("new_schedule_button")
                ) {
                    Icon(Icons.Filled.AddAlarm, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Schedule")
                }
            }
        }

        // Active Cloud Workers Monitor
        item {
            Text("Active Cloud Workers (${cloudRunning.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (cloudRunning.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.CloudQueue,
                    title = "No active cloud streams",
                    subtitle = "Cloud streams continue broadcasting to YouTube, Facebook, or Custom RTMP even after you close this app."
                )
            }
        } else {
            items(cloudRunning, key = { it.id }) { stream ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stream.title, style = MaterialTheme.typography.titleMedium)
                            StatusPill(
                                label = stream.status,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Text(
                            "Destinations: ${stream.destinations.joinToString()} • ${LoopMode.safeFrom(stream.loopMode).label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Worker CPU: ${stream.workerCpuPct}%", style = MaterialTheme.typography.labelMedium)
                            Text("RAM: ${stream.workerRamPct}%", style = MaterialTheme.typography.labelMedium)
                            Text("Limit: ${stream.durationLimitSeconds / 3600}h", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            "Elapsed: ${formatDurationHms(stream.elapsedSeconds)} • Reconnects: ${stream.reconnectAttempts}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(
                            onClick = { viewModel.stopOrCancelCloudStream(stream) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Stop Cloud Worker")
                        }
                    }
                }
            }
        }

        // Scheduled Queue
        item {
            Text("Scheduled Queue (${scheduled.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (scheduled.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.EventNote,
                    title = "No scheduled streams",
                    subtitle = "Create a one-time or recurring broadcast from any uploaded video.",
                    actionLabel = if (isPaid) "Schedule Stream" else "Upgrade for Scheduling",
                    onAction = { if (isPaid) showCreateSchedule = true else onNavigateUpgrade() }
                )
            }
        } else {
            items(scheduled, key = { it.id }) { item ->
                val dateText = item.scheduledStartAt?.toDate()?.let {
                    SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US).format(it)
                } ?: "Pending"
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            StatusPill(
                                label = item.recurringRule ?: "ONE-TIME",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text("Start: $dateText • Duration: ${item.durationLimitSeconds / 3600}h", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Video: ${item.videoTitle ?: "Uploaded Media"} • ${LoopMode.safeFrom(item.loopMode).label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { viewModel.duplicateScheduledStream(item) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Duplicate")
                            }
                            OutlinedButton(
                                onClick = { viewModel.stopOrCancelCloudStream(item) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateSchedule) {
        CreateScheduleDialog(
            videos = readyVideos,
            destinations = destinations,
            onDismiss = { showCreateSchedule = false },
            onCreate = { video, title, dests, loopMode, hours, delayHours, recurring ->
                val startAt = Date(System.currentTimeMillis() + delayHours * 3600_000L)
                viewModel.launchOrScheduleCloudStream(
                    video = video,
                    title = title,
                    destinations = dests,
                    orientation = OrientationMode.LANDSCAPE_16_9,
                    aspectFitMode = AspectFitMode.FIT,
                    resolution = "1080p",
                    fps = 30,
                    bitrateKbps = 6000,
                    loopMode = loopMode,
                    durationHours = hours,
                    scheduledDate = startAt,
                    recurringRule = recurring,
                    profile = profile
                )
                showCreateSchedule = false
            }
        )
    }
}

@Composable
private fun CreateScheduleDialog(
    videos: List<VideoAsset>,
    destinations: List<StreamDestination>,
    onDismiss: () -> Unit,
    onCreate: (
        video: VideoAsset,
        title: String,
        destinations: List<StreamDestination>,
        loopMode: LoopMode,
        durationHours: Int,
        delayHours: Int,
        recurringRule: String?
    ) -> Unit
) {
    var selectedVideo by remember { mutableStateOf(videos.firstOrNull()) }
    var title by remember { mutableStateOf(selectedVideo?.title ?: "24/7 Tutorial Replay") }
    var loopMode by remember { mutableStateOf(LoopMode.UNLIMITED) }
    var durationHours by remember { mutableStateOf(168) }
    var delayHours by remember { mutableStateOf(2) }
    var recurring by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Cloud Stream", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (videos.isEmpty()) {
                    Text("Upload and process at least one video in the Videos tab first.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Select Ready Video", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(videos, key = { it.id }) { v ->
                            FilterChip(
                                selected = selectedVideo?.id == v.id,
                                onClick = {
                                    selectedVideo = v
                                    title = v.title
                                },
                                label = { Text(v.title) }
                            )
                        }
                    }
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Schedule Title") }, singleLine = true)
                    Text("Start Offset", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1 to "+1h", 2 to "+2h", 12 to "+12h", 24 to "Tomorrow").forEach { (h, lbl) ->
                            FilterChip(selected = delayHours == h, onClick = { delayHours = h }, label = { Text(lbl) })
                        }
                    }
                    Text("Duration (up to 7 Days)", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf(6 to "6h", 24 to "24h", 72 to "3 Days", 168 to "7 Days")) { (h, lbl) ->
                            FilterChip(selected = durationHours == h, onClick = { durationHours = h }, label = { Text(lbl) })
                        }
                    }
                    Text("Recurrence & Loop", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to "One-time", "DAILY" to "Daily", "WEEKLY" to "Weekly").forEach { (rule, lbl) ->
                            FilterChip(selected = recurring == rule, onClick = { recurring = rule }, label = { Text(lbl) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedVideo != null,
                onClick = {
                    selectedVideo?.let { vid ->
                        onCreate(vid, title, destinations.take(2), loopMode, durationHours, delayHours, recurring)
                    }
                }
            ) { Text("Schedule Stream") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

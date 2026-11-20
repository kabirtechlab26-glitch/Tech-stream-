package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.StreamSession
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatBytesHuman
import com.example.ui.components.formatDurationHms
import com.example.ui.viewmodel.TechLiveViewModel
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AnalyticsScreen(viewModel: TechLiveViewModel) {
    val profileState by viewModel.userProfileState.collectAsState()
    val streamsState by viewModel.streamsState.collectAsState()

    val profile = (profileState as? UiState.Success)?.data
    val streams = (streamsState as? UiState.Success)?.data.orEmpty()

    var inspectedStream by remember { mutableStateOf<StreamSession?>(null) }

    val totalSeconds = streams.sumOf { it.elapsedSeconds }
    val totalStreams = streams.size
    val successful = streams.count { it.status in listOf("COMPLETED", "RUNNING", "STOPPED") }
    val failed = streams.count { it.status == "FAILED" }
    val active = streams.count { it.status in listOf("RUNNING", "RECONNECTING") }
    val avgDuration = if (totalStreams > 0) totalSeconds / totalStreams else 0L
    val totalBytes = streams.sumOf { it.bytesTransferred }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("analytics_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Stream Analytics & History", style = MaterialTheme.typography.headlineMedium)
        }

        // KPI Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Streaming Hours", formatDurationHms(totalSeconds), Modifier.weight(1f))
                    MetricCard("Total Streams", totalStreams.toString(), Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Success / Failed", "$successful / $failed", Modifier.weight(1f))
                    MetricCard("Avg Duration", formatDurationHms(avgDuration), Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Data Transferred", formatBytesHuman(totalBytes), Modifier.weight(1f))
                    MetricCard("Active / Storage", "$active live • ${formatBytesHuman(profile?.storageUsedBytes ?: 0L)}", Modifier.weight(1f))
                }
            }
        }

        // Duration Distribution Chart (Jetpack Compose Canvas)
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Recent Broadcast Duration Profile", style = MaterialTheme.typography.titleSmall)
                    val primaryColor = MaterialTheme.colorScheme.primary
                    val trackColor = MaterialTheme.colorScheme.surfaceVariant
                    val bars = streams.take(7).reversed().map { it.elapsedSeconds.coerceAtLeast(60L) }
                    if (bars.isEmpty()) {
                        Text(
                            "Complete at least one live or cloud stream to visualize session durations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val maxVal = bars.max().toFloat().coerceAtLeast(1f)
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                        ) {
                            val count = bars.size
                            val slotW = size.width / count.coerceAtLeast(1)
                            val barW = slotW * 0.55f
                            bars.forEachIndexed { idx, value ->
                                val x = idx * slotW + (slotW - barW) / 2f
                                val h = (value / maxVal) * (size.height - 16f)
                                drawRoundRect(
                                    color = trackColor,
                                    topLeft = Offset(x, 0f),
                                    size = Size(barW, size.height),
                                    cornerRadius = CornerRadius(12f, 12f)
                                )
                                drawRoundRect(
                                    color = primaryColor,
                                    topLeft = Offset(x, size.height - h),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(12f, 12f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Stream History List
        item {
            Text("Stream History", style = MaterialTheme.typography.titleMedium)
        }
        if (streams.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.History,
                    title = "No stream history yet",
                    subtitle = "Every live camera, screen share, and cloud session is logged here with telemetry."
                )
            }
        } else {
            items(streams, key = { it.id }) { stream ->
                val startStr = stream.startedAt?.toDate()?.let {
                    SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(it)
                } ?: "Queued"
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { inspectedStream = stream }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stream.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${stream.destinations.joinToString()} • $startStr • ${stream.resolution} ${stream.fps}fps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Duration: ${formatDurationHms(stream.elapsedSeconds)} • Reconnects: ${stream.reconnectAttempts}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        StatusPill(
                            label = stream.status,
                            containerColor = when (stream.status) {
                                "RUNNING" -> MaterialTheme.colorScheme.secondaryContainer
                                "FAILED" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = when (stream.status) {
                                "RUNNING" -> MaterialTheme.colorScheme.secondary
                                "FAILED" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }

    inspectedStream?.let { s ->
        AlertDialog(
            onDismissRequest = { inspectedStream = null },
            title = { Text(s.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Source: ${s.sourceType} (${if (s.cloudStream) "Cloud Worker" else "Mobile Device"})")
                    Text("Destinations: ${s.destinations.joinToString()}")
                    Text("Quality: ${s.resolution} @ ${s.fps} FPS • ${s.bitrateKbps} kbps")
                    Text("Orientation: ${s.orientation} (${s.aspectFitMode})")
                    Text("Elapsed: ${formatDurationHms(s.elapsedSeconds)} • Data: ${formatBytesHuman(s.bytesTransferred)}")
                    Text("Dropped Frames: ${String.format("%.1f", s.droppedFramesPct)}% • Reconnects: ${s.reconnectAttempts}")
                    Text("Status: ${s.status}")
                    if (!s.errorMessage.isNullOrBlank()) {
                        Text("Log: ${s.errorMessage}", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectedStream = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

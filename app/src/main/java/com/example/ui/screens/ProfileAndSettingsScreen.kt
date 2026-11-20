package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatBytesHuman
import com.example.ui.viewmodel.TechLiveViewModel
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ProfileAndSettingsScreen(
    viewModel: TechLiveViewModel,
    onOpenAdminConsole: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val profileState by viewModel.userProfileState.collectAsState()
    val settings by viewModel.systemSettings.collectAsState()
    val destState by viewModel.destinationsState.collectAsState()
    val presetsState by viewModel.presetsState.collectAsState()
    val paymentsState by viewModel.paymentsState.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    val darkThemeOverride by viewModel.darkThemeOverride.collectAsState()

    val profile = (profileState as? UiState.Success)?.data
    val destinations = (destState as? UiState.Success)?.data.orEmpty()
    val presets = (presetsState as? UiState.Success)?.data.orEmpty()
    val payments = (paymentsState as? UiState.Success)?.data.orEmpty()

    var showUpgradeDialog by remember { mutableStateOf(false) }
    var showAddDestinationDialog by remember { mutableStateOf(false) }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var showLegalDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.US) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("profile_settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Profile Header Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (!profile?.photoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = profile?.photoUrl,
                                contentDescription = "User profile avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Person,
                                    contentDescription = "Default profile icon",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = profile?.name ?: "Tech Live Creator",
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Filled.Verified,
                                    contentDescription = "Verified Google account",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = profile?.email ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "ID: ${profile?.userId?.take(14) ?: "..."}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Plan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if (isAdmin) "ADMIN (${profile?.plan ?: "PRO"})" else (profile?.plan ?: "FREE"),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text("Status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(profile?.subscriptionStatus ?: "ACTIVE", style = MaterialTheme.typography.titleSmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Joined", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = profile?.createdAt?.toDate()?.let { dateFormatter.format(it) } ?: "Today",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }

                    if (profile?.subscriptionStartAt != null || profile?.subscriptionExpiryAt != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Started: ${profile.subscriptionStartAt?.toDate()?.let { dateFormatter.format(it) } ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Expiry: ${profile.subscriptionExpiryAt?.toDate()?.let { dateFormatter.format(it) } ?: "Lifetime"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "Storage Used: ${formatBytesHuman(profile?.storageUsedBytes ?: 0L)} / ${formatBytesHuman(profile?.storageLimitBytes ?: 524288000L)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Admin Console Entry (strictly visible when role / claim check is true)
        if (isAdmin) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Admin Console",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Verify payments, monitor workers, moderate users & set global SaaS pricing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onOpenAdminConsole,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("open_admin_console_button")
                        ) {
                            Icon(Icons.Filled.AdminPanelSettings, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Open")
                        }
                    }
                }
            }
        }

        // Subscription & Payment Upgrade Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Subscription & Billing", style = MaterialTheme.typography.titleMedium)
                        StatusPill(
                            label = profile?.plan ?: "FREE",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Monthly: ${settings.monthlyPriceBdt} BDT/mo (${settings.monthlyStorageLimitMb / 1024} GB) • Lifetime: ${settings.lifetimePriceBdt} BDT one-time (${settings.lifetimeStorageLimitMb / 1024} GB). Unlock unlimited daily live time, multi-destination RTMP, 1 GB uploads & 7-day cloud workers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { showUpgradeDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("upgrade_plan_button")
                    ) {
                        Icon(Icons.Filled.WorkspacePremium, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Upgrade / Submit Payment (bKash • Nagad • Card)")
                    }
                }
            }
        }

        // Payment History
        if (payments.isNotEmpty()) {
            item {
                Text("Payment History (${payments.size})", style = MaterialTheme.typography.titleMedium)
            }
            items(payments, key = { it.id }) { tx ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${tx.plan} • ${tx.amountBdt} ${tx.currency} (${tx.paymentMethod})",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "TRX: ${tx.transactionReference} • Mode: ${tx.gatewayMode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!tx.adminNote.isNullOrBlank()) {
                                Text(
                                    "Note: ${tx.adminNote}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        StatusPill(
                            label = tx.status,
                            containerColor = when (tx.status) {
                                "PAID" -> MaterialTheme.colorScheme.tertiaryContainer
                                "REJECTED", "FAILED" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.primaryContainer
                            },
                            contentColor = when (tx.status) {
                                "PAID" -> MaterialTheme.colorScheme.tertiary
                                "REJECTED", "FAILED" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
        }

        // Streaming Destinations Manager
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("RTMP / RTMPS Destinations", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Stream keys encrypted locally with Android Keystore",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = { showAddDestinationDialog = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("add_destination_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        if (destinations.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.Router,
                    title = "No destinations configured",
                    subtitle = "Add YouTube, Facebook, Telegram, or Custom RTMP/RTMPS servers.",
                    actionLabel = "Add Destination",
                    onAction = { showAddDestinationDialog = true }
                )
            }
        } else {
            items(destinations, key = { it.id }) { dest ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(dest.name, style = MaterialTheme.typography.titleSmall)
                                StatusPill(
                                    label = dest.connectionStatus,
                                    containerColor = if (dest.connectionStatus == "CONNECTED")
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.errorContainer,
                                    contentColor = if (dest.connectionStatus == "CONNECTED")
                                        MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                "${dest.platform} • ${dest.protocol} • Up to ${dest.maxResolution} ${dest.maxFps}fps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Key: ${dest.maskedStreamKey} • ${dest.rtmpUrl}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { viewModel.deleteDestination(dest.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "Delete destination ${dest.name}",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // Stream Presets / Templates
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Stream Presets & Templates", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "One-tap encoder, orientation & layout setups",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = { showAddPresetDialog = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("add_preset_button")
                ) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
        }

        if (presets.isEmpty()) {
            item {
                Text(
                    "No presets saved yet. Save presets like 'Kotlin Tutorial 1080p' or 'Shorts 9:16'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(presets, key = { it.id }) { preset ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${preset.resolution} @ ${preset.fps}fps • ${preset.bitrateKbps} kbps • ${OrientationMode.safeFrom(preset.orientation).ratioLabel} (${AspectFitMode.safeFrom(preset.aspectFitMode).label})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewModel.deletePreset(preset.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete preset ${preset.name}")
                        }
                    }
                }
            }
        }

        // Preferences & Governance
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    ListItem(
                        headlineContent = { Text("Dark Broadcast Theme") },
                        supportingContent = { Text("High-contrast obsidian studio palette") },
                        leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = darkThemeOverride ?: true,
                                onCheckedChange = { viewModel.toggleTheme() },
                                modifier = Modifier.testTag("dark_theme_switch")
                            )
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ListItem(
                        headlineContent = { Text("Legal & Compliance Policies") },
                        supportingContent = { Text("Terms of Service, Privacy, Refund & Acceptable Use") },
                        leadingContent = { Icon(Icons.Filled.Gavel, contentDescription = null) },
                        trailingContent = {
                            TextButton(
                                onClick = { showLegalDialog = true },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("View") }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ListItem(
                        headlineContent = { Text("Report Stream / Abuse") },
                        supportingContent = { Text("Flag policy violations for admin review") },
                        leadingContent = { Icon(Icons.Filled.Flag, contentDescription = null) },
                        trailingContent = {
                            TextButton(
                                onClick = { showReportDialog = true },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("report_abuse_button")
                            ) { Text("Report") }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ListItem(
                        headlineContent = { Text("Help, Support & About Tech Live") },
                        supportingContent = { Text("RTMP guide, TikTok RTMP fallback & system version") },
                        leadingContent = { Icon(Icons.Filled.HelpOutline, contentDescription = null) },
                        trailingContent = {
                            TextButton(
                                onClick = { showSupportDialog = true },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Guide") }
                        }
                    )
                }
            }
        }

        // Account Actions: Sign Out & Delete Account
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        signOutUser(
                            credentialManager = credentialManager,
                            scope = scope,
                            onSignOutComplete = onSignedOut
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("logout_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out")
                }

                TextButton(
                    onClick = { showDeleteConfirmDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("delete_account_button")
                ) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete My Account")
                }
            }
        }
    }

    if (showUpgradeDialog) {
        PaymentUpgradeDialog(
            settings = settings,
            onDismiss = { showUpgradeDialog = false },
            onSubmit = { plan, method, trx, mode ->
                viewModel.submitPayment(plan, method, trx, mode, profile, settings)
                showUpgradeDialog = false
            }
        )
    }

    if (showAddDestinationDialog) {
        AddDestinationDialog(
            onDismiss = { showAddDestinationDialog = false },
            onAdd = { name, platform, protocol, url, key, channel ->
                viewModel.addDestination(name, platform, protocol, url, key, channel)
                showAddDestinationDialog = false
            }
        )
    }

    if (showAddPresetDialog) {
        AddPresetDialog(
            onDismiss = { showAddPresetDialog = false },
            onSave = { preset ->
                viewModel.savePreset(preset)
                showAddPresetDialog = false
            }
        )
    }

    if (showLegalDialog) {
        LegalPoliciesDialog(onDismiss = { showLegalDialog = false })
    }

    if (showReportDialog) {
        AbuseReportDialog(
            onDismiss = { showReportDialog = false },
            onSubmit = { streamId, reason, details ->
                viewModel.submitAbuseReport(streamId, reason, details)
                showReportDialog = false
            }
        )
    }

    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("Tech Live Support & Guide") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Live RTMP/RTMPS: Configure YouTube, Facebook, Telegram, or Custom RTMP/RTMPS endpoints under Destinations.")
                    Text("• TikTok Live: When direct OAuth eligibility is unavailable, select Custom RTMP/RTMPS with your TikTok Live Server URL and Stream Key.")
                    Text("• Cloud Workers: Scheduled and prerecorded 1h–168h streams run server-side via FFmpeg and continue independently when this app closes.")
                    Text("• Daily Quota: Free accounts receive 3 hours (03:00:00) of live streaming per day, resetting automatically every midnight.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showSupportDialog = false }) { Text("Got It") }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Account Permanently?") },
            text = {
                Text("This removes your Tech Live profile, uploaded metadata, and encrypted local stream keys. This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteAccount(onDeleted = onSignedOut)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Account")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PaymentUpgradeDialog(
    settings: SystemSettings,
    onDismiss: () -> Unit,
    onSubmit: (PlanType, PaymentMethod, String, String) -> Unit
) {
    var selectedPlan by remember { mutableStateOf(PlanType.MONTHLY) }
    var selectedMethod by remember { mutableStateOf(PaymentMethod.BKASH) }
    var gatewayMode by remember { mutableStateOf("MANUAL") }
    var trxReference by remember { mutableStateOf("") }

    val amount = if (selectedPlan == PlanType.MONTHLY) settings.monthlyPriceBdt else settings.lifetimePriceBdt

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upgrade Tech Live Plan") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Select Plan", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedPlan == PlanType.MONTHLY,
                        onClick = { selectedPlan = PlanType.MONTHLY },
                        label = { Text("Monthly (${settings.monthlyPriceBdt} BDT)") }
                    )
                    FilterChip(
                        selected = selectedPlan == PlanType.LIFETIME,
                        onClick = { selectedPlan = PlanType.LIFETIME },
                        label = { Text("Lifetime (${settings.lifetimePriceBdt} BDT)") }
                    )
                }

                Text("Payment Method", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaymentMethod.entries.forEach { method ->
                        FilterChip(
                            selected = selectedMethod == method,
                            onClick = { selectedMethod = method },
                            label = { Text(method.displayName) }
                        )
                    }
                }

                Text("Verification Mode", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = gatewayMode == "MANUAL",
                        onClick = { gatewayMode = "MANUAL" },
                        label = { Text("Manual TRX ID") }
                    )
                    FilterChip(
                        selected = gatewayMode == "AUTOMATIC",
                        onClick = { gatewayMode = "AUTOMATIC" },
                        label = { Text("Automatic Gateway") }
                    )
                }

                OutlinedTextField(
                    value = trxReference,
                    onValueChange = { trxReference = it },
                    label = {
                        Text(
                            if (gatewayMode == "MANUAL") "Enter bKash/Nagad/Card TRX ID"
                            else "Gateway Reference / Order ID"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payment_trx_input")
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Payable: $amount BDT via ${selectedMethod.displayName}. All submissions stay PENDING until verified by backend webhook or Admin.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(selectedPlan, selectedMethod, trxReference, gatewayMode) },
                enabled = trxReference.trim().length >= 4,
                modifier = Modifier.testTag("submit_payment_button")
            ) {
                Text("SUBMIT PAYMENT")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddDestinationDialog(
    onDismiss: () -> Unit,
    onAdd: (String, DestinationPlatform, String, String, String, String?) -> Unit
) {
    var platform by remember { mutableStateOf(DestinationPlatform.YOUTUBE) }
    var name by remember { mutableStateOf("My YouTube Channel") }
    var protocol by remember { mutableStateOf("RTMPS") }
    var rtmpUrl by remember { mutableStateOf(DestinationPlatform.YOUTUBE.defaultUrl) }
    var streamKey by remember { mutableStateOf("") }
    var channelName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Streaming Destination") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DestinationPlatform.entries) { p ->
                        FilterChip(
                            selected = platform == p,
                            onClick = {
                                platform = p
                                name = p.displayName
                                if (p.defaultUrl.isNotBlank()) rtmpUrl = p.defaultUrl
                                protocol = if (rtmpUrl.startsWith("rtmps")) "RTMPS" else "RTMP"
                            },
                            label = { Text(p.displayName) }
                        )
                    }
                }
                if (platform == DestinationPlatform.TIKTOK) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "TikTok Live direct API requires account eligibility. Use Custom RTMP/RTMPS if your TikTok account uses RTMP ingest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Destination Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("RTMPS", "RTMP").forEach { proto ->
                        FilterChip(
                            selected = protocol == proto,
                            onClick = { protocol = proto },
                            label = { Text(proto) }
                        )
                    }
                }
                OutlinedTextField(
                    value = rtmpUrl,
                    onValueChange = { rtmpUrl = it },
                    label = { Text("RTMP / RTMPS Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = streamKey,
                    onValueChange = { streamKey = it },
                    label = { Text("Stream Key (Encrypted in Keystore)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = channelName,
                    onValueChange = { channelName = it },
                    label = { Text("Channel / Page Name (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, platform, protocol, rtmpUrl, streamKey, channelName.takeIf { it.isNotBlank() }) },
                enabled = name.isNotBlank() && rtmpUrl.isNotBlank() && streamKey.isNotBlank()
            ) {
                Text("Save Destination")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddPresetDialog(
    onDismiss: () -> Unit,
    onSave: (StreamPreset) -> Unit
) {
    var name by remember { mutableStateOf("Tutorial 1080p 60fps") }
    var resolution by remember { mutableStateOf("1080p") }
    var fps by remember { mutableIntStateOf(60) }
    var orientation by remember { mutableStateOf(OrientationMode.LANDSCAPE_16_9) }
    var fitMode by remember { mutableStateOf(AspectFitMode.FIT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Stream Preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("720p", "1080p", "1440p", "2160p").forEach { res ->
                        FilterChip(selected = resolution == res, onClick = { resolution = res }, label = { Text(res) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(24, 30, 60).forEach { f ->
                        FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text("${f}fps") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OrientationMode.entries.forEach { o ->
                        FilterChip(selected = orientation == o, onClick = { orientation = o }, label = { Text(o.ratioLabel) })
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(AspectFitMode.entries) { m ->
                        FilterChip(selected = fitMode == m, onClick = { fitMode = m }, label = { Text(m.label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bitrate = when (resolution) {
                        "2160p" -> 16000L
                        "1440p" -> 9000L
                        "1080p" -> 6000L
                        else -> 3500L
                    }
                    onSave(
                        StreamPreset(
                            name = name,
                            resolution = resolution,
                            fps = fps.toLong(),
                            bitrateKbps = bitrate,
                            audioBitrateKbps = 160L,
                            orientation = orientation.name,
                            aspectFitMode = fitMode.name
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("Save Preset")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LegalPoliciesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Legal & Compliance Policies") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Terms of Service", fontWeight = FontWeight.Bold)
                Text(
                    "Tech Live is provided for legitimate software engineering tutorials, coding livestreams, and community Q&A. Broadcasters confirm they own or hold licenses to all streamed video, audio, and codebase demonstrations.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Privacy Policy", fontWeight = FontWeight.Bold)
                Text(
                    "Google Sign-In authenticates your account. Stream keys are encrypted locally using Android Keystore AES-256-GCM and never transmitted in plain logs. You may delete your account anytime from Profile.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Refund & Subscription Policy", fontWeight = FontWeight.Bold)
                Text(
                    "Monthly and Lifetime BDT plans unlock extended quotas once verified. Verified refunds are processed by an administrator upon valid dispute review within 7 days of activation.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Acceptable Use Policy", fontWeight = FontWeight.Bold)
                Text(
                    "Pirated content, unauthorized restreaming, malware tutorials, harassment, or automated quota circumvention result in immediate suspension or ban.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun AbuseReportDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit
) {
    var streamId by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("Copyright / Unauthorized Restream") }
    var details by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report Content or Abuse") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = streamId,
                    onValueChange = { streamId = it },
                    label = { Text("Stream ID or Channel (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Details") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(streamId, reason, details) },
                enabled = reason.trim().length >= 2 && details.trim().length >= 2
            ) {
                Text("Submit Report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

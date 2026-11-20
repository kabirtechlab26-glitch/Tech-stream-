package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatBytesHuman
import com.example.ui.components.formatDurationHms
import com.example.ui.viewmodel.TechLiveViewModel
import com.example.ui.viewmodel.UiState

enum class AdminSection(val label: String) {
    DASHBOARD("Dashboard"),
    USERS("Users"),
    STREAMS("Streams"),
    VIDEOS("Videos"),
    PAYMENTS("Payments"),
    SUBSCRIPTIONS("Subscriptions"),
    REVENUE("Revenue"),
    WITHDRAWALS("Withdrawals"),
    SERVERS("Servers"),
    REPORTS("Reports"),
    SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    viewModel: TechLiveViewModel,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val isAdmin by viewModel.isAdmin.collectAsState()
    if (!isAdmin) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Restricted Access") },
                    navigationIcon = {
                        IconButton(onClick = onClose, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyStateCard(
                    icon = Icons.Filled.Lock,
                    title = "Admin Access Denied",
                    subtitle = "Your account does not hold verified administrator permissions.",
                    actionLabel = "Go Back",
                    onAction = onClose
                )
            }
        }
        return
    }

    val users by viewModel.adminUsers.collectAsState()
    val payments by viewModel.adminPayments.collectAsState()
    val withdrawals by viewModel.adminWithdrawals.collectAsState()
    val reports by viewModel.adminReports.collectAsState()
    val auditLogs by viewModel.adminAuditLogs.collectAsState()
    val streamsState by viewModel.streamsState.collectAsState()
    val videosState by viewModel.videosState.collectAsState()
    val settings by viewModel.systemSettings.collectAsState()
    val serverHealthState by viewModel.adminServerHealth.collectAsState()

    val streams = (streamsState as? UiState.Success)?.data.orEmpty()
    val videos = (videosState as? UiState.Success)?.data.orEmpty()

    var selectedSection by remember { mutableStateOf(AdminSection.DASHBOARD) }
    var userSearchQuery by remember { mutableStateOf("") }
    var verifyingPayment by remember { mutableStateOf<PaymentTransaction?>(null) }
    var editingUser by remember { mutableStateOf<UserProfile?>(null) }

    // Real Revenue & Available Balance calculation strictly from verified transactions
    val paidRevenueBdt = payments.filter { it.status == "PAID" }.sumOf { it.amountBdt }
    val refundedBdt = payments.filter { it.status == "REFUNDED" }.sumOf { it.amountBdt }
    val committedWithdrawalsBdt = withdrawals
        .filter { it.status in listOf("PENDING", "PROCESSING", "COMPLETED") }
        .sumOf { it.amountBdt }
    val availableBalanceBdt = (paidRevenueBdt - refundedBdt - committedWithdrawalsBdt).coerceAtLeast(0L)
    val pendingPaymentsCount = payments.count { it.status == "PENDING" }
    val openReportsCount = reports.count { it.status == "OPEN" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tech Live Admin Console", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("close_admin_console_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Admin Console")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = AdminSection.entries.indexOf(selectedSection),
                edgePadding = 12.dp
            ) {
                AdminSection.entries.forEach { section ->
                    Tab(
                        selected = selectedSection == section,
                        onClick = {
                            selectedSection = section
                            if (section == AdminSection.SERVERS) {
                                viewModel.refreshAdminServerHealth()
                            }
                        },
                        text = { Text(section.label) },
                        modifier = Modifier.testTag("admin_tab_${section.name.lowercase()}")
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("admin_content_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedSection) {
                    AdminSection.DASHBOARD -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AdminKpiCard("Total Users", users.size.toString(), Modifier.weight(1f))
                                    AdminKpiCard("Verified Revenue", "$paidRevenueBdt BDT", Modifier.weight(1f))
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AdminKpiCard("Pending Payments", pendingPaymentsCount.toString(), Modifier.weight(1f))
                                    AdminKpiCard("Open Reports", openReportsCount.toString(), Modifier.weight(1f))
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AdminKpiCard("Active Subscriptions", users.count { it.plan != "FREE" && it.subscriptionStatus == "ACTIVE" }.toString(), Modifier.weight(1f))
                                    AdminKpiCard("Available Balance", "$availableBalanceBdt BDT", Modifier.weight(1f))
                                }
                            }
                        }
                        item {
                            Text("Recent Security & Audit Logs", style = MaterialTheme.typography.titleMedium)
                        }
                        if (auditLogs.isEmpty()) {
                            item {
                                Text(
                                    "No audit events recorded yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            items(auditLogs.take(15), key = { it.id }) { log ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(log.action, style = MaterialTheme.typography.titleSmall)
                                            StatusPill(
                                                label = log.severity,
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(log.details, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    AdminSection.USERS -> {
                        item {
                            OutlinedTextField(
                                value = userSearchQuery,
                                onValueChange = { userSearchQuery = it },
                                label = { Text("Search users by name or email") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        val filteredUsers = users.filter {
                            userSearchQuery.isBlank() ||
                                it.name.contains(userSearchQuery, ignoreCase = true) ||
                                it.email.contains(userSearchQuery, ignoreCase = true)
                        }
                        if (filteredUsers.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    icon = Icons.Filled.People,
                                    title = "No matching users",
                                    subtitle = "Registered Google accounts appear here in real time."
                                )
                            }
                        } else {
                            items(filteredUsers, key = { it.id }) { u ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(u.name, style = MaterialTheme.typography.titleSmall)
                                            Text(u.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                "${u.plan} • ${u.accountStatus} • Storage ${formatBytesHuman(u.storageUsedBytes)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { editingUser = u },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text("Manage")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdminSection.STREAMS -> {
                        item {
                            Text("Broadcast & Cloud Sessions (${streams.size})", style = MaterialTheme.typography.titleMedium)
                        }
                        if (streams.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    icon = Icons.Filled.LiveTv,
                                    title = "No active or recent streams",
                                    subtitle = "Live and cloud worker sessions appear here with bitrate & FPS metrics."
                                )
                            }
                        } else {
                            items(streams, key = { it.id }) { s ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(s.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            StatusPill(s.status, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
                                        }
                                        Text(
                                            "${s.sourceType} • ${s.resolution} ${s.fps}fps • ${formatDurationHms(s.elapsedSeconds)} • Reconnects: ${s.reconnectAttempts}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AdminSection.VIDEOS -> {
                        item {
                            Text("Uploaded Media Assets (${videos.size})", style = MaterialTheme.typography.titleMedium)
                        }
                        if (videos.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    icon = Icons.Filled.VideoLibrary,
                                    title = "No uploaded videos",
                                    subtitle = "Uploaded MP4/MOV assets and transcoding status appear here."
                                )
                            }
                        } else {
                            items(videos, key = { it.id }) { v ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(v.title, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "${formatBytesHuman(v.fileSizeBytes)} • ${v.resolution} • ${v.status}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        StatusPill(v.status, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    AdminSection.PAYMENTS -> {
                        item {
                            Text("Payment Verification Queue ($pendingPaymentsCount pending)", style = MaterialTheme.typography.titleMedium)
                        }
                        if (payments.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    icon = Icons.Filled.ReceiptLong,
                                    title = "No payment transactions",
                                    subtitle = "bKash, Nagad, and Card transactions awaiting verification appear here."
                                )
                            }
                        } else {
                            items(payments, key = { it.id }) { p ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${p.userName} (${p.userEmail})", style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    "${p.plan} • ${p.amountBdt} ${p.currency} • ${p.paymentMethod} (${p.gatewayMode})",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    "TRX: ${p.transactionReference}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            StatusPill(
                                                label = p.status,
                                                containerColor = when (p.status) {
                                                    "PAID" -> MaterialTheme.colorScheme.tertiaryContainer
                                                    "REJECTED", "FAILED" -> MaterialTheme.colorScheme.errorContainer
                                                    else -> MaterialTheme.colorScheme.primaryContainer
                                                },
                                                contentColor = when (p.status) {
                                                    "PAID" -> MaterialTheme.colorScheme.tertiary
                                                    "REJECTED", "FAILED" -> MaterialTheme.colorScheme.error
                                                    else -> MaterialTheme.colorScheme.primary
                                                }
                                            )
                                        }
                                        if (p.status == "PENDING") {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = { verifyingPayment = p },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .heightIn(min = 48.dp)
                                                        .testTag("admin_verify_payment_${p.id}")
                                                ) {
                                                    Text("Approve / Reject")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdminSection.SUBSCRIPTIONS -> {
                        val paidUsers = users.filter { it.plan != "FREE" }
                        item {
                            Text("Paid Subscriptions (${paidUsers.size})", style = MaterialTheme.typography.titleMedium)
                        }
                        if (paidUsers.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    icon = Icons.Filled.WorkspacePremium,
                                    title = "No active paid subscriptions yet",
                                    subtitle = "Approve a Monthly or Lifetime payment or assign a plan under Users."
                                )
                            }
                        } else {
                            items(paidUsers, key = { it.id }) { u ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(u.name, style = MaterialTheme.typography.titleSmall)
                                            Text("${u.email} • ${u.plan} (${u.subscriptionStatus})", style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = { editingUser = u }) { Text("Edit") }
                                    }
                                }
                            }
                        }
                    }

                    AdminSection.REVENUE -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AdminKpiCard("Gross Verified Revenue", "$paidRevenueBdt BDT", Modifier.fillMaxWidth())
                                AdminKpiCard("Refunded Amount", "$refundedBdt BDT", Modifier.fillMaxWidth())
                                AdminKpiCard("Withdrawals Committed / Completed", "$committedWithdrawalsBdt BDT", Modifier.fillMaxWidth())
                                AdminKpiCard("Available Withdrawable Balance", "$availableBalanceBdt BDT", Modifier.fillMaxWidth())
                                Text(
                                    "Note: Totals are calculated solely from verified PAID transactions in Firestore minus REFUNDS and WITHDRAWALS.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    AdminSection.WITHDRAWALS -> {
                        item {
                            WithdrawalRequestCard(
                                availableBalanceBdt = availableBalanceBdt,
                                onRequest = { amount, method, dest ->
                                    viewModel.adminRequestWithdrawal(amount, method, dest, availableBalanceBdt)
                                }
                            )
                        }
                        item {
                            Text("Withdrawal History (${withdrawals.size})", style = MaterialTheme.typography.titleMedium)
                        }
                        items(withdrawals, key = { it.id }) { w ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${w.amountBdt} BDT • ${w.method}", style = MaterialTheme.typography.titleSmall)
                                            Text("To: ${w.destination}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        StatusPill(w.status, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
                                    }
                                    if (w.status == "PENDING" || w.status == "PROCESSING") {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { viewModel.adminUpdateWithdrawalStatus(w.id, "COMPLETED") },
                                                modifier = Modifier.heightIn(min = 48.dp)
                                            ) { Text("Mark Completed") }
                                            TextButton(
                                                onClick = { viewModel.adminUpdateWithdrawalStatus(w.id, "REJECTED") },
                                                modifier = Modifier.heightIn(min = 48.dp)
                                            ) { Text("Reject") }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdminSection.SERVERS -> {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Cloud FFmpeg Workers & Node Health", style = MaterialTheme.typography.titleMedium)
                                        IconButton(onClick = { viewModel.refreshAdminServerHealth() }, modifier = Modifier.minimumInteractiveComponentSize()) {
                                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh server health")
                                        }
                                    }
                                    when (val state = serverHealthState) {
                                        null -> Text("Tap Refresh to query live backend FFmpeg worker telemetry.", style = MaterialTheme.typography.bodySmall)
                                        is UiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        is UiState.Error -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                        is UiState.Success -> {
                                            val h = state.data
                                            Text("Status: ${h.serverStatus} • CPU: ${h.cpuPct}% • RAM: ${h.ramPct}% • Active Workers: ${h.activeWorkers}", style = MaterialTheme.typography.bodyMedium)
                                            h.workers.forEach { w ->
                                                Text("Worker ${w.streamId}: ${w.status} (${w.cpuPct}% CPU, ${w.reconnectAttempts} reconnects)", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdminSection.REPORTS -> {
                        item {
                            Text("Content & Abuse Moderation (${reports.size})", style = MaterialTheme.typography.titleMedium)
                        }
                        if (reports.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    icon = Icons.Filled.Shield,
                                    title = "No abuse reports",
                                    subtitle = "User-submitted stream or conduct reports appear here for admin action."
                                )
                            }
                        } else {
                            items(reports, key = { it.id }) { r ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(r.reason, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                            StatusPill(r.status, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary)
                                        }
                                        Text("Target: ${r.targetStreamId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Text(r.details, style = MaterialTheme.typography.bodySmall)
                                        if (r.status == "OPEN") {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = { viewModel.adminResolveReport(r.id, "REVIEWED") },
                                                    modifier = Modifier.heightIn(min = 48.dp)
                                                ) { Text("Mark Reviewed") }
                                                OutlinedButton(
                                                    onClick = { viewModel.adminResolveReport(r.id, "DISMISSED") },
                                                    modifier = Modifier.heightIn(min = 48.dp)
                                                ) { Text("Dismiss") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdminSection.SETTINGS -> {
                        item {
                            GlobalSettingsEditor(
                                current = settings,
                                onSave = { updated -> viewModel.adminSaveSettings(updated) }
                            )
                        }
                    }
                }
            }
        }
    }

    verifyingPayment?.let { p ->
        var note by remember(p.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { verifyingPayment = null },
            title = { Text("Verify Payment") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${p.userName} (${p.userEmail})")
                    Text("${p.plan} Plan — ${p.amountBdt} BDT via ${p.paymentMethod}")
                    Text("TRX Reference: ${p.transactionReference}", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Admin verification note") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.adminVerifyPayment(p, approve = true, note = note.ifBlank { "Verified by Admin" })
                        verifyingPayment = null
                    },
                    modifier = Modifier.testTag("admin_approve_payment_button")
                ) {
                    Text("Approve & Activate")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.adminVerifyPayment(p, approve = false, note = note.ifBlank { "TRX mismatch" })
                            verifyingPayment = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Reject") }
                    TextButton(onClick = { verifyingPayment = null }) { Text("Cancel") }
                }
            }
        )
    }

    editingUser?.let { u ->
        var selectedStatus by remember(u.id) { mutableStateOf(AccountStatus.safeFrom(u.accountStatus)) }
        var selectedPlan by remember(u.id) { mutableStateOf(PlanType.safeFrom(u.plan)) }
        AlertDialog(
            onDismissRequest = { editingUser = null },
            title = { Text("Manage ${u.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(u.email, style = MaterialTheme.typography.bodySmall)
                    Text("Account Status", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccountStatus.entries.forEach { st ->
                            FilterChip(selected = selectedStatus == st, onClick = { selectedStatus = st }, label = { Text(st.name) })
                        }
                    }
                    Text("Subscription Plan", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlanType.entries.forEach { pl ->
                            FilterChip(selected = selectedPlan == pl, onClick = { selectedPlan = pl }, label = { Text(pl.name) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.adminUpdateUser(u.userId, selectedStatus, selectedPlan)
                    editingUser = null
                }) { Text("Save Changes") }
            },
            dismissButton = {
                TextButton(onClick = { editingUser = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AdminKpiCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WithdrawalRequestCard(
    availableBalanceBdt: Long,
    onRequest: (Long, String, String) -> Unit
) {
    var amountStr by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("bKash Merchant") }
    var destination by remember { mutableStateOf("") }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Request Revenue Withdrawal", style = MaterialTheme.typography.titleMedium)
            Text("Available Balance: $availableBalanceBdt BDT (min 100 BDT)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = amountStr,
                onValueChange = { amountStr = it.filter { ch -> ch.isDigit() } },
                label = { Text("Amount (BDT)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = method,
                onValueChange = { method = it },
                label = { Text("Payout Method (bKash / Nagad / Bank)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Account / Wallet Number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val amt = amountStr.toLongOrNull() ?: 0L
                    onRequest(amt, method, destination)
                },
                enabled = (amountStr.toLongOrNull() ?: 0L) >= 100L && destination.trim().length >= 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("Submit Withdrawal Request")
            }
        }
    }
}

@Composable
private fun GlobalSettingsEditor(
    current: SystemSettings,
    onSave: (SystemSettings) -> Unit
) {
    var freeLimitSec by remember(current) { mutableStateOf(current.freeDailyLimitSeconds.toString()) }
    var monthlyBdt by remember(current) { mutableStateOf(current.monthlyPriceBdt.toString()) }
    var lifetimeBdt by remember(current) { mutableStateOf(current.lifetimePriceBdt.toString()) }
    var maxUploadMb by remember(current) { mutableStateOf(current.maxUploadSizeMb.toString()) }
    var maxHours by remember(current) { mutableStateOf(current.maxStreamDurationHours.toString()) }
    var maxConcurrent by remember(current) { mutableStateOf(current.maxConcurrentStreams.toString()) }
    var maintenance by remember(current) { mutableStateOf(current.maintenanceMode) }
    var announcement by remember(current) { mutableStateOf(current.announcementMessage) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Global SaaS Controls", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = freeLimitSec,
                onValueChange = { freeLimitSec = it.filter(Char::isDigit) },
                label = { Text("Free Daily Limit (seconds, default 10800)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = monthlyBdt,
                    onValueChange = { monthlyBdt = it.filter(Char::isDigit) },
                    label = { Text("Monthly BDT") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lifetimeBdt,
                    onValueChange = { lifetimeBdt = it.filter(Char::isDigit) },
                    label = { Text("Lifetime BDT") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxUploadMb,
                    onValueChange = { maxUploadMb = it.filter(Char::isDigit) },
                    label = { Text("Max Upload (MB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxHours,
                    onValueChange = { maxHours = it.filter(Char::isDigit) },
                    label = { Text("Max Hours (1-168)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = maxConcurrent,
                onValueChange = { maxConcurrent = it.filter(Char::isDigit) },
                label = { Text("Max Concurrent Streams") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = announcement,
                onValueChange = { announcement = it },
                label = { Text("Global Announcement Banner") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Maintenance Mode")
                Switch(checked = maintenance, onCheckedChange = { maintenance = it })
            }
            Button(
                onClick = {
                    onSave(
                        current.copy(
                            freeDailyLimitSeconds = (freeLimitSec.toLongOrNull() ?: 10800L).coerceIn(60L, 86400L),
                            monthlyPriceBdt = (monthlyBdt.toLongOrNull() ?: 100L).coerceIn(1L, 100000L),
                            lifetimePriceBdt = (lifetimeBdt.toLongOrNull() ?: 1500L).coerceIn(1L, 500000L),
                            maxUploadSizeMb = (maxUploadMb.toLongOrNull() ?: 1024L).coerceIn(10L, 10240L),
                            maxStreamDurationHours = (maxHours.toLongOrNull() ?: 168L).coerceIn(1L, 168L),
                            maxConcurrentStreams = (maxConcurrent.toLongOrNull() ?: 3L).coerceIn(1L, 20L),
                            maintenanceMode = maintenance,
                            announcementMessage = announcement
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("admin_save_settings_button")
            ) {
                Text("Save Global Settings")
            }
        }
    }
}

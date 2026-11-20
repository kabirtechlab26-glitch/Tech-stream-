package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.network.BackendApiService
import com.example.data.repository.TechLiveRepository
import com.example.data.security.SecureCredentialVault
import com.example.ui.components.ConnectivityStatusBanner
import com.example.ui.screens.*
import com.example.ui.theme.TechLiveTheme
import com.example.ui.viewmodel.TechLiveViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

enum class MainTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: String
) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home, "home"),
    LIVE("Live", Icons.Filled.Podcasts, Icons.Outlined.Podcasts, "live"),
    VIDEOS("Videos", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, "videos"),
    SCHEDULE("Schedule", Icons.Filled.Schedule, Icons.Outlined.Schedule, "schedule"),
    ANALYTICS("Stats", Icons.Filled.Analytics, Icons.Outlined.Analytics, "analytics"),
    PROFILE("Profile", Icons.Filled.Person, Icons.Outlined.Person, "profile")
}

private fun FirebaseAuth.authUserFlow() = callbackFlow<FirebaseUser?> {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    trySend(currentUser)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val auth = remember { Firebase.auth }
            val currentUser by remember(auth) { auth.authUserFlow() }.collectAsState(initial = auth.currentUser)

            if (currentUser == null) {
                TechLiveTheme(darkTheme = true) {
                    AuthScreen(onAuthSuccess = { /* authUserFlow emits automatically */ })
                }
            } else {
                val signedInUser = currentUser!!
                key(signedInUser.uid) {
                    val context = LocalContext.current.applicationContext
                    val viewModel: TechLiveViewModel = viewModel(
                        key = signedInUser.uid,
                        factory = viewModelFactory {
                            initializer {
                                val dbId = context.getString(R.string.firestore_database_id)
                                val firestore = FirebaseFirestore.getInstance(dbId)
                                val repo = TechLiveRepository(firestore, auth)
                                val vault = SecureCredentialVault(context)
                                val api = BackendApiService.create()
                                TechLiveViewModel(
                                    repository = repo,
                                    vault = vault,
                                    api = api,
                                    currentUserId = signedInUser.uid,
                                    auth = auth
                                )
                            }
                        }
                    )
                    TechLiveAuthenticatedApp(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun TechLiveAuthenticatedApp(viewModel: TechLiveViewModel) {
    val context = LocalContext.current
    val darkThemeOverride by viewModel.darkThemeOverride.collectAsState()
    val networkState by viewModel.networkState.collectAsState()
    val bannerMessage by viewModel.bannerMessage.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()

    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var startLiveInScreenMode by remember { mutableStateOf(false) }
    var showAdminConsole by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.observeConnectivity(context)
    }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    if (currentTab != MainTab.HOME && !showAdminConsole) {
        BackHandler { currentTab = MainTab.HOME }
    }

    TechLiveTheme(darkTheme = darkThemeOverride ?: true) {
        if (showAdminConsole && isAdmin) {
            AdminDashboardScreen(
                viewModel = viewModel,
                onClose = { showAdminConsole = false }
            )
            return@TechLiveTheme
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 600.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!useNavigationRail) {
                        NavigationBar(
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            MainTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (tab != MainTab.LIVE) startLiveInScreenMode = false
                                        currentTab = tab
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label, maxLines = 1) },
                                    modifier = Modifier.testTag("nav_${tab.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavigationRail) {
                        NavigationRail {
                            Spacer(Modifier.height(12.dp))
                            MainTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = {
                                        if (tab != MainTab.LIVE) startLiveInScreenMode = false
                                        currentTab = tab
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag("rail_${tab.route}")
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ConnectivityStatusBanner(state = networkState)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .widthIn(max = 840.dp)
                        ) {
                            when (currentTab) {
                                MainTab.HOME -> HomeDashboardScreen(
                                    viewModel = viewModel,
                                    onNavigateLive = { screenMode ->
                                        startLiveInScreenMode = screenMode
                                        currentTab = MainTab.LIVE
                                    },
                                    onNavigateVideos = { currentTab = MainTab.VIDEOS },
                                    onNavigateSchedule = { currentTab = MainTab.SCHEDULE },
                                    onNavigateUpgrade = { currentTab = MainTab.PROFILE }
                                )

                                MainTab.LIVE -> LiveStudioScreen(
                                    viewModel = viewModel,
                                    initialScreenMode = startLiveInScreenMode,
                                    onNavigateUpgrade = { currentTab = MainTab.PROFILE }
                                )

                                MainTab.VIDEOS -> VideosScreen(
                                    viewModel = viewModel,
                                    onNavigateUpgrade = { currentTab = MainTab.PROFILE }
                                )

                                MainTab.SCHEDULE -> ScheduleScreen(
                                    viewModel = viewModel,
                                    onNavigateUpgrade = { currentTab = MainTab.PROFILE }
                                )

                                MainTab.ANALYTICS -> AnalyticsScreen(viewModel = viewModel)

                                MainTab.PROFILE -> ProfileAndSettingsScreen(
                                    viewModel = viewModel,
                                    onOpenAdminConsole = { showAdminConsole = true },
                                    onSignedOut = {
                                        showAdminConsole = false
                                        currentTab = MainTab.HOME
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil.Coil
import coil.ImageLoader
import com.example.data.ai.configureAppCheck
import com.example.data.network.AuctionHttpClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.update.AppUpdateStatus
import com.example.ui.components.AppUpdateDialog
import com.example.ui.components.BrowserLauncher
import com.example.ui.components.HiddenAttachedCarfaxWebView
import com.example.ui.screens.AuctionDetailScreen
import com.example.ui.screens.CalculatorScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.SourcesScreen
import com.example.ui.theme.EncanLiveTheme
import com.example.ui.viewmodel.EncanViewModel

const val ROUTE_EXPLORE = "explore"
const val ROUTE_FAVORITES = "favorites"
const val ROUTE_SOURCES = "sources"
const val ROUTE_CALCULATOR = "calculator"
const val ROUTE_DETAIL = "auction_detail"

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TopLevelDestinations = listOf(
    TopLevelDestination(
        route = ROUTE_EXPLORE,
        label = "Explorer",
        selectedIcon = Icons.Filled.Explore,
        unselectedIcon = Icons.Outlined.Explore,
        testTag = "nav_explore"
    ),
    TopLevelDestination(
        route = ROUTE_FAVORITES,
        label = "Favoris",
        selectedIcon = Icons.Filled.Bookmark,
        unselectedIcon = Icons.Outlined.BookmarkBorder,
        testTag = "nav_favorites"
    ),
    TopLevelDestination(
        route = ROUTE_SOURCES,
        label = "Sources",
        selectedIcon = Icons.Filled.Hub,
        unselectedIcon = Icons.Outlined.Hub,
        testTag = "nav_sources"
    ),
    TopLevelDestination(
        route = ROUTE_CALCULATOR,
        label = "Taxes",
        selectedIcon = Icons.Filled.Calculate,
        unselectedIcon = Icons.Outlined.Calculate,
        testTag = "nav_calculator"
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure Firebase App Check with launch intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)
        Coil.setImageLoader {
            ImageLoader.Builder(applicationContext)
                .okHttpClient(AuctionHttpClient.imageClient)
                .crossfade(true)
                .respectCacheHeaders(false)
                .build()
        }
        enableEdgeToEdge()
        setContent {
            EncanLiveTheme {
                EncanLiveApp()
            }
        }
    }
}

@Composable
fun EncanLiveApp(viewModel: EncanViewModel = viewModel()) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val exploreState by viewModel.exploreState.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val savedSearches by viewModel.savedSearches.collectAsStateWithLifecycle()
    val selectedLot by viewModel.selectedLot.collectAsStateWithLifecycle()
    val selectedFavoriteItem by viewModel.selectedFavoriteItem.collectAsStateWithLifecycle()
    val selectedLotStudyState by viewModel.selectedLotStudyState.collectAsStateWithLifecycle()
    val activeCarfaxRender by viewModel.activeCarfaxRender.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val calculatorState by viewModel.calculatorState.collectAsStateWithLifecycle()

    LaunchedEffect(exploreState.snackbarMessage) {
        val msg = exploreState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isDetailScreen = currentDestination?.route == ROUTE_DETAIL

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        // Activity-Window-attached silent CARFAX reader mounted behind the opaque Scaffold
        HiddenAttachedCarfaxWebView(request = activeCarfaxRender)

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!useNavRail && !isDetailScreen) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        TopLevelDestinations.forEach { dest ->
                            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    if (dest.route == ROUTE_FAVORITES && favorites.isNotEmpty()) {
                                        BadgedBox(badge = { Badge { Text("${favorites.size}") } }) {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = dest.label
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    }
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag)
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
                if (useNavRail && !isDetailScreen) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        TopLevelDestinations.forEach { dest ->
                            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_EXPLORE,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 840.dp)
                    ) {
                        composable(ROUTE_EXPLORE) {
                            ExploreScreen(
                                uiState = exploreState,
                                onQueryChange = viewModel::onQueryChange,
                                onSubmitSearch = viewModel::submitSearch,
                                onQuickKeyword = viewModel::runQuickKeyword,
                                onSelectProvince = viewModel::selectProvince,
                                onSelectCategory = viewModel::selectCategory,
                                onSelectSortOrder = viewModel::selectSortOrder,
                                onSetMaxPrice = viewModel::setMaxPriceFilter,
                                onSelectSourceFilter = viewModel::selectSourceFilter,
                                onToggleHideAccessories = viewModel::toggleHideAccessories,
                                onRefresh = viewModel::refreshLiveAuctions,
                                onSaveSearch = viewModel::saveCurrentSearch,
                                onLotClick = { lot ->
                                    viewModel.openLotDetail(lot.id)
                                    navController.navigate(ROUTE_DETAIL)
                                },
                                onStudyLotClick = { lot ->
                                    viewModel.openLotDetail(lot.id)
                                    viewModel.studyAuction(lot)
                                    navController.navigate(ROUTE_DETAIL)
                                },
                                onFavoriteToggle = viewModel::toggleFavorite,
                                onOpenSourcesTab = { navigateTopLevel(ROUTE_SOURCES) }
                            )
                        }

                        composable(ROUTE_FAVORITES) {
                            FavoritesScreen(
                                favorites = favorites,
                                savedSearches = savedSearches,
                                onLotClick = { lot ->
                                    viewModel.openLotDetail(lot.id)
                                    navController.navigate(ROUTE_DETAIL)
                                },
                                onStudyLotClick = { lot ->
                                    viewModel.openLotDetail(lot.id)
                                    viewModel.studyAuction(lot)
                                    navController.navigate(ROUTE_DETAIL)
                                },
                                onRemoveFavorite = viewModel::toggleFavorite,
                                onApplySavedSearch = { saved ->
                                    viewModel.applySavedSearch(saved)
                                    navigateTopLevel(ROUTE_EXPLORE)
                                },
                                onDeleteSavedSearch = viewModel::deleteSavedSearch,
                                onExploreClick = { navigateTopLevel(ROUTE_EXPLORE) }
                            )
                        }

                        composable(ROUTE_SOURCES) {
                            SourcesScreen(
                                selectedProvince = exploreState.selectedProvince,
                                currentQuery = exploreState.query,
                                sourceStatuses = exploreState.sourceStatuses,
                                enabledSources = exploreState.enabledSources,
                                isRefreshing = exploreState.isRefreshing,
                                appUpdateState = appUpdateState,
                                onToggleSource = viewModel::toggleSourceEnabled,
                                onEnableAll = viewModel::enableAllSources,
                                onRefreshConnections = viewModel::refreshLiveAuctions,
                                onCheckUpdatesNow = { viewModel.checkForAppUpdates(silent = false) },
                                onSaveUpdateRepo = viewModel::saveGithubUpdateRepo
                            )
                        }

                        composable(ROUTE_CALCULATOR) {
                            CalculatorScreen(
                                uiState = calculatorState,
                                onBidChange = viewModel::updateCalculatorBid,
                                onPremiumChange = viewModel::updateCalculatorPremium,
                                onExtraFeesChange = viewModel::updateCalculatorExtraFees,
                                onProvinceChange = viewModel::updateCalculatorProvince
                            )
                        }

                        composable(ROUTE_DETAIL) {
                            AuctionDetailScreen(
                                lot = selectedLot,
                                favoriteItem = selectedFavoriteItem,
                                studyState = selectedLotStudyState,
                                onBack = { navController.popBackStack() },
                                onStudyLot = { lot, forceRefresh ->
                                    viewModel.studyAuction(lot, forceRefresh = forceRefresh)
                                },
                                onAnalyzeCapturedCarfax = { lot, capturedText ->
                                    viewModel.studyAuctionWithCapturedCarfax(lot, capturedText)
                                },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onSaveFavoriteNotes = viewModel::updateFavoriteNotes,
                                onOpenInCalculator = { lot ->
                                    viewModel.prefillCalculatorFromLot(lot)
                                    navigateTopLevel(ROUTE_CALCULATOR)
                                }
                            )
                        }
                    }
                }
            }
        }

        val availableUpdate = appUpdateState.status as? AppUpdateStatus.UpdateAvailable
        if (appUpdateState.showDialog && availableUpdate != null) {
            AppUpdateDialog(
                updateInfo = availableUpdate.info,
                onInstallUpdate = { downloadUrl ->
                    viewModel.dismissUpdateDialog()
                    BrowserLauncher.openAuctionUrl(context, downloadUrl)
                },
                onOpenReleasePage = { releaseUrl ->
                    viewModel.dismissUpdateDialog()
                    BrowserLauncher.openAuctionUrl(context, releaseUrl)
                },
                onDismiss = viewModel::dismissUpdateDialog
            )
        }
    }
}

package com.insituledger.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.insituledger.app.ui.theme.AppSpacing
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.remote.dto.AccessibleOwnerDto
import com.insituledger.app.data.repository.PreferencesRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.SharedRepository
import com.insituledger.app.ui.accounts.AccountFormScreen
import com.insituledger.app.ui.accounts.AccountsScreen
import com.insituledger.app.ui.categories.CategoriesScreen
import com.insituledger.app.ui.categories.CategoryFormScreen
import com.insituledger.app.ui.dashboard.DashboardScreen
import com.insituledger.app.ui.login.LoginScreen
import com.insituledger.app.ui.reports.ReportsScreen
import com.insituledger.app.ui.scheduled.ScheduledFormScreen
import com.insituledger.app.ui.scheduled.ScheduledScreen
import com.insituledger.app.ui.settings.SettingsScreen
import com.insituledger.app.ui.shared.SharedScreen
import com.insituledger.app.ui.about.AboutScreen
import com.insituledger.app.ui.common.LocalSnackbarHostState
import com.insituledger.app.ui.transactions.TransactionFormScreen
import com.insituledger.app.ui.transactions.TransactionsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val label: String
)

private val bottomNavItems = listOf(
    BottomNavItem(Screen.Dashboard, Icons.Default.Home, "Home"),
    BottomNavItem(Screen.Transactions, Icons.Default.Receipt, "Transactions"),
    BottomNavItem(Screen.Scheduled, Icons.Default.CalendarMonth, "Scheduled"),
    BottomNavItem(Screen.More, Icons.Default.MoreHoriz, "More")
)

private val bottomNavRoutes = bottomNavItems.map { it.screen.route }.toSet()

@HiltViewModel
class SharedOwnerViewModel @Inject constructor(
    private val sharedRepository: SharedRepository,
    private val sharedAccessState: SharedAccessState,
    private val preferencesRepository: PreferencesRepository,
    private val prefs: UserPreferences
) : ViewModel() {
    val accessibleOwners = sharedAccessState.accessibleOwners
    val ownerFilter = sharedAccessState.ownerFilter
    val syncMode = prefs.syncModeFlow

    // Apply the server-side default account exactly once per process so that
    // an explicit user filter change isn't overridden.
    private var defaultApplied = false

    init {
        viewModelScope.launch {
            combine(prefs.syncModeFlow, prefs.tokenFlow) { mode, token -> mode to token }
                .collectLatest { (mode, token) ->
                if (mode == "webapp" && token != null) {
                    try {
                        val owners = sharedRepository.loadAccessibleOwners()
                        val savedFilter = prefs.sharedOwnerIdFlow.first()
                        if (savedFilter != null && owners.any { it.ownerUserId == savedFilter }) {
                            sharedAccessState.setOwnerFilter(savedFilter)
                        }
                        if (!defaultApplied) {
                            defaultApplied = true
                            applyServerDefaultAccount()
                        }
                    } catch (_: Exception) {
                        // Network error — ignore, will retry on next emission
                    }
                } else {
                    sharedAccessState.clear()
                    prefs.saveSharedOwnerId(null)
                    defaultApplied = false
                }
            }
        }
    }

    private suspend fun applyServerDefaultAccount() {
        val defaultId = preferencesRepository.loadFromServer().getOrNull() ?: return
        // Pin the per-form last-used account so the txn form lands on it. The
        // dashboard/transactions screens always aggregate, so no owner filter
        // needs to be set for the default account to be discoverable.
        prefs.saveLastUsedAccountId(defaultId)
    }

    fun setOwnerFilter(ownerId: Long?) {
        sharedAccessState.setOwnerFilter(ownerId)
        viewModelScope.launch { prefs.saveSharedOwnerId(ownerId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    newTransactionEvents: SharedFlow<Unit>,
    launchedFromWidget: Boolean = false
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute by remember {
        derivedStateOf { navBackStackEntry?.destination?.route }
    }
    val showBottomBar = currentRoute in bottomNavRoutes

    val sharedOwnerViewModel: SharedOwnerViewModel = hiltViewModel()
    val accessibleOwners by sharedOwnerViewModel.accessibleOwners.collectAsStateWithLifecycle()
    val ownerFilter by sharedOwnerViewModel.ownerFilter.collectAsStateWithLifecycle()
    val syncMode by sharedOwnerViewModel.syncMode.collectAsStateWithLifecycle(initialValue = "none")

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(navController) {
        if (launchedFromWidget) return@LaunchedEffect
        navController.currentBackStackEntryFlow.first()
        newTransactionEvents.collect {
            navController.navigate(Screen.TransactionForm.createRoute()) {
                launchSingleTop = true
            }
        }
    }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                Column {
                    // Owner filter dropdown when connected to webapp and there are co-owners
                    if (syncMode == "webapp" && accessibleOwners.isNotEmpty()) {
                        OwnerSwitcher(
                            owners = accessibleOwners,
                            selectedOwnerId = ownerFilter,
                            onSelect = { ownerId -> sharedOwnerViewModel.setOwnerFilter(ownerId) }
                        )
                    }
                    BottomNavBar(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = if (launchedFromWidget) Screen.TransactionForm.route else Screen.Dashboard.route,
                enterTransition = {
                    if (initialState.destination.route in bottomNavRoutes &&
                        targetState.destination.route in bottomNavRoutes) {
                        EnterTransition.None
                    } else {
                        slideInHorizontally(tween(150)) { it / 6 } + fadeIn(tween(150))
                    }
                },
                exitTransition = {
                    if (initialState.destination.route in bottomNavRoutes &&
                        targetState.destination.route in bottomNavRoutes) {
                        ExitTransition.None
                    } else {
                        fadeOut(tween(150))
                    }
                },
                popEnterTransition = { fadeIn(tween(150)) },
                popExitTransition = {
                    slideOutHorizontally(tween(150)) { it / 6 } + fadeOut(tween(150))
                }
            ) {
                composable(Screen.Dashboard.route) {
                    DashboardScreen(
                        onTransactionClick = { id ->
                            navController.navigate(Screen.TransactionForm.createRoute(id))
                        },
                        onAddClick = { navController.navigate(Screen.TransactionForm.createRoute()) }
                    )
                }

                composable(Screen.Transactions.route) {
                    TransactionsScreen(
                        onAddClick = { navController.navigate(Screen.TransactionForm.createRoute()) },
                        onTransactionClick = { id ->
                            navController.navigate(Screen.TransactionForm.createRoute(id))
                        }
                    )
                }

                composable(
                    Screen.TransactionForm.route,
                    arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })
                ) {
                    TransactionFormScreen(onBack = {
                        if (launchedFromWidget) {
                            (context as? android.app.Activity)?.finish()
                        } else {
                            navController.popBackStack()
                        }
                    })
                }

                composable(Screen.Scheduled.route) {
                    ScheduledScreen(
                        onAddClick = { navController.navigate(Screen.ScheduledForm.createRoute()) },
                        onEditClick = { id -> navController.navigate(Screen.ScheduledForm.createRoute(id)) }
                    )
                }

                composable(
                    Screen.ScheduledForm.route,
                    arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })
                ) {
                    ScheduledFormScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.More.route) {
                    MoreScreen(
                        onAccountsClick = { navController.navigate(Screen.Accounts.route) },
                        onCategoriesClick = { navController.navigate(Screen.Categories.route) },
                        onReportsClick = { navController.navigate(Screen.Reports.route) },
                        onSharedClick = { navController.navigate(Screen.Shared.route) },
                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                        onAboutClick = { navController.navigate(Screen.About.route) }
                    )
                }

                composable(Screen.Accounts.route) {
                    AccountsScreen(
                        onBack = { navController.popBackStack() },
                        onAddClick = { navController.navigate(Screen.AccountForm.createRoute()) },
                        onEditClick = { id -> navController.navigate(Screen.AccountForm.createRoute(id)) }
                    )
                }

                composable(
                    Screen.AccountForm.route,
                    arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })
                ) {
                    AccountFormScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Categories.route) {
                    CategoriesScreen(
                        onBack = { navController.popBackStack() },
                        onAddClick = { navController.navigate(Screen.CategoryForm.createRoute()) },
                        onEditClick = { id -> navController.navigate(Screen.CategoryForm.createRoute(id)) }
                    )
                }

                composable(
                    Screen.CategoryForm.route,
                    arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })
                ) {
                    CategoryFormScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Reports.route) {
                    ReportsScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Shared.route) {
                    SharedScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onConnectWebapp = { navController.navigate(Screen.Login.route) }
                    )
                }

                composable(Screen.About.route) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }

                // Login screen accessible from Settings for webapp sync setup
                composable(Screen.Login.route) {
                    LoginScreen(
                        onLoginSuccess = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun BottomNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationBar {
        bottomNavItems.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.screen.route,
                onClick = { onNavigate(item.screen.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = {
                    Text(
                        text = item.label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerSwitcher(
    owners: List<AccessibleOwnerDto>,
    selectedOwnerId: Long?,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = if (selectedOwnerId == null) {
        "All accounts"
    } else {
        val owner = owners.find { it.ownerUserId == selectedOwnerId }
        owner?.let { "${it.name} (${it.accounts.size})" } ?: "All accounts"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Text("Filter:", style = MaterialTheme.typography.labelSmall)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                FilterChip(
                    selected = selectedOwnerId != null,
                    onClick = { expanded = true },
                    label = { Text(currentLabel, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("All accounts") },
                        onClick = { onSelect(null); expanded = false }
                    )
                    owners.forEach { owner ->
                        DropdownMenuItem(
                            text = { Text("${owner.name} (${owner.accounts.size} account${if (owner.accounts.size == 1) "" else "s"})") },
                            onClick = { onSelect(owner.ownerUserId); expanded = false }
                        )
                    }
                }
            }
        }
    }
}

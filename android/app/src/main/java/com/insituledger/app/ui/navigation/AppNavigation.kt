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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.filled.ArrowDropDown
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
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalSnackbarController
import com.insituledger.app.ui.common.SnackbarController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.insituledger.app.ui.transactions.TransactionFormScreen
import com.insituledger.app.ui.transactions.TransactionsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String
)

private val bottomNavItems = listOf(
    BottomNavItem(Screen.Dashboard, Icons.Outlined.Home, Icons.Filled.Home, "Home"),
    BottomNavItem(Screen.Transactions, Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Filled.ReceiptLong, "Transactions"),
    BottomNavItem(Screen.Scheduled, Icons.Outlined.EventRepeat, Icons.Filled.EventRepeat, "Scheduled"),
    BottomNavItem(Screen.More, Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz, "More")
)

private val fabRoutes = setOf(
    Screen.Dashboard.route, Screen.Transactions.route, Screen.Scheduled.route,
    Screen.Accounts.route, Screen.Categories.route
)

/** Width at which the bottom bar becomes a navigation rail. */
private const val RAIL_MIN_WIDTH_DP = 600

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
    val authNotice = prefs.authNoticeFlow
    val autoLogoutPending = prefs.autoLogoutPendingFlow

    suspend fun clearAutoLogoutPending() {
        prefs.clearAutoLogoutPending()
    }

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
    val isTopLevel = currentRoute in bottomNavRoutes
    val useRail = LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP

    val sharedOwnerViewModel: SharedOwnerViewModel = hiltViewModel()
    val accessibleOwners by sharedOwnerViewModel.accessibleOwners.collectAsStateWithLifecycle()
    val ownerFilter by sharedOwnerViewModel.ownerFilter.collectAsStateWithLifecycle()
    val syncMode by sharedOwnerViewModel.syncMode.collectAsStateWithLifecycle(initialValue = "none")
    val showOwnerSwitcher = isTopLevel && syncMode == "webapp" && accessibleOwners.isNotEmpty()

    val snackbarHostState = remember { SnackbarHostState() }
    val appScope = rememberCoroutineScope()
    val snackbarController = remember(snackbarHostState, appScope) {
        SnackbarController(snackbarHostState, appScope)
    }

    val navigateTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Form screens report their result here. Feedback is shown from this
    // scope so it outlives the popped form; on the widget path the activity
    // finishes, so a toast is the only feedback that survives.
    val finishForm: () -> Unit = {
        if (launchedFromWidget) {
            (context as? android.app.Activity)?.finish()
        } else {
            navController.popBackStack()
        }
    }
    val onFormSaved: (message: String, offerScheduled: Boolean) -> Unit = { message, offerScheduled ->
        if (launchedFromWidget) {
            android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        } else if (offerScheduled) {
            snackbarController.show(message, actionLabel = "View") { navigateTopLevel(Screen.Scheduled.route) }
        } else {
            snackbarController.show(message)
        }
        finishForm()
    }

    LaunchedEffect(navController) {
        if (launchedFromWidget) return@LaunchedEffect
        navController.currentBackStackEntryFlow.first()
        newTransactionEvents.collect {
            navController.navigate(Screen.TransactionForm.createRoute()) {
                launchSingleTop = true
            }
        }
    }

    // Surface silent 401 auto-logouts. The flag is set by the auth
    // interceptor (UserPreferences.clearAuthSession) and persists across
    // restarts, so a logout that happened while a background SyncWorker was
    // running is still shown the next time the app is foregrounded.
    LaunchedEffect(Unit) {
        sharedOwnerViewModel.autoLogoutPending
            .filter { it }
            .collect {
                val result = snackbarHostState.showSnackbar(
                    message = sharedOwnerViewModel.authNotice.first(),
                    actionLabel = "Log in",
                    duration = SnackbarDuration.Indefinite,
                    withDismissAction = true,
                )
                sharedOwnerViewModel.clearAutoLogoutPending()
                if (result == SnackbarResult.ActionPerformed) {
                    navController.navigate(Screen.Login.route)
                }
            }
    }

    CompositionLocalProvider(
        LocalSnackbarController provides snackbarController
    ) {
    Scaffold(
        // Insets are handled once: inner screens' top bars take the status
        // bar, forms take the IME + navigation bar, the bottom bar takes its own.
        contentWindowInsets = WindowInsets(0),
        // Lift snackbars above the FAB on screens that have one.
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.padding(bottom = if (currentRoute in fabRoutes) 72.dp else 0.dp)
            )
        },
        bottomBar = {
            if (isTopLevel) {
                Column {
                    if (showOwnerSwitcher) {
                        OwnerSwitcher(
                            owners = accessibleOwners,
                            selectedOwnerId = ownerFilter,
                            onSelect = { ownerId -> sharedOwnerViewModel.setOwnerFilter(ownerId) },
                            padNavigationBar = useRail
                        )
                    }
                    if (!useRail) {
                        BottomNavBar(currentRoute = currentRoute, onNavigate = navigateTopLevel)
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            if (useRail && isTopLevel) {
                NavRail(currentRoute = currentRoute, onNavigate = navigateTopLevel)
            }
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Bound content width on large screens by widening the side padding.
                val sidePadding = max(AppSpacing.screenH, (maxWidth - AppSpacing.maxContentWidth) / 2)
                CompositionLocalProvider(LocalContentPadding provides sidePadding) {
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
                        onAddClick = { navController.navigate(Screen.TransactionForm.createRoute()) },
                        onSeeAllTransactions = { navigateTopLevel(Screen.Transactions.route) },
                        onManageAccounts = { navController.navigate(Screen.Accounts.route) }
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
                    TransactionFormScreen(
                        onBack = finishForm,
                        onSaved = onFormSaved
                    )
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
                    ScheduledFormScreen(
                        onBack = { navController.popBackStack() },
                        onSaved = { message -> snackbarController.show(message); navController.popBackStack() }
                    )
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
                    AccountFormScreen(
                        onBack = { navController.popBackStack() },
                        onSaved = { message -> snackbarController.show(message); navController.popBackStack() }
                    )
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
                    CategoryFormScreen(
                        onBack = { navController.popBackStack() },
                        onSaved = { message -> snackbarController.show(message); navController.popBackStack() }
                    )
                }

                composable(Screen.Reports.route) {
                    ReportsScreen(
                        onBack = { navController.popBackStack() },
                        onTransactionClick = { id -> navController.navigate(Screen.TransactionForm.createRoute(id)) }
                    )
                }

                composable(Screen.Shared.route) {
                    SharedScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSettings = { navController.navigate(Screen.Settings.route) }
                    )
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
    }
}

@Composable
private fun BottomNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        bottomNavItems.forEach { item ->
            val selected = currentRoute == item.screen.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.screen.route) },
                // The visible label already names the destination.
                icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                label = { NavLabel(item.label) }
            )
        }
    }
}

@Composable
private fun NavRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Spacer(Modifier.weight(1f))
        bottomNavItems.forEach { item ->
            val selected = currentRoute == item.screen.route
            NavigationRailItem(
                selected = selected,
                onClick = { onNavigate(item.screen.route) },
                icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                label = { NavLabel(item.label) }
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun OwnerSwitcher(
    owners: List<AccessibleOwnerDto>,
    selectedOwnerId: Long?,
    onSelect: (Long?) -> Unit,
    padNavigationBar: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = if (selectedOwnerId == null) {
        "All accounts"
    } else {
        owners.find { it.ownerUserId == selectedOwnerId }?.name ?: "All accounts"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = if (padNavigationBar) Modifier.navigationBarsPadding() else Modifier
            ) {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier.padding(horizontal = AppSpacing.sm)
                ) {
                    Text(
                        "Viewing: $currentLabel",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("All accounts") },
                        onClick = { onSelect(null); expanded = false }
                    )
                    owners.forEach { owner ->
                        val count = owner.accounts.size
                        DropdownMenuItem(
                            text = { Text("${owner.name} · $count account${if (count == 1) "" else "s"}") },
                            onClick = { onSelect(owner.ownerUserId); expanded = false }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Navigation labels scale with the system font up to 1.3× (1.0× under 360dp), then stop: past
 * that four labels can't fit a 320dp bar and would truncate to "Ho…". The
 * icons and TalkBack labels carry the meaning beyond that point.
 */
@Composable
private fun NavLabel(text: String) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // On narrow phones even 1.3× doesn't fit "Transactions" in a quarter of the bar.
    val cap = if (LocalConfiguration.current.screenWidthDp < 360) 1.0f else 1.3f
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
            density.density, density.fontScale.coerceAtMost(cap)
        )
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
}

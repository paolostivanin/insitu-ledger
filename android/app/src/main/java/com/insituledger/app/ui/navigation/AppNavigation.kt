package com.insituledger.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.insituledger.app.ui.accounts.AccountFormScreen
import com.insituledger.app.ui.accounts.AccountsScreen
import com.insituledger.app.ui.categories.CategoriesScreen
import com.insituledger.app.ui.categories.CategoryFormScreen
import com.insituledger.app.ui.dashboard.DashboardScreen
import com.insituledger.app.ui.login.LoginScreen
import com.insituledger.app.ui.scheduled.ScheduledFormScreen
import com.insituledger.app.ui.scheduled.ScheduledScreen
import com.insituledger.app.ui.settings.SettingsScreen
import com.insituledger.app.ui.transactions.TransactionFormScreen
import com.insituledger.app.ui.transactions.TransactionsScreen

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

@Composable
fun AppNavigation(isLoggedIn: Boolean) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomNavRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = navBackStackEntry?.destination?.hierarchy?.any {
                            it.route == item.screen.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) Screen.Dashboard.route else Screen.Login.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Login.route) {
                LoginScreen(
                    onLoginSuccess = { forcePasswordChange ->
                        if (forcePasswordChange) {
                            navController.navigate(Screen.ChangePassword.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        } else {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onTransactionClick = { id ->
                        navController.navigate(Screen.TransactionForm.createRoute(id))
                    }
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
                TransactionFormScreen(onBack = { navController.popBackStack() })
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
                    onSettingsClick = { navController.navigate(Screen.Settings.route) }
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

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.ChangePassword.route) {
                SettingsScreen(
                    onBack = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}

package com.insituledger.app.ui.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Dashboard : Screen("dashboard")
    data object Transactions : Screen("transactions")
    data object TransactionForm : Screen("transaction_form?id={id}") {
        fun createRoute(id: Long? = null) = if (id != null) "transaction_form?id=$id" else "transaction_form"
    }
    data object Accounts : Screen("accounts")
    data object AccountForm : Screen("account_form?id={id}") {
        fun createRoute(id: Long? = null) = if (id != null) "account_form?id=$id" else "account_form"
    }
    data object Categories : Screen("categories")
    data object CategoryForm : Screen("category_form?id={id}") {
        fun createRoute(id: Long? = null) = if (id != null) "category_form?id=$id" else "category_form"
    }
    data object Scheduled : Screen("scheduled")
    data object ScheduledForm : Screen("scheduled_form?id={id}") {
        fun createRoute(id: Long? = null) = if (id != null) "scheduled_form?id=$id" else "scheduled_form"
    }
    data object Settings : Screen("settings")
    data object Shared : Screen("shared")
    data object Reports : Screen("reports")
    data object More : Screen("more")
}

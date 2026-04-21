package com.insituledger.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Account(
    val id: Long,
    val userId: Long,
    val name: String,
    val currency: String,
    val balance: Double,
    val isLocalOnly: Boolean = false,
    val ownerName: String = "",
    val isShared: Boolean = false
)

@Immutable
data class Category(
    val id: Long,
    val userId: Long,
    val parentId: Long?,
    val name: String,
    val type: String,
    val icon: String?,
    val color: String?,
    val isLocalOnly: Boolean = false
)

@Immutable
data class Transaction(
    val id: Long,
    val accountId: Long,
    val categoryId: Long,
    val userId: Long,
    val type: String,
    val amount: Double,
    val currency: String,
    val description: String?,
    val note: String?,
    val date: String,
    val isLocalOnly: Boolean = false,
    val createdByUserId: Long? = null,
    val createdByName: String? = null
)

@Immutable
data class ScheduledTransaction(
    val id: Long,
    val accountId: Long,
    val categoryId: Long,
    val userId: Long,
    val type: String,
    val amount: Double,
    val currency: String,
    val description: String?,
    val note: String?,
    val rrule: String,
    val nextOccurrence: String,
    val active: Boolean,
    val maxOccurrences: Int? = null,
    val occurrenceCount: Int = 0,
    val isLocalOnly: Boolean = false,
    val createdByUserId: Long? = null,
    val createdByName: String? = null
)

@Immutable
data class DashboardData(
    val totalBalance: Double,
    val monthIncome: Double,
    val monthExpense: Double,
    val recentTransactions: List<Transaction>,
    val accounts: List<Account>
)

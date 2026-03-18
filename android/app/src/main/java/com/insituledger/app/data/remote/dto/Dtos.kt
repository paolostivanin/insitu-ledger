package com.insituledger.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val login: String,
    val password: String,
    @SerializedName("totp_code") val totpCode: String? = null
)

data class LoginResponse(
    val token: String?,
    @SerializedName("user_id") val userId: Long,
    val name: String,
    @SerializedName("is_admin") val isAdmin: Boolean,
    @SerializedName("force_password_change") val forcePasswordChange: Boolean,
    @SerializedName("totp_enabled") val totpEnabled: Boolean,
    @SerializedName("totp_required") val totpRequired: Boolean?
)

data class UserProfileDto(
    val id: Long,
    val username: String,
    val name: String,
    val email: String,
    @SerializedName("is_admin") val isAdmin: Boolean,
    @SerializedName("force_password_change") val forcePasswordChange: Boolean,
    @SerializedName("totp_enabled") val totpEnabled: Boolean
)

data class ChangePasswordRequest(
    @SerializedName("current_password") val currentPassword: String,
    @SerializedName("new_password") val newPassword: String
)

data class UpdateProfileRequest(
    val username: String? = null,
    val email: String? = null,
    val name: String? = null
)

data class TotpSetupResponse(
    val secret: String,
    @SerializedName("qr_code") val qrCode: String,
    val otpauth: String
)

data class TotpVerifyRequest(val code: String)
data class TotpResetRequest(val password: String)

data class TransactionDto(
    val id: Long,
    @SerializedName("account_id") val accountId: Long,
    @SerializedName("category_id") val categoryId: Long,
    @SerializedName("user_id") val userId: Long,
    val type: String,
    val amount: Double,
    val currency: String,
    val description: String?,
    val date: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("deleted_at") val deletedAt: String?,
    @SerializedName("sync_version") val syncVersion: Long
)

data class TransactionInput(
    @SerializedName("account_id") val accountId: Long,
    @SerializedName("category_id") val categoryId: Long,
    val type: String,
    val amount: Double,
    val currency: String = "EUR",
    val description: String? = null,
    val date: String
)

data class CategoryDto(
    val id: Long,
    @SerializedName("user_id") val userId: Long,
    @SerializedName("parent_id") val parentId: Long?,
    val name: String,
    val type: String,
    val icon: String?,
    val color: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("deleted_at") val deletedAt: String?,
    @SerializedName("sync_version") val syncVersion: Long
)

data class CategoryInput(
    @SerializedName("parent_id") val parentId: Long? = null,
    val name: String,
    val type: String,
    val icon: String? = null,
    val color: String? = null
)

data class AccountDto(
    val id: Long,
    @SerializedName("user_id") val userId: Long,
    val name: String,
    val currency: String,
    val balance: Double,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("deleted_at") val deletedAt: String?,
    @SerializedName("sync_version") val syncVersion: Long
)

data class AccountInput(
    val name: String,
    val currency: String = "EUR",
    val balance: Double? = null
)

data class ScheduledTransactionDto(
    val id: Long,
    @SerializedName("account_id") val accountId: Long,
    @SerializedName("category_id") val categoryId: Long,
    @SerializedName("user_id") val userId: Long,
    val type: String,
    val amount: Double,
    val currency: String,
    val description: String?,
    val rrule: String,
    @SerializedName("next_occurrence") val nextOccurrence: String,
    val active: Boolean,
    @SerializedName("max_occurrences") val maxOccurrences: Int? = null,
    @SerializedName("occurrence_count") val occurrenceCount: Int = 0,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("deleted_at") val deletedAt: String?,
    @SerializedName("sync_version") val syncVersion: Long
)

data class ScheduledInput(
    @SerializedName("account_id") val accountId: Long,
    @SerializedName("category_id") val categoryId: Long,
    val type: String,
    val amount: Double,
    val currency: String = "EUR",
    val description: String? = null,
    val rrule: String,
    @SerializedName("next_occurrence") val nextOccurrence: String,
    @SerializedName("max_occurrences") val maxOccurrences: Int? = null
)

data class SyncResponse(
    @SerializedName("current_version") val currentVersion: Long,
    val transactions: List<TransactionDto>,
    val categories: List<CategoryDto>,
    val accounts: List<AccountDto>,
    @SerializedName("scheduled_transactions") val scheduledTransactions: List<ScheduledTransactionDto>
)

data class AutocompleteSuggestion(
    val description: String,
    @SerializedName("category_id") val categoryId: Long
)

data class IdResponse(val id: Long)

data class AccessibleOwnerDto(
    @SerializedName("owner_user_id") val ownerUserId: Long,
    val name: String,
    val email: String,
    val permission: String
)

data class SharedAccessDto(
    val id: Long,
    @SerializedName("owner_user_id") val ownerUserId: Long,
    @SerializedName("guest_user_id") val guestUserId: Long,
    val permission: String,
    @SerializedName("guest_name") val guestName: String,
    @SerializedName("guest_email") val guestEmail: String
)

data class SharedAccessInput(
    @SerializedName("guest_email") val guestEmail: String,
    val permission: String
)

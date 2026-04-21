package com.insituledger.app.data.repository

import com.insituledger.app.data.remote.dto.AccessibleOwnerDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SharedAccountGrant(
    val accountId: Long,
    val accountName: String,
    val permission: String
)

data class SelectedOwner(
    val ownerId: Long,
    val name: String,
    val accounts: List<SharedAccountGrant>
)

@Singleton
class SharedAccessState @Inject constructor() {
    private val _selectedOwner = MutableStateFlow<SelectedOwner?>(null)
    val selectedOwner: StateFlow<SelectedOwner?> = _selectedOwner.asStateFlow()

    private val _accessibleOwners = MutableStateFlow<List<AccessibleOwnerDto>>(emptyList())
    val accessibleOwners: StateFlow<List<AccessibleOwnerDto>> = _accessibleOwners.asStateFlow()

    fun selectOwner(owner: AccessibleOwnerDto) {
        _selectedOwner.value = SelectedOwner(
            ownerId = owner.ownerUserId,
            name = owner.name,
            accounts = owner.accounts.map {
                SharedAccountGrant(it.accountId, it.accountName, it.permission)
            }
        )
    }

    fun clearOwner() {
        _selectedOwner.value = null
    }

    fun updateAccessibleOwners(owners: List<AccessibleOwnerDto>) {
        _accessibleOwners.value = owners
        // Refresh the selected owner's account grants if it's still in the list.
        _selectedOwner.value?.let { current ->
            val refreshed = owners.find { it.ownerUserId == current.ownerId }
            if (refreshed == null) {
                _selectedOwner.value = null
            } else {
                _selectedOwner.value = current.copy(
                    name = refreshed.name,
                    accounts = refreshed.accounts.map {
                        SharedAccountGrant(it.accountId, it.accountName, it.permission)
                    }
                )
            }
        }
    }

    /** "write" when viewing own data; the per-account grant when viewing a shared owner; null if not shared. */
    fun accountPermission(accountId: Long): String? {
        val owner = _selectedOwner.value ?: return "write"
        return owner.accounts.firstOrNull { it.accountId == accountId }?.permission
    }

    fun canWrite(accountId: Long): Boolean = accountPermission(accountId) == "write"
    fun canRead(accountId: Long): Boolean = accountPermission(accountId) != null

    /** True when viewing own data, OR at least one accessible account is writable. */
    val hasAnyWriteInCurrentContext: Boolean
        get() {
            val owner = _selectedOwner.value ?: return true
            return owner.accounts.any { it.permission == "write" }
        }

    val isViewingShared: Boolean get() = _selectedOwner.value != null
    val currentOwnerId: Long? get() = _selectedOwner.value?.ownerId
}

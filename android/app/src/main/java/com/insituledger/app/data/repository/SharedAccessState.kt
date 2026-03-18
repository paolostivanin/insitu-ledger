package com.insituledger.app.data.repository

import com.insituledger.app.data.remote.dto.AccessibleOwnerDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SelectedOwner(
    val ownerId: Long,
    val name: String,
    val permission: String
)

@Singleton
class SharedAccessState @Inject constructor() {
    private val _selectedOwner = MutableStateFlow<SelectedOwner?>(null)
    val selectedOwner: StateFlow<SelectedOwner?> = _selectedOwner.asStateFlow()

    private val _accessibleOwners = MutableStateFlow<List<AccessibleOwnerDto>>(emptyList())
    val accessibleOwners: StateFlow<List<AccessibleOwnerDto>> = _accessibleOwners.asStateFlow()

    fun selectOwner(owner: AccessibleOwnerDto) {
        _selectedOwner.value = SelectedOwner(owner.ownerUserId, owner.name, owner.permission)
    }

    fun clearOwner() {
        _selectedOwner.value = null
    }

    fun updateAccessibleOwners(owners: List<AccessibleOwnerDto>) {
        _accessibleOwners.value = owners
    }

    val isViewingShared: Boolean get() = _selectedOwner.value != null
    val isReadOnly: Boolean get() = _selectedOwner.value?.permission == "read"
    val currentOwnerId: Long? get() = _selectedOwner.value?.ownerId
}

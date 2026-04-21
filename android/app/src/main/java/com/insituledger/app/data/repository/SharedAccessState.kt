package com.insituledger.app.data.repository

import com.insituledger.app.data.remote.dto.AccessibleOwnerDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the list of owners the current user can co-own accounts with, plus an
 * optional "filter by owner" selection. Since v1.24.0 there is no notion of
 * "switching context": the local DB always contains every accessible account
 * (own + co-owned), and the filter is purely a UI affordance.
 */
@Singleton
class SharedAccessState @Inject constructor() {
    private val _accessibleOwners = MutableStateFlow<List<AccessibleOwnerDto>>(emptyList())
    val accessibleOwners: StateFlow<List<AccessibleOwnerDto>> = _accessibleOwners.asStateFlow()

    // Optional filter. null = "show all accessible".
    private val _ownerFilter = MutableStateFlow<Long?>(null)
    val ownerFilter: StateFlow<Long?> = _ownerFilter.asStateFlow()

    fun updateAccessibleOwners(owners: List<AccessibleOwnerDto>) {
        _accessibleOwners.value = owners
        // If the active filter no longer corresponds to a reachable owner,
        // drop it so the UI doesn't end up stuck filtering to nothing.
        val current = _ownerFilter.value
        if (current != null && owners.none { it.ownerUserId == current }) {
            _ownerFilter.value = null
        }
    }

    fun setOwnerFilter(ownerId: Long?) {
        _ownerFilter.value = ownerId
    }

    fun clear() {
        _accessibleOwners.value = emptyList()
        _ownerFilter.value = null
    }
}

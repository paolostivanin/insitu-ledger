package com.insituledger.app.ui.common

fun entryAttribution(
    isShared: Boolean,
    creatorId: Long?,
    creatorName: String?,
    currentUserId: Long?
): String? {
    if (!isShared) return null
    val name = creatorName?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (creatorId != null && creatorId > 0 && creatorId == currentUserId) "you" else "unknown"
    return "Added by $name"
}

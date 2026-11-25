package com.insituledger.app.data.local.db

/**
 * The id for a row created on the device: negative until its CREATE is pushed
 * and remapped to the server id.
 *
 * It goes below the lowest id in the table AND below the lowest entity_id
 * the pending-op queue still refers to for that entity type. A soft-deleted
 * local row is purged from its table on the next pull, but its failed
 * CREATE/DELETE can stay in the queue. Reusing that id for a new row would let
 * a later Retry (CREATE succeeds, remap rewrites every op with the old id)
 * rename the new row and send its edits to the wrong server id.
 */
fun nextLocalId(tableMinId: Long?, pendingMinEntityId: Long?): Long =
    minOf(tableMinId ?: 0L, pendingMinEntityId ?: 0L, 0L) - 1

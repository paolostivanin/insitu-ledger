package com.insituledger.app.data.local.db.dao

import androidx.room.*
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingOperationDao {
    // Push order = queue (insertion) order. id is AUTOINCREMENT, so it never
    // goes backwards; created_at is the wall clock, which can (manual change,
    // NTP correction) and would let an older full-row UPDATE land after a
    // newer PATCH and revert it.
    @Query("SELECT * FROM pending_operations WHERE state = 'pending' ORDER BY id ASC")
    suspend fun getAll(): List<PendingOperationEntity>

    @Query("SELECT COUNT(*) FROM pending_operations WHERE state = 'pending'")
    fun getCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_operations WHERE state = 'failed'")
    fun getFailedCount(): Flow<Int>

    @Query("SELECT last_error FROM pending_operations WHERE state = 'failed' ORDER BY id ASC LIMIT 1")
    fun getFirstFailedError(): Flow<String?>

    @Insert
    suspend fun insert(op: PendingOperationEntity): Long

    @Delete
    suspend fun delete(op: PendingOperationEntity)

    @Query("DELETE FROM pending_operations")
    suspend fun deleteAll()

    @Query("UPDATE pending_operations SET state = 'failed', last_error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("UPDATE pending_operations SET state = 'pending', last_error = NULL WHERE state = 'failed'")
    suspend fun retryFailed()

    @Query("DELETE FROM pending_operations WHERE state = 'failed'")
    suspend fun deleteFailed()

    @Query("UPDATE pending_operations SET entity_id = :newId WHERE entity_id = :oldId AND entity_type = :entityType")
    suspend fun updateEntityId(oldId: Long, newId: Long, entityType: String)

    // Used by SyncRepository.enqueueLocalDataForSync to skip entities that
    // already have a pending CREATE — without this, an offline-created
    // transaction would end up with two pending ops after re-login.
    @Query("SELECT * FROM pending_operations WHERE state = 'pending' AND entity_type = :entityType AND operation = :operation AND entity_id = :entityId LIMIT 1")
    suspend fun findByEntity(entityType: String, operation: String, entityId: Long): PendingOperationEntity?

    // Used by SyncRepository.remapAccountId / remapCategoryId to find queued
    // CREATEs whose payload_json still references a freshly-remapped negative
    // foreign key. Without rewriting those payloads the backend keeps rejecting
    // them on every retry.
    @Query("SELECT * FROM pending_operations WHERE state = 'pending' AND entity_type = :entityType AND operation = :operation ORDER BY id ASC")
    suspend fun findByEntityTypeAndOperation(entityType: String, operation: String): List<PendingOperationEntity>

    // Used by SyncRepository.pushPendingOperations to re-read an op just
    // before sending it: a prior op in the same round may have rewritten
    // this op's payload_json (FK remap).
    @Query("SELECT * FROM pending_operations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PendingOperationEntity?

    @Query("UPDATE pending_operations SET payload_json = :payloadJson WHERE id = :id")
    suspend fun updatePayloadJson(id: Long, payloadJson: String)

    // Used by SyncRepository.pull when an account's grant was revoked
    // server-side: drop any queued op that targets the now-inaccessible
    // entity (account itself, or transactions/schedules belonging to it).
    @Query("DELETE FROM pending_operations WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteByEntity(entityType: String, entityId: Long)

    // Supersede-on-success reconciliation (SyncRepository): every op queued
    // before :beforeId for the same entity, pending or failed. The entity key
    // is COALESCE(server_id, entity_id), the same one the push loop uses
    // (after a CREATE remap entity_id holds the server id and server_id is
    // null). CREATE ops are never superseded: they start an entity's history.
    @Query("""
        SELECT * FROM pending_operations
        WHERE entity_type = :entityType
          AND COALESCE(server_id, entity_id) = :entityId
          AND id < :beforeId
          AND operation != 'CREATE'
        ORDER BY id ASC
    """)
    suspend fun getOlderOpsForEntity(entityType: String, entityId: Long, beforeId: Long): List<PendingOperationEntity>

    // Lowest entity id any queued op (pending or failed) still refers to. New
    // local (negative) ids are allocated below it too: reusing a purged row's
    // id would let a CREATE remap move that row's leftover ops onto the new
    // row (see nextLocalId).
    @Query("SELECT MIN(entity_id) FROM pending_operations WHERE entity_type = :entityType")
    suspend fun getMinEntityId(entityType: String): Long?

    // Every CREATE queued for one local row, in any state, oldest first.
    // enqueueLocalDataForSync uses it so a row never gets a second CREATE.
    @Query("SELECT * FROM pending_operations WHERE entity_type = :entityType AND operation = 'CREATE' AND entity_id = :entityId ORDER BY id ASC")
    suspend fun findCreatesAnyState(entityType: String, entityId: Long): List<PendingOperationEntity>

    // Re-queue a failed CREATE with the row's current values, keeping its
    // id (queue position) and client_id.
    @Query("UPDATE pending_operations SET state = 'pending', last_error = NULL, payload_json = :payloadJson WHERE id = :id")
    suspend fun requeueCreate(id: Long, payloadJson: String)

    // Drop every other CREATE for the same row (by its old local id or its
    // new server id). Sending a second CREATE would create a second server row.
    @Query("DELETE FROM pending_operations WHERE entity_type = :entityType AND operation = 'CREATE' AND entity_id IN (:entityIds) AND id != :keepId")
    suspend fun deleteOtherCreates(entityType: String, entityIds: List<Long>, keepId: Long)
}

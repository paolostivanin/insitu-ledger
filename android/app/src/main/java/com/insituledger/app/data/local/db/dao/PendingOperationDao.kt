package com.insituledger.app.data.local.db.dao

import androidx.room.*
import com.insituledger.app.data.local.db.entity.PendingOperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingOperationDao {
    @Query("SELECT * FROM pending_operations ORDER BY created_at ASC")
    suspend fun getAll(): List<PendingOperationEntity>

    @Query("SELECT COUNT(*) FROM pending_operations")
    fun getCount(): Flow<Int>

    @Insert
    suspend fun insert(op: PendingOperationEntity): Long

    @Delete
    suspend fun delete(op: PendingOperationEntity)

    @Query("DELETE FROM pending_operations")
    suspend fun deleteAll()

    @Query("UPDATE pending_operations SET entity_id = :newId WHERE entity_id = :oldId AND entity_type = :entityType")
    suspend fun updateEntityId(oldId: Long, newId: Long, entityType: String)

    // Used by SyncRepository.enqueueLocalDataForSync to skip entities that
    // already have a pending CREATE — without this, an offline-created
    // transaction would end up with two pending ops after re-login.
    @Query("SELECT * FROM pending_operations WHERE entity_type = :entityType AND operation = :operation AND entity_id = :entityId LIMIT 1")
    suspend fun findByEntity(entityType: String, operation: String, entityId: Long): PendingOperationEntity?

    // Used by SyncRepository.remapAccountId / remapCategoryId to find queued
    // CREATEs whose payload_json still references a freshly-remapped negative
    // foreign key. Without rewriting those payloads the backend keeps rejecting
    // them on every retry.
    @Query("SELECT * FROM pending_operations WHERE entity_type = :entityType AND operation = :operation ORDER BY created_at ASC")
    suspend fun findByEntityTypeAndOperation(entityType: String, operation: String): List<PendingOperationEntity>

    // Used by SyncRepository.pushPendingOperations to re-read an op just
    // before sending it: a prior op in the same round may have rewritten
    // this op's payload_json (FK remap).
    @Query("SELECT * FROM pending_operations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PendingOperationEntity?

    @Query("UPDATE pending_operations SET payload_json = :payloadJson WHERE id = :id")
    suspend fun updatePayloadJson(id: Long, payloadJson: String)
}

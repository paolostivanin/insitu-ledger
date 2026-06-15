package com.insituledger.app.data.sync

import androidx.work.ListenableWorker.Result
import com.insituledger.app.data.repository.SyncPushException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Exercises SyncWorker.decideResult — the pure retry-classification logic
// extracted from doWork so it can be tested without WorkManager / Context.
// The fix it covers: pre-v1.20 the worker retried every failure 5x including
// permanent 400s and revoked-access 403s, wasting battery and clogging the
// unique-work slot.
class SyncWorkerTest {

    @Test
    fun successReturnsSuccess() {
        val out = SyncWorker.decideResult(kotlin.Result.success(Unit), runAttemptCount = 0)
        assertEquals(Result.success(), out)
    }

    @Test
    fun transientFailureBelowCapReturnsRetry() {
        val ex = SyncPushException(
            failedCount = 1, transientCount = 1, permanentCount = 0,
            firstFailure = "tx UPDATE #42: HTTP 503"
        )
        val out = SyncWorker.decideResult(kotlin.Result.failure(ex), runAttemptCount = 2)
        assertEquals(Result.retry(), out)
    }

    @Test
    fun transientFailureAtCapReturnsFailure() {
        val ex = SyncPushException(
            failedCount = 1, transientCount = 1, permanentCount = 0,
            firstFailure = "tx UPDATE #42: HTTP 503"
        )
        val out = SyncWorker.decideResult(kotlin.Result.failure(ex), runAttemptCount = SyncWorker.MAX_ATTEMPTS)
        assertEquals(Result.failure(), out)
    }

    @Test
    fun permanentOnlyFailureReturnsFailureWithoutRetry() {
        // 400 validation error — retrying can't help; user needs to act.
        val ex = SyncPushException(
            failedCount = 1, transientCount = 0, permanentCount = 1,
            firstFailure = "tx CREATE #-7: HTTP 400"
        )
        val out = SyncWorker.decideResult(kotlin.Result.failure(ex), runAttemptCount = 0)
        assertEquals("permanent should fail without retry regardless of attempt", Result.failure(), out)
    }

    @Test
    fun mixedFailuresRetryWhileTransientPresent() {
        val ex = SyncPushException(
            failedCount = 2, transientCount = 1, permanentCount = 1,
            firstFailure = "first failure"
        )
        val out = SyncWorker.decideResult(kotlin.Result.failure(ex), runAttemptCount = 0)
        assertEquals(Result.retry(), out)
    }

    @Test
    fun unknownExceptionTypeAssumesRetryable() {
        // Pull-side failures wrap a plain Exception, not SyncPushException.
        // Treat unknown failures as retryable so a flaky network doesn't
        // become a permanent dead-letter on the first transient failure.
        val out = SyncWorker.decideResult(
            kotlin.Result.failure(java.io.IOException("network down")),
            runAttemptCount = 0
        )
        assertEquals(Result.retry(), out)
    }

    @Test
    fun canRetryFlagDrivenByTransientCount() {
        // Document the SyncPushException.canRetry contract.
        val transient = SyncPushException(1, 1, 0, "x")
        val permanent = SyncPushException(1, 0, 1, "y")
        val mixed = SyncPushException(2, 1, 1, "z")
        assertTrue(transient.canRetry)
        assertTrue(!permanent.canRetry)
        assertTrue(mixed.canRetry)
    }
}

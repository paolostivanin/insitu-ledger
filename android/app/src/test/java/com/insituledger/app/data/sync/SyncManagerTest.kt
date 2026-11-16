package com.insituledger.app.data.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.SyncRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class SyncManagerTest {

    private val workManager: WorkManager = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)

    // 2026-11-13 10:00 in Rome.
    private val clock = Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneId.of("Europe/Rome"))

    private fun manager(mode: String): SyncManager {
        every { prefs.getSyncModeImmediate() } returns mode
        return spyk(SyncManager(dagger.Lazy { workManager }, mockk<SyncRepository>(relaxed = true), prefs))
    }

    @Test
    fun delayedSyncIsANoOpOutsideWebappMode() {
        manager("none").scheduleDelayedSync(90_000)
        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
        verify(exactly = 0) { workManager.enqueue(any<WorkRequest>()) }
    }

    @Test
    fun delayedSyncUsesItsOwnUniqueNameWithReplace() {
        val request = slot<OneTimeWorkRequest>()
        manager("webapp").scheduleDelayedSync(90_000)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(SyncManager.DELAYED_SYNC_WORK, ExistingWorkPolicy.REPLACE, capture(request))
        }
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(SyncManager.ONE_TIME_SYNC_WORK, any(), any<OneTimeWorkRequest>())
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(SyncManager.ONE_TIME_SYNC_WORK) }
        assertEquals("delayed_sync", SyncManager.DELAYED_SYNC_WORK)
        assertEquals(90_000L, request.captured.workSpec.initialDelay)
        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun kickSchedulerDelaysAFutureOccurrenceByWallClock() {
        val sm = manager("none")
        val request = slot<WorkRequest>()
        sm.kickScheduler("2026-11-20T09:00+01:00", clock)

        // 7 days minus 1 hour.
        verify(exactly = 1) { sm.scheduleDelayedScheduledCheck(601_200_000L) }
        verify(exactly = 0) { sm.triggerImmediateScheduledCheck() }
        verify(exactly = 1) { workManager.enqueue(capture(request)) }
        assertEquals(601_200_000L, request.captured.workSpec.initialDelay)
    }

    @Test
    fun kickSchedulerUsesTheWallClockNotTheStoredOffset() {
        // 09:00 wall clock written with a stale summer offset still fires at
        // 09:00 local (+01:00), matching the worker's SUBSTR due check.
        val sm = manager("none")
        sm.kickScheduler("2026-11-20T09:00+02:00", clock)
        verify(exactly = 1) { sm.scheduleDelayedScheduledCheck(601_200_000L) }
    }

    @Test
    fun kickSchedulerRunsDueStartsNowAndPullsLater() {
        val sm = manager("none")
        sm.kickScheduler("2026-11-06T10:00+01:00", clock)

        verify(exactly = 1) { sm.triggerImmediateScheduledCheck() }
        verify(exactly = 1) { sm.scheduleDelayedSync(90_000L) }
        verify(exactly = 0) { sm.scheduleDelayedScheduledCheck(any()) }
        // Local mode: the worker is enqueued, the delayed sync no-ops.
        verify(exactly = 1) { workManager.enqueue(any<WorkRequest>()) }
        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun kickSchedulerInWebappModeOnlySchedulesThePull() {
        val sm = manager("webapp")
        sm.kickScheduler("2026-11-13T10:00+01:00", clock) // due this minute

        verify(exactly = 0) { workManager.enqueue(any<WorkRequest>()) }
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(SyncManager.DELAYED_SYNC_WORK, ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun kickSchedulerInWebappModeIgnoresFutureOccurrences() {
        val sm = manager("webapp")
        sm.kickScheduler("2026-11-20T09:00+01:00", clock)

        verify(exactly = 0) { workManager.enqueue(any<WorkRequest>()) }
        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun cancelAllCancelsTheDelayedSync() {
        manager("webapp").cancelAll()
        verify { workManager.cancelUniqueWork(SyncManager.DELAYED_SYNC_WORK) }
    }
}

package com.insituledger.app.ui.settings

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.remote.tls.ClientCertificateKeyManager
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.AuthRepository
import com.insituledger.app.data.repository.FileBackupRepository
import com.insituledger.app.data.repository.PreferencesRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.SharedRepository
import com.insituledger.app.data.sync.BackupManager
import com.insituledger.app.data.sync.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)
    private val fileBackupRepository: FileBackupRepository = mockk(relaxed = true)
    private val backupManager: BackupManager = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val pendingOpDao: PendingOperationDao = mockk(relaxed = true)
    private val okHttpClient: OkHttpClient = mockk(relaxed = true)
    private val clientCertKeyManager: ClientCertificateKeyManager = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val sharedRepository: SharedRepository = mockk(relaxed = true)
    private val sharedAccessState = SharedAccessState()
    private val preferencesRepository: PreferencesRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { prefs.themeModeFlow } returns flowOf("system")
        every { prefs.biometricEnabledFlow } returns flowOf(false)
        every { prefs.syncModeFlow } returns flowOf("webapp")
        every { prefs.tokenFlow } returns flowOf("token")
        every { prefs.userNameFlow } returns flowOf("User")
        every { prefs.weekStartDayFlow } returns flowOf("monday")
        every { prefs.screenSecureFlow } returns flowOf(true)
        every { prefs.lastSyncVersionFlow } returns flowOf(1L)
        every { pendingOpDao.getCount() } returns flowOf(0)
        every { pendingOpDao.getFailedCount() } returns flowOf(1)
        every { pendingOpDao.getFirstFailedError() } returns flowOf("account CREATE #-1: HTTP 400")
        every { prefs.mtlsEnabledFlow } returns flowOf(false)
        every { prefs.mtlsAliasFlow } returns flowOf(null)
        every { prefs.currencySymbolFlow } returns flowOf("EUR")
        every { prefs.dashboardHeroModeFlow } returns flowOf("net_worth")
        every { prefs.allowCleartextHttpFlow } returns flowOf(false)
        every { prefs.backupPassphraseSetFlow } returns flowOf(false)
        every { prefs.defaultAccountIdFlow } returns flowOf(null)
        every { prefs.autoBackupFolderUriFlow } returns flowOf(null)
        every { prefs.autoBackupDailyEnabledFlow } returns flowOf(false)
        every { prefs.autoBackupDailyRetentionFlow } returns flowOf(7)
        every { prefs.autoBackupWeeklyEnabledFlow } returns flowOf(false)
        every { prefs.autoBackupWeeklyRetentionFlow } returns flowOf(4)
        every { prefs.autoBackupMonthlyEnabledFlow } returns flowOf(false)
        every { prefs.autoBackupMonthlyRetentionFlow } returns flowOf(6)
        every { prefs.autoBackupLastAttemptAtFlow } returns flowOf(null)
        every { prefs.autoBackupLastSuccessfulAtFlow } returns flowOf(null)
        coEvery { accountRepository.getCached() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(
        authRepository, syncManager, fileBackupRepository, backupManager, prefs,
        pendingOpDao, okHttpClient, clientCertKeyManager, accountRepository,
        sharedRepository, sharedAccessState, preferencesRepository
    )

    @Test
    fun manualSyncFailureIsSurfaced() = runTest {
        coEvery { syncManager.syncNow() } returns Result.failure(Exception("validation failed"))
        val vm = viewModel()

        vm.forceSync()

        assertFalse(vm.uiState.value.isSyncing)
        assertEquals("validation failed", vm.uiState.value.syncError)
        assertEquals("account CREATE #-1: HTTP 400", vm.uiState.value.firstFailedOpError)
    }

    @Test
    fun failedOperationsCanBeRetriedOrDiscarded() = runTest {
        val vm = viewModel()

        vm.retryFailedSyncOperations()
        vm.discardFailedSyncOperations()

        coVerify(exactly = 1) { pendingOpDao.retryFailed() }
        coVerify(exactly = 1) { pendingOpDao.deleteFailed() }
        coVerify(exactly = 1) { syncManager.triggerImmediateSync() }
    }
}

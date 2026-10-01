package com.insituledger.app.data.repository

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.remote.api.AuthApi
import com.insituledger.app.data.remote.interceptor.AuthInterceptor
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AuthRepositoryTest {
    @Test fun passwordChangeClearsOnlySessionWithoutQueuingAnotherLoginAction() = runTest {
        val api: AuthApi = mockk()
        val prefs: UserPreferences = mockk(relaxed = true)
        coEvery { api.changePassword(any()) } returns Response.success(Unit)
        val repo = AuthRepository(api, prefs, mockk<AuthInterceptor>())
        assertTrue(repo.changePassword("old password", "new password").isSuccess)
        coVerify { prefs.clearAuthSession("Password changed. Sign in with your new password.", notifyLogout = false) }
        coVerify(exactly = 0) { prefs.clearAll() }
        coVerify(exactly = 0) { prefs.saveSyncMode(any()) }
    }
}

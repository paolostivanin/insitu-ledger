package com.insituledger.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.navigation.AppNavigation
import com.insituledger.app.ui.theme.InSituLedgerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var syncManager: SyncManager

    private var biometricUnlocked = mutableStateOf(false)
    private var biometricPromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // Use a sentinel to distinguish "not yet loaded" from "no token"
            val token by userPreferences.tokenFlow.collectAsStateWithLifecycle(initialValue = "\u0000")
            val themeMode by userPreferences.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
            val biometricEnabled by userPreferences.biometricEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
            val unlocked by biometricUnlocked

            InSituLedgerTheme(themeMode = themeMode) {
                when {
                    token == "\u0000" -> {
                        // Still loading from DataStore
                        LoadingIndicator()
                    }
                    token != null && biometricEnabled && !unlocked -> {
                        // Has token + biometric enabled but not yet unlocked
                        LoadingIndicator()
                        LaunchedEffect(Unit) {
                            if (!biometricPromptShown) {
                                biometricPromptShown = true
                                showBiometricPrompt()
                            }
                        }
                    }
                    else -> {
                        AppNavigation(isLoggedIn = token != null)
                    }
                }
            }
        }
    }

    private fun showBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometric hardware or not enrolled — skip biometric, go straight in
            biometricUnlocked.value = true
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                biometricUnlocked.value = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                    // User cancelled — close the app
                    finish()
                } else {
                    // Other error (lockout, etc.) — let them in anyway
                    biometricUnlocked.value = true
                }
            }

            override fun onAuthenticationFailed() {
                // Single attempt failed, prompt stays open for retry
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock InSitu Ledger")
            .setSubtitle("Authenticate to access your finances")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(promptInfo)
    }

    override fun onResume() {
        super.onResume()
        syncManager.triggerImmediateSync()
    }
}

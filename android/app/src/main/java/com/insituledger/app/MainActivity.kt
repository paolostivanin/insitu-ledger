package com.insituledger.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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
    private var openNewTransaction = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        openNewTransaction.value = isNewTransactionIntent(intent)

        // Always schedule the local scheduled-transaction worker
        syncManager.scheduleScheduledTransactionCheck()

        setContent {
            val themeMode by userPreferences.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
            val biometricEnabled by userPreferences.biometricEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
            val screenSecure by userPreferences.screenSecureFlow.collectAsStateWithLifecycle(initialValue = true)
            val unlocked by biometricUnlocked
            val navigateToNewTransaction by openNewTransaction

            LaunchedEffect(screenSecure) {
                if (screenSecure) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            InSituLedgerTheme(themeMode = themeMode) {
                if (biometricEnabled && !unlocked) {
                    LoadingIndicator()
                    LaunchedEffect(Unit) {
                        if (!biometricPromptShown) {
                            biometricPromptShown = true
                            showBiometricPrompt()
                        }
                    }
                } else {
                    AppNavigation(
                        openNewTransaction = navigateToNewTransaction,
                        onNewTransactionConsumed = { openNewTransaction.value = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isNewTransactionIntent(intent)) {
            openNewTransaction.value = true
        }
    }

    private fun isNewTransactionIntent(intent: Intent?): Boolean {
        return intent?.action == ACTION_NEW_TRANSACTION
    }

    private fun showBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
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
                    finish()
                } else {
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
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }

    override fun onResume() {
        super.onResume()
        // Only trigger sync if webapp sync is configured
        if (userPreferences.getSyncModeImmediate() == "webapp") {
            syncManager.triggerImmediateSync()
        }
    }

    companion object {
        const val ACTION_NEW_TRANSACTION = "com.insituledger.app.ACTION_NEW_TRANSACTION"
    }
}

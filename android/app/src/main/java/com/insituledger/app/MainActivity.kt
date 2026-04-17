package com.insituledger.app

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.AnticipateInterpolator
import androidx.activity.compose.setContent
import androidx.core.animation.doOnEnd
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.sync.BackupManager
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.navigation.AppNavigation
import com.insituledger.app.ui.theme.InSituLedgerTheme
import com.insituledger.app.widget.AddTransactionWidgetProvider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var backupManager: BackupManager

    private var biometricUnlocked = mutableStateOf(false)
    private var biometricPromptShown = false
    private var biometricRequested = false
    private val newTransactionEvents = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !contentReady }
        splash.setOnExitAnimationListener { provider ->
            // androidx.core.splashscreen declares iconView as non-null and
            // dereferences it internally — but the platform returns null on
            // widget/shortcut cold-launches, so the getter itself throws NPE.
            // Catch it and fall through to the icon-less branch.
            val icon: View? = try { provider.iconView } catch (_: NullPointerException) { null }
            if (icon != null) {
                val scale = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 0.6f).apply {
                    interpolator = AnticipateInterpolator()
                    duration = 250L
                }
                val scaleY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 0.6f).apply {
                    interpolator = AnticipateInterpolator()
                    duration = 250L
                }
                val fade = ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f).apply {
                    duration = 250L
                    doOnEnd { provider.remove() }
                }
                scale.start(); scaleY.start(); fade.start()
            } else {
                // Widget / shortcut cold-launch sometimes hands us a splash with no icon view.
                ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f).apply {
                    duration = 200L
                    doOnEnd { provider.remove() }
                    start()
                }
            }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val fromWidget = isFromWidget(intent)
        if (fromWidget) {
            // Widget is a quick-add path — biometric would defeat the point.
            biometricUnlocked.value = true
            biometricRequested = true
            biometricPromptShown = true
        }

        if (isNewTransactionIntent(intent)) newTransactionEvents.tryEmit(Unit)

        // Always schedule the local scheduled-transaction worker
        syncManager.scheduleScheduledTransactionCheck()
        backupManager.scheduleAutoBackup()

        setContent {
            val themeMode by userPreferences.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
            val biometricEnabled by userPreferences.biometricEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
            val screenSecure by userPreferences.screenSecureFlow.collectAsStateWithLifecycle(initialValue = true)
            val unlocked by biometricUnlocked

            LaunchedEffect(screenSecure, biometricEnabled, unlocked) {
                // Defer FLAG_SECURE while the biometric prompt is up: under-display
                // fingerprint sensors won't render their touch indicator on a SECURE
                // window, so the user sees the prompt but no place to tap.
                val shouldSecure = screenSecure && (!biometricEnabled || unlocked)
                if (shouldSecure) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(Unit) { contentReady = true }

            InSituLedgerTheme(themeMode = themeMode) {
                if (biometricEnabled && !unlocked) {
                    LoadingIndicator()
                    LaunchedEffect(Unit) {
                        biometricRequested = true
                        maybeShowBiometricPrompt()
                    }
                } else {
                    AppNavigation(
                        newTransactionEvents = newTransactionEvents,
                        launchedFromWidget = fromWidget
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isNewTransactionIntent(intent)) {
            newTransactionEvents.tryEmit(Unit)
        }
    }

    private fun isNewTransactionIntent(intent: Intent?): Boolean {
        return intent?.action == ACTION_NEW_TRANSACTION
    }

    private fun isFromWidget(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(AddTransactionWidgetProvider.EXTRA_FROM_WIDGET, false) == true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Widget cold-launch: the splash window owns focus during its exit
        // animation, so the under-display fingerprint indicator overlay can't
        // attach to our activity until focus transfers here.
        if (hasFocus) maybeShowBiometricPrompt()
    }

    private fun maybeShowBiometricPrompt() {
        if (!biometricRequested || biometricPromptShown) return
        if (!hasWindowFocus()) return
        biometricPromptShown = true
        showBiometricPrompt()
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
        lifecycleScope.launch {
            if (userPreferences.getSyncModeImmediate() == "webapp") {
                syncManager.triggerImmediateSync()
            }
        }
    }

    companion object {
        const val ACTION_NEW_TRANSACTION = "com.insituledger.app.ACTION_NEW_TRANSACTION"
    }
}

package com.insituledger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.ui.navigation.AppNavigation
import com.insituledger.app.ui.theme.InSituLedgerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var syncManager: SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val token by userPreferences.tokenFlow.collectAsState(initial = null)
            val themeMode by userPreferences.themeModeFlow.collectAsState(initial = "system")

            InSituLedgerTheme(themeMode = themeMode) {
                AppNavigation(isLoggedIn = token != null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncManager.triggerImmediateSync()
    }
}

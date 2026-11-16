package com.insituledger.app.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.insituledger.app.BuildConfig
import com.insituledger.app.R
import com.insituledger.app.ui.common.ListRow
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalSnackbarController
import com.insituledger.app.ui.theme.AppSpacing

private const val SOURCE_URL = "https://github.com/paolostivanin/insitu-ledger"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    val pad = LocalContentPadding.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppSpacing.xl)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = pad, end = pad, top = AppSpacing.xl, bottom = AppSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                Icon(
                    painter = painterResource(R.drawable.splash_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(bottom = AppSpacing.md)
                        .size(64.dp)
                )
                Text(
                    text = "InSitu Ledger",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "A self-hosted personal finance tracker. Your data stays in situ: " +
                        "on your device or your own server, under your control.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = AppSpacing.md)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ListRow(
                headline = "Author",
                supporting = "Paolo Stivanin",
                horizontalPadding = pad
            )
            ListRow(
                headline = "License",
                supporting = "GPL-3.0",
                horizontalPadding = pad
            )
            ListRow(
                headline = "Source code",
                supporting = "github.com/paolostivanin/insitu-ledger",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
                    } catch (_: ActivityNotFoundException) {
                        snackbar.show("No app found to open the link")
                    }
                },
                horizontalPadding = pad
            )
        }
    }
}

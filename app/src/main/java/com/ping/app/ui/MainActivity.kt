package com.ping.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.navigation3.runtime.NavKey
import com.ping.app.ui.navigation.AppNavHost
import com.ping.app.ui.theme.PingTheme
import com.ping.app.utils.RequiredPermissions
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    /** Screen asked for by a shortcut or the tile; consumed once navigated. */
    private val pendingKey = mutableStateOf<NavKey?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        // Ask once per launch, not on rotation; screens that need a permission still ask again.
        if (savedInstanceState == null) {
            val missing = RequiredPermissions.missing(this)
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        }
        if (savedInstanceState == null) pendingKey.value = QuickLaunch.keyOf(intent)
        QuickLaunch.publishShortcuts(this)
        setContent {
            PingTheme {
                AppNavHost(
                    launchKey = pendingKey.value,
                    onLaunchKeyHandled = { pendingKey.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        QuickLaunch.keyOf(intent)?.let { pendingKey.value = it }
    }
}

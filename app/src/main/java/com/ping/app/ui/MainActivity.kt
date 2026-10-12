package com.ping.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.ping.app.ui.navigation.AppNavHost
import com.ping.app.ui.theme.PingTheme
import com.ping.app.utils.RequiredPermissions
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    /** Screen asked for by a shortcut or the tile; consumed once navigated. */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ask once per launch, not on rotation; screens that need a permission still ask again.
        if (savedInstanceState == null) {
            val missing = RequiredPermissions.missing(this)
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        }
        if (savedInstanceState == null) pendingRoute.value = QuickLaunch.routeOf(intent)
        QuickLaunch.publishShortcuts(this)
        setContent {
            PingTheme {
                AppNavHost(
                    launchRoute = pendingRoute.value,
                    onLaunchRouteHandled = { pendingRoute.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        QuickLaunch.routeOf(intent)?.let { pendingRoute.value = it }
    }
}

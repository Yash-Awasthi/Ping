package com.ping.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.ping.app.ui.navigation.AppNavHost
import com.ping.app.ui.theme.PingTheme
import com.ping.app.utils.RequiredPermissions
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ask once per launch, not on rotation; screens that need a permission still ask again.
        if (savedInstanceState == null) {
            val missing = RequiredPermissions.missing(this)
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        }
        setContent {
            PingTheme {
                AppNavHost()
            }
        }
    }
}

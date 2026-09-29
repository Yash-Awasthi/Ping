package com.ping.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

/** Route constants for Compose navigation. */
object Routes {
    const val HOME = "home"
    const val PROFILE = "profile"
    const val CONTACTS = "contacts"
    const val EXCHANGE = "exchange"
    const val ROOM = "room"
}

/** Bottom navigation items. */
data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(Routes.HOME, "Home", Icons.Default.Home),
    BottomNavItem(Routes.CONTACTS, "Contacts", Icons.Default.AccountBox),
    BottomNavItem(Routes.PROFILE, "Profile", Icons.Default.Person),
)

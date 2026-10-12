package com.ping.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Back stack keys; serializable so the stack survives process death. */
@Serializable data object Home : NavKey
@Serializable data object Profile : NavKey
@Serializable data object Contacts : NavKey
@Serializable data class ContactDetail(val contactId: String) : NavKey
@Serializable data object Exchange : NavKey
@Serializable data object Room : NavKey
@Serializable data object Practice : NavKey
@Serializable data object RollCall : NavKey

/** Bottom navigation items. */
data class BottomNavItem(
    val key: NavKey,
    val label: String,
    val icon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(Home, "Home", Icons.Default.Home),
    BottomNavItem(Contacts, "Contacts", Icons.Default.AccountBox),
    BottomNavItem(Profile, "Profile", Icons.Default.Person),
)

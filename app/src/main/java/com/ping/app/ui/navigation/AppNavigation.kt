package com.ping.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import com.ping.app.R
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/** Back stack keys; serializable so the stack survives process death. */
@Serializable sealed interface Screen : NavKey

@Serializable data object Home : Screen
@Serializable data object Profile : Screen
@Serializable data object Contacts : Screen
@Serializable data class ContactDetail(val contactId: String) : Screen
@Serializable data object Exchange : Screen
@Serializable data object Room : Screen
@Serializable data object Practice : Screen
@Serializable data object RollCall : Screen

// Registered explicitly: the reflective default looks keys up by class name, which R8 renames.
@OptIn(ExperimentalSerializationApi::class)
val navKeyModule = SerializersModule {
    polymorphic(NavKey::class) { subclassesOfSealed<Screen>() }
}

val navStateConfiguration = SavedStateConfiguration { serializersModule = navKeyModule }

/** Bottom navigation items. */
data class BottomNavItem(
    val key: NavKey,
    @StringRes val label: Int,
    val icon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(Home, R.string.nav_home, Icons.Default.Home),
    BottomNavItem(Contacts, R.string.nav_contacts, Icons.Default.AccountBox),
    BottomNavItem(Profile, R.string.nav_profile, Icons.Default.Person),
)

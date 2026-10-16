package com.ping.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ping.app.ui.QuickLaunch
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.ping.app.rollcall.RollCallScreen
import com.ping.app.room.RoomScreen
import com.ping.app.ui.contacts.ContactDetailScreen
import com.ping.app.ui.contacts.ContactsScreen
import com.ping.app.ui.exchange.ExchangeScreen
import com.ping.app.ui.home.HomeScreen
import com.ping.app.ui.practice.PracticeScreen
import com.ping.app.ui.profile.ProfileScreen

/** Drops everything above [Home], then opens [key] on top of it. */
private fun NavBackStack<NavKey>.openFromHome(key: NavKey) {
    while (size > 1) removeAt(lastIndex)
    if (key != Home) add(key)
}

@Composable
fun AppNavHost(launchKey: NavKey? = null, onLaunchKeyHandled: () -> Unit = {}) {
    val backStack = rememberNavBackStack(navStateConfiguration, Home)
    var shareCard by remember { mutableStateOf(false) }
    LaunchedEffect(launchKey) {
        val key = launchKey ?: return@LaunchedEffect
        if (key == QuickLaunch.ShareMyCard) {
            backStack.openFromHome(Profile)
            shareCard = true
        } else backStack.openFromHome(key)
        onLaunchKeyHandled()
    }
    val current = backStack.lastOrNull()
    val pop: () -> Unit = { backStack.removeLastOrNull() }

    Scaffold(
        bottomBar = {
            // Only show bottom nav on root destinations.
            if (bottomNavItems.any { it.key == current }) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = current == item.key,
                            onClick = { if (current != item.key) backStack.openFromHome(item.key) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(innerPadding),
            onBack = pop,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<Home> {
                    HomeScreen(
                        onShareClick = { backStack.add(Exchange) },
                        onRoomClick = { backStack.add(Room) },
                        onPracticeClick = { backStack.add(Practice) },
                        onRollCallClick = { backStack.add(RollCall) },
                    )
                }
                entry<Profile> { ProfileScreen(shareOnOpen = shareCard, onShareHandled = { shareCard = false }) }
                entry<Contacts> {
                    ContactsScreen(onContactClick = { backStack.add(ContactDetail(it)) })
                }
                entry<ContactDetail> { key ->
                    ContactDetailScreen(contactId = key.contactId, onBack = pop)
                }
                entry<Practice> { PracticeScreen(onBack = pop) }
                entry<RollCall> { RollCallScreen(onBack = pop) }
                entry<Room> { RoomScreen(onBack = pop) }
                entry<Exchange> {
                    ExchangeScreen(
                        onDone = { backStack.openFromHome(Contacts) },
                        onBack = pop,
                    )
                }
            },
        )
    }
}

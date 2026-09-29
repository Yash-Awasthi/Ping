package com.ping.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ping.app.ui.home.HomeScreen
import com.ping.app.ui.profile.ProfileScreen
import com.ping.app.ui.contacts.ContactsScreen
import com.ping.app.ui.contacts.ContactDetailScreen
import com.ping.app.ui.exchange.ExchangeScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            // Only show bottom nav on root destinations.
            val showBottomBar = currentDestination?.route in bottomNavItems.map { it.route }
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onShareClick = { navController.navigate(Routes.EXCHANGE) },
                )
            }

            composable(Routes.PROFILE) {
                ProfileScreen()
            }

            composable(Routes.CONTACTS) {
                ContactsScreen(
                    onContactClick = { contactId ->
                        navController.navigate("contact_detail/$contactId")
                    },
                )
            }

            composable(
                route = "contact_detail/{contactId}",
                arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
            ) {
                ContactDetailScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.EXCHANGE) {
                ExchangeScreen(
                    onDone = {
                        navController.navigate(Routes.CONTACTS) {
                            popUpTo(Routes.HOME)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

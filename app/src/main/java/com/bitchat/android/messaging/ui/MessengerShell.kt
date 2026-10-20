package com.bitchat.android.messaging.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bitchat.android.messaging.identity.MessagingPublicKey

/**
 * Navigation for the messenger.
 *
 * Four tabs, in the order a messaging app is expected to have them. There is no entry point from
 * here to geohash, relays, diagnostics or raw peers — those surfaces still exist in the project and
 * still build, they simply cannot be reached from the ordinary interface.
 */
object MessengerRoutes {
    const val CHATS = "chats"
    const val CONTACTS = "contacts"
    const val GROUPS = "groups"
    const val PROFILE = "profile"

    const val CHAT_DETAIL = "chat/{peerKey}"
    const val CONTACT_PROFILE = "contact/{peerKey}"
    const val GROUP_DETAIL = "group/{groupId}"
    const val CREATE_GROUP = "create-group"
    const val PRIVACY = "privacy"
    const val ADVANCED = "advanced"
    const val ALERTS = "alerts"

    fun chat(key: MessagingPublicKey) = "chat/${key.hex}"
    fun contact(key: MessagingPublicKey) = "contact/${key.hex}"
    fun group(groupId: String) = "group/$groupId"
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun MessengerShell(viewModel: MessengerViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val status by viewModel.status.collectAsState()

    LaunchedEffect(status) {
        status?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeStatus()
        }
    }

    val tabs = listOf(
        Tab(MessengerRoutes.CHATS, "Chats") {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
        },
        Tab(MessengerRoutes.CONTACTS, "Contacts") {
            Icon(Icons.Filled.People, contentDescription = null)
        },
        Tab(MessengerRoutes.GROUPS, "Groups") {
            Icon(Icons.Filled.Group, contentDescription = null)
        },
        Tab(MessengerRoutes.PROFILE, "Profile") {
            Icon(Icons.Filled.Person, contentDescription = null)
        }
    )

    val needsSetup by viewModel.needsSetup.collectAsState()

    // First run: ask who they are before anything else. The messenger is unusable in a meaningful
    // sense until the user has a name, since every other device would otherwise see a number.
    if (needsSetup) {
        MessengerOnboardingScreen(
            demoVerification = com.bitchat.android.campus.CampusConfig.demoIssuerAvailable,
            onRequestCode = viewModel::requestEmailCode,
            onConfirmCode = viewModel::confirmEmailCode,
            onComplete = viewModel::completeSetup
        )
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = tab.icon,
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            MessengerNavHost(navController, viewModel)
        }
    }
}

@Composable
private fun MessengerNavHost(
    navController: NavHostController,
    viewModel: MessengerViewModel
) {
    NavHost(navController = navController, startDestination = MessengerRoutes.CHATS) {

        composable(MessengerRoutes.CHATS) {
            ChatsScreen(
                viewModel = viewModel,
                onOpenDirect = { navController.navigate(MessengerRoutes.chat(it)) },
                onOpenGroup = { navController.navigate(MessengerRoutes.group(it)) },
                onOpenAlerts = { navController.navigate(MessengerRoutes.ALERTS) }
            )
        }

        composable(MessengerRoutes.ALERTS) {
            AlertsScreen(viewModel) { navController.popBackStack() }
        }

        composable(MessengerRoutes.CONTACTS) {
            ContactsScreen(
                viewModel = viewModel,
                onOpenContact = { navController.navigate(MessengerRoutes.contact(it)) }
            )
        }

        composable(MessengerRoutes.GROUPS) {
            GroupsScreen(
                viewModel = viewModel,
                onOpenGroup = { navController.navigate(MessengerRoutes.group(it)) },
                onCreateGroup = { navController.navigate(MessengerRoutes.CREATE_GROUP) }
            )
        }

        composable(MessengerRoutes.PROFILE) {
            ProfileScreen(
                viewModel = viewModel,
                onOpenPrivacy = { navController.navigate(MessengerRoutes.PRIVACY) },
                onOpenAdvanced = { navController.navigate(MessengerRoutes.ADVANCED) }
            )
        }

        composable(MessengerRoutes.CHAT_DETAIL) { entry ->
            // A malformed key in a route is treated as no destination rather than as a blank chat,
            // so a bad deep link cannot open a conversation with nobody.
            val key = MessagingPublicKey.parse(entry.arguments?.getString("peerKey"))
            if (key == null) {
                navController.popBackStack()
            } else {
                ChatScreen(viewModel, key) { navController.popBackStack() }
            }
        }

        composable(MessengerRoutes.CONTACT_PROFILE) { entry ->
            val key = MessagingPublicKey.parse(entry.arguments?.getString("peerKey"))
            if (key == null) {
                navController.popBackStack()
            } else {
                ContactProfileScreen(
                    viewModel = viewModel,
                    publicKey = key,
                    onBack = { navController.popBackStack() },
                    onMessage = { navController.navigate(MessengerRoutes.chat(it)) }
                )
            }
        }

        composable(MessengerRoutes.GROUP_DETAIL) { entry ->
            val groupId = entry.arguments?.getString("groupId")
            if (groupId == null) {
                navController.popBackStack()
            } else {
                GroupChatScreen(viewModel, groupId) { navController.popBackStack() }
            }
        }

        composable(MessengerRoutes.CREATE_GROUP) {
            CreateGroupScreen(
                viewModel = viewModel,
                onCreated = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(MessengerRoutes.PRIVACY) { PrivacySettingsScreen(viewModel) }

        composable(MessengerRoutes.ADVANCED) { AdvancedScreen(viewModel) }
    }
}

/** Switch tabs without stacking them, so Back leaves the app rather than walking tab history. */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

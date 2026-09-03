package com.bitchat.android.campus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.bitchat.android.campus.CampusTab
import com.bitchat.android.campus.CampusViewModel

/**
 * Root of the academic overlay.
 *
 * Replaces the technical single-screen chat surface with a three-tab Material 3 shell. Geohash,
 * Tor/Arti and raw mesh diagnostics are simply never reachable from here — the upstream composables
 * still exist and still compile, they just have no entry point in this build.
 */
@Composable
fun CampusShell(viewModel: CampusViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val openClass by viewModel.openClass.collectAsState()
    val classRows by viewModel.classRows.collectAsState()
    val alerts by viewModel.alerts.collectAsState()
    val signalBars by viewModel.signalBars.collectAsState()
    val showComposer by viewModel.showAlertComposer.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeStatusMessage()
        }
    }

    // Enrol once per composition of the shell; joinChannel is idempotent for already-joined channels.
    LaunchedEffect(Unit) { viewModel.enrolInConfiguredClasses() }

    val totalClassUnread = classRows.sumOf { it.unreadCount }

    // An open class takes over the whole screen, matching the consumer-messenger convention.
    if (showComposer) {
        CampusAlertComposer(
            onDismiss = viewModel::hideAlertComposer,
            onPublish = viewModel::publishAlert
        )
    }

    val current = openClass
    if (current != null) {
        CampusClassChatScreen(
            definition = current,
            viewModel = viewModel,
            onBack = viewModel::closeClass
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == CampusTab.DIRECT_MESSAGES,
                    onClick = { viewModel.selectTab(CampusTab.DIRECT_MESSAGES) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                    label = { Text("Messages") }
                )
                NavigationBarItem(
                    selected = selectedTab == CampusTab.CLASSES,
                    onClick = { viewModel.selectTab(CampusTab.CLASSES) },
                    icon = {
                        BadgedBox(badge = {
                            if (totalClassUnread > 0) Badge { Text(totalClassUnread.toString()) }
                        }) { Icon(Icons.Filled.School, contentDescription = null) }
                    },
                    label = { Text("Classes") }
                )
                NavigationBarItem(
                    selected = selectedTab == CampusTab.ALERTS,
                    onClick = { viewModel.selectTab(CampusTab.ALERTS) },
                    icon = {
                        BadgedBox(badge = {
                            if (alerts.isNotEmpty()) Badge { Text(alerts.size.toString()) }
                        }) { Icon(Icons.Filled.Campaign, contentDescription = null) }
                    },
                    label = { Text("Alerts") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                CampusTab.DIRECT_MESSAGES -> CampusDirectMessagesScreen(viewModel, signalBars)
                CampusTab.CLASSES -> CampusClassesScreen(classRows, signalBars, viewModel::openClass)
                CampusTab.ALERTS -> CampusAlertsScreen(viewModel, signalBars)
            }
        }
    }
}

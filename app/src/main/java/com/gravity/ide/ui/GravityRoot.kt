@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.ui.screens.AboutScreen
import com.gravity.ide.ui.screens.ActivityScreen
import com.gravity.ide.ui.screens.AgentNewTaskScreen
import com.gravity.ide.ui.screens.BackupScreen
import com.gravity.ide.ui.screens.BrowserScreen
import com.gravity.ide.ui.screens.BuildScreen
import com.gravity.ide.ui.screens.ChatListScreen
import com.gravity.ide.ui.screens.ChatScreen
import com.gravity.ide.ui.screens.DashboardScreen
import com.gravity.ide.ui.screens.DevicesScreen
import com.gravity.ide.ui.screens.EditorScreen
import com.gravity.ide.ui.screens.ExportScreen
import com.gravity.ide.ui.screens.FilesScreen
import com.gravity.ide.ui.screens.GitScreen
import com.gravity.ide.ui.screens.GlobalSearchScreen
import com.gravity.ide.ui.screens.HelpScreen
import com.gravity.ide.ui.screens.ModelsScreen
import com.gravity.ide.ui.screens.NewChatRedirect
import com.gravity.ide.ui.screens.NotificationsScreen
import com.gravity.ide.ui.screens.OfflineReadinessScreen
import com.gravity.ide.ui.screens.PluginsScreen
import com.gravity.ide.ui.screens.ProjectsScreen
import com.gravity.ide.ui.screens.ProvidersScreen
import com.gravity.ide.ui.screens.ScreenshotsScreen
import com.gravity.ide.ui.screens.SettingsScreen
import com.gravity.ide.ui.screens.TaskDetailScreen
import com.gravity.ide.ui.screens.TasksScreen
import com.gravity.ide.ui.screens.TeamsScreen
import com.gravity.ide.ui.screens.TerminalScreen
import com.gravity.ide.ui.screens.TestingScreen
import com.gravity.ide.ui.screens.WorkspacesScreen
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.launch

@Composable
fun GravityRoot() {
    val container = LocalAppContainer.current
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val network by container.network.state.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    val unread by container.db.events().observeUnreadCount().collectAsState(initial = 0)
    val approvals by container.approvals.pending.collectAsState()
    val isTopLevel = route == null || route == Routes.DASHBOARD

    fun go(target: String) {
        nav.navigate(target) {
            launchSingleTop = true
            if (target == Routes.DASHBOARD) popUpTo(Routes.DASHBOARD) { inclusive = false }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).background(GravityColors.Cyan.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                            Text("G", color = GravityColors.Cyan, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("GRAVITY", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Text("Autonomous AI development", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    var lastGroup = ""
                    for (item in drawerItems) {
                        if (item.group != lastGroup) {
                            lastGroup = item.group
                            Text(
                                item.group.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        val selected = route?.substringBefore('?') == item.route.substringBefore('?')
                        NavigationDrawerItem(
                            label = { Text(item.label) },
                            icon = { Icon(item.icon, null) },
                            selected = selected,
                            onClick = {
                                scope.launch { drawer.close() }
                                go(item.route)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).height(44.dp).testTag("nav:${item.label}"),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(titleFor(route), maxLines = 1, modifier = Modifier.testTag("screen-title")) },
                    navigationIcon = {
                        if (isTopLevel) {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Open navigation") }
                        } else {
                            IconButton(onClick = { if (!nav.popBackStack()) go(Routes.DASHBOARD) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        }
                    },
                    actions = {
                        ModeIndicator(online = network.online, mode = settings.operatingMode.label, onClick = { go(Routes.SETTINGS) })
                        IconButton(onClick = { go(Routes.SEARCH) }) { Icon(Icons.Filled.Search, "Search") }
                        Box {
                            IconButton(onClick = { go(Routes.NOTIFICATIONS) }) { Icon(Icons.Filled.Notifications, "Notifications") }
                            if (unread > 0) {
                                Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(8.dp).background(GravityColors.Red, CircleShape))
                            }
                        }
                        if (!isTopLevel) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Open navigation") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (approvals.isNotEmpty()) {
                    val first = approvals.first()
                    Row(
                        Modifier.fillMaxWidth().background(GravityColors.Amber.copy(alpha = 0.16f)).clickable { go(Routes.task(first.taskId)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Agent needs approval: ${first.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("Review", color = GravityColors.Amber, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Box(Modifier.weight(1f)) { GravityNavHost(nav, ::go) }
            }
        }
    }
}

@Composable
private fun ModeIndicator(online: Boolean, mode: String, onClick: () -> Unit) {
    val color = if (online) GravityColors.Green else GravityColors.Amber
    Row(
        Modifier.clickable(onClick = onClick).background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(if (online) mode else "Offline · $mode", fontSize = 11.sp, color = color)
    }
}

@Composable
private fun GravityNavHost(nav: NavHostController, go: (String) -> Unit) {
    val optionalString = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }
    NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) { DashboardScreen(go) }
        composable(Routes.NEW_CHAT) { NewChatRedirect { id -> nav.navigate(Routes.chat(id)) { popUpTo(Routes.NEW_CHAT) { inclusive = true } } } }
        composable(Routes.CHATS) { ChatListScreen(go) }
        composable(Routes.CHAT) { e -> ChatScreen(e.arguments?.getString("chatId").orEmpty(), go) }
        composable(Routes.PROJECTS, arguments = listOf(optionalString("new"))) { e -> ProjectsScreen(e.arguments?.getString("new") == "1", go) }
        composable(Routes.WORKSPACES) { WorkspacesScreen(go) }
        composable(Routes.FILES, arguments = listOf(optionalString("projectId"))) { e -> FilesScreen(e.arguments?.getString("projectId"), go) }
        composable(Routes.EDITOR, arguments = listOf(navArgument("projectId") { type = NavType.StringType }, optionalString("path"))) { e ->
            EditorScreen(e.arguments?.getString("projectId").orEmpty(), e.arguments?.getString("path"), go)
        }
        composable(Routes.SEARCH) { GlobalSearchScreen(go) }
        composable(Routes.AGENT_NEW, arguments = listOf(optionalString("projectId"))) { e ->
            AgentNewTaskScreen(e.arguments?.getString("projectId")) { id -> nav.navigate(Routes.task(id)) { popUpTo(Routes.AGENT_NEW) { inclusive = true } } }
        }
        composable(Routes.TASKS) { TasksScreen(go) }
        composable(Routes.TASK) { e -> TaskDetailScreen(e.arguments?.getString("taskId").orEmpty(), go) }
        composable(Routes.TERMINAL, arguments = listOf(optionalString("cwd"))) { e -> TerminalScreen(e.arguments?.getString("cwd")) }
        composable(Routes.MODELS, arguments = listOf(optionalString("tab"))) { e -> ModelsScreen(e.arguments?.getString("tab")?.toIntOrNull() ?: 0) }
        composable(Routes.PROVIDERS) { ProvidersScreen() }
        composable(Routes.TEAMS) { TeamsScreen() }
        composable(Routes.BUILD, arguments = listOf(optionalString("projectId"))) { e -> BuildScreen(e.arguments?.getString("projectId"), go) }
        composable(Routes.DEVICES, arguments = listOf(optionalString("apk"))) { e -> DevicesScreen(e.arguments?.getString("apk"), go) }
        composable(Routes.TESTING) { TestingScreen(go) }
        composable(Routes.SCREENSHOTS) { ScreenshotsScreen() }
        composable(Routes.BROWSER, arguments = listOf(optionalString("url"))) { e -> BrowserScreen(e.arguments?.getString("url")) }
        composable(Routes.GIT) { GitScreen(go) }
        composable(Routes.PLUGINS) { PluginsScreen() }
        composable(Routes.ACTIVITY) { ActivityScreen(go) }
        composable(Routes.NOTIFICATIONS) { NotificationsScreen(go) }
        composable(Routes.EXPORT) { ExportScreen() }
        composable(Routes.BACKUP) { BackupScreen() }
        composable(Routes.SETTINGS) { SettingsScreen(go) }
        composable(Routes.HELP) { HelpScreen(go) }
        composable(Routes.ABOUT) { AboutScreen() }
        composable(Routes.OFFLINE) { OfflineReadinessScreen(go) }
    }
}

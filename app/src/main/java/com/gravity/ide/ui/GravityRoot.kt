@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import com.gravity.ide.ui.theme.DisplayFamily
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
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
import com.gravity.ide.ui.screens.AppScreen
import com.gravity.ide.ui.screens.HomeChatScreen
import com.gravity.ide.ui.screens.MyAppsScreen
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
    val isTopLevel = route == null || route == Routes.HOME || route == Routes.CHAT || route == Routes.APPS

    fun go(target: String) = onMain {
        nav.navigate(target) {
            launchSingleTop = true
            if (target == Routes.HOME) popUpTo(Routes.HOME) { inclusive = false }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            GravitySidebar(route, go = { target -> scope.launch { drawer.close() }; go(target) })
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            titleFor(route), maxLines = 1, style = MaterialTheme.typography.titleMedium,
                            fontFamily = if (isTopLevel) DisplayFamily else null, modifier = Modifier.testTag("screen-title"),
                        )
                    },
                    navigationIcon = {
                        if (isTopLevel) {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Open navigation") }
                        } else {
                            IconButton(onClick = { if (!nav.popBackStack()) go(Routes.HOME) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        }
                    },
                    actions = {
                        if (!network.online) {
                            Text(
                                "Offline", fontSize = 11.sp, color = GravityColors.Amber,
                                modifier = Modifier.background(GravityColors.Amber.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        if (isTopLevel) {
                            IconButton(onClick = { go(Routes.NEW_CHAT) }) { Icon(Icons.Filled.EditNote, "New chat") }
                        } else {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Open navigation") }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    // Thinner than Material's 64dp bar, so the chat gets more of the screen.
                    expandedHeight = 48.dp,
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
                        Text("Needs your approval: ${first.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("Review", color = GravityColors.Amber, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Box(Modifier.weight(1f)) { GravityNavHost(nav, ::go) }
                // Gravity closed unexpectedly last time: say why, with a report the user can share.
                val crash by LocalAppContainer.current.crashReports.pending.collectAsState()
                val crashCtx = androidx.compose.ui.platform.LocalContext.current
                val reports = LocalAppContainer.current.crashReports
                crash?.let { r ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { reports.pending.value = null },
                        title = { Text(r.title) },
                        text = {
                            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                Text(r.explanation)
                                Spacer(Modifier.height(8.dp))
                                Text(r.details.take(4000), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_SUBJECT, r.title).putExtra(android.content.Intent.EXTRA_TEXT, r.title + "\n\n" + r.details)
                                runCatching { crashCtx.startActivity(android.content.Intent.createChooser(send, "Share report").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }, modifier = Modifier.testTag("crash-share")) { Text("Share report") }
                        },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { reports.pending.value = null }) { Text("OK") } },
                        modifier = Modifier.testTag("crash-report"),
                    )
                }
                // A "reply ready" notification was tapped: open that chat.
                val openChat by com.gravity.ide.agent.ChatRuns.openChat.collectAsState()
                LaunchedEffect(openChat) {
                    openChat?.let { id -> com.gravity.ide.agent.ChatRuns.openChat.value = null; go(Routes.chat(id)) }
                }
            }
        }
    }
}

/** Claude-style sidebar: New chat, apps and recent chats; settings and folded developer tools at the bottom. */
@Composable
private fun GravitySidebar(route: String?, go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val chats by c.db.chats().observe(false).collectAsState(initial = emptyList())
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    val apps = projects.filter { it.type == "APP" }.sortedByDescending { maxOf(it.updatedAt, it.lastOpenedAt) }.take(5)
    val recents = chats.filter { it.title.isNotBlank() && it.title != "New chat" }.sortedByDescending { it.updatedAt }.take(30)
    var devOpen by rememberSaveable { mutableStateOf(false) }

    /** One compact sidebar row (less space between rows than Material's 56dp items). */
    @Composable
    fun Row1(
        label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, selected: Boolean, onClick: () -> Unit,
        modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null,
    ) {
        Row(
            // Compact like Claude's sidebar: 32dp rows (taller only if the phone's font is very large).
            modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 32.dp).clip(RoundedCornerShape(50))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
    }

    @Composable
    fun Entry(item: NavItem) {
        val selected = route?.substringBefore('?') == item.route.substringBefore('?')
        Row1(item.label, item.icon, selected, { go(item.route) }, Modifier.testTag("nav:${item.label}"))
    }

    @Composable
    fun Header(text: String) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 26.dp, top = 10.dp, bottom = 0.dp))
    }

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(Modifier.padding(start = 24.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).background(GravityColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = GravityColors.OnAccent, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("Gravity", fontFamily = DisplayFamily, fontSize = 24.sp)
            }
            Surface(
                onClick = { go(Routes.NEW_CHAT) }, shape = RoundedCornerShape(50), color = GravityColors.Accent,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(42.dp).testTag("nav:New Chat"),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, tint = GravityColors.OnAccent)
                    Spacer(Modifier.width(8.dp))
                    Text("New chat", color = GravityColors.OnAccent, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(6.dp))
            mainItems.filter { it.route != Routes.NEW_CHAT && it.route != Routes.SETTINGS }.forEach { Entry(it) }
            if (apps.isNotEmpty()) {
                Header("Your apps")
                apps.forEach { p -> Row1(p.name, Icons.Filled.PhoneAndroid, false, { go(Routes.app(p.id)) }) }
            }
            if (recents.isNotEmpty()) {
                Header("Recents")
                recents.forEach { ch -> Row1(ch.title, null, route?.contains(ch.id) == true, { go(Routes.chat(ch.id)) }) }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            mainItems.filter { it.route == Routes.SETTINGS }.forEach { Entry(it) }
            Row1(
                "Developer tools", Icons.Filled.Code, false, { devOpen = !devOpen }, Modifier.testTag("nav:Developer tools"),
                trailing = { Icon(if (devOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, modifier = Modifier.size(20.dp)) },
            )
            if (devOpen) devItems.forEach { Entry(it) }
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

/** Screens navigate from coroutines too; NavController must only be used on the main thread. */
private fun onMain(block: () -> Unit) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
    else android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
}

@Composable
private fun GravityNavHost(nav: NavHostController, go: (String) -> Unit) {
    val optionalString = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeChatScreen(go) }
        composable(Routes.APPS) { MyAppsScreen(go) }
        composable(Routes.APP, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { e ->
            AppScreen(e.arguments?.getString("projectId").orEmpty(), go)
        }
        composable(Routes.DASHBOARD) { DashboardScreen(go) }
        composable(Routes.NEW_CHAT) { NewChatRedirect { id -> onMain { nav.navigate(Routes.chat(id)) { popUpTo(Routes.NEW_CHAT) { inclusive = true } } } } }
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
            AgentNewTaskScreen(e.arguments?.getString("projectId")) { id -> onMain { nav.navigate(Routes.task(id)) { popUpTo(Routes.AGENT_NEW) { inclusive = true } } } }
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

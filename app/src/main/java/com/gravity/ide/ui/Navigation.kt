package com.gravity.ide.ui

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gravity.ide.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Creates (or reuses) a ViewModel wired to the app's real services. */
@Composable
inline fun <reified VM : ViewModel> gravityViewModel(key: String? = null, crossinline factory: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = factory(container) as T
        },
    )
}

object Routes {
    /** Start screen: "What do you want to build?" */
    const val HOME = "home"
    const val APPS = "apps"
    const val APP = "app/{projectId}"
    const val DASHBOARD = "dashboard"
    const val NEW_CHAT = "chat_new"
    const val CHATS = "chats"
    const val CHAT = "chat/{chatId}"
    const val PROJECTS = "projects?new={new}"
    const val WORKSPACES = "workspaces"
    const val FILES = "files?projectId={projectId}"
    const val EDITOR = "editor/{projectId}?path={path}"
    const val SEARCH = "search"
    const val AGENT_NEW = "agent_new?projectId={projectId}"
    const val TASKS = "tasks"
    const val TASK = "task/{taskId}"
    const val TERMINAL = "terminal?cwd={cwd}"
    const val MODELS = "models?tab={tab}"
    const val PROVIDERS = "providers"
    const val TEAMS = "teams"
    const val BUILD = "build?projectId={projectId}"
    const val DEVICES = "devices?apk={apk}"
    const val TESTING = "testing"
    const val SCREENSHOTS = "screenshots"
    const val BROWSER = "browser?url={url}"
    const val GIT = "git"
    const val PLUGINS = "plugins"
    const val ACTIVITY = "activity"
    const val NOTIFICATIONS = "notifications"
    const val EXPORT = "export"
    const val BACKUP = "backup"
    const val SETTINGS = "settings"
    const val HELP = "help"
    const val ABOUT = "about"
    const val OFFLINE = "offline"

    fun chat(id: String) = "chat/$id"
    fun app(projectId: String) = "app/$projectId"
    const val PROJECTS_LIST = "projects"
    const val PROJECTS_NEW = "projects?new=1"
    fun files(projectId: String?) = if (projectId == null) "files" else "files?projectId=$projectId"
    fun editor(projectId: String, path: String) = "editor/$projectId?path=${Uri.encode(path)}"
    fun agentNew(projectId: String? = null) = if (projectId == null) "agent_new" else "agent_new?projectId=$projectId"
    fun task(id: String) = "task/$id"
    fun devices(apk: String? = null) = if (apk == null) "devices" else "devices?apk=${Uri.encode(apk)}"
    fun terminal(cwd: String? = null) = if (cwd == null) "terminal" else "terminal?cwd=${Uri.encode(cwd)}"
    fun models(tab: Int = 0) = "models?tab=$tab"
    fun build(projectId: String? = null) = if (projectId == null) "build" else "build?projectId=$projectId"
    fun browser(url: String? = null) = if (url == null) "browser" else "browser?url=${Uri.encode(url)}"
}

data class NavItem(val label: String, val route: String, val icon: ImageVector, val group: String)

/** Everyday entries, always shown in the sidebar. */
val mainItems = listOf(
    NavItem("New Chat", Routes.NEW_CHAT, Icons.Filled.AddComment, "Main"),
    NavItem("My Apps", Routes.APPS, Icons.Filled.Apps, "Main"),
    NavItem("Phone Models", Routes.models(0), Icons.Filled.Memory, "Main"),
    NavItem("Cloud AI", Routes.PROVIDERS, Icons.Filled.Cloud, "Main"),
    NavItem("Chat History", Routes.CHATS, Icons.Filled.History, "Main"),
    NavItem("Settings", Routes.SETTINGS, Icons.Filled.Settings, "Main"),
)

/** Advanced tools, folded away under "Developer tools" so the app stays simple. */
val devItems = listOf(
    NavItem("Dashboard", Routes.DASHBOARD, Icons.Filled.Dashboard, "Developer tools"),
    NavItem("Projects", "projects", Icons.Filled.Folder, "Developer tools"),
    NavItem("Workspaces", Routes.WORKSPACES, Icons.Filled.Workspaces, "Developer tools"),
    NavItem("Files", "files", Icons.Filled.Description, "Developer tools"),
    NavItem("Global Search", Routes.SEARCH, Icons.Filled.Search, "Developer tools"),
    NavItem("AI Agent", "agent_new", Icons.Filled.SmartToy, "Developer tools"),
    NavItem("Agent Tasks", Routes.TASKS, Icons.Filled.ListAlt, "Developer tools"),
    NavItem("Hybrid AI Team", Routes.TEAMS, Icons.Filled.Groups, "Developer tools"),
    NavItem("Terminal", "terminal", Icons.Filled.Terminal, "Developer tools"),
    NavItem("Model Downloads", Routes.models(1), Icons.Filled.Download, "Developer tools"),
    NavItem("Build Center", "build", Icons.Filled.Build, "Developer tools"),
    NavItem("Device Manager", "devices", Icons.Filled.PhoneAndroid, "Developer tools"),
    NavItem("Testing Center", Routes.TESTING, Icons.Filled.BugReport, "Developer tools"),
    NavItem("Screenshot Gallery", Routes.SCREENSHOTS, Icons.Filled.PhotoLibrary, "Developer tools"),
    NavItem("Browser Preview", "browser", Icons.Filled.Public, "Developer tools"),
    NavItem("Git Manager", Routes.GIT, Icons.Filled.AccountTree, "Developer tools"),
    NavItem("Plugins", Routes.PLUGINS, Icons.Filled.Extension, "Developer tools"),
    NavItem("Activity Logs", Routes.ACTIVITY, Icons.Filled.Rule, "Developer tools"),
    NavItem("Notifications", Routes.NOTIFICATIONS, Icons.Filled.Notifications, "Developer tools"),
    NavItem("Export Center", Routes.EXPORT, Icons.Filled.Upload, "Developer tools"),
    NavItem("Backup and Recovery", Routes.BACKUP, Icons.Filled.Backup, "Developer tools"),
    NavItem("Help and Diagnostics", Routes.HELP, Icons.Filled.Help, "Developer tools"),
    NavItem("About Gravity", Routes.ABOUT, Icons.Filled.Info, "Developer tools"),
)

val drawerItems = mainItems + devItems

fun titleFor(route: String?): String {
    val base = route?.substringBefore('?')?.substringBefore('/') ?: return "Gravity"
    return when (base) {
        "home" -> "Gravity"
        "apps" -> "My Apps"
        "app" -> "App"
        "dashboard" -> "Dashboard"
        "chat_new", "chat" -> "Chat"
        "chats" -> "Chat History"
        "projects" -> "Projects"
        "workspaces" -> "Workspaces"
        "files" -> "Files"
        "editor" -> "Editor"
        "search" -> "Global Search"
        "agent_new" -> "AI Agent"
        "tasks" -> "Agent Tasks"
        "task" -> "Agent Task"
        "terminal" -> "Terminal"
        "models" -> "Phone Models"
        "providers" -> "Cloud AI"
        "teams" -> "Hybrid AI Teams"
        "build" -> "Build Center"
        "devices" -> "Device Manager"
        "testing" -> "Testing Center"
        "screenshots" -> "Screenshots"
        "browser" -> "Browser Preview"
        "git" -> "Git Manager"
        "plugins" -> "Plugins"
        "activity" -> "Activity Logs"
        "notifications" -> "Notifications"
        "export" -> "Export Center"
        "backup" -> "Backup & Recovery"
        "settings" -> "Settings"
        "help" -> "Help & Diagnostics"
        "about" -> "About Gravity"
        "offline" -> "Offline Readiness"
        else -> "Gravity"
    }
}

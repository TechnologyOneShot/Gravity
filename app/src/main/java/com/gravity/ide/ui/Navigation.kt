package com.gravity.ide.ui

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AddComment
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

val drawerItems = listOf(
    NavItem("Dashboard", Routes.DASHBOARD, Icons.Filled.Dashboard, "Home"),
    NavItem("New Chat", Routes.NEW_CHAT, Icons.Filled.AddComment, "Home"),
    NavItem("Chat History", Routes.CHATS, Icons.Filled.History, "Home"),
    NavItem("Projects", "projects", Icons.Filled.Folder, "Workspace"),
    NavItem("Workspaces", Routes.WORKSPACES, Icons.Filled.Workspaces, "Workspace"),
    NavItem("Files", "files", Icons.Filled.Description, "Workspace"),
    NavItem("Global Search", Routes.SEARCH, Icons.Filled.Search, "Workspace"),
    NavItem("AI Agent", "agent_new", Icons.Filled.SmartToy, "AI"),
    NavItem("Agent Tasks", Routes.TASKS, Icons.Filled.ListAlt, "AI"),
    NavItem("Terminal", "terminal", Icons.Filled.Terminal, "Workspace"),
    NavItem("Model Manager", Routes.models(0), Icons.Filled.Memory, "AI"),
    NavItem("Model Downloads", Routes.models(1), Icons.Filled.Download, "AI"),
    NavItem("AI Providers", Routes.PROVIDERS, Icons.Filled.Cloud, "AI"),
    NavItem("Hybrid AI Team", Routes.TEAMS, Icons.Filled.Groups, "AI"),
    NavItem("Build Center", "build", Icons.Filled.Build, "Build & Test"),
    NavItem("Device Manager", "devices", Icons.Filled.PhoneAndroid, "Build & Test"),
    NavItem("Testing Center", Routes.TESTING, Icons.Filled.BugReport, "Build & Test"),
    NavItem("Screenshot Gallery", Routes.SCREENSHOTS, Icons.Filled.PhotoLibrary, "Build & Test"),
    NavItem("Browser Preview", "browser", Icons.Filled.Public, "Build & Test"),
    NavItem("Git Manager", Routes.GIT, Icons.Filled.AccountTree, "Workspace"),
    NavItem("Plugins", Routes.PLUGINS, Icons.Filled.Extension, "System"),
    NavItem("Activity Logs", Routes.ACTIVITY, Icons.Filled.Rule, "System"),
    NavItem("Notifications", Routes.NOTIFICATIONS, Icons.Filled.Notifications, "System"),
    NavItem("Export Center", Routes.EXPORT, Icons.Filled.Upload, "System"),
    NavItem("Backup and Recovery", Routes.BACKUP, Icons.Filled.Backup, "System"),
    NavItem("Settings", Routes.SETTINGS, Icons.Filled.Settings, "System"),
    NavItem("Help and Diagnostics", Routes.HELP, Icons.Filled.Help, "System"),
    NavItem("About Gravity", Routes.ABOUT, Icons.Filled.Info, "System"),
)

fun titleFor(route: String?): String {
    val base = route?.substringBefore('?')?.substringBefore('/') ?: return "Gravity"
    return when (base) {
        "dashboard" -> "Gravity"
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
        "models" -> "Model Manager"
        "providers" -> "AI Providers"
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

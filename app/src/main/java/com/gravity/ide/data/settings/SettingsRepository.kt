package com.gravity.ide.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gravity.ide.ai.ReasoningMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gravityDataStore by preferencesDataStore(name = "gravity_settings")

enum class OperatingMode(val label: String) {
    HYBRID("Hybrid"),
    LOCAL_ONLY("Local only"),
    CLOUD_ONLY("Cloud only"),
    AUTO("Auto model"),
}

enum class ThemeMode { DARK, LIGHT, SYSTEM, NEON }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.NEON,
    val operatingMode: OperatingMode = OperatingMode.HYBRID,
    val defaultProviderId: String? = null,
    val defaultModel: String? = null,
    // Auto: ordinary chat is answered at once (no thinking time); bigger tasks think more.
    val reasoning: ReasoningMode = ReasoningMode.AUTO,
    val agentMaxSteps: Int = 40,
    val approveAllCommands: Boolean = false,
    val approveFileWrites: Boolean = false,
    val maxParallelTasks: Int = 2,
    val autoSave: Boolean = true,
    val editorFontSize: Int = 13,
    val terminalFontSize: Int = 12,
    val showHiddenFiles: Boolean = false,
    val webSearchProvider: String = "none",
    /**
     * Pictures and video scenes: "ask" (first time), "online" (free online image AI, Pollinations),
     * "phone" (realistic, made on the phone by Stable Diffusion), "draw" (drawn as code by the chat's AI).
     */
    val pictureMaker: String = "ask",
    /** Size of pictures made on the phone: 512 (better) or 384 (about twice as fast). */
    val phonePictureSize: Int = 512,
    val localThreads: Int = 4,
    val localContextSize: Int = 4096,
    val lowMemoryMode: Boolean = false,
    /** AUTO: the phone's fast cores (default); ADAPTIVE: [localThreads]; HIGH: fast cores; LOW: 2. */
    val performanceMode: String = "AUTO",
    val redactSecrets: Boolean = true,
    val confirmCloudUpload: Boolean = true,
    val notifications: Boolean = true,
    val dashboardWidgets: Set<String> = DEFAULT_WIDGETS,
    val experimentalFeatures: Boolean = false,
    val offlineFallbackToLocal: Boolean = true,
    val retryWhenOnline: Boolean = true,
    val onboardingDone: Boolean = false,
    /** SAF tree URI of the shared folder used to hand projects to Termux. */
    val termuxShareUri: String? = null,
) {
    companion object {
        val DEFAULT_WIDGETS = setOf("quick", "tasks", "projects", "chats", "builds", "screenshots", "activity", "status")
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        /** Since the Neon theme: the old key held DARK for everyone who never chose, so it is read once and moved here. */
        val theme2 = stringPreferencesKey("theme_v2")
        val mode = stringPreferencesKey("operating_mode")
        val provider = stringPreferencesKey("default_provider")
        val model = stringPreferencesKey("default_model")
        val reasoning = stringPreferencesKey("reasoning")
        val maxSteps = intPreferencesKey("agent_max_steps")
        val approveCommands = booleanPreferencesKey("approve_all_commands")
        val approveWrites = booleanPreferencesKey("approve_file_writes")
        val parallel = intPreferencesKey("max_parallel_tasks")
        val autoSave = booleanPreferencesKey("auto_save")
        val editorFont = intPreferencesKey("editor_font")
        val terminalFont = intPreferencesKey("terminal_font")
        val hidden = booleanPreferencesKey("show_hidden")
        val webSearch = stringPreferencesKey("web_search_provider")
        val pictureMaker = stringPreferencesKey("picture_maker")
        val phonePictureSize = intPreferencesKey("phone_picture_size")
        val threads = intPreferencesKey("local_threads")
        val ctx = intPreferencesKey("local_ctx")
        val lowMem = booleanPreferencesKey("low_memory")
        val perf = stringPreferencesKey("performance_mode")
        /** Since the fast-cores default: the old key held "ADAPTIVE" (4 threads) for every install that saved settings. */
        val perf2 = stringPreferencesKey("performance_mode2")
        val redact = booleanPreferencesKey("redact_secrets")
        val confirmUpload = booleanPreferencesKey("confirm_cloud_upload")
        val notifications = booleanPreferencesKey("notifications")
        val widgets = stringPreferencesKey("dashboard_widgets")
        val experimental = booleanPreferencesKey("experimental")
        val offlineFallback = booleanPreferencesKey("offline_fallback")
        val retryOnline = booleanPreferencesKey("retry_online")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val termuxShare = stringPreferencesKey("termux_share_uri")
    }

    val settings: Flow<AppSettings> = context.gravityDataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = this[Keys.theme2]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }?.takeIf { it != ThemeMode.DARK }
                ?: d.theme,
            operatingMode = this[Keys.mode]?.let { runCatching { OperatingMode.valueOf(it) }.getOrNull() } ?: d.operatingMode,
            defaultProviderId = this[Keys.provider],
            defaultModel = this[Keys.model],
            reasoning = this[Keys.reasoning]?.let { ReasoningMode.fromName(it) } ?: ReasoningMode.AUTO,
            agentMaxSteps = this[Keys.maxSteps] ?: d.agentMaxSteps,
            approveAllCommands = this[Keys.approveCommands] ?: d.approveAllCommands,
            approveFileWrites = this[Keys.approveWrites] ?: d.approveFileWrites,
            maxParallelTasks = this[Keys.parallel] ?: d.maxParallelTasks,
            autoSave = this[Keys.autoSave] ?: d.autoSave,
            editorFontSize = this[Keys.editorFont] ?: d.editorFontSize,
            terminalFontSize = this[Keys.terminalFont] ?: d.terminalFontSize,
            showHiddenFiles = this[Keys.hidden] ?: d.showHiddenFiles,
            webSearchProvider = this[Keys.webSearch] ?: d.webSearchProvider,
            pictureMaker = this[Keys.pictureMaker] ?: d.pictureMaker,
            phonePictureSize = this[Keys.phonePictureSize] ?: d.phonePictureSize,
            localThreads = this[Keys.threads] ?: d.localThreads,
            localContextSize = this[Keys.ctx] ?: d.localContextSize,
            lowMemoryMode = this[Keys.lowMem] ?: d.lowMemoryMode,
            // A saved choice, else an old one that was not just the old default (Adaptive, 4 threads).
            performanceMode = this[Keys.perf2]
                ?: this[Keys.perf]?.takeIf { it != "ADAPTIVE" || (this[Keys.threads] ?: 4) != 4 }
                ?: d.performanceMode,
            redactSecrets = this[Keys.redact] ?: d.redactSecrets,
            confirmCloudUpload = this[Keys.confirmUpload] ?: d.confirmCloudUpload,
            notifications = this[Keys.notifications] ?: d.notifications,
            dashboardWidgets = this[Keys.widgets]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: d.dashboardWidgets,
            experimentalFeatures = this[Keys.experimental] ?: d.experimentalFeatures,
            offlineFallbackToLocal = this[Keys.offlineFallback] ?: d.offlineFallbackToLocal,
            retryWhenOnline = this[Keys.retryOnline] ?: d.retryWhenOnline,
            onboardingDone = this[Keys.onboarding] ?: d.onboardingDone,
            termuxShareUri = this[Keys.termuxShare],
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.gravityDataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[Keys.theme2] = s.theme.name
            prefs[Keys.mode] = s.operatingMode.name
            if (s.defaultProviderId != null) prefs[Keys.provider] = s.defaultProviderId else prefs.remove(Keys.provider)
            if (s.defaultModel != null) prefs[Keys.model] = s.defaultModel else prefs.remove(Keys.model)
            prefs[Keys.reasoning] = s.reasoning.name
            prefs[Keys.maxSteps] = s.agentMaxSteps.coerceIn(1, 200)
            prefs[Keys.approveCommands] = s.approveAllCommands
            prefs[Keys.approveWrites] = s.approveFileWrites
            prefs[Keys.parallel] = s.maxParallelTasks.coerceIn(1, 8)
            prefs[Keys.autoSave] = s.autoSave
            prefs[Keys.editorFont] = s.editorFontSize.coerceIn(8, 28)
            prefs[Keys.terminalFont] = s.terminalFontSize.coerceIn(8, 28)
            prefs[Keys.hidden] = s.showHiddenFiles
            prefs[Keys.webSearch] = s.webSearchProvider
            prefs[Keys.pictureMaker] = s.pictureMaker
            prefs[Keys.phonePictureSize] = s.phonePictureSize
            prefs[Keys.threads] = s.localThreads.coerceIn(1, 16)
            prefs[Keys.ctx] = s.localContextSize.coerceIn(512, 32768)
            prefs[Keys.lowMem] = s.lowMemoryMode
            prefs[Keys.perf2] = s.performanceMode
            prefs[Keys.redact] = s.redactSecrets
            prefs[Keys.confirmUpload] = s.confirmCloudUpload
            prefs[Keys.notifications] = s.notifications
            prefs[Keys.widgets] = s.dashboardWidgets.joinToString(",")
            prefs[Keys.experimental] = s.experimentalFeatures
            prefs[Keys.offlineFallback] = s.offlineFallbackToLocal
            prefs[Keys.retryOnline] = s.retryWhenOnline
            prefs[Keys.onboarding] = s.onboardingDone
            if (s.termuxShareUri != null) prefs[Keys.termuxShare] = s.termuxShareUri else prefs.remove(Keys.termuxShare)
        }
    }
}

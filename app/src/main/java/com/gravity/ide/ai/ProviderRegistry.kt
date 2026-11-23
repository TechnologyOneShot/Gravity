package com.gravity.ide.ai

import com.gravity.ide.ai.local.LocalLlamaProvider
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.ProviderEntity
import com.gravity.ide.data.settings.OperatingMode
import com.gravity.ide.data.settings.SettingsRepository
import com.gravity.ide.network.NetworkMonitor
import com.gravity.ide.security.SecretStore

enum class ProviderKind(val label: String) { GEMINI("Google Gemini"), OPENAI_COMPAT("OpenAI-compatible API"), LOCAL("Local (llama.cpp)") }

/** A provider plus the model to call, after routing rules were applied. */
data class ResolvedModel(val provider: LlmProvider, val model: String, val entity: ProviderEntity?, val fallbackReason: String? = null)

class NoProviderException(message: String, val waitForNetwork: Boolean = false) : Exception(message)

/**
 * Builds provider clients from stored configuration and applies the operating-mode rules:
 * Local-only never touches the network; Cloud-only never uses the local model; Hybrid/Auto
 * prefer the requested provider and fall back to the local model when offline.
 */
class ProviderRegistry(
    private val db: GravityDatabase,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val localHost: LocalModelHost,
    private val events: EventLog,
) {
    val local: LocalLlamaProvider = LocalLlamaProvider(localHost) { localHost.loadedOrNull()?.name }

    fun build(entity: ProviderEntity): LlmProvider = when (ProviderKind.valueOf(entity.kind)) {
        ProviderKind.GEMINI -> GeminiProvider(
            entity.id,
            secrets.get(SecretStore.providerKey(entity.id)) ?: throw NoProviderException("No API key stored for ${entity.name}"),
            entity.baseUrl.ifBlank { GeminiProvider.DEFAULT_BASE_URL },
            entity.timeoutSec,
        )
        ProviderKind.OPENAI_COMPAT -> OpenAiCompatibleProvider(
            entity.id,
            secrets.get(SecretStore.providerKey(entity.id)),
            entity.baseUrl,
            entity.timeoutSec,
            entity.supportsReasoningEffort,
            entity.streamUsage,
        )
        ProviderKind.LOCAL -> local
    }

    suspend fun resolve(providerId: String?, model: String?): ResolvedModel {
        val s = settings.current()
        val online = network.isOnline
        val all = db.providers().all().filter { it.enabled }
        val requested = (providerId ?: s.defaultProviderId)?.let { id -> if (id == LocalLlamaProvider.ID) null else all.firstOrNull { it.id == id } }
        val wantsLocal = (providerId ?: s.defaultProviderId) == LocalLlamaProvider.ID

        fun localOrThrow(reason: String?): ResolvedModel {
            val loaded = localHost.loadedOrNull()
                ?: throw NoProviderException(
                    (reason?.let { "$it " } ?: "") + if (!localHost.runtimeAvailable) (localHost.runtimeError ?: "Local runtime unavailable.") else "Load a local model in Model Manager first.",
                    waitForNetwork = reason != null && s.retryWhenOnline,
                )
            return ResolvedModel(local, loaded.name, null, reason)
        }

        return when (s.operatingMode) {
            OperatingMode.LOCAL_ONLY -> return localOrThrow(null)
            OperatingMode.CLOUD_ONLY -> {
                val p = requested ?: all.firstOrNull { it.kind != ProviderKind.LOCAL.name }
                    ?: throw NoProviderException("Cloud-only mode: no cloud provider configured.")
                if (!online) throw NoProviderException("Offline. Cloud-only mode is active.", waitForNetwork = true)
                return ResolvedModel(build(p), model ?: p.defaultModel, p)
            }
            OperatingMode.HYBRID, OperatingMode.AUTO -> {
                if (wantsLocal) return localOrThrow(null)
                val cloud = requested ?: all.firstOrNull { it.kind != ProviderKind.LOCAL.name }
                if (cloud == null) return localOrThrow("No cloud provider configured.")
                if (!online) {
                    if (s.offlineFallbackToLocal && localHost.loadedOrNull() != null) {
                        events.log(EventCategory.PROVIDER, "Offline: routed to local model", "Requested ${cloud.name}")
                        return localOrThrow("Offline — using the local model instead of ${cloud.name}.")
                    }
                    throw NoProviderException("Offline and no local model is loaded.", waitForNetwork = true)
                }
                if (cloud.usageLimitTokens > 0 && cloud.totalInputTokens + cloud.totalOutputTokens >= cloud.usageLimitTokens) {
                    val backup = all.firstOrNull { it.id != cloud.id && it.kind != ProviderKind.LOCAL.name }
                    if (backup != null) {
                        events.log(EventCategory.PROVIDER, "Usage limit reached for ${cloud.name}", "Using backup ${backup.name}", EventLevel.WARN)
                        return ResolvedModel(build(backup), backup.defaultModel, backup, "Usage limit reached for ${cloud.name}")
                    }
                    throw NoProviderException("Usage limit reached for ${cloud.name} and no backup provider is configured.")
                }
                return ResolvedModel(build(cloud), model ?: cloud.defaultModel, cloud)
            }
        }
    }

    /** Backup provider used after repeated failures of the primary (priority order). */
    suspend fun backupFor(failedId: String?): ResolvedModel? {
        if (!network.isOnline) return localHost.loadedOrNull()?.let { ResolvedModel(local, it.name, null, "Primary failed while offline") }
        val p = db.providers().all().firstOrNull { it.enabled && it.id != failedId && it.kind != ProviderKind.LOCAL.name } ?: return null
        return ResolvedModel(build(p), p.defaultModel, p, "Primary provider failed")
    }

    suspend fun recordUsage(entity: ProviderEntity?, inTok: Int, outTok: Int) {
        if (entity == null) return
        db.providers().addUsage(entity.id, inTok.toLong(), outTok.toLong())
    }

    suspend fun recordStatus(entity: ProviderEntity?, ok: Boolean, message: String) {
        if (entity == null) return
        val current = db.providers().get(entity.id) ?: return
        db.providers().setStatus(entity.id, message.take(200), System.currentTimeMillis(), if (ok) 0 else current.failureCount + 1)
    }

    /** Real connection test: lists models with the stored credentials. */
    suspend fun test(entity: ProviderEntity): Result<List<String>> = try {
        val models = build(entity).listModels()
        recordStatus(entity, true, "OK · ${models.size} models")
        events.log(EventCategory.PROVIDER, "Connection OK: ${entity.name}", "${models.size} models available")
        Result.success(models)
    } catch (e: Exception) {
        recordStatus(entity, false, e.message ?: "failed")
        events.log(EventCategory.PROVIDER, "Connection failed: ${entity.name}", e.message ?: "", EventLevel.ERROR)
        Result.failure(e)
    }
}

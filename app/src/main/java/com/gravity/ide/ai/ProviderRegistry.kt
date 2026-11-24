package com.gravity.ide.ai

import com.gravity.ide.ai.local.LocalLlamaProvider
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.core.ModelPicker
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

/** Every model of a cloud key has used up its free quota for now. */
class QuotaExhaustedException(val providerName: String, val retryIn: String?, cause: LlmException, quota: Boolean = true) : LlmException(
    (if (quota) "$providerName's free limit is used up for now" + (retryIn?.let { " (it resets in about $it)" } ?: "")
    else "$providerName's models are busy right now") +
        ". Gravity tried this key's other models too. Wait and try again, or use the phone model.",
    httpCode = if (quota) 429 else 503,
) {
    init { initCause(cause) }
}

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
        ProviderKind.GEMINI -> SelfHealingProvider(
            GeminiProvider(
                entity.id,
                secrets.get(SecretStore.providerKey(entity.id)) ?: throw NoProviderException("No API key stored for ${entity.name}"),
                entity.baseUrl.ifBlank { GeminiProvider.DEFAULT_BASE_URL },
                entity.timeoutSec,
            ),
            entity,
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

        suspend fun localOrThrow(reason: String?): ResolvedModel {
            val loaded = localHost.loadedOrNull()
                ?: localHost.autoLoad(db.models().all(), s)
                ?: throw NoProviderException(
                    (reason?.let { "$it " } ?: "") + if (!localHost.runtimeAvailable) (localHost.runtimeError ?: "Local runtime unavailable.")
                    else "No phone model could be loaded. Download one in Model Manager (or load it there), or connect a free Gemini key.",
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
                return ResolvedModel(build(p), model?.takeIf { it.isNotBlank() } ?: p.defaultModel, p)
            }
            OperatingMode.HYBRID, OperatingMode.AUTO -> {
                if (wantsLocal) return localOrThrow(null)
                val cloud = requested ?: all.firstOrNull { it.kind != ProviderKind.LOCAL.name }
                if (cloud == null) return localOrThrow("No cloud provider configured.")
                if (!online) {
                    if (s.offlineFallbackToLocal && (localHost.loadedOrNull() ?: localHost.autoLoad(db.models().all(), s)) != null) {
                        events.log(EventCategory.PROVIDER, "Offline: routed to local model", "Requested ${cloud.name}")
                        return localOrThrow("Offline — using the local model instead of ${cloud.name}.")
                    }
                    throw NoProviderException("Offline, and no phone model is downloaded to use instead.", waitForNetwork = true)
                }
                if (cloud.usageLimitTokens > 0 && cloud.totalInputTokens + cloud.totalOutputTokens >= cloud.usageLimitTokens) {
                    val backup = all.firstOrNull { it.id != cloud.id && it.kind != ProviderKind.LOCAL.name }
                    if (backup != null) {
                        events.log(EventCategory.PROVIDER, "Usage limit reached for ${cloud.name}", "Using backup ${backup.name}", EventLevel.WARN)
                        return ResolvedModel(build(backup), backup.defaultModel, backup, "Usage limit reached for ${cloud.name}")
                    }
                    throw NoProviderException("Usage limit reached for ${cloud.name} and no backup provider is configured.")
                }
                return ResolvedModel(build(cloud), model?.takeIf { it.isNotBlank() } ?: cloud.defaultModel, cloud)
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

    /**
     * Picks the newest stable model this key can use (Gemini only) and saves it as the provider's
     * default. Used when a provider is added without a model ID.
     */
    suspend fun autoSelectModel(entity: ProviderEntity): Result<String> = try {
        val models = build(entity).listModels()
        val best = (if (entity.kind == ProviderKind.GEMINI.name) ModelPicker.bestGemini(models) else models.firstOrNull())
            ?: throw LlmException("${entity.name} returned no usable models for this key.")
        db.providers().get(entity.id)?.let { db.providers().upsert(it.copy(defaultModel = best)) }
        settings.update { if (it.defaultProviderId == entity.id && it.defaultModel.isNullOrBlank()) it.copy(defaultModel = best) else it }
        events.log(EventCategory.PROVIDER, "Default model for ${entity.name}: $best", "Chosen from ${models.size} models offered to this key")
        Result.success(best)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Called when [failedModel] is reported missing or retired. Lists what the key can use, and
     * if the model really is gone, switches the provider, the app default and existing chats to
     * the newest stable model. Returns the replacement, or null when nothing should change.
     */
    suspend fun repairModel(entity: ProviderEntity, failedModel: String): String? {
        if (entity.kind != ProviderKind.GEMINI.name) return null
        val models = try { build(entity).listModels() } catch (_: Exception) { return null }
        if (failedModel.isNotBlank() && failedModel in models) return null
        val best = ModelPicker.bestGemini(models) ?: return null
        if (best == failedModel) return null
        db.providers().get(entity.id)?.let { if (it.defaultModel == failedModel || it.defaultModel.isBlank()) db.providers().upsert(it.copy(defaultModel = best)) }
        settings.update { if (it.defaultModel == failedModel) it.copy(defaultModel = best) else it }
        if (failedModel.isNotBlank()) db.chats().replaceModel(failedModel, best)
        events.log(
            EventCategory.PROVIDER,
            "Model ${failedModel.ifBlank { "(none)" }} unavailable on ${entity.name}: switched to $best",
            "The provider no longer offers ${failedModel.ifBlank { "the configured model" }} to this key. $best is the newest stable model it lists.",
            EventLevel.WARN,
        )
        return best
    }

    /**
     * Gemini provider wrapper: if the requested model is missing or retired (and nothing has been
     * streamed yet), switch to the newest available model, announce it with
     * [LlmEvent.ModelSwitched] and retry once. Any other failure is passed through unchanged.
     */
    private inner class SelfHealingProvider(private val inner: LlmProvider, private val entity: ProviderEntity) : LlmProvider by inner {
        override suspend fun stream(request: LlmRequest, onEvent: (LlmEvent) -> Unit) {
            if (request.model.isBlank()) {
                val chosen = repairModel(entity, "") ?: throw LlmException("No model is set for ${entity.name}. Open AI Providers → Discover models.")
                onEvent(LlmEvent.ModelSwitched("", chosen))
                return inner.stream(request.copy(model = chosen), onEvent)
            }
            var emitted = false
            val start = exhaustedNow(entity, request.model)?.let { firstAvailable(entity, request.model) } ?: request.model
            if (start != request.model) onEvent(LlmEvent.ModelSwitched(request.model, start, "${request.model}'s free limit is used up for now"))
            try {
                inner.stream(request.copy(model = start)) { emitted = true; onEvent(it) }
            } catch (e: LlmException) {
                if (emitted) throw e
                when {
                    ModelPicker.isTemporarilyUnavailable(e) -> streamWithQuotaFallback(request, start, e, onEvent)
                    ModelPicker.isModelUnavailable(e) -> {
                        val replacement = repairModel(entity, start) ?: throw e
                        onEvent(LlmEvent.ModelSwitched(start, replacement))
                        inner.stream(request.copy(model = replacement), onEvent)
                    }
                    else -> throw e
                }
            }
        }

        /** [failed] is busy or out of free quota: try the key's other Gemini models (each has its own quota and load). */
        private suspend fun streamWithQuotaFallback(request: LlmRequest, failed: String, error: LlmException, onEvent: (LlmEvent) -> Unit) {
            markExhausted(entity, failed, error)
            fun why(model: String, e: Throwable) = if (ModelPicker.isQuotaExhausted(e)) "$model's free limit is used up for now" else "$model is busy right now"
            val models = try { inner.listModels() } catch (_: Exception) { throw error }
            var last: LlmException = error
            var from = failed
            for (candidate in ModelPicker.quotaFallbacks(models, failed, exhaustedSet(entity)).take(4)) {
                var emitted = false
                try {
                    onEvent(LlmEvent.ModelSwitched(from, candidate, why(from, last)))
                    inner.stream(request.copy(model = candidate)) { emitted = true; onEvent(it) }
                    events.log(EventCategory.PROVIDER, "${why(failed, error)}: answered with $candidate", error.message.orEmpty().take(300), EventLevel.WARN)
                    return
                } catch (e: LlmException) {
                    if (emitted || !(ModelPicker.isTemporarilyUnavailable(e) || ModelPicker.isModelUnavailable(e))) throw e
                    markExhausted(entity, candidate, e)
                    last = e
                    from = candidate
                }
            }
            throw QuotaExhaustedException(entity.name, ModelPicker.retryHint(error), last, quota = ModelPicker.isQuotaExhausted(error))
        }
    }

    // Models whose free quota ran out, per provider, until about when (in memory; resets on restart).
    private val exhausted = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun markExhausted(entity: ProviderEntity, model: String, e: Throwable) {
        val hint = Regex("retry in (?:(\\d+)h)?(?:(\\d+)m)?(\\d+(?:\\.\\d+)?)s", RegexOption.IGNORE_CASE).find(e.message.orEmpty())
        val ms = hint?.let { m ->
            ((m.groupValues[1].toLongOrNull() ?: 0) * 3600 + (m.groupValues[2].toLongOrNull() ?: 0) * 60 + (m.groupValues[3].toDoubleOrNull() ?: 0.0).toLong() + 1) * 1000
        } ?: (if (ModelPicker.isQuotaExhausted(e)) 60L * 60 * 1000 else 5L * 60 * 1000)
        exhausted["${entity.id}/$model"] = System.currentTimeMillis() + ms
    }

    private fun exhaustedNow(entity: ProviderEntity, model: String): Long? =
        exhausted["${entity.id}/$model"]?.takeIf { it > System.currentTimeMillis() }

    private fun exhaustedSet(entity: ProviderEntity): Set<String> =
        exhausted.filter { it.key.startsWith("${entity.id}/") && it.value > System.currentTimeMillis() }.keys.map { it.substringAfter('/') }.toSet()

    /** The first model of this key that is not known to be out of quota, or null if none is known. */
    private suspend fun firstAvailable(entity: ProviderEntity, preferred: String): String? {
        val models = try { build(entity).listModels() } catch (_: Exception) { return null }
        return ModelPicker.quotaFallbacks(models, preferred, exhaustedSet(entity)).firstOrNull()
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

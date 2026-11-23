package com.gravity.ide.models

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.gravity.ide.ai.Http
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.core.GgufReader
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.LocalModelEntity
import com.gravity.ide.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Hugging Face Hub metadata (real API: /api/models and /api/models/{repo}/tree/main). */
data class HubModel(val repoId: String, val downloads: Long, val likes: Long, val license: String?, val tags: List<String>)

data class HubFile(val path: String, val size: Long, val sha256: String?)

enum class ModelStatus { QUEUED, DOWNLOADING, PAUSED, VERIFYING, READY, FAILED }

class ModelManager(
    private val context: Context,
    private val db: GravityDatabase,
    private val secrets: SecretStore,
    private val host: LocalModelHost,
    private val events: EventLog,
) {
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val work = WorkManager.getInstance(context)

    fun freeBytes(): Long = StatFs(modelsDir.absolutePath).availableBytes

    private fun hubRequest(url: String): Request.Builder = Request.Builder().url(url).apply {
        secrets.get(SecretStore.HF_TOKEN)?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    /** Searches GGUF repositories on the Hub. */
    suspend fun searchHub(query: String): List<HubModel> {
        val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("filter", "gguf")
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("limit", "30")
            .addQueryParameter("full", "false")
            .build().toString()
        return Http.execute(Http.base, hubRequest(url).get().build()) { response ->
            if (!response.isSuccessful) throw Http.errorFrom(response)
            val arr = JSONArray(response.body?.string().orEmpty())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList()
                HubModel(
                    repoId = o.optString("id", o.optString("modelId")),
                    downloads = o.optLong("downloads"),
                    likes = o.optLong("likes"),
                    license = tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:"),
                    tags = tags,
                )
            }
        }
    }

    /** Lists .gguf files with their real size and LFS SHA-256 (used as the expected checksum). */
    suspend fun listHubFiles(repoId: String): List<HubFile> {
        val url = "https://huggingface.co/api/models/$repoId/tree/main?recursive=true"
        return Http.execute(Http.base, hubRequest(url).get().build()) { response ->
            if (!response.isSuccessful) throw Http.errorFrom(response)
            val arr = JSONArray(response.body?.string().orEmpty())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val path = o.optString("path")
                if (o.optString("type") != "file" || !path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
                val lfs = o.optJSONObject("lfs")
                HubFile(path, lfs?.optLong("size") ?: o.optLong("size"), lfs?.optString("oid")?.takeIf { it.length == 64 })
            }.sortedBy { it.size }
        }
    }

    suspend fun enqueueHubDownload(repoId: String, file: HubFile, license: String?): LocalModelEntity {
        val url = "https://huggingface.co/$repoId/resolve/main/${file.path}"
        return enqueueDownload(url, file.path.substringAfterLast('/'), file.sha256, file.size, repoId, license)
    }

    suspend fun enqueueDownload(
        url: String,
        name: String,
        sha256: String?,
        expectedSize: Long = 0,
        repoId: String? = null,
        license: String? = null,
    ): LocalModelEntity = withContext(Dispatchers.IO) {
        val parsed = try { url.toHttpUrl() } catch (e: IllegalArgumentException) { throw IOException("Invalid URL") }
        if (parsed.scheme != "https") throw IOException("Model downloads require HTTPS")
        if (expectedSize > 0 && expectedSize + SAFETY_MARGIN > freeBytes()) {
            throw IOException("Not enough storage: need ${mb(expectedSize + SAFETY_MARGIN)} MB, ${mb(freeBytes())} MB free")
        }
        val id = UUID.randomUUID().toString()
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val entity = LocalModelEntity(
            id = id, name = name, sourceUrl = url, repoId = repoId, filePath = File(modelsDir, "$id-$safeName").absolutePath,
            sizeBytes = expectedSize, sha256Expected = sha256?.lowercase(), status = ModelStatus.QUEUED.name, license = license,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        )
        db.models().upsert(entity)
        schedule(id)
        events.log(EventCategory.MODEL, "Queued download: $name", url)
        entity
    }

    fun schedule(modelId: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to modelId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(workName(modelId), ExistingWorkPolicy.KEEP, request)
    }

    suspend fun pause(modelId: String) {
        work.cancelUniqueWork(workName(modelId))
        db.models().get(modelId)?.let { db.models().upsert(it.copy(status = ModelStatus.PAUSED.name, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun resume(modelId: String) {
        db.models().get(modelId)?.let { db.models().upsert(it.copy(status = ModelStatus.QUEUED.name, error = null, updatedAt = System.currentTimeMillis())) }
        schedule(modelId)
    }

    suspend fun delete(modelId: String) = withContext(Dispatchers.IO) {
        work.cancelUniqueWork(workName(modelId))
        val m = db.models().get(modelId) ?: return@withContext
        if (host.loadedOrNull()?.modelId == modelId) host.unload()
        File(m.filePath).delete()
        File(m.filePath + ".part").delete()
        db.models().delete(modelId)
        events.log(EventCategory.MODEL, "Deleted model ${m.name}", "Freed ${mb(m.sizeBytes)} MB")
    }

    /** Imports a GGUF file chosen with the system picker (copied into app storage, then verified). */
    suspend fun importLocal(uri: Uri): LocalModelEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "imported.gguf"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) 0 else c.getLong(1)
            }
        }
        if (size > 0 && size + SAFETY_MARGIN > freeBytes()) throw IOException("Not enough storage to import ${mb(size)} MB")
        val id = UUID.randomUUID().toString()
        val dest = File(modelsDir, "$id-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    digest.update(buf, 0, r)
                    out.write(buf, 0, r)
                }
            }
        } ?: throw IOException("Cannot read selected file")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val base = LocalModelEntity(
            id = id, name = name, filePath = dest.absolutePath, sizeBytes = dest.length(), downloadedBytes = dest.length(),
            sha256Actual = sha, status = ModelStatus.VERIFYING.name, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        )
        val final = withGgufMetadata(base)
        db.models().upsert(final)
        events.log(EventCategory.MODEL, "Imported ${final.name}", "SHA-256 $sha\n${final.architecture ?: "unknown arch"} ${final.quantization ?: ""}", if (final.status == ModelStatus.READY.name) EventLevel.INFO else EventLevel.ERROR)
        final
    }

    /** Parses the GGUF header; a file that is not GGUF is marked FAILED rather than READY. */
    fun withGgufMetadata(m: LocalModelEntity): LocalModelEntity = try {
        val info = GgufReader.read(File(m.filePath))
        m.copy(
            status = ModelStatus.READY.name,
            architecture = info.architecture,
            quantization = info.quantization,
            contextLength = info.contextLength,
            sizeLabel = info.sizeLabel,
            license = m.license ?: info.license,
            hasChatTemplate = info.hasChatTemplate,
            // Prefer the model's own name; a bare architecture name ("llama") says less than the file name.
            name = info.name?.takeIf { it.isNotBlank() && !it.equals(info.architecture, ignoreCase = true) }
                ?.let { n -> info.quantization?.let { "$n ($it)" } ?: n } ?: m.name,
            error = if (!info.hasChatTemplate) "No chat template in GGUF metadata — chat formatting may fail" else null,
            updatedAt = System.currentTimeMillis(),
        )
    } catch (e: Exception) {
        m.copy(status = ModelStatus.FAILED.name, error = "Not a valid GGUF model: ${e.message}", updatedAt = System.currentTimeMillis())
    }

    /** Measures real generation speed of the loaded model (tokens/s) and stores it. */
    suspend fun benchmark(modelId: String, runPrompt: suspend () -> Pair<Int, Long>): Double {
        val (tokens, ms) = runPrompt()
        val tps = if (ms > 0) tokens * 1000.0 / ms else 0.0
        db.models().get(modelId)?.let { db.models().upsert(it.copy(benchmarkTokensPerSec = tps, updatedAt = System.currentTimeMillis())) }
        events.log(EventCategory.MODEL, "Benchmark: %.1f tokens/s".format(tps), "$tokens tokens in $ms ms")
        return tps
    }

    companion object {
        const val TAG = "model-download"
        const val SAFETY_MARGIN = 300L * 1024 * 1024
        fun workName(id: String) = "model-$id"
        fun mb(bytes: Long) = bytes / (1024 * 1024)
    }
}

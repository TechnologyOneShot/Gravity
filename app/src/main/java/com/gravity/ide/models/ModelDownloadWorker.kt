package com.gravity.ide.models

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gravity.ide.GravityApp
import com.gravity.ide.agent.TaskForegroundService
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Resumable model download (HTTP Range) with progress, storage checks and SHA-256
 * verification. A file is only marked READY after the hash matches (when an expected hash
 * is known) and its GGUF header parses.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as GravityApp).container
        val db = container.db
        val manager = container.models
        val events = container.events
        val id = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        var model = db.models().get(id) ?: return Result.failure()
        val url = model.sourceUrl ?: return Result.failure()
        val target = File(model.filePath)
        val part = File(model.filePath + ".part")

        try {
            setForeground(foreground(model.name, 0))
        } catch (_: Exception) {
            // Foreground not permitted right now; continue as regular work.
        }

        try {
            var existing = if (part.exists()) part.length() else 0L
            val request = Request.Builder().url(url).header("User-Agent", "Gravity/0.1").apply {
                if (existing > 0) header("Range", "bytes=$existing-")
                container.secrets.get(com.gravity.ide.security.SecretStore.HF_TOKEN)?.takeIf { it.isNotBlank() && url.contains("huggingface.co") }
                    ?.let { header("Authorization", "Bearer $it") }
            }.build()

            val response = com.gravity.ide.ai.Http.base.newCall(request).execute()
            response.use { resp ->
                if (resp.code == 416 && existing > 0) {
                    // Range not satisfiable: the part file is already complete.
                } else {
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val append = resp.code == 206 && existing > 0
                    if (!append) existing = 0
                    val body = resp.body ?: throw IOException("Empty response")
                    val remaining = body.contentLength()
                    val total = if (remaining > 0) existing + remaining else model.sizeBytes
                    if (remaining > 0 && remaining + ModelManager.SAFETY_MARGIN > manager.freeBytes()) {
                        throw IOException("Not enough storage: need ${ModelManager.mb(remaining)} MB more, ${ModelManager.mb(manager.freeBytes())} MB free")
                    }
                    db.models().progress(id, existing, total, ModelStatus.DOWNLOADING.name, System.currentTimeMillis())
                    RandomAccessFile(part, "rw").use { raf ->
                        if (!append) raf.setLength(0)
                        raf.seek(existing)
                        val input = body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        var written = existing
                        var lastReport = 0L
                        while (true) {
                            if (isStopped) {
                                db.models().progress(id, written, total, ModelStatus.PAUSED.name, System.currentTimeMillis())
                                return Result.success()
                            }
                            val r = input.read(buf)
                            if (r < 0) break
                            raf.write(buf, 0, r)
                            written += r
                            if (written - lastReport > 4L * 1024 * 1024) {
                                lastReport = written
                                db.models().progress(id, written, total, ModelStatus.DOWNLOADING.name, System.currentTimeMillis())
                                val pct = if (total > 0) (written * 100 / total).toInt() else 0
                                try { setForeground(foreground(model.name, pct)) } catch (_: Exception) {}
                            }
                        }
                        if (total > 0 && written != total) throw IOException("Incomplete download: $written of $total bytes")
                    }
                }
            }

            // Verify.
            db.models().progress(id, part.length(), part.length(), ModelStatus.VERIFYING.name, System.currentTimeMillis())
            val sha = sha256(part)
            model = db.models().get(id) ?: return Result.failure()
            val expected = model.sha256Expected
            if (expected != null && !expected.equals(sha, ignoreCase = true)) {
                part.delete()
                db.models().upsert(model.copy(status = ModelStatus.FAILED.name, sha256Actual = sha, error = "Checksum mismatch: expected $expected, got $sha. The partial file was deleted.", updatedAt = System.currentTimeMillis()))
                events.log(EventCategory.MODEL, "Checksum mismatch: ${model.name}", "expected $expected\nactual   $sha", EventLevel.ERROR)
                return Result.failure()
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not finalise file")
            val verified = manager.withGgufMetadata(
                model.copy(sha256Actual = sha, sizeBytes = target.length(), downloadedBytes = target.length())
            )
            db.models().upsert(verified)
            events.log(
                EventCategory.MODEL,
                if (verified.status == ModelStatus.READY.name) "Downloaded and verified ${verified.name}" else "Downloaded file is not a usable model",
                "SHA-256 $sha" + (if (expected != null) " (matches expected)" else " (no expected hash was provided)") + (verified.error?.let { "\n$it" } ?: ""),
                if (verified.status == ModelStatus.READY.name) EventLevel.INFO else EventLevel.ERROR,
            )
            return if (verified.status == ModelStatus.READY.name) Result.success() else Result.failure()
        } catch (e: Exception) {
            if (isStopped) return Result.success()
            val current = db.models().get(id)
            if (current != null) {
                db.models().upsert(current.copy(status = ModelStatus.FAILED.name, downloadedBytes = part.length(), error = e.message ?: e.javaClass.simpleName, updatedAt = System.currentTimeMillis()))
            }
            events.log(EventCategory.MODEL, "Download failed: ${model.name}", e.message ?: "", EventLevel.ERROR)
            return Result.failure()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun foreground(name: String, percent: Int): ForegroundInfo {
        TaskForegroundService.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, TaskForegroundService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $name")
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        private const val NOTIFICATION_ID = 4201
    }
}

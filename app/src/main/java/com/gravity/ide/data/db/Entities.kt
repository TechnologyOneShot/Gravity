package com.gravity.ide.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val rootPath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long = 0,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    /** When true, no file content from this project is ever sent to a cloud provider. */
    val localOnly: Boolean = false,
    val buildCommand: String? = null,
    /** Glob relative to the project root that must exist for a build to count as successful. */
    val artifactPattern: String? = null,
    val testCommand: String? = null,
    val sourceTreeUri: String? = null,
    /** Build backend override: COMMAND, STATIC_WEB or TERMUX (null = automatic). */
    val buildBackend: String? = null,
)

@Entity(tableName = "chats", indices = [Index("projectId")])
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val projectId: String? = null,
    val providerId: String? = null,
    val model: String? = null,
    val reasoning: String = "BALANCED",
    val webGrounding: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "messages", indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val role: String,
    val content: String,
    /** JSON array of {name, mime, size, kind} — attachment metadata only. */
    val attachments: String = "[]",
    val model: String? = null,
    val providerId: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** JSON array of {title, url} from web grounding. */
    val sources: String = "[]",
    val error: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** GEMINI, OPENAI_COMPAT or LOCAL */
    val kind: String,
    val baseUrl: String,
    val defaultModel: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val timeoutSec: Int = 120,
    val supportsReasoningEffort: Boolean = false,
    val streamUsage: Boolean = true,
    val inputPricePerMTok: Double = 0.0,
    val outputPricePerMTok: Double = 0.0,
    val usageLimitTokens: Long = 0,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val failureCount: Int = 0,
    val lastStatus: String? = null,
    val lastCheckedAt: Long = 0,
    val createdAt: Long,
)

@Entity(tableName = "local_models")
data class LocalModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sourceUrl: String? = null,
    val repoId: String? = null,
    val filePath: String,
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val sha256Expected: String? = null,
    val sha256Actual: String? = null,
    /** QUEUED, DOWNLOADING, PAUSED, VERIFYING, READY, FAILED */
    val status: String,
    val architecture: String? = null,
    val quantization: String? = null,
    val contextLength: Long? = null,
    val sizeLabel: String? = null,
    val license: String? = null,
    val hasChatTemplate: Boolean = false,
    val role: String? = null,
    val isDefault: Boolean = false,
    val benchmarkTokensPerSec: Double? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "agent_tasks", indices = [Index("projectId")])
data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val instruction: String,
    /** PENDING, RUNNING, PAUSED, WAITING_APPROVAL, WAITING_NETWORK, INTERRUPTED, COMPLETED, FAILED, CANCELLED */
    val status: String,
    val providerId: String?,
    val model: String?,
    val reasoning: String,
    val teamId: String? = null,
    /** Serialised conversation (see LlmMessageCodec) so the task can resume after process death. */
    val conversation: String = "[]",
    val stage: String? = null,
    val stepCount: Int = 0,
    val plan: String? = null,
    val summary: String? = null,
    val error: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Every activity / notification shown in the app comes from one of these rows. */
@Entity(tableName = "events", indices = [Index("taskId"), Index("createdAt")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val title: String,
    val detail: String = "",
    val level: String = "INFO",
    val taskId: String? = null,
    val projectId: String? = null,
    val agentRole: String? = null,
    val model: String? = null,
    val tool: String? = null,
    val read: Boolean = false,
    val createdAt: Long,
)

/** A saved previous version of a file (checkpoint). blobPath == null means "file did not exist". */
@Entity(tableName = "file_versions", indices = [Index("projectId"), Index("taskId")])
data class FileVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val relPath: String,
    val blobPath: String?,
    val source: String,
    val taskId: String? = null,
    val agentRole: String? = null,
    val model: String? = null,
    val note: String = "",
    val createdAt: Long,
)

@Entity(tableName = "builds", indices = [Index("projectId")])
data class BuildRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val kind: String,
    /** RUNNING, SUCCEEDED, FAILED, CANCELLED */
    val status: String,
    val command: String,
    val exitCode: Int? = null,
    val artifactPath: String? = null,
    val log: String = "",
    val error: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

@Entity(tableName = "screenshots")
data class ScreenshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val packageName: String? = null,
    val label: String = "",
    val width: Int,
    val height: Int,
    val analysis: String? = null,
    val testRunId: Long? = null,
    val createdAt: Long,
)

@Entity(tableName = "teams")
data class TeamEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** JSON array of {role, providerId, model} */
    val members: String,
    val reviewRounds: Int = 1,
    val runTests: Boolean = true,
    val createdAt: Long,
)

@Entity(tableName = "trash")
data class TrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String,
    val trashPath: String,
    val projectId: String? = null,
    val isDirectory: Boolean,
    val deletedAt: Long,
)

/** Recorded result of a real check (self-test, device test, acceptance test). */
@Entity(tableName = "test_results", indices = [Index("suite")])
data class TestResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val suite: String,
    val name: String,
    val expected: String,
    val actual: String,
    /** PASS, FAIL, SKIPPED */
    val outcome: String,
    val details: String = "",
    val evidencePath: String? = null,
    val durationMs: Long = 0,
    val createdAt: Long,
)

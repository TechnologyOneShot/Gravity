package com.gravity.ide.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY pinned DESC, lastOpenedAt DESC, updatedAt DESC")
    fun observeActive(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun all(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE archived = :archived ORDER BY pinned DESC, updatedAt DESC")
    fun observe(archived: Boolean): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeChat(id: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun get(id: String): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    suspend fun all(): List<ChatEntity>

    /** Points chats that used a retired model at its replacement. */
    @Query("UPDATE chats SET model = :replacement WHERE model = :old")
    suspend fun replaceModel(old: String, replacement: String)

    @Upsert
    suspend fun upsert(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun messages(chatId: String): List<MessageEntity>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessages(chatId: String)

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 100")
    suspend fun searchMessages(q: String): List<MessageEntity>
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY priority DESC, name ASC")
    fun observe(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY priority DESC, name ASC")
    suspend fun all(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun get(id: String): ProviderEntity?

    @Upsert
    suspend fun upsert(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE providers SET totalInputTokens = totalInputTokens + :inTok, totalOutputTokens = totalOutputTokens + :outTok WHERE id = :id")
    suspend fun addUsage(id: String, inTok: Long, outTok: Long)

    @Query("UPDATE providers SET lastStatus = :status, lastCheckedAt = :at, failureCount = :failures WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long, failures: Int)
}

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models ORDER BY isDefault DESC, updatedAt DESC")
    fun observe(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM local_models ORDER BY updatedAt DESC")
    suspend fun all(): List<LocalModelEntity>

    @Query("SELECT * FROM local_models WHERE id = :id")
    suspend fun get(id: String): LocalModelEntity?

    @Upsert
    suspend fun upsert(model: LocalModelEntity)

    @Query("UPDATE local_models SET downloadedBytes = :downloaded, sizeBytes = :size, status = :status, updatedAt = :at WHERE id = :id")
    suspend fun progress(id: String, downloaded: Long, size: Long, status: String, at: Long)

    @Query("UPDATE local_models SET isDefault = (id = :id)")
    suspend fun setDefault(id: String)

    @Query("DELETE FROM local_models WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY updatedAt DESC")
    fun observe(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    fun observeTask(id: String): Flow<AgentTaskEntity?>

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun get(id: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE status IN (:statuses)")
    suspend fun withStatus(statuses: List<String>): List<AgentTaskEntity>

    @Upsert
    suspend fun upsert(task: AgentTaskEntity)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE taskId = :taskId ORDER BY id ASC")
    fun observeForTask(taskId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE level IN ('WARN','ERROR') OR category = 'NOTIFY' ORDER BY id DESC LIMIT 200")
    fun observeNotifications(): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM events WHERE read = 0 AND (level IN ('WARN','ERROR') OR category = 'NOTIFY')")
    fun observeUnreadCount(): Flow<Int>

    @Query("UPDATE events SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

    @Query("SELECT * FROM events ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventEntity>

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("DELETE FROM events WHERE createdAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface FileVersionDao {
    @Insert
    suspend fun insert(version: FileVersionEntity): Long

    @Query("SELECT * FROM file_versions WHERE taskId = :taskId ORDER BY id ASC")
    fun observeForTask(taskId: String): Flow<List<FileVersionEntity>>

    @Query("SELECT * FROM file_versions WHERE taskId = :taskId ORDER BY id ASC")
    suspend fun forTask(taskId: String): List<FileVersionEntity>

    @Query("SELECT * FROM file_versions WHERE projectId = :projectId AND relPath = :relPath ORDER BY id DESC")
    suspend fun forFile(projectId: String, relPath: String): List<FileVersionEntity>

    @Query("SELECT * FROM file_versions ORDER BY id DESC LIMIT 300")
    fun observeRecent(): Flow<List<FileVersionEntity>>

    @Query("SELECT * FROM file_versions WHERE id = :id")
    suspend fun get(id: Long): FileVersionEntity?
}

@Dao
interface BuildDao {
    @Query("SELECT * FROM builds ORDER BY id DESC LIMIT 100")
    fun observe(): Flow<List<BuildRecordEntity>>

    @Insert
    suspend fun insert(build: BuildRecordEntity): Long

    @Update
    suspend fun update(build: BuildRecordEntity)

    @Query("SELECT * FROM builds WHERE id = :id")
    suspend fun get(id: Long): BuildRecordEntity?

    @Query("SELECT * FROM builds WHERE projectId = :projectId ORDER BY id DESC LIMIT 50")
    suspend fun forProject(projectId: String): List<BuildRecordEntity>
}

@Dao
interface ScreenshotDao {
    @Query("SELECT * FROM screenshots ORDER BY id DESC")
    fun observe(): Flow<List<ScreenshotEntity>>

    @Query("SELECT * FROM screenshots WHERE id = :id")
    suspend fun get(id: Long): ScreenshotEntity?

    @Insert
    suspend fun insert(s: ScreenshotEntity): Long

    @Update
    suspend fun update(s: ScreenshotEntity)

    @Query("DELETE FROM screenshots WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TeamDao {
    @Query("SELECT * FROM teams ORDER BY name")
    fun observe(): Flow<List<TeamEntity>>

    @Query("SELECT * FROM teams ORDER BY name")
    suspend fun all(): List<TeamEntity>

    @Query("SELECT * FROM teams WHERE id = :id")
    suspend fun get(id: String): TeamEntity?

    @Upsert
    suspend fun upsert(team: TeamEntity)

    @Query("DELETE FROM teams WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun observe(): Flow<List<TrashEntity>>

    @Insert
    suspend fun insert(item: TrashEntity): Long

    @Query("SELECT * FROM trash WHERE id = :id")
    suspend fun get(id: Long): TrashEntity?

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TestResultDao {
    @Query("SELECT * FROM test_results ORDER BY id DESC LIMIT 500")
    fun observe(): Flow<List<TestResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: TestResultEntity): Long

    @Query("SELECT * FROM test_results ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TestResultEntity>
}

package com.gravity.ide.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema version 1. Future versions must add a Migration — destructive fallback is
 * deliberately NOT enabled so user data is never silently dropped on upgrade.
 */
@Database(
    entities = [
        ProjectEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        ProviderEntity::class,
        LocalModelEntity::class,
        AgentTaskEntity::class,
        EventEntity::class,
        FileVersionEntity::class,
        BuildRecordEntity::class,
        ScreenshotEntity::class,
        TeamEntity::class,
        TrashEntity::class,
        TestResultEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GravityDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun chats(): ChatDao
    abstract fun providers(): ProviderDao
    abstract fun models(): LocalModelDao
    abstract fun tasks(): TaskDao
    abstract fun events(): EventDao
    abstract fun fileVersions(): FileVersionDao
    abstract fun builds(): BuildDao
    abstract fun screenshots(): ScreenshotDao
    abstract fun teams(): TeamDao
    abstract fun trash(): TrashDao
    abstract fun testResults(): TestResultDao

    companion object {
        fun create(context: Context): GravityDatabase =
            Room.databaseBuilder(context, GravityDatabase::class.java, "gravity.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

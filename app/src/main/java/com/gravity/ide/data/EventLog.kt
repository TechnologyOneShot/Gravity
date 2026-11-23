package com.gravity.ide.data

import com.gravity.ide.data.db.EventDao
import com.gravity.ide.data.db.EventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class EventCategory { AGENT, TOOL, TERMINAL, BUILD, MODEL, PROVIDER, NETWORK, DEVICE, TEST, SECURITY, WORKSPACE, SYSTEM, NOTIFY }

enum class EventLevel { INFO, WARN, ERROR }

/**
 * Single sink for activity. Everything shown in the activity monitor, notifications and
 * agent timelines is written here by the code that actually performed the operation.
 */
class EventLog(private val dao: EventDao, private val scope: CoroutineScope) {

    fun log(
        category: EventCategory,
        title: String,
        detail: String = "",
        level: EventLevel = EventLevel.INFO,
        taskId: String? = null,
        projectId: String? = null,
        agentRole: String? = null,
        model: String? = null,
        tool: String? = null,
    ) {
        val row = EventEntity(
            category = category.name,
            title = title.take(300),
            detail = detail.take(20_000),
            level = level.name,
            taskId = taskId,
            projectId = projectId,
            agentRole = agentRole,
            model = model,
            tool = tool,
            createdAt = System.currentTimeMillis(),
        )
        scope.launch(Dispatchers.IO) { dao.insert(row) }
    }

    suspend fun logNow(category: EventCategory, title: String, detail: String = "", level: EventLevel = EventLevel.INFO, taskId: String? = null) {
        dao.insert(
            EventEntity(category = category.name, title = title.take(300), detail = detail.take(20_000), level = level.name, taskId = taskId, createdAt = System.currentTimeMillis())
        )
    }
}

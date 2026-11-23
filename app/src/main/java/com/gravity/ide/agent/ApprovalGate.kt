package com.gravity.ide.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Suspends an agent until the user approves or rejects a sensitive action
 * (dangerous command, delete, sensitive file write, cloud upload).
 */
class ApprovalGate {

    data class Request(
        val id: String,
        val taskId: String,
        val title: String,
        val detail: String,
        val risk: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val _pending = MutableStateFlow<List<Request>>(emptyList())
    val pending: StateFlow<List<Request>> = _pending.asStateFlow()

    suspend fun ask(taskId: String, title: String, detail: String, risk: String, onWaiting: suspend () -> Unit = {}, onResumed: suspend () -> Unit = {}): Boolean {
        val req = Request(UUID.randomUUID().toString(), taskId, title, detail, risk)
        val deferred = CompletableDeferred<Boolean>()
        waiters[req.id] = deferred
        _pending.update { it + req }
        onWaiting()
        return try {
            deferred.await()
        } finally {
            waiters.remove(req.id)
            _pending.update { list -> list.filterNot { it.id == req.id } }
            onResumed()
        }
    }

    fun respond(requestId: String, approved: Boolean) {
        waiters[requestId]?.complete(approved)
    }

    fun rejectAllFor(taskId: String) {
        _pending.value.filter { it.taskId == taskId }.forEach { respond(it.id, false) }
    }
}

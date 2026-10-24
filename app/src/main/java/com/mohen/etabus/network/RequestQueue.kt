package com.mohen.etabus.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class RequestQueue(private val intervalMillis: Long = 1300L) {
    private class Subscription(val owner: String, val priority: Int) {
        val result = CompletableDeferred<Any>()
    }
    private class Task(val key: String, val order: Long, val send: suspend () -> Any) {
        val subscribers = mutableListOf<Subscription>()
        var started = false
        var attempts = 0
        val priority: Int get() = subscribers.minOf { it.priority }
    }

    private val lock = Any()
    private val tasks = mutableMapOf<String, Task>()
    private val pending = mutableListOf<Task>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sequence = 0L
    private var lastSendNanos: Long? = null

    init {
        require(intervalMillis >= 1300L)
        scope.launch {
            for (ignored in wakeup) {
                while (synchronized(lock) { pending.isNotEmpty() }) {
                    awaitSlot()
                    val task = synchronized(lock) {
                        pending.minWithOrNull(compareBy<Task> { it.priority }.thenBy { it.order })?.also {
                            pending.remove(it)
                            it.started = true
                        }
                    } ?: break
                    runTask(task)
                }
            }
        }
    }

    suspend fun <T : Any> execute(key: String, owner: String, priority: Int, send: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val subscription = Subscription(owner, priority)
        val task = synchronized(lock) {
            tasks[key]?.also { it.subscribers.add(subscription) } ?: Task(key, sequence++) { send() }.also {
                it.subscribers.add(subscription)
                tasks[key] = it
                pending.add(it)
            }
        }
        wakeup.trySend(Unit)
        try {
            @Suppress("UNCHECKED_CAST")
            return subscription.result.await() as T
        } finally {
            synchronized(lock) {
                task.subscribers.remove(subscription)
                if (task.subscribers.isEmpty() && !task.started) remove(task)
            }
        }
    }

    fun cancelOwner(owner: String) {
        val cancelled = mutableListOf<Subscription>()
        synchronized(lock) {
            pending.toList().forEach { task ->
                task.subscribers.filter { it.owner == owner }.forEach { subscription ->
                    task.subscribers.remove(subscription)
                    cancelled.add(subscription)
                }
                if (task.subscribers.isEmpty()) remove(task)
            }
        }
        cancelled.forEach { it.result.cancel(CancellationException("页面已离开，取消未发送的查询")) }
    }

    private suspend fun runTask(task: Task) {
        if (synchronized(lock) { task.subscribers.isEmpty() }) {
            finish(task, null, CancellationException("查询已取消"))
            return
        }
        try {
            task.attempts++
            lastSendNanos = System.nanoTime()
            finish(task, task.send(), null)
        } catch (error: CancellationException) {
            finish(task, null, error)
        } catch (error: Exception) {
            if (error is ServerMessageException || task.attempts >= 2) {
                finish(task, null, error)
            } else {
                synchronized(lock) {
                    task.started = false
                    if (task.subscribers.isEmpty()) remove(task) else pending.add(task)
                }
            }
        } finally {
            lastSendNanos = System.nanoTime()
        }
    }

    private suspend fun awaitSlot() {
        val previous = lastSendNanos ?: return
        val intervalNanos = intervalMillis * 1_000_000L
        while (true) {
            val left = intervalNanos - (System.nanoTime() - previous)
            if (left <= 0) return
            delay((left + 999_999L) / 1_000_000L)
        }
    }

    private fun finish(task: Task, value: Any?, failure: Throwable?) {
        val subscribers = synchronized(lock) {
            remove(task)
            task.subscribers.toList().also { task.subscribers.clear() }
        }
        subscribers.forEach {
            when {
                failure is CancellationException -> it.result.cancel(failure)
                failure != null -> it.result.completeExceptionally(failure)
                value != null -> it.result.complete(value)
                else -> it.result.completeExceptionally(BusApiException("公交查询未返回有效数据"))
            }
        }
    }

    private fun remove(task: Task) {
        pending.remove(task)
        if (tasks[task.key] === task) tasks.remove(task.key)
    }
}
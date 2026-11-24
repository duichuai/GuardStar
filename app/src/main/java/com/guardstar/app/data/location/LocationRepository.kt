package com.guardstar.app.data.location

import android.os.PowerManager
import com.guardstar.app.core.net.ApiException
import com.guardstar.app.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 位置共享状态，供 UI 观察。 */
data class LocationStatus(
    val running: Boolean = false,
    val pendingCount: Int = 0,
    val lastUploadAt: Long? = null,
    val lastUploadedCount: Int = 0,
    val lastError: String? = null,
    val locationAvailable: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val lastPoint: QueuePoint? = null,
)

class LocationRepository(
    private val store: LocationQueueStore,
    private val api: LocationApi,
    private val session: SessionStore,
    private val flushWakeLock: PowerManager.WakeLock? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flushMutex = Mutex()

    private val _status = MutableStateFlow(LocationStatus())
    val status: StateFlow<LocationStatus> = _status.asStateFlow()

    init {
        // 恢复上次进程崩溃前可能遗留的 uploading 记录。
        runCatching { store.resetUploading() }
    }

    fun setRunning(running: Boolean) {
        _status.update { it.copy(running = running, lastError = null) }
    }

    fun setLocationAvailable(available: Boolean) {
        _status.update { it.copy(locationAvailable = available) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _status.update { it.copy(notificationsEnabled = enabled) }
    }

    suspend fun enqueue(point: QueuePoint): Long = withContext(Dispatchers.IO) {
        store.enqueue(point).also {
            _status.update { it.copy(lastPoint = point) }
            updatePending()
        }
    }

    private fun updatePending() {
        _status.update { it.copy(pendingCount = store.countPending()) }
    }

    /**
     * 批量上报：取最多 200 条 pending，服务端返回 accepted/duplicated 的视为成功删除，
     * rejected 的丢弃（服务端判定为过期/非法）；网络失败则按退避回退。
     */
    suspend fun flush(): Unit = withContext(Dispatchers.IO) {
        // 串行化 flush，避免服务循环与 WorkManager 并发取到同一批 pending 记录。
        flushMutex.withLock {
            val batch = store.pending(BATCH_SIZE)
            if (batch.isEmpty()) {
                updatePending()
                return@withLock
            }
            // 上报期间持有唤醒锁，避免设备 Doze 中断网络请求。
            runCatching { flushWakeLock?.acquire(FLUSH_WAKELOCK_TIMEOUT_MS) }
            try {
                val seqs = batch.map { it.clientSeq }
                store.markUploading(seqs)
                runCatching { api.upload(session.deviceId, batch.map { it.toUpload() }) }
                    .onSuccess { resp ->
                        val rejected = resp.rejected.map { it.clientSeq }.toSet()
                        val accepted = seqs.filterNot { rejected.contains(it) }
                        store.delete(accepted)
                        store.delete(rejected.toList())
                        _status.update {
                            it.copy(
                                lastUploadAt = System.currentTimeMillis(),
                                lastUploadedCount = accepted.size,
                                lastError = null,
                                pendingCount = store.countPending(),
                            )
                        }
                    }
                    .onFailure { error ->
                        store.backoff(seqs, System.currentTimeMillis())
                        _status.update {
                            it.copy(
                                lastError = if (error is ApiException) error.message else "网络错误",
                                pendingCount = store.countPending(),
                            )
                        }
                    }
            } finally {
                runCatching { flushWakeLock?.takeIf { it.isHeld }?.release() }
            }
        }
    }

    fun schedulePurge() {
        scope.launch {
            while (true) {
                val cutoff = System.currentTimeMillis() - RETENTION_MS
                withContext(Dispatchers.IO) { store.purgeOlderThan(cutoff) }
                delay(PURGE_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val BATCH_SIZE = 200
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        const val PURGE_INTERVAL_MS = 60 * 60 * 1000L
        const val FLUSH_WAKELOCK_TIMEOUT_MS = 30_000L
    }
}

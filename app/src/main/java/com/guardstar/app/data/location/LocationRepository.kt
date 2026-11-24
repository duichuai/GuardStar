package com.guardstar.app.data.location

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
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** 位置共享状态，供 UI 观察。 */
data class LocationStatus(
    val running: Boolean = false,
    val pendingCount: Int = 0,
    val lastUploadAt: Long? = null,
    val lastError: String? = null,
)

class LocationRepository(
    private val store: LocationQueueStore,
    private val api: LocationApi,
    private val session: SessionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(LocationStatus())
    val status: StateFlow<LocationStatus> = _status.asStateFlow()

    fun setRunning(running: Boolean) {
        _status.update { it.copy(running = running, lastError = null) }
    }

    suspend fun enqueue(point: QueuePoint): Long = withContext(Dispatchers.IO) {
        store.enqueue(point).also { updatePending() }
    }

    private fun updatePending() {
        _status.update { it.copy(pendingCount = store.countPending()) }
    }

    /**
     * 批量上报：取最多 200 条 pending，服务端返回 accepted/duplicated 的视为成功删除，
     * rejected 的丢弃（服务端判定为过期/非法）；网络失败则按退避回退。
     */
    suspend fun flush(): Unit = withContext(Dispatchers.IO) {
        val batch = store.pending(BATCH_SIZE)
        if (batch.isEmpty()) {
            updatePending()
            return@withContext
        }
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
                        lastError = null,
                        pendingCount = store.countPending(),
                    )
                }
            }
            .onFailure { error ->
                store.backoff(seqs, System.currentTimeMillis() + BACKOFF_MS)
                _status.update {
                    it.copy(
                        lastError = if (error is ApiException) error.message else "网络错误",
                        pendingCount = store.countPending(),
                    )
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
        const val BACKOFF_MS = 30 * 1000L
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        const val PURGE_INTERVAL_MS = 60 * 60 * 1000L
    }
}

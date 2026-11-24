package com.guardstar.app.data.location

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.guardstar.app.GuardStarApp
import java.util.concurrent.TimeUnit

/**
 * 兜底批量上报 Worker：服务被系统杀死后仍能周期性把本地队列补传到服务端。
 * 仅在网络可用时由系统调度（见 constraints），最小周期 15 分钟。
 */
class LocationFlushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GuardStarApp
        runCatching { app.locationRepository.flush() }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "guardstar_location_flush"
        private const val ONCE_WORK_NAME = "guardstar_location_flush_once"

        private fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocationFlushWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(constraints()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * 一次性立即补传：用户停止共享或服务销毁时调用，把残余队列尽快上报。
         * 网络约束保证离线时由系统在联网后自动调度。
         */
        fun flushOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<LocationFlushWorker>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

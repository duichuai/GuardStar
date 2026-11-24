package com.guardstar.app.data.location

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<LocationFlushWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

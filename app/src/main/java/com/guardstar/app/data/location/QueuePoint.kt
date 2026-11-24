package com.guardstar.app.data.location

/** 本地待上报位置点（写入 SQLite 队列）。 */
data class QueuePoint(
    val clientSeq: Int = 0,
    val recordedAt: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val source: String,
    val battery: Int,
    val charging: Boolean,
    val backfill: Boolean,
) {
    fun toUpload(): UploadPoint = UploadPoint(
        clientSeq = clientSeq,
        recordedAt = recordedAt,
        lat = lat,
        lng = lng,
        accuracyM = accuracyM,
        source = source,
        battery = battery,
        charging = charging,
        backfill = backfill,
    )
}

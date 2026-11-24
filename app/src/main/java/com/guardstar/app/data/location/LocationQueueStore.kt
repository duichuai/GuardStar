package com.guardstar.app.data.location

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 轻量级本地位置队列，使用系统 SQLiteOpenHelper，避免引入 Room/KSP 编译器。
 * 状态：0 = pending，1 = uploading（失败会回退为 pending 并累计 attempts）。
 */
class LocationQueueStore(context: Context) {

    private val helper = OpenHelper(context)

    fun enqueue(point: QueuePoint): Long {
        val values = ContentValues().apply {
            put(COL_RECORDED_AT, point.recordedAt)
            put(COL_LAT, point.lat)
            put(COL_LNG, point.lng)
            put(COL_ACCURACY, point.accuracyM)
            put(COL_SOURCE, point.source)
            put(COL_BATTERY, point.battery)
            put(COL_CHARGING, if (point.charging) 1 else 0)
            put(COL_BACKFILL, if (point.backfill) 1 else 0)
            put(COL_STATUS, STATUS_PENDING)
            put(COL_ATTEMPTS, 0)
            put(COL_NEXT_ATTEMPT_AT, 0L)
        }
        return helper.writableDatabase.insert(TABLE, null, values)
    }

    fun pending(batch: Int): List<QueuePoint> {
        val db = helper.readableDatabase
        val now = System.currentTimeMillis()
        val cursor = db.query(
            TABLE,
            null,
            "$COL_STATUS = ? AND $COL_NEXT_ATTEMPT_AT <= ?",
            arrayOf(STATUS_PENDING.toString(), now.toString()),
            null,
            null,
            "$COL_CLIENT_SEQ ASC",
            batch.toString(),
        )
        val result = ArrayList<QueuePoint>(cursor.count)
        cursor.use {
            while (it.moveToNext()) result.add(read(it))
        }
        return result
    }

    fun markUploading(clientSeqs: List<Int>) {
        if (clientSeqs.isEmpty()) return
        val placeholders = clientSeqs.joinToString(",") { "?" }
        val args = (clientSeqs.map { it.toString() }).toTypedArray()
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE SET $COL_STATUS = $STATUS_UPLOADING WHERE $COL_CLIENT_SEQ IN ($placeholders)",
            args,
        )
    }

    fun delete(clientSeqs: List<Int>) {
        if (clientSeqs.isEmpty()) return
        val placeholders = clientSeqs.joinToString(",") { "?" }
        val args = clientSeqs.map { it.toString() }.toTypedArray()
        helper.writableDatabase.delete(TABLE, "$COL_CLIENT_SEQ IN ($placeholders)", args)
    }

    /**
     * 网络失败时回退为 pending，并按 attempts 指数退避设置下次可重试时间。
     * 退避：30s * 2^attempts，上限 5 分钟；attempts 自增。
     */
    fun backoff(clientSeqs: List<Int>, nowMs: Long) {
        if (clientSeqs.isEmpty()) return
        val placeholders = clientSeqs.joinToString(",") { "?" }
        val args = (clientSeqs.map { it.toString() } + nowMs.toString()).toTypedArray()
        helper.writableDatabase.execSQL(
            """
            UPDATE $TABLE SET
                $COL_STATUS = $STATUS_PENDING,
                $COL_NEXT_ATTEMPT_AT = ? + CAST(min($BACKOFF_BASE_MS * pow(2.0, $COL_ATTEMPTS), $BACKOFF_MAX_MS) AS INTEGER),
                $COL_ATTEMPTS = $COL_ATTEMPTS + 1
            WHERE $COL_CLIENT_SEQ IN ($placeholders)
            """.trimIndent(),
            args,
        )
    }

    fun countPending(): Int {
        val now = System.currentTimeMillis()
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_STATUS = $STATUS_PENDING AND $COL_NEXT_ATTEMPT_AT <= ?",
            arrayOf(now.toString()),
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * 启动时调用：将所有 uploading 状态（status=1）的记录重置为 pending。
     * 处理进程在 markUploading 后、delete/backoff 前被杀导致的孤儿记录。
     */
    fun resetUploading() {
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE SET $COL_STATUS = $STATUS_PENDING WHERE $COL_STATUS = $STATUS_UPLOADING"
        )
    }

    /**
     * 认证失败（401）时使用：将 uploading 记录直接重置为可立即上报的 pending，
     * 不应用指数退避、不递增 attempts。重新登录后可立即补传，避免等待退避到期。
     */
    fun resetToPending(clientSeqs: List<Int>) {
        if (clientSeqs.isEmpty()) return
        val placeholders = clientSeqs.joinToString(",") { "?" }
        val now = System.currentTimeMillis().toString()
        val args = (listOf(now) + clientSeqs.map { it.toString() }).toTypedArray()
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE SET $COL_STATUS = $STATUS_PENDING, $COL_NEXT_ATTEMPT_AT = ? " +
                "WHERE $COL_CLIENT_SEQ IN ($placeholders)",
            args,
        )
    }

    /** 删除超过保留期（默认 24 小时）的队列数据。仅删除 pending 状态，避免误删正在上传的记录。 */
    fun purgeOlderThan(cutoffMs: Long): Int {
        return helper.writableDatabase.delete(
            TABLE,
            "$COL_CREATED_AT < ? AND $COL_STATUS = ?",
            arrayOf(cutoffMs.toString(), STATUS_PENDING.toString()),
        )
    }

    private fun read(c: android.database.Cursor): QueuePoint = QueuePoint(
        clientSeq = c.getInt(c.getColumnIndexOrThrow(COL_CLIENT_SEQ)),
        recordedAt = c.getString(c.getColumnIndexOrThrow(COL_RECORDED_AT)),
        lat = c.getDouble(c.getColumnIndexOrThrow(COL_LAT)),
        lng = c.getDouble(c.getColumnIndexOrThrow(COL_LNG)),
        accuracyM = c.getDouble(c.getColumnIndexOrThrow(COL_ACCURACY)),
        source = c.getString(c.getColumnIndexOrThrow(COL_SOURCE)),
        battery = c.getInt(c.getColumnIndexOrThrow(COL_BATTERY)),
        charging = c.getInt(c.getColumnIndexOrThrow(COL_CHARGING)) == 1,
        backfill = c.getInt(c.getColumnIndexOrThrow(COL_BACKFILL)) == 1,
    )

    private class OpenHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    $COL_CLIENT_SEQ INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_RECORDED_AT TEXT NOT NULL,
                    $COL_LAT REAL NOT NULL,
                    $COL_LNG REAL NOT NULL,
                    $COL_ACCURACY REAL NOT NULL,
                    $COL_SOURCE TEXT NOT NULL,
                    $COL_BATTERY INTEGER NOT NULL,
                    $COL_CHARGING INTEGER NOT NULL,
                    $COL_BACKFILL INTEGER NOT NULL,
                    $COL_STATUS INTEGER NOT NULL,
                    $COL_ATTEMPTS INTEGER NOT NULL DEFAULT 0,
                    $COL_NEXT_ATTEMPT_AT INTEGER NOT NULL DEFAULT 0,
                    $COL_CREATED_AT INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_seq ON $TABLE ($COL_STATUS, $COL_CLIENT_SEQ)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private companion object {
        const val DB_NAME = "guardstar_location.db"
        const val DB_VERSION = 1
        const val TABLE = "location_queue"
        const val COL_CLIENT_SEQ = "client_seq"
        const val COL_RECORDED_AT = "recorded_at"
        const val COL_LAT = "lat"
        const val COL_LNG = "lng"
        const val COL_ACCURACY = "accuracy_m"
        const val COL_SOURCE = "source"
        const val COL_BATTERY = "battery"
        const val COL_CHARGING = "charging"
        const val COL_BACKFILL = "backfill"
        const val COL_STATUS = "status"
        const val COL_ATTEMPTS = "attempts"
        const val COL_NEXT_ATTEMPT_AT = "next_attempt_at"
        const val COL_CREATED_AT = "created_at"
        const val STATUS_PENDING = 0
        const val STATUS_UPLOADING = 1
        const val BACKOFF_BASE_MS = 30_000L
        const val BACKOFF_MAX_MS = 5 * 60_000L
    }
}

package com.guardstar.app.data.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标转换工具：仅用于地图展示层。
 *
 * 服务端统一存储 WGS-84，客户端上报时不做转换；
 * 但国内地图服务商（如高德）使用 GCJ-02（火星坐标），
 * 因此在地图上展示前需将 WGS-84 转为 GCJ-02，避免位置偏移。
 * 该转换为纯数学变换，不影响已存储/已上报的原始坐标。
 */
object CoordConverter {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** 将 GCJ-02 坐标转为 WGS-84，返回 (lat, lng)。境外坐标直接返回原值。 */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var wgsLat = lat
        var wgsLng = lng
        // 用正变换迭代逼近，3 次即可达到厘米级精度。
        repeat(3) {
            val (gcjLat, gcjLng) = wgs84ToGcj02(wgsLat, wgsLng)
            wgsLat -= gcjLat - lat
            wgsLng -= gcjLng - lng
        }
        return wgsLat to wgsLng
    }

    /** 将 WGS-84 坐标转为 GCJ-02，返回 (lat, lng)。境外坐标直接返回原值。 */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * kotlin.math.cos(radLat) * PI)
        return (lat + dLat) to (lng + dLng)
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}

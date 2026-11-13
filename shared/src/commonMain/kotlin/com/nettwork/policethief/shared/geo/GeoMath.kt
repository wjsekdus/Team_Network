package com.nettwork.policethief.shared.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 위도·경도 한 점. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * 게임에 필요한 위치 계산 모음. 서버 판정(감옥 10m, 구역 500m)과 클라이언트 지도 표시가 같은 식을 쓴다.
 */
object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** 두 점 사이의 지표면 거리(미터). Haversine 공식. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude.toRadians()
        val lat2 = b.latitude.toRadians()
        val dLat = (b.latitude - a.latitude).toRadians()
        val dLon = (b.longitude - a.longitude).toRadians()
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun isWithinRadius(point: GeoPoint, center: GeoPoint, radiusMeters: Double): Boolean =
        distanceMeters(point, center) <= radiusMeters

    /**
     * 여러 점의 중심(위도·경도 산술 평균).
     *
     * 게임 구역은 수백 미터 규모이므로 구면 보정 없이 평균을 써도 오차가 무시할 수준이다.
     * 날짜변경선(경도 ±180) 근처는 고려하지 않는다(대한민국 서비스 범위).
     */
    fun centroid(points: List<GeoPoint>): GeoPoint {
        require(points.isNotEmpty()) { "centroid 계산에는 최소 1개 이상의 점이 필요합니다." }
        return GeoPoint(
            latitude = points.sumOf { it.latitude } / points.size,
            longitude = points.sumOf { it.longitude } / points.size,
        )
    }

    /**
     * 중심과 반경으로 원을 근사하는 다각형 꼭짓점 목록(첫 점 = 마지막 점).
     * 지도 SDK 에 원형 구역을 그릴 때 사용한다.
     */
    fun circlePolygon(center: GeoPoint, radiusMeters: Double, segments: Int = 64): List<GeoPoint> {
        require(segments >= 8) { "segments 는 8 이상이어야 합니다." }
        val angularDistance = radiusMeters / EARTH_RADIUS_METERS
        val lat = center.latitude.toRadians()
        val lon = center.longitude.toRadians()
        val ring = (0 until segments).map { i ->
            val bearing = 2 * kotlin.math.PI * i / segments
            val pointLat = asin(sin(lat) * cos(angularDistance) + cos(lat) * sin(angularDistance) * cos(bearing))
            val pointLon = lon + kotlin.math.atan2(
                sin(bearing) * sin(angularDistance) * cos(lat),
                cos(angularDistance) - sin(lat) * sin(pointLat),
            )
            GeoPoint(pointLat.toDegrees(), pointLon.toDegrees())
        }
        return ring + ring.first()
    }

    private fun Double.toRadians() = this * kotlin.math.PI / 180.0
    private fun Double.toDegrees() = this * 180.0 / kotlin.math.PI
}

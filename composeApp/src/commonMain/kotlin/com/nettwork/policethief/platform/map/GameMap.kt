package com.nettwork.policethief.platform.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nettwork.policethief.presentation.theme.AppColors
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import kotlin.math.cos
import kotlin.math.min

/*
 * 지도 추상화.
 * 화면(ViewModel)은 [GameMapState] 만 만들고, 실제 그리기는 플랫폼별 [PlatformMapView] 가 한다.
 *   Android: MapLibre Native + OpenFreeMap 타일
 *   iOS    : 현재는 [SchematicMap](타일 없는 도식 지도). MapLibre iOS / MapKit 연동은 TODO.
 * 지도 SDK 문제로 빌드가 깨지지 않도록 지도 SDK 코드는 androidMain 에만 있다.
 */

enum class MarkerKind { ME, POLICE, THIEF }

data class MapMarker(
    val id: String,
    val position: GeoPoint,
    val label: String,
    val kind: MarkerKind,
)

data class GameMapState(
    /** 카메라 중심. null 이면 내 위치 → 기본 좌표 순으로 사용. */
    val center: GeoPoint?,
    val zoneRadiusMeters: Double? = null,
    val jail: GeoPoint? = null,
    val jailRadiusMeters: Double? = null,
    val markers: List<MapMarker> = emptyList(),
) {
    /** 스크린리더용 지도 요약(지도 내용은 시각 정보뿐이므로 텍스트로 대신 제공). */
    fun accessibilitySummary(): String = buildString {
        append("지도. ")
        zoneRadiusMeters?.let { append("게임 구역 반경 ${it.toInt()}미터. ") }
        if (jail != null) append("감옥이 구역 중심에 있음. ")
        markers.groupBy { it.kind }.forEach { (kind, list) ->
            val name = when (kind) { MarkerKind.ME -> "내 위치"; MarkerKind.POLICE -> "경찰"; MarkerKind.THIEF -> "도둑" }
            append("$name ${list.size}명: ${list.joinToString { it.label }}. ")
        }
    }
}

/** 지도 타일 출처 표시(OpenStreetMap / OpenMapTiles / OpenFreeMap 이용 조건). */
const val MAP_ATTRIBUTION = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

/** 플랫폼 지도. 지도 로딩 실패 시 [onMapError] 로 알려 공통 코드가 도식 지도로 대체한다. */
@Composable
expect fun PlatformMapView(state: GameMapState, modifier: Modifier, onMapError: (String) -> Unit)

/** 화면에서 사용하는 지도. 실패하면 도식 지도로 자동 대체하고 그 사실을 표시한다. */
@Composable
fun GameMap(state: GameMapState, modifier: Modifier = Modifier) {
    var mapError by remember { mutableStateOf<String?>(null) }
    Box(modifier.semantics { contentDescription = state.accessibilitySummary() }) {
        if (mapError == null) {
            PlatformMapView(state, Modifier.fillMaxSize()) { mapError = it }
        } else {
            SchematicMap(state, Modifier.fillMaxSize())
            Text(
                "지도 타일을 불러오지 못해 도식 지도로 표시합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Background,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(AppColors.Jail).padding(4.dp),
            )
        }
        Text(
            MAP_ATTRIBUTION,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.align(Alignment.BottomStart).background(AppColors.Background.copy(alpha = 0.7f)).padding(4.dp),
        )
    }
}

/**
 * 타일 없이 구역·감옥·마커만 그리는 도식 지도. iOS 임시 구현, 지도 로딩 실패 대체, 미리보기용.
 * 위경도를 구역 중심 기준 미터 평면으로 근사해 그린다.
 */
@Composable
fun SchematicMap(state: GameMapState, modifier: Modifier = Modifier) {
    val center = state.center ?: state.markers.firstOrNull()?.position
    Canvas(modifier.background(AppColors.Surface)) {
        if (center == null) return@Canvas
        val viewRadiusMeters = (state.zoneRadiusMeters ?: 300.0) * 1.15
        val scale = (min(size.width, size.height) / 2f) / viewRadiusMeters.toFloat()
        val metersPerDegLat = 111_195.0
        val metersPerDegLon = metersPerDegLat * cos(center.latitude * kotlin.math.PI / 180)
        fun toOffset(p: GeoPoint) = Offset(
            x = size.width / 2 + ((p.longitude - center.longitude) * metersPerDegLon * scale).toFloat(),
            y = size.height / 2 - ((p.latitude - center.latitude) * metersPerDegLat * scale).toFloat(),
        )
        state.zoneRadiusMeters?.let { r ->
            drawCircle(AppColors.TextPrimary.copy(alpha = 0.06f), radius = (r * scale).toFloat(), center = toOffset(center))
            drawCircle(AppColors.TextPrimary, radius = (r * scale).toFloat(), center = toOffset(center), style = Stroke(width = 3f))
        }
        state.jail?.let { jail ->
            // 감옥: 사각형(보고서 규격 "사각형 + 감옥") — 실제 반경 10m 는 화면에서 너무 작아 최소 크기로 표시.
            val half = maxOf(((state.jailRadiusMeters ?: 10.0) * scale).toFloat(), 14f)
            val o = toOffset(jail)
            drawRect(AppColors.Jail, topLeft = Offset(o.x - half, o.y - half), size = Size(half * 2, half * 2), style = Stroke(width = 5f))
        }
        state.markers.forEach { marker ->
            val color = when (marker.kind) {
                MarkerKind.ME -> Color.White
                MarkerKind.POLICE -> AppColors.Police
                MarkerKind.THIEF -> AppColors.Thief
            }
            drawCircle(color, radius = 14f, center = toOffset(marker.position))
            drawCircle(AppColors.Background, radius = 14f, center = toOffset(marker.position), style = Stroke(width = 3f))
        }
    }
}

/** 구역 원을 다각형으로 만든다(플랫폼 지도 SDK 에서 사용). */
fun GameMapState.zonePolygon(): List<GeoPoint>? {
    val c = center ?: return null
    val r = zoneRadiusMeters ?: return null
    return GeoMath.circlePolygon(c, r)
}

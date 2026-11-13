package com.nettwork.policethief.platform.map

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Android 지도: MapLibre Native + OpenFreeMap(무료, API 키 없음) 벡터 타일.
 * 구역 원·감옥·참가자 마커는 GeoJSON 소스 + 스타일 레이어로 그린다.
 */
@Composable
actual fun PlatformMapView(state: GameMapState, modifier: Modifier, onMapError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnError by rememberUpdatedState(onMapError)
    // 네이티브 라이브러리를 불러오지 못하는 환경(일부 에뮬레이터 ABI, JVM 테스트)에서는 도식 지도로 대체한다.
    val created = remember {
        runCatching {
            MapLibre.getInstance(context)
            MapView(context).apply { onCreate(null) }
        }
    }
    val mapView = created.getOrNull()
    if (mapView == null) {
        LaunchedEffect(Unit) { currentOnError("지도 SDK 초기화 실패: ${created.exceptionOrNull()?.message}") }
        return
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var cameraInitialized by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        val failListener = MapView.OnDidFailLoadingMapListener { message -> currentOnError(message ?: "지도 로딩 실패") }
        mapView.addOnDidFailLoadingMapListener(failListener)
        mapView.getMapAsync { loaded ->
            loaded.uiSettings.isRotateGesturesEnabled = false
            loaded.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { loadedStyle ->
                addGameLayers(loadedStyle)
                map = loaded
                style = loadedStyle
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.removeOnDidFailLoadingMapListener(failListener)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(state, style) {
        val loadedStyle = style ?: return@LaunchedEffect
        updateGameSources(loadedStyle, state)
        val target = state.center ?: state.markers.firstOrNull()?.position
        if (!cameraInitialized && target != null) {
            map?.cameraPosition = CameraPosition.Builder()
                .target(LatLng(target.latitude, target.longitude))
                .zoom(if (state.zoneRadiusMeters != null) 14.5 else 16.0)
                .build()
            cameraInitialized = true
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            FilledTonalButton(
                onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) },
                modifier = Modifier.size(48.dp).semantics { contentDescription = "지도 확대" },
            ) { Text("+") }
            FilledTonalButton(
                onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) },
                modifier = Modifier.padding(top = 8.dp).size(48.dp).semantics { contentDescription = "지도 축소" },
            ) { Text("−") }
        }
    }
}

private const val SRC_ZONE = "pnt-zone"
private const val SRC_JAIL = "pnt-jail"
private const val SRC_ME = "pnt-me"
private const val SRC_POLICE = "pnt-police"
private const val SRC_THIEF = "pnt-thief"
private val LABEL_FONT = arrayOf("Noto Sans Regular")

private fun addGameLayers(style: Style) {
    listOf(SRC_ZONE, SRC_JAIL, SRC_ME, SRC_POLICE, SRC_THIEF).forEach { style.addSource(GeoJsonSource(it)) }
    style.addLayer(FillLayer("pnt-zone-fill", SRC_ZONE).withProperties(PropertyFactory.fillColor(AndroidColor.WHITE), PropertyFactory.fillOpacity(0.06f)))
    style.addLayer(LineLayer("pnt-zone-line", SRC_ZONE).withProperties(PropertyFactory.lineColor(AndroidColor.WHITE), PropertyFactory.lineWidth(2.5f)))
    style.addLayer(FillLayer("pnt-jail-fill", SRC_JAIL).withProperties(PropertyFactory.fillColor(JAIL_COLOR), PropertyFactory.fillOpacity(0.55f)))
    style.addLayer(
        SymbolLayer("pnt-jail-label", SRC_JAIL).withProperties(
            PropertyFactory.textField("감옥"),
            PropertyFactory.textFont(LABEL_FONT),
            PropertyFactory.textColor(JAIL_COLOR),
            PropertyFactory.textSize(14f),
            PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
        ),
    )
    addMarkerLayers(style, SRC_POLICE, POLICE_COLOR)
    addMarkerLayers(style, SRC_THIEF, THIEF_COLOR)
    addMarkerLayers(style, SRC_ME, AndroidColor.WHITE)
}

private fun addMarkerLayers(style: Style, source: String, color: Int) {
    style.addLayer(
        CircleLayer("$source-circle", source).withProperties(
            PropertyFactory.circleColor(color),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor(BACKGROUND_COLOR),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        SymbolLayer("$source-label", source).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(LABEL_FONT),
            PropertyFactory.textColor(color),
            PropertyFactory.textSize(13f),
            PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
            PropertyFactory.textAllowOverlap(true),
        ),
    )
}

private fun updateGameSources(style: Style, state: GameMapState) {
    style.getSourceAs<GeoJsonSource>(SRC_ZONE)?.setGeoJson(
        state.zonePolygon()?.let { FeatureCollection.fromFeature(Feature.fromGeometry(it.toPolygon())) } ?: FeatureCollection.fromFeatures(emptyList()),
    )
    val jail = state.jail
    style.getSourceAs<GeoJsonSource>(SRC_JAIL)?.setGeoJson(
        if (jail == null) {
            FeatureCollection.fromFeatures(emptyList())
        } else {
            // 반경 10m 는 확대하지 않으면 보이지 않으므로 다각형 + 라벨로 표시한다.
            FeatureCollection.fromFeature(Feature.fromGeometry(GeoMath.circlePolygon(jail, state.jailRadiusMeters ?: 10.0, 16).toPolygon()))
        },
    )
    mapOf(MarkerKind.ME to SRC_ME, MarkerKind.POLICE to SRC_POLICE, MarkerKind.THIEF to SRC_THIEF).forEach { (kind, source) ->
        val features = state.markers.filter { it.kind == kind }.map { marker ->
            Feature.fromGeometry(Point.fromLngLat(marker.position.longitude, marker.position.latitude)).apply {
                addStringProperty("label", marker.label)
            }
        }
        style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
}

private fun List<GeoPoint>.toPolygon(): Polygon = Polygon.fromLngLats(listOf(map { Point.fromLngLat(it.longitude, it.latitude) }))

private val POLICE_COLOR = AndroidColor.parseColor("#2563EB")
private val THIEF_COLOR = AndroidColor.parseColor("#FB923C")
private val JAIL_COLOR = AndroidColor.parseColor("#FACC15")
private val BACKGROUND_COLOR = AndroidColor.parseColor("#0A0E16")

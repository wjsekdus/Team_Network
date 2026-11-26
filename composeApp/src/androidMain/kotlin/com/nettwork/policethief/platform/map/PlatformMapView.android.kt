package com.nettwork.policethief.platform.map

import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.CircleOverlay
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.util.MarkerIcons
import com.nettwork.policethief.shared.geo.GeoPoint

private fun GeoPoint.latLng() = LatLng(latitude, longitude)

@Composable
actual fun PlatformMapView(state: GameMapState, modifier: Modifier, onMapError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(context, lifecycle) {
        runCatching { MapView(context).apply { onCreate(null) } }.getOrNull()
    }
    if (view == null) {
        LaunchedEffect(Unit) { onMapError("네이버 지도 초기화 실패") }
        return
    }
    var naverMap by remember(view) { mutableStateOf<NaverMap?>(null) }
    var centered by remember(view) { mutableStateOf(false) }
    DisposableEffect(view, lifecycle) {
        var active = true
        var resumed = false
        var started = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { view.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { view.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { view.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { view.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        view.getMapAsync { map ->
            if (active) {
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isZoomControlEnabled = true
                naverMap = map
            }
        }
        onDispose {
            active = false
            lifecycle.removeObserver(observer)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
    val map = naverMap ?: return
    val center = state.center ?: state.markers.firstOrNull()?.position
    LaunchedEffect(map, center) {
        if (!centered && center != null) {
            map.moveCamera(CameraUpdate.scrollAndZoomTo(center.latLng(), if (state.zoneRadiusMeters == null) 16.0 else 14.5))
            centered = true
        }
    }
    DisposableEffect(map, state) {
        val overlays = mutableListOf<Overlay>()
        fun circle(point: GeoPoint?, meters: Double?, fill: Int, border: Int, order: Int) {
            if (point == null || meters == null || meters <= 0) return
            overlays += CircleOverlay().apply {
                this.center = point.latLng(); radius = meters
                color = fill; outlineColor = border; outlineWidth = 3
                zIndex = order; this.map = map
            }
        }
        fun pin(point: GeoPoint, label: String, tint: Int) {
            overlays += Marker().apply {
                position = point.latLng(); icon = MarkerIcons.BLACK; iconTintColor = tint
                captionText = label; this.map = map
            }
        }
        val radius = state.zoneRadiusMeters
        circle(center, state.initialZoneRadiusMeters?.takeIf { radius != null && it > radius }, Color.TRANSPARENT, Color.GRAY, 0)
        circle(center, radius, 0x202563EB, 0xFF2563EB.toInt(), 1)
        circle(state.mission, state.missionRadiusMeters ?: 30.0, 0x3622C55E, 0xFF15803D.toInt(), 2)
        circle(state.jail, state.jailRadiusMeters ?: 10.0, 0x58FACC15, 0xFFB77900.toInt(), 3)
        state.jail?.let { pin(it, "감옥", 0xFFB77900.toInt()) }
        for (marker in state.markers) {
            pin(marker.position, marker.label, when (marker.kind) {
                MarkerKind.ME -> 0xFF202124.toInt()
                MarkerKind.POLICE -> 0xFF2563EB.toInt()
                MarkerKind.THIEF -> 0xFFE85D04.toInt()
            })
        }
        onDispose { overlays.forEach { it.map = null } }
    }
}

package com.nettwork.policethief.platform.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * TODO(iOS 지도): MapLibre Native iOS(SPM) 또는 MapKit(UIKitView + MKMapView) 연동.
 * 현재는 타일 없는 도식 지도로 구역·감옥·마커만 표시한다(빌드가 지도 SDK 에 의존하지 않도록).
 */
@Composable
actual fun PlatformMapView(state: GameMapState, modifier: Modifier, onMapError: (String) -> Unit) {
    SchematicMap(state, modifier)
}

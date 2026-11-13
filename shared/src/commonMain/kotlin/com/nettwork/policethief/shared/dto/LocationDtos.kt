package com.nettwork.policethief.shared.dto

import com.nettwork.policethief.shared.model.LocationSource
import kotlinx.serialization.Serializable

/**
 * POST /api/locations 요청. 사용자 식별은 토큰으로 하므로 userId 를 보내지 않는다.
 * source = MOCK 은 서버가 개발 모드(PNT_ALLOW_MOCK_LOCATIONS=true)일 때만 받아들인다.
 */
@Serializable
data class LocationUpdateRequest(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val measuredAtEpochMillis: Long,
    val source: LocationSource = LocationSource.GPS,
)

/** 서버에 저장된 위치 한 건. */
@Serializable
data class LocationDto(
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val measuredAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val source: LocationSource,
)

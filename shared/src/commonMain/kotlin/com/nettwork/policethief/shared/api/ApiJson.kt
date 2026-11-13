package com.nettwork.policethief.shared.api

import kotlinx.serialization.json.Json

/**
 * 서버와 클라이언트가 동일하게 쓰는 JSON 설정.
 *
 * - ignoreUnknownKeys: 서버가 필드를 추가해도 구버전 앱이 깨지지 않게 한다.
 * - explicitNulls = false: null 필드는 JSON 에서 생략한다(받는 쪽은 기본값 null 로 해석).
 * - classDiscriminator = "type": WebSocket 메시지처럼 sealed class 를 직렬화할 때 종류를 "type" 필드로 구분한다.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

package com.nettwork.policethief

import com.nettwork.policethief.config.AppConfig
import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.data.local.DevSettingsStore
import com.nettwork.policethief.data.local.ServerUrlProvider
import com.nettwork.policethief.data.local.SessionLocalDataSource
import com.nettwork.policethief.data.remote.PoliceThiefApi
import com.nettwork.policethief.data.remote.createHttpClient
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.platform.AppPermission
import com.nettwork.policethief.platform.LocationProvider
import com.nettwork.policethief.platform.PermissionController
import com.nettwork.policethief.platform.PermissionState
import com.nettwork.policethief.platform.SecureTokenStorage
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.model.LocationSource
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.KSerializer

const val TEST_NOW = 1_800_000_000_000L

class FixedClock(var now: Long = TEST_NOW) : EpochClock {
    override fun nowMillis(): Long = now
}

class InMemoryTokenStorage(var token: String? = null) : SecureTokenStorage {
    override fun readToken(): String? = token
    override fun writeToken(token: String) {
        this.token = token
    }
    override fun clear() {
        token = null
    }
}

class FakePermissionController(var states: MutableMap<AppPermission, PermissionState> = mutableMapOf()) : PermissionController {
    var openedSettings = 0
    override fun status(permission: AppPermission): PermissionState = states[permission] ?: PermissionState.GRANTED
    override fun openAppSettings() {
        openedSettings++
    }
}

class FakeLocationProvider(var next: () -> PlayerLocation = { sampleLocation() }) : LocationProvider {
    override suspend fun getCurrentLocation(): PlayerLocation = next()
}

fun sampleLocation(timestamp: Long = TEST_NOW - 1_000) =
    PlayerLocation(null, 37.5665, 126.9780, 6.0, timestamp, LocationSource.GPS)

val testConfig = AppConfig(defaultServerUrl = "http://test.local", isDevBuild = true, appVersion = "test", platformName = "test")

/** MockEngine 으로 서버 응답을 흉내 내는 API 묶음. */
class TestApi(
    val tokenStorage: InMemoryTokenStorage = InMemoryTokenStorage(),
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()
    val settings = MapSettings()
    val devSettings = DevSettingsStore(settings, testConfig)
    val urls = ServerUrlProvider(testConfig, devSettings)
    val session = SessionLocalDataSource(tokenStorage, settings)
    val api = PoliceThiefApi(
        createHttpClient(MockEngine { request -> requests += request; handler(request) }),
        urls,
    ) { session.token() }
}

fun <T> MockRequestHandleScope.respondJson(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(ApiJson.encodeToString(serializer, value), status, headersOf(HttpHeaders.ContentType, "application/json"))

fun MockRequestHandleScope.respondRaw(body: String, status: HttpStatusCode) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

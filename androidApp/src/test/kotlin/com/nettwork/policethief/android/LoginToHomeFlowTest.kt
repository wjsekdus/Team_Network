package com.nettwork.policethief.android

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.nettwork.policethief.App
import com.nettwork.policethief.config.AppConfig
import com.nettwork.policethief.di.REAL_LOCATION_PROVIDER
import com.nettwork.policethief.di.initKoin
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.platform.AppPermission
import com.nettwork.policethief.platform.BackgroundLocationController
import com.nettwork.policethief.platform.GameAlerts
import com.nettwork.policethief.platform.LocationProvider
import com.nettwork.policethief.platform.PermissionController
import com.nettwork.policethief.platform.PermissionState
import com.nettwork.policethief.platform.SecureTokenStorage
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.GuestLoginResponse
import com.nettwork.policethief.shared.dto.HealthResponse
import com.nettwork.policethief.shared.dto.UserDto
import com.nettwork.policethief.shared.model.LocationSource
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * UI 흐름 테스트(에뮬레이터 없이 Robolectric 으로 JVM 에서 실제 Compose 화면을 띄운다).
 * 앱 실행 → 닉네임 입력 → 게스트 로그인 → 위치 권한 확인 → 홈 → 현재 위치 조회 → 지도 표시
 *
 * 서버는 MockEngine 으로 대체한다(실제 서버 연동은 server 모듈 테스트와 수동 실행으로 검증).
 * 지도: Robolectric 에는 MapLibre 네이티브 라이브러리가 없으므로 도식 지도로 대체된 상태를 확인한다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp")
class LoginToHomeFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val permissions = object : PermissionController {
        val states = mutableMapOf(AppPermission.LOCATION to PermissionState.DENIED)
        override fun status(permission: AppPermission) = states[permission] ?: PermissionState.GRANTED
        override fun openAppSettings() = Unit
    }

    private val user = UserDto("user-1", "테스터", 1L, 1L)

    private val server = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when (request.url.encodedPath) {
            ApiPaths.HEALTH -> respond(ApiJson.encodeToString(HealthResponse.serializer(), HealthResponse("UP", "UP", "test", 0)), HttpStatusCode.OK, json)
            ApiPaths.AUTH_GUEST -> respond(
                ApiJson.encodeToString(GuestLoginResponse.serializer(), GuestLoginResponse("token-1", user = user)),
                HttpStatusCode.Created,
                json,
            )
            ApiPaths.USERS_ME -> respond(ApiJson.encodeToString(UserDto.serializer(), user), HttpStatusCode.OK, json)
            else -> respond("""{"code":"NOT_FOUND","message":"x"}""", HttpStatusCode.NotFound, json)
        }
    }

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        val testPlatform = module {
            single<HttpClientEngine> { server }
            single<Settings> { SharedPreferencesSettings(context.getSharedPreferences("test", Context.MODE_PRIVATE)) }
            single<SecureTokenStorage> {
                object : SecureTokenStorage {
                    var token: String? = null
                    override fun readToken() = token
                    override fun writeToken(token: String) { this.token = token }
                    override fun clear() { token = null }
                }
            }
            single<PermissionController> { permissions }
            single<LocationProvider>(REAL_LOCATION_PROVIDER) {
                object : LocationProvider {
                    override suspend fun getCurrentLocation() =
                        PlayerLocation(null, 37.5665, 126.9780, 7.0, System.currentTimeMillis(), LocationSource.GPS)
                }
            }
            single<BackgroundLocationController> {
                object : BackgroundLocationController {
                    override val isSupported = false
                    override val isRunning: StateFlow<Boolean> = MutableStateFlow(false)
                    override fun start() = Unit
                    override fun stop() = Unit
                }
            }
            single<GameAlerts> {
                object : GameAlerts {
                    override fun notify(title: String, message: String) = Unit
                    override fun vibrate() = Unit
                }
            }
        }
        initKoin(AppConfig("http://test.local", isDevBuild = true, appVersion = "test", platformName = "robolectric"), testPlatform)
    }

    @After
    fun tearDown() = stopKoin()

    @Test
    fun guestLogin_thenPermission_thenHomeShowsUserLocationAndMap() {
        compose.setContent { App() }

        // 1) 저장된 세션이 없으므로 로그인 화면
        compose.waitUntilAtLeastOneExists(hasTestTag("nicknameField"), 5_000)
        compose.onNodeWithTag("nicknameField").performTextInput("테스터")
        compose.onNodeWithTag("loginButton").performClick()

        // 2) 위치 권한이 없으므로 권한 안내 화면
        compose.waitUntilAtLeastOneExists(hasText("권한 안내"), 5_000)
        permissions.states[AppPermission.LOCATION] = PermissionState.GRANTED
        compose.onNodeWithText("상태 다시 확인").performScrollTo().performClick()
        compose.onNodeWithText("확인").performScrollTo().performClick()

        // 3) 홈: 서버에서 받은 닉네임, 서버 상태, 현재 위치, 지도
        compose.waitUntilAtLeastOneExists(hasText("테스터 님"), 5_000)
        compose.waitUntilAtLeastOneExists(hasTestTag("currentLocationText"), 5_000)
        compose.onNodeWithTag("currentLocationText").assert(hasText("위도 37.56650, 경도 126.97800"))
        compose.waitUntilAtLeastOneExists(hasText("REST 정상", substring = true), 5_000)
        compose.onNodeWithTag("homeMap").assertExists()
    }
}

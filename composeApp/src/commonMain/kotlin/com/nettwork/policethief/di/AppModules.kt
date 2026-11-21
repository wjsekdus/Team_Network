package com.nettwork.policethief.di

import com.nettwork.policethief.config.AppConfig
import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.core.ServerClock
import com.nettwork.policethief.core.SystemEpochClock
import com.nettwork.policethief.data.local.DevSettingsStore
import com.nettwork.policethief.data.local.ServerUrlProvider
import com.nettwork.policethief.data.local.SessionLocalDataSource
import com.nettwork.policethief.data.local.UserPreferencesStore
import com.nettwork.policethief.data.repository.GameSessionRepositoryImpl
import com.nettwork.policethief.data.repository.RemoteDevToolsRepository
import com.nettwork.policethief.domain.repository.DevSettingsRepository
import com.nettwork.policethief.domain.repository.DevToolsRepository
import com.nettwork.policethief.domain.repository.GameSessionRepository
import com.nettwork.policethief.domain.repository.ServerAddressProvider
import com.nettwork.policethief.domain.repository.UserPreferencesRepository
import com.nettwork.policethief.platform.GameAlertDispatcher
import com.nettwork.policethief.data.mock.MockGameBackend
import com.nettwork.policethief.data.remote.PoliceThiefApi
import com.nettwork.policethief.data.remote.WebSocketRealtimeRepository
import com.nettwork.policethief.data.remote.createHttpClient
import com.nettwork.policethief.data.repository.AuthRepositoryImpl
import com.nettwork.policethief.data.repository.LocationRepositoryImpl
import com.nettwork.policethief.data.repository.RemoteGameRepository
import com.nettwork.policethief.data.repository.RemoteMatchmakingRepository
import com.nettwork.policethief.data.repository.ServerStatusRepositoryImpl
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.domain.repository.MatchmakingRepository
import com.nettwork.policethief.domain.repository.MockGameController
import com.nettwork.policethief.domain.repository.RealtimeRepository
import com.nettwork.policethief.domain.repository.ServerStatusRepository
import com.nettwork.policethief.domain.usecase.CheckServerStatusUseCase
import com.nettwork.policethief.domain.usecase.GuestLoginUseCase
import com.nettwork.policethief.domain.usecase.RestoreSessionUseCase
import com.nettwork.policethief.domain.usecase.ShareCurrentLocationUseCase
import com.nettwork.policethief.platform.LocationProvider
import com.nettwork.policethief.platform.location.DevAwareLocationProvider
import com.nettwork.policethief.platform.location.MockLocationProvider
import com.nettwork.policethief.presentation.game.EscortViewModel
import com.nettwork.policethief.presentation.game.GameMapViewModel
import com.nettwork.policethief.presentation.game.PowerSaveViewModel
import com.nettwork.policethief.presentation.home.HomeViewModel
import com.nettwork.policethief.presentation.login.LaunchViewModel
import com.nettwork.policethief.presentation.login.LoginViewModel
import com.nettwork.policethief.presentation.matchmaking.MatchmakingViewModel
import com.nettwork.policethief.presentation.permission.PermissionViewModel
import com.nettwork.policethief.presentation.prepare.GameSessionViewModel
import com.nettwork.policethief.presentation.qr.MyQrViewModel
import com.nettwork.policethief.presentation.qr.QrScanViewModel
import com.nettwork.policethief.presentation.result.GameResultViewModel
import com.nettwork.policethief.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** 플랫폼 모듈이 제공해야 하는 실제 GPS 공급자의 이름. */
val REAL_LOCATION_PROVIDER = named("realLocationProvider")

/**
 * 앱 공통 의존성 그래프.
 *
 * 플랫폼 모듈(androidPlatformModule / iosPlatformModule)이 아래를 제공해야 한다.
 *   HttpClientEngine, Settings, SecureTokenStorage, PermissionController,
 *   BackgroundLocationController, GameAlerts, LocationProvider(REAL_LOCATION_PROVIDER)
 *
 * Mock ↔ 실제 구현 교체 지점: MatchmakingRepository / GameRepository 바인딩(아래 isMockGame).
 */
fun commonModule(config: AppConfig): Module = module {
    single { config }
    single<EpochClock> { SystemEpochClock }
    // 서버 응답 시각으로 맞춘 시계. 남은 시간·카운트다운 표시에 쓴다.
    single { ServerClock(get()) }
    // 앱 전체 수명 동안 유지되는 작업(WebSocket, 경기 동기화, Mock 매칭 진행)에 사용.
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // ---- Data: local ----
    // 화면·플랫폼 코드는 domain 인터페이스로만 받는다(Presentation → Data 구현체 직접 참조 금지).
    single<DevSettingsRepository> { DevSettingsStore(get(), get()) }
    single<UserPreferencesRepository> { UserPreferencesStore(get()) }
    single { ServerUrlProvider(get(), get()) }
    single<ServerAddressProvider> { get<ServerUrlProvider>() }
    single { SessionLocalDataSource(get(), get()) }

    // ---- Data: remote ----
    single { createHttpClient(get()) }
    single { PoliceThiefApi(get(), get()) { get<SessionLocalDataSource>().token() } }

    // ---- Platform: 위치 (개발 빌드에서는 테스트 좌표로 전환 가능) ----
    single { MockLocationProvider(get(), get()) }
    single<LocationProvider> { DevAwareLocationProvider(get(REAL_LOCATION_PROVIDER), get<MockLocationProvider>(), get(), get()) }

    // ---- Repository: 실제 서버 ----
    single<AuthRepository> { AuthRepositoryImpl(get(), get()) }
    single<ServerStatusRepository> { ServerStatusRepositoryImpl(get(), get(), get()) }
    single<LocationRepository> { LocationRepositoryImpl(get(), get(), get()) }
    single<RealtimeRepository> {
        WebSocketRealtimeRepository(get(), get(), { get<SessionLocalDataSource>().token() }, get(), get())
    }

    // ---- Repository: Mock ↔ 실제 교체 ----
    single { MockGameBackend(get(), get(), get()).apply { setMockRole(get<DevSettingsRepository>().current.value.mockRole) } }
    single<MatchmakingRepository> { if (isMockGame()) get<MockGameBackend>() else RemoteMatchmakingRepository(get(), get(), get(), get()) }
    single<GameRepository> { if (isMockGame()) get<MockGameBackend>() else RemoteGameRepository(get(), get(), get()) }
    single<DevToolsRepository> { RemoteDevToolsRepository(get()) }

    // ---- 경기 동기화(앱 범위): 화면을 옮겨도 동기화·위치 전송이 이어진다 ----
    single<GameSessionRepository> {
        val prefs = get<UserPreferencesRepository>()
        GameSessionRepositoryImpl(
            games = get(),
            locations = get(),
            realtime = get(),
            auth = get(),
            background = get(),
            clock = get(),
            scope = get(),
            autoStartBackground = { prefs.current.value.backgroundLocation },
        )
    }
    single(createdAtStart = true) { GameAlertDispatcher(get(), get(), get(), get()) }

    // ---- UseCase ----
    factory { GuestLoginUseCase(get()) }
    factory { RestoreSessionUseCase(get()) }
    factory { CheckServerStatusUseCase(get()) }
    factory { ShareCurrentLocationUseCase(get()) }

    // ---- ViewModel ----
    viewModel { LaunchViewModel(get()) }
    viewModel { LoginViewModel(get(), get()) }
    viewModel { PermissionViewModel(get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { MatchmakingViewModel(get(), get(), get<ServerClock>(), isMock = isMockGame()) }
    viewModel { params -> GameSessionViewModel(params.get(), get(), get(), get(), get<ServerClock>(), isMock = isMockGame()) }
    viewModel { params -> GameMapViewModel(params.get(), get(), get(), get(), get<ServerClock>(), get(), mockControllerOrNull()) }
    viewModel { params -> EscortViewModel(params.get(), get(), get(), mockControllerOrNull()) }
    viewModel { params -> PowerSaveViewModel(params.get(), get(), get(), get(), get<ServerClock>()) }
    viewModel { params -> QrScanViewModel(params.get(), get(), get(), get(), get(), get(), mockControllerOrNull()) }
    viewModel { params -> MyQrViewModel(params.get(), get(), get(), get(), isMock = isMockGame()) }
    viewModel { params -> GameResultViewModel(params.get(), get(), get(), isMock = isMockGame()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), mockControllerOrNull(), get(), get(), get(), get(), get()) }
}

private fun org.koin.core.scope.Scope.isMockGame(): Boolean =
    get<AppConfig>().isDevBuild && get<DevSettingsRepository>().current.value.useMockGameData

private fun org.koin.core.scope.Scope.mockControllerOrNull(): MockGameController? =
    if (isMockGame()) get<MockGameBackend>() else null

/** 플랫폼 진입점에서 한 번 호출한다. */
fun initKoin(config: AppConfig, platformModule: Module) {
    startKoin { modules(platformModule, commonModule(config)) }
}

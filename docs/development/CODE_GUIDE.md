# CODE_GUIDE — 코드 학습 가이드

AI 가 만든 코드를 **어디서부터 어떻게 읽을지** 안내한다. 전체 지도는 [`ARCHITECTURE.md`](../architecture/ARCHITECTURE.md), 영역별 분담은 [`TEAM_ANALYSIS_GUIDE.md`](TEAM_ANALYSIS_GUIDE.md) 를 먼저 본다.
경로는 모두 프로젝트 루트 기준이며 `…/` 는 `com/nettwork/policethief/` 를 줄여 쓴 것이다.

> 실기기·iOS·Cloudflare Tunnel·GitHub Actions 는 검증하지 않았다. 자동 테스트 137개(shared 17, server 75, composeApp 42, androidApp 3)와 실제 서버+PostgreSQL 4인 시나리오(40항목)는 통과했다.

## 1. 읽는 순서 추천

1. `shared/src/commonMain/kotlin/…/shared/rules/GameRules.kt` — 확정 규칙 숫자(4명, 500m, 30분…). 가장 짧고 중요하다.
2. `shared/…/model/GameEnums.kt`, `shared/…/dto/GameDtos.kt`, `shared/…/api/ApiPaths.kt` — 앱·서버가 주고받는 말(상태, 이벤트 종류, 요청/응답).
3. 서버: `server/src/main/kotlin/…/server/Application.kt` → `game/GameEngine.kt` → `game/GameSession.kt` → `game/rules/*.kt`.
4. 앱: `composeApp/src/commonMain/kotlin/…/di/AppModules.kt` → `domain/repository/Repositories.kt` → `data/repository/GameSessionRepositoryImpl.kt` → 화면(`presentation/…`).

## 2. 서버 핵심 클래스

경로 기준: `server/src/main/kotlin/…/server/`

| 클래스·함수 | 파일 | 한 줄 설명 |
|---|---|---|
| `main`, `ServerDependencies.create`, `module`, `startGameScheduler` | `Application.kt` | 시작, 객체 조립(수동 DI), 라우트 연결, 1초마다 `tick` 호출 |
| `AppConfig.fromEnvironment` | `config/AppConfig.kt` | 환경 변수 → 설정(`PNT_*`) |
| `GameSettings` | `game/GameSettings.kt` | **PROVISIONAL**(임시) 수치와 `PNT_GAME_*` 환경 변수 |
| `AppDatabase.query` | `database/AppDatabase.kt` | 트랜잭션 실행, 시작 시 Flyway 마이그레이션 |
| `GameLock.withLock` | `common/GameLock.kt` | 경기·매칭 상태를 바꾸는 처리를 한 줄로 세우는 전역 잠금 |
| `GameEngine` (`mutate`, `capture`, `rescue`, `ingest`, `tick`, `recoverAfterRestart`) | `game/GameEngine.kt` | 모든 경기 요청의 진입점 |
| `GameSession` | `game/GameSession.kt` | DB 에서 읽어 온 경기를 메모리에서 바꾸는 객체(플레이어, 구역 단계, 미션, 이벤트 목록, `changed` 표시) |
| `GameStore` (`loadSession`, `createGame`, `save`, `saveScanResult`) | `game/GameStore.kt` | DB 읽기/쓰기. `save` 는 버전을 1 올리고 이벤트를 저장 |
| `capture`, `rescue`, `applyLocation`, `advanceTo`, `finish` … | `game/rules/*.kt` | `GameSession` 의 **확장 함수**로 쓴 순수 규칙 |
| `DeadlineKind`, `pendingDeadlines` | `game/rules/DeadlineRules.kt` | 예정 판정(시작·구역 경고/축소·미션·종료·유예 만료) 목록과 우선순위 |
| `MatchFinder.findGroups` | `matchmaking/MatchFinder.kt` | 대기자 중 4명 묶음 찾기(Haversine 500m + 중심점) |
| `MatchmakingService` | `matchmaking/MatchmakingService.kt` | 대기열 등록/취소/만료, 확정 직전 `FOR UPDATE` 재확인 |
| `ConnectionManager` | `event/ConnectionManager.kt` | 사용자당 WebSocket 1개 보관, 새 연결이 오면 이전 연결 대체 |
| `realtimeRoutes` | `event/RealtimeRoutes.kt` | `/ws`: `welcome` → 등록 → ping/pong |
| `Outbox`, `NotificationService` | `notification/NotificationService.kt` | 트랜잭션 중에 "보낼 메시지" 를 모아 두었다가 커밋 후 전달 |
| `LocationService.submit/validate` | `location/LocationService.kt` | 위치 형식·정확도 검증 후 저장, 경기 중이면 `GameEngine.ingest` |
| `DevToolsService`, `DevClock` | `dev/DevTools.kt` | 개발 서버 전용 봇·시간 이동 |
| `ApiException` 계열 | `common/ApiException.kt` | 404/403/409/422 등 오류 응답으로 변환되는 예외 |

### 2-1. 요청 하나가 지나가는 길: QR 체포 `POST /api/games/{gameId}/captures`

```text
앱 QrScanViewModel → GameRepository.capture → PoliceThiefApi → HTTP
  │
  ▼
game/GameRoutes.kt   post(gameCaptures)      요청 본문 QrScanRequest(token, requestId) 를 받음
  ▼
game/GameEngine.capture → scan(kind="CAPTURE")
  ▼
mutate { now, outbox -> … }                  (GameEngine.kt 맨 아래)
  ├─ GameLock.withLock                       ① 전역 잠금
  ├─ db.query { … }                          ② DB 트랜잭션 시작
  │    ├─ requestId 검사 (1~64자) → store.findScanResult(userId, requestId)
  │    │     이미 처리한 요청이면 처음 결과를 그대로 반환(멱등)   ← 재전송해도 이중 체포 없음
  │    ├─ loadForParticipant: GameStore.loadSession → 참가자 아니면 403 NOT_A_PARTICIPANT
  │    ├─ session.advanceTo(now)             ③ 지난 예정 판정을 시간 순서대로 먼저 적용
  │    │     (예: endsAt 이후의 체포는 여기서 TIME_OVER 로 경기가 끝나 GAME_NOT_PLAYING 으로 거절)
  │    ├─ store.lookupToken(token, gameId)   ④ QR 토큰을 DB 에서 조회(내용이 아니라 DB 가 기준)
  │    ├─ session.capture(userId, token, now)  rules/CaptureRules.kt ⑤ 검사 순서대로 판정
  │    │     경기 상태 → 요청자 역할 → 토큰 → 대상 상태 → 호송 제한
  │    │     통과: 도둑 ESCORTED, 경찰 escortingUserId 설정, THIEF_ARRESTED 이벤트 기록
  │    ├─ persist(session, outbox, now)      ⑥ GameStore.save: 버전+1, 상태·이벤트 저장
  │    │     + 이벤트마다 session.recipientsOf(...) 로 받을 사람 계산 → outbox.add
  │    └─ store.saveScanResult(...)          ⑦ 같은 트랜잭션에서 requestId 결과 저장
  └─ (잠금 해제 후) notifications.deliver(outbox)   ⑧ 커밋이 끝난 뒤 WebSocket 전달
        → WebSocketNotificationService → ConnectionManager.sendTo → 각 참가자의 /ws 로 game_event
```

거절(예: `ALREADY_CAPTURED`)도 HTTP 200 에 `accepted=false` + `rejectReason` 으로 돌려준다(오류가 아니라 "판정 결과"). 구출(`rescue`)은 같은 `scan()` 을 `rules/RescueRules.kt` 로 호출한다. 자세한 거절 사유는 [`GAME_LOGIC.md`](GAME_LOGIC.md).

### 2-2. 위치 하나가 지나가는 길: `POST /api/locations`

`location/LocationRoutes.kt` → `LocationService.submit`(`validate`: 좌표 범위, 정확도, MOCK 허용 여부, 측정 시각) → `GameEngine.ingest`(`LocationIngest` 구현) → `mutate` 안에서 위치 행 저장(`insertLocationRow`) → 경기 중이면 `session.applyLocation`(`rules/LocationRules.kt`: 순서 역전이면 무시 → 과속 → 구역 → 감옥 → 미션) → `persist` → `publishLocation`(볼 수 있는 사람에게만 `player_location`). 매칭 대기 중이면 대기열 위치를 갱신하고 매칭을 다시 시도한다.

### 2-3. 시간이 지나서 일어나는 일: 스케줄러

`Application.startGameScheduler` 가 서버 시작 시 `GameEngine.recoverAfterRestart()` 를 한 번, 이후 약 1초마다 `GameEngine.tick()` 을 부른다. `tick` 은 매칭 만료/재시도, 진행 중 경기 전부에 `advanceTo(now)`, 10분마다 오래된 위치 삭제를 한다. 그래서 아무도 요청을 보내지 않아도 30분이 지나면 경기가 끝난다.

### 2-4. 실시간 이벤트 전달

1. 규칙 코드가 `session.record(type, at, visibility, target, data)` 로 이벤트를 쌓는다(`GameSession.kt`).
2. `GameStore.save` 가 `game_events` 에 저장(버전 포함) → `GameEventDto`(`eventId` 부여).
3. `GameEngine.persist` 가 `session.recipientsOf` 로 수신자를 정해 `Outbox` 에 넣는다. 저장하지 않는 일시 이벤트(`recordTransient`)는 `eventId=0`.
4. 커밋 후 `NotificationService.deliver` → `ConnectionManager.sendTo`.

## 3. 앱 핵심 클래스

경로 기준: `composeApp/src/commonMain/kotlin/…/`

| 클래스 | 파일 | 한 줄 설명 |
|---|---|---|
| `App` | `App.kt` | 앱 최상위 Composable, 테마·내비게이션·알림 배너 |
| `commonModule`, `initKoin` | `di/AppModules.kt` | Koin 의존성 조립, Mock/실제 교체 |
| `AppNavHost`, `*Route` | `presentation/navigation/` | 화면 이동. `Routes.kt` 에 경로 정의 |
| `PoliceThiefApi` | `data/remote/PoliceThiefApi.kt` | 모든 REST 호출(Ktor Client, Bearer 토큰 자동) |
| `WebSocketRealtimeRepository` | `data/remote/WebSocketRealtimeRepository.kt` | `/ws` 연결·재연결·메시지 해석 |
| `AuthRepositoryImpl` 등 | `data/repository/RepositoryImpls.kt` | 인터페이스 구현(Auth, ServerStatus, Location, Matchmaking, Game, DevTools) |
| `GameSessionRepositoryImpl`, `GameNotices` | `data/repository/GameSessionRepositoryImpl.kt` | 경기 동기화(스냅샷+이벤트), 위치 전송, 알림 문구 |
| `DtoMappers` | `data/mapper/DtoMappers.kt` | `shared` DTO → `domain.model` |
| `SessionLocalDataSource`, `DevSettingsStore`, `UserPreferencesStore`, `ServerUrlProvider` | `data/local/LocalStores.kt` | 토큰·설정 저장, 서버 주소 결정. 설정 3종은 domain 인터페이스(`DevSettingsRepository`, `UserPreferencesRepository`, `ServerAddressProvider`)를 구현한다 |
| `MockGameBackend` | `data/mock/MockGameBackend.kt` | 서버 없이 화면을 보기 위한 가짜 경기(개발 빌드 전용) |
| `AppResult`, `AppError` | `domain/model/AppResult.kt` | 성공/실패를 값으로 표현 |
| `ServerClock` | `core/EpochClock.kt` | 서버 시각 기준 시계 |
| `GameAlertDispatcher` | `platform/GameAlertDispatcher.kt` | 알림 → 진동/시스템 알림 |

### 3-1. UseCase 흐름 (4개뿐)

`domain/usecase/UseCases.kt`:

| UseCase | 부르는 곳 | 흐름 |
|---|---|---|
| `GuestLoginUseCase` | `LoginViewModel` | 닉네임 검증(`NicknamePolicy`) → `AuthRepository.guestLogin` → 토큰 저장 |
| `RestoreSessionUseCase` | `LaunchViewModel` | 저장된 토큰으로 `GET /api/users/me`; 네트워크 오류면 저장된 사용자로 오프라인 복구 |
| `CheckServerStatusUseCase` | `HomeViewModel` | `GET /api/health` |
| `ShareCurrentLocationUseCase` | `HomeViewModel` | `LocationRepository.fetchCurrentLocation` → `sendLocation` |

### 3-2. Repository 흐름 (경기)

```text
화면(GameMapScreen) → GameMapViewModel → GameSessionRepository.state  (StateFlow 구독만)
GameSessionRepositoryImpl.start(gameId):
   ├─ realtime.connect()  → WebSocketRealtimeRepository
   ├─ realtime.events.collect → handle()  (중복 제거 → notice → refresh)
   ├─ 15초마다 refresh() → GameRepository.state → RemoteGameRepository → PoliceThiefApi.gameState
   └─ locationLoop → LocationRepository.fetchCurrentLocation / sendLocation  (또는 백그라운드 서비스)
QR 스캔: QrScanViewModel → GameRepository.capture/rescue → RemoteGameRepository.scan(requestId 생성)
          → PoliceThiefApi.capture → 서버 (2-1 흐름)
```

`RemoteGameRepository.scan`(`RepositoryImpls.kt`)은 스캔한 **토큰마다** `requestId` 를 만들어 `pendingScanIds` 에 보관하고, 응답이 **네트워크 오류가 아니면** 지운다. 즉 네트워크 실패 후 같은 QR 을 다시 스캔하면 같은 `requestId` 가 재사용되어 서버가 처음 결과를 돌려준다(테스트 `scanRetryAfterNetworkFailure_reusesRequestId`). 요청에 담는 `scannedAtEpochMillis` 는 서버가 사용하지 않는다.

### 3-3. 화면과 ViewModel

| 화면(Composable) | ViewModel | 파일 |
|---|---|---|
| `LaunchScreen`, `LoginScreen` | `LaunchViewModel`, `LoginViewModel` | `presentation/login/` |
| `PermissionScreen` | `PermissionViewModel` | `presentation/permission/PermissionScreen.kt` |
| `HomeScreen` | `HomeViewModel` | `presentation/home/` |
| `MatchmakingScreen` | `MatchmakingViewModel` | `presentation/matchmaking/Matchmaking.kt` |
| `RoleRevealScreen`, `ZoneConfirmScreen` | `GameSessionViewModel` | `presentation/prepare/Prepare.kt` |
| `GameMapScreen`(경찰·도둑 공용), `EscortScreen`, `PowerSaveScreen` | `GameMapViewModel`, `EscortViewModel`, `PowerSaveViewModel` | `presentation/game/` |
| `QrScanScreen`, `MyQrScreen` | `QrScanViewModel`, `MyQrViewModel` | `presentation/qr/QrScreens.kt` |
| `GameResultScreen` | `GameResultViewModel` | `presentation/result/GameResultScreen.kt` |
| `SettingsScreen` | `SettingsViewModel` | `presentation/settings/Settings.kt` |

경로(화면 ID)는 `presentation/navigation/Routes.kt`, 화면별 상세는 [`UI_STRUCTURE.md`](UI_STRUCTURE.md).

## 4. 기능을 바꾸는 방법 (예시)

### 4-1. PROVISIONAL(임시) 값 바꾸기 — 예: 감옥 판정 정확도 15m → 20m

1. 코드 수정 없이 `server/.env` 에 `PNT_GAME_JAIL_MAX_ACCURACY_M=20.0` 추가 후 서버 재시작.
2. 기본값 자체를 바꾸려면 `game/GameSettings.kt` 의 해당 필드 기본값 + `fromEnvironment` 를 본다. 문서 `GAME_CONFIG.md`, `server/.env.example` 도 같이 고친다.
3. **확정 규칙**(`shared/…/rules/GameRules.kt`: 4명, 500m, 30분, 25km/h…)은 바꾸면 앱·서버·테스트가 모두 영향을 받는다. 팀 합의 없이 바꾸지 않는다.
4. 확인: `.\gradlew.bat :server:test`.

### 4-2. 이벤트 종류 추가 — 예: `THIEF_WAVED`

1. `shared/…/model/GameEnums.kt` 의 `GameEventType` 에 값 추가. **`when` 을 쓰는 곳이 컴파일 오류로 알려 준다**(예: 앱의 `GameNotices.from` 은 모든 값을 다룬다).
2. 필요한 데이터 키는 같은 파일의 `GameEventKeys` 에 추가.
3. 서버 규칙 코드에서 `record(GameEventType.THIEF_WAVED, at, visibility = …, data = eventData(…))`. 누가 받을지는 `Visibility`(`GameSession.kt`)로 정한다.
4. 앱: `GameNotices.from`(`GameSessionRepositoryImpl.kt`)에 알림 문구 추가.
5. `docs/api/API.md` 의 이벤트 표 갱신. 테스트: `GameProgressApiTest`(서버), `GameSyncTest`(앱).

### 4-3. 화면 추가

1. `presentation/navigation/Routes.kt` 에 `@Serializable data class XxxRoute(val gameId: String)`.
2. `presentation/xxx/XxxScreen.kt`(Composable) + `XxxViewModel`.
3. `di/AppModules.kt` 에 `viewModel { params -> XxxViewModel(params.get(), get(), …) }`.
4. `presentation/navigation/AppNavHost.kt` 에 `composable<XxxRoute> { … }`.
5. 서버 데이터가 필요하면 Repository 인터페이스(`domain/repository/Repositories.kt`) → 구현(`data/repository/`) → `PoliceThiefApi` 순으로 추가.
6. 테스트: `composeApp/src/commonTest/.../PresentationAndMockTest.kt` 참고.

### 4-4. 새 REST API 추가

`shared/…/api/ApiPaths.kt`(경로) → `shared/…/dto/`(DTO) → 서버 `GameRoutes.kt`(라우트) → `GameEngine`(로직) → 앱 `PoliceThiefApi` → Repository. 오류는 `common/ApiException.kt` 의 예외를 던지면 상태코드로 변환된다. `RealtimeAndContractTest`/`SerializationContractTest` 가 DTO 직렬화 계약을 지킨다.

## 5. 테스트 위치

| 위치 | 대상 | 실행 |
|---|---|---|
| `shared/src/commonTest/…/RulesTest.kt`, `SerializationContractTest.kt` | 규칙 상수, 거리, 직렬화 계약 (17개) | `.\gradlew.bat :shared:jvmTest` |
| `server/src/test/kotlin/…/server/` `AuthApiTest`, `LocationApiTest`, `MatchmakingApiTest`, `GameFlowApiTest`, `GameProgressApiTest`, `ConnectionAndRecoveryTest`, `RealtimeAndContractTest`, `PostgresIntegrationTest`, `TestSupport.kt` (75개) | 서버 API·판정·복구(H2 + 임베디드 PostgreSQL) | `.\gradlew.bat :server:test` |
| `composeApp/src/commonTest/…` `DataLayerTest`, `GameSyncTest`, `PresentationAndMockTest`, `TestFakes.kt` (42개) | 앱 데이터·동기화·ViewModel | `.\gradlew.bat :composeApp:testAndroidHostTest` |
| `androidApp/src/test/kotlin/…/android/` `LoginToHomeFlowTest`, `ClientServerIntegrationTest` (3개) | Robolectric UI 흐름, 클라이언트–서버 통합 | `.\gradlew.bat :androidApp:testDebugUnitTest` |

테스트 결과 기록은 [`TEST_REPORT.md`](../testing/TEST_REPORT.md). 서버 판정 테스트는 `TestSupport.kt` 의 가짜 시계로 시간을 앞당긴다.

## 6. 읽다가 막힐 때 용어

- **DTO**: 통신용 데이터 상자. `shared/dto/`.
- **멱등(idempotent)**: 같은 요청을 여러 번 보내도 결과가 한 번 보낸 것과 같음(`requestId`).
- **Outbox**: "나중에 보낼 메시지함". DB 저장이 끝난 뒤에만 보내려고 쓴다.
- **확장 함수**: `fun GameSession.capture(...)` 처럼 기존 클래스에 붙여 쓰는 함수. `rules/` 가 이 방식이다.
- **StateFlow**: 최신 값을 계속 알려 주는 Kotlin 흐름. 화면이 구독해서 그린다.
- **Koin**: 객체 조립 도구(`di/AppModules.kt`). `get()` 은 "필요한 객체를 달라" 는 뜻.

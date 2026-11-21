# TEAM_ANALYSIS_GUIDE — 4명이 나눠서 코드 이해하기

AI 가 만든 코드를 4명이 **서로 겹치지 않게** 나눠 읽고, 각자 자기 영역을 팀에 설명할 수 있게 하는 가이드다.
먼저 [`ARCHITECTURE.md`](../architecture/ARCHITECTURE.md)(전체 지도)와 [`CODE_GUIDE.md`](CODE_GUIDE.md)(핵심 클래스·요청 흐름)를 모두 한 번 훑어본다. 그다음 자기 영역 절을 따라간다.

- 경로의 `…/` 는 `com/nettwork/policethief/` 의 줄임이다. 서버는 `server/src/main/kotlin/…/server/`, 앱 공통은 `composeApp/src/commonMain/kotlin/…/`.
- 실행 방법은 [`RUN_GUIDE.md`](RUN_GUIDE.md), 게임 규칙은 [`GAME_LOGIC.md`](GAME_LOGIC.md), 설정값은 [`GAME_CONFIG.md`](GAME_CONFIG.md), API 는 [`API.md`](../api/API.md), DB 는 [`DATABASE.md`](../architecture/DATABASE.md).
- **검증 범위**: 아래 테스트 명령은 서버·공유·앱 공통 로직의 자동 테스트다(전체 137개 통과 기록). 실기기, iOS 빌드, Cloudflare Tunnel, GitHub Actions 는 검증하지 않았다. 아래 "버그 후보"는 **문서화하면서 코드를 읽고 의심한 것**이지 확정된 버그가 아니다. 직접 재현해 보고 판단한다.
- 연습 문제는 **개인 브랜치**에서 하고 끝나면 되돌린다(`CONTRIBUTING.md` 규칙). 앱 모듈은 동시에 수정 중일 수 있어 빌드가 깨질 수 있다.

| 영역 | 주제 | 주로 읽는 곳 |
|---|---|---|
| ① | 인증 + 매칭 + 서버 기반(설정·DB·조립) | 서버 `auth`, `matchmaking`, `config`, `database`, `plugins`, `Application.kt`, `location` |
| ② | 게임 엔진·규칙 | 서버 `game/`, `game/rules/`, `shared/rules/GameRules.kt` |
| ③ | 실시간 + 클라이언트 데이터 계층 | 서버 `event`, `notification` / 앱 `data/`, `domain/`, `core/` |
| ④ | 화면·ViewModel + 플랫폼 코드 | 앱 `presentation/`, `platform/`, `androidMain`, `iosMain`, `androidApp` |

---

## ① 인증 + 매칭 + 서버 기반/DB

### 읽을 패키지·파일
- `server/…/Application.kt` — `ServerDependencies.create`(객체 조립), `module`(라우트), `startGameScheduler`.
- `config/AppConfig.kt` — 환경 변수 → 설정. `game/GameSettings.kt` 의 `fromEnvironment` 도 함께.
- `database/AppDatabase.kt`, `database/Tables.kt`, `resources/db/migration/V1__*.sql`, `V2__game_progress.sql`.
- `plugins/Plugins.kt`(JSON, 오류 응답, Bearer 인증, WebSocket 설정), `common/ApiException.kt`.
- `auth/AuthService.kt`, `AuthRoutes.kt`, `AuthRepositories.kt`.
- `location/LocationService.kt`, `LocationRoutes.kt` (위치 검증·저장).
- `matchmaking/MatchFinder.kt`, `MatchmakingService.kt`.
- `shared/…/geo/GeoMath.kt`(Haversine), `shared/…/rules/LocationValidator.kt`, `NicknamePolicy.kt`.

### 주요 클래스
`AuthService`(게스트 로그인·토큰 발급/검증, TTL), `MatchFinder.findGroups`(순수 함수: 대기자 → 4인 그룹), `MatchmakingService`(join/cancel/myTicket/tickLocked, 만료, 취소 vs 기권), `LocationService.validate`, `AppDatabase.query`.

### 데이터 흐름
`POST /api/auth/guest` → 토큰 발급 → 이후 모든 요청 `Authorization: Bearer`.
`POST /api/matchmaking`(내 위치 포함) → 대기열 행 저장 → 같은 트랜잭션에서 `matchAndNotify` → 4명이 모이면 `FOR UPDATE` 로 재확인 후 `GameStore.createGame` → 모두에게 `matchmaking_update` + `GAME_PREPARING` 전달. 서버가 켜져 있는 동안 `tick` 이 만료(10분)와 재시도를 처리.

### 핵심 알고리즘
- **매칭**: 대기 시간이 오래된 순으로 후보를 보고, 4명이 **쌍마다 500m 이내**이고 **무게 중심에서도 500m 이내**인지 확인. 안 되면 다른 조합을 시도(자세히는 `GAME_LOGIC.md`).
- **동시 참여 방지**: `active_participations` 고유 제약 + `GameLock` + 확정 직전 `FOR UPDATE`.
- **위치 검증**: 좌표 범위, 정확도 상한, MOCK 은 `PNT_ALLOW_MOCK_LOCATIONS=true` 일 때만.

### 버그 후보·개선 아이디어
- 환경 변수 파싱이 둘로 갈린다: `PNT_GAME_*`(`GameSettings`)는 형식이 틀리면 시작 거부, `AppConfig` 값은 조용히 기본값으로 대체(`PNT_ALLOW_MOCK_LOCATIONS=True` 대문자는 `false`). 통일하면 안전하다.
- `PNT_GAME_ZONE_STAGES` 첫 반경이 초기 반경(500m)보다 작은지 검사하지 않는다.
- `FOR UPDATE` 는 매칭 확정에서만 쓰고 경기 요청은 프로세스 안 `GameLock` 에 의존한다 → 서버 여러 대 확장 시 깨짐(`GameLock.kt` 주석).
- Gradle `.env` 파서는 같은 줄 뒤쪽 `# 설명`을 값으로 읽는다.

### 직접 해 보기
1. **임시 값 바꾸기**: `server/.env` 에 `PNT_GAME_MATCH_MAX_ACCURACY_M=50.0` 을 넣고 서버를 켠 뒤, 정확도 80m 위치로 매칭을 요청하면 어떤 오류(상태코드, `details`)가 나는지 확인. 이어서 해당 값을 검증하는 코드(`MatchmakingService.validateMatchLocation`)를 읽고 `MatchmakingApiTest` 에서 비슷한 테스트를 찾는다.
   테스트: `.\gradlew.bat :server:test --tests "*MatchmakingApiTest*"`
2. **MatchFinder 단위 테스트 추가**: 대기자 5명(1명만 700m 밖)을 만들어 "먼저 온 4명이 아니라 서로 가까운 4명이 묶이는지" 테스트를 `server/src/test/…`(새 파일 `MatchFinderTest.kt`) 에 작성. `Waiter(userId, point, joinedAt)` 를 직접 만든다.
   테스트: `.\gradlew.bat :server:test --tests "*MatchFinderTest*"`
3. **닉네임 규칙 이해**: `shared/…/rules/NicknamePolicy.kt` 의 허용 문자를 읽고 `AuthApiTest` 에서 거절되는 경우를 확인한 뒤, 닉네임 최대 길이를 12→10 으로 바꿨을 때 깨지는 테스트가 무엇인지 예측하고 실행(끝나면 되돌리기).
   테스트: `.\gradlew.bat :shared:jvmTest :server:test --tests "*AuthApiTest*"`

---

## ② 게임 엔진·규칙

### 읽을 패키지·파일
- `server/…/game/GameEngine.kt` (`mutate`, `scan`, `ingest`, `tick`, `recoverAfterRestart`).
- `game/GameSession.kt` (메모리 속 경기 상태), `game/GameStore.kt`(DB 저장·`save`·`createGame`·`resultOf`).
- `game/rules/` 전부: `LifecycleRules`, `DeadlineRules`, `CaptureRules`, `RescueRules`, `JailRules`, `ZoneRules`, `MissionRules`, `SpeedRules`, `LocationRules`.
- `shared/…/rules/GameRules.kt`(확정 상수), `shared/…/model/GameEnums.kt`(`ScanRejectReason`, `GameEventType`, `GameEndReason`).

### 주요 클래스
`GameEngine`, `GameSession`(`updatePlayer`, `record`, `recipientsOf`), `DeadlineKind`(우선순위), `JailJudge`, `SpeedJudge`, `GameStore`.

### 데이터 흐름
요청 → `GameLock` → 트랜잭션 → `loadSession` → **`advanceTo(now)`** → 규칙 → `save`(버전+1, 이벤트) → 커밋 후 전달. `CODE_GUIDE.md` 2-1 의 QR 체포 흐름을 먼저 따라간다.

### 핵심 알고리즘
- **경계 시간 정책**: 지난 예정 판정을 `(시각, DeadlineKind.priority)` 순서로 모두 먼저 적용 → 정확히 종료 시각에 도착한 체포는 `TIME_OVER` 가 먼저라 거절.
- **상태 전이**: 도둑 `ACTIVE → ESCORTED → JAILED → ACTIVE`(구출). 경찰 한 명은 한 번에 한 명만 호송(`ESCORT_LIMIT`).
- **체포/구출 검사 순서**가 거절 사유를 정함(`CaptureRules.kt` 주석: 경기 상태 → 역할 → 토큰 → 대상 상태 → 호송 제한).
- **자동 수감**: 경찰·도둑이 함께 감옥 10m 이내 + 정확도·신선도 조건(`JailRules.kt`).
- **과속**: 보수적 거리, 연속 표본, 쿨다운, 순간이동 제외(`SpeedRules.kt`), MOCK 은 개발 서버에서만 예외.
- **구역 이탈·축소·미션**: `ZoneRules.kt`, `MissionRules.kt`.
- **멱등**: `scan()` 의 `requestId` → `saveScanResult`.

### 버그 후보·개선 아이디어
- `GET /api/games/{id}/events` 는 `advanceTo` 를 적용하지 않는다(`GameEngine.events` 는 `db.query` 만 사용, 다른 조회는 `mutate` + `advanceTo`). 스케줄러 지연(약 1초) 안에서만 차이가 난다.
- 호송 중복 방지는 DB 고유 제약이 없고(`escorts` 에 "도둑당 열린 호송 1개" 제약 없음) `GameLock` 과 상태 검사에 의존한다. 구출은 별도 테이블 없이 이벤트로만 남는다.
- `SPEED_WARNING`, `PLAYER_DISCONNECTED` 이벤트가 4명 모두에게 보인다(상대 팀 포함) — 의도인지 팀 확인 필요.
- 역할 섞기: `java.util.Random` 을 `SecureRandom.nextLong()` 시드로 사용(64비트 시드).
- `QrScanRequest.scannedAtEpochMillis`, `GameRules.MAX_ESCORTS_PER_POLICE` 등 코드에서 쓰지 않는 값이 있다.

### 직접 해 보기
1. **경계 시간 테스트 읽기**: `GameProgressApiTest`/`GameFlowApiTest` 에서 종료 시각 정각의 체포를 다루는 테스트를 찾아 가짜 시계가 어떻게 시간을 맞추는지 설명해 본다. 그다음 `DeadlineKind.priority` 의 숫자를 서로 바꿔 어떤 테스트가 깨지는지 확인(되돌리기).
   테스트: `.\gradlew.bat :server:test --tests "*GameProgressApiTest*" --tests "*GameFlowApiTest*"`
2. **PROVISIONAL 값 바꾸기**: `PNT_GAME_ZONE_OUT_DISQUALIFY_SECONDS` 를 10 으로 줄였을 때 실격 시점이 어떻게 바뀌는지, `GameSettings` 를 만드는 테스트 코드에서 값을 바꿔 구역 이탈 테스트를 복사해 새 테스트로 만든다.
   테스트: `.\gradlew.bat :server:test --tests "*GameProgressApiTest*"`
3. **새 거절 사유 상상 구현**: 예) "수감 중인 도둑이 QR 을 스캔" 은 이미 `NOT_ALLOWED` 인지 `RescueRules.kt` 에서 확인하고, 아직 테스트가 없는 거절 경로 1개를 골라 테스트를 추가한다.
   테스트: `.\gradlew.bat :server:test` 와 `.\gradlew.bat :shared:jvmTest`

---

## ③ 실시간 + 클라이언트 데이터 계층

### 읽을 패키지·파일
- 서버: `event/ConnectionManager.kt`, `event/RealtimeRoutes.kt`, `notification/NotificationService.kt`, `plugins/Plugins.kt`(WebSocket ping 15초/timeout 30초), `shared/…/dto/RealtimeMessages.kt`.
- 앱: `data/remote/WebSocketRealtimeRepository.kt`, `data/remote/PoliceThiefApi.kt`, `data/repository/RepositoryImpls.kt`, **`data/repository/GameSessionRepositoryImpl.kt`**, `data/mapper/DtoMappers.kt`, `data/local/LocalStores.kt`, `domain/`, `core/EpochClock.kt`.

### 주요 클래스
`ConnectionManager`(사용자당 연결 1개, 대체 시 `session_replaced`), `Outbox`/`NotificationService`, `WebSocketRealtimeRepository`(재연결 루프, `backoffMillis`), `GameSessionRepositoryImpl`(`markNew`, `applySnapshot`, `mergeLocation`, `locationLoop`), `ServerClock`.

### 데이터 흐름
서버: 규칙 → `session.record` → `save` → `persist`(수신자 계산 → `Outbox`) → 커밋 후 `deliver` → `sendTo`.
앱: `/ws` → `welcome`(항상 첫 메시지) → `RealtimeEvent` 흐름 → `GameSessionRepositoryImpl.handle` → 중복 제거 → 알림(`notices`) + `refresh()`(`GET /state`) → `state: StateFlow` → 화면.

### 핵심 알고리즘
- **동기화 모델**: REST 스냅샷이 진실, 이벤트는 신호. `eventId` 중복 제거(최대 500개 기억), `game.version` 이 낮은 스냅샷 무시, 위치는 측정 시각 비교.
- **재연결**: 1,2,4,8,16,30초 백오프, 최대 8회, 401 이면 중단, `session_replaced` 면 중단.
- **재접속 유예**: 끊기면 3분 유예(`PLAYER_DISCONNECTED`), 돌아오면 `PLAYER_RECONNECTED`. 서버 재시작 직후에는 `recoverAfterRestart` 가 모두를 "끊김"으로 보고 유예를 새로 시작.
- **서버 시계**: 응답의 `serverTimeEpochMillis` 로 offset 계산.
- **`welcome` 순서**: 최근 수정 — 재접속 처리보다 먼저 `welcome` 을 보낸다(`RealtimeRoutes.kt` 주석).

### 버그 후보·개선 아이디어
- `welcome.heartbeatIntervalMillis` 가 하트비트가 아니라 위치 전송 주기(`LOCATION_UPDATE_INTERVAL_MILLIS`)를 담는다 — 이름과 값이 다르다.
- `ErrorCode.NOT_IMPLEMENTED`, `GameEventType.PLAYER_LOCATION_UPDATED` 는 사용되지 않는다(위치는 `player_location` 메시지).
- 알림 전달이 실패해도(연결 없음) 재전송하지 않는다. 앱이 `welcome`·15초 주기 `refresh` 로 메운다. 메우는 간격이 충분한지 검토.
- `GameSessionRepositoryImpl.start` 의 `realtime.connect()` 가 이미 연결 중일 때의 동작, 앱이 백그라운드에서 연결이 끊긴 뒤 복귀 시 지연을 확인.
- `RemoteGameRepository.pendingScanIds` 는 토큰별 `requestId` 이므로 같은 QR 을 의도적으로 두 번 스캔하는 경우 서버 결과가 재사용될 수 있는지 확인.

### 직접 해 보기
1. **동기화 테스트 읽고 확장**: `composeApp/src/commonTest/…/GameSyncTest.kt` 의 `duplicateEvent_isNotifiedOnce_andTriggersOneRefresh`, `olderVersionSnapshot_isIgnored` 를 읽고, "eventId 가 0 인 일시 이벤트는 중복 제거 없이 알림" 을 확인하는 테스트를 추가.
   테스트: `.\gradlew.bat :composeApp:testAndroidHostTest --tests "*GameSyncTest*"`
2. **새 이벤트 종류 추가**(`CODE_GUIDE.md` 4-2): `GameEventType` 에 값 추가 → 서버 규칙에서 기록 → `GameNotices.from` 문구 추가 → `API.md` 갱신. 컴파일러가 `when` 누락을 알려 주는 것을 확인.
   테스트: `.\gradlew.bat :shared:jvmTest :server:test :composeApp:testAndroidHostTest`
3. **재연결 간격 바꾸기**: `WebSocketRealtimeRepository.backoffMillis` 를 읽고 `DataLayerTest` 의 관련 테스트를 찾은 뒤 최대 대기 30초를 10초로 바꿔 어떤 테스트가 영향받는지 확인(되돌리기).
   테스트: `.\gradlew.bat :composeApp:testAndroidHostTest --tests "*DataLayerTest*"`

---

## ④ 화면/ViewModel + 플랫폼 코드

### 읽을 패키지·파일
- 앱 공통: `presentation/navigation/`(`Routes.kt`, `AppNavHost.kt`), `presentation/login`, `home`, `matchmaking`, `prepare`, `game`, `qr`, `result`, `settings`, `permission`, `components`(`NoticeHost`), `theme`; `App.kt`, `di/AppModules.kt`.
- 플랫폼 경계: `platform/PlatformContracts.kt`, `PlatformComposables.kt`, `map/GameMap.kt`, `location/LocationProviders.kt`, `GameAlertDispatcher.kt`.
- Android: `composeApp/src/androidMain/…/platform/`(`AndroidPlatform.kt`, `AndroidGameAlerts.kt`, `QrScanner.android.kt`, `PlatformComposables.android.kt`, `map/PlatformMapView.android.kt`, `location/RealLocationProvider.kt`, `background/LocationTrackingService.kt`), `androidApp/src/main/…`.
- iOS(**미검증**): `composeApp/src/iosMain/…`, `iosApp/iosApp/`.
- 화면별 설명 문서: [`UI_STRUCTURE.md`](UI_STRUCTURE.md).

### 주요 클래스
`HomeViewModel`, `MatchmakingViewModel`, `GameSessionViewModel`, `GameMapViewModel`/`EscortViewModel`/`PowerSaveViewModel`, `QrScanViewModel`/`MyQrViewModel`, `GameResultViewModel`, `SettingsViewModel`; `GameMap`/`SchematicMap`, `LocationTrackingService`, `AndroidGameAlerts`, `DevAwareLocationProvider`.

### 데이터 흐름
화면은 ViewModel 의 `StateFlow` 를 구독 → 사용자가 누르면 ViewModel 함수 → Repository 인터페이스. 경기 화면은 `GameSessionRepository.state` 만 보고 그린다. 위치는 앱 쪽에서 15초마다(또는 Android Foreground Service 가) 서버로 전송, QR 은 `expect/actual` 컴포저블(카메라 스캔·QR 이미지)로 처리.

### 핵심 알고리즘·개념
- **역할별 화면**: 같은 `GameMapScreen` 이 역할(경찰/도둑)에 따라 마커·버튼을 다르게 보인다. 보이는 사람은 서버가 이미 걸러 준 위치만 받는다(화면에서 숨기지 않음).
- **지도 대체**: `PlatformMapView` 오류 시 `SchematicMap`.
- **개발자 모드**: `isMockGame()`(Koin) / `DevSettingsRepository`(구현 `DevSettingsStore`) / `DevAwareLocationProvider`(MOCK 좌표 전환) / 서버 개발 도구(봇, 시간 이동).
- **권한**: 위치·카메라·알림은 `PermissionController`(상태) + `rememberPermissionRequester`(팝업).
- **절전 모드**: `PowerSaveScreen` — 최소 정보 표시(배터리).

### 버그 후보·개선 아이디어
- `platform/map/GameMap.kt` 가 `presentation.theme.AppColors` 를 참조(역방향).
- iOS 지도는 도식 지도로 대체되어 있고 `PlatformBackHandler` 는 아무 동작도 하지 않는다. iOS QR 이미지(CIQRCodeGenerator)·카메라 스캔(AVFoundation)·백그라운드 위치 코드는 있으나 Kotlin 교차 컴파일만 확인했고 Mac 에서 빌드·실행한 적이 없다.
- 위치 권한 거부·GPS 꺼짐·카메라 거부 때 안내가 `LocationException` 종류별로 나오는지, 백그라운드 서비스가 OS 에 종료될 때 복구되는지는 **실기기에서 검증되지 않았다**.

### 직접 해 보기
1. **알림 문구 바꾸기**: `GameNotices.from`(`GameSessionRepositoryImpl.kt`)의 `GAME_STARTED` 문구를 바꾸고, `GameSyncTest` 의 알림 문구 테스트가 깨지는지 확인한 뒤 테스트를 맞춰 고친다(데이터 영역 ③과 같은 파일이므로 ③ 담당과 협의).
   테스트: `.\gradlew.bat :composeApp:testAndroidHostTest --tests "*GameSyncTest*"`
2. **설정 토글 추가**: `UserPreferences`(`data/local/LocalStores.kt`)에 불리언 설정 1개(예: 지도 도식 강제)를 추가하고 `SettingsViewModel`/`SettingsScreen` 에 스위치 연결. 저장 후 앱 재시작 시 유지되는지 `PresentationAndMockTest` 의 설정 테스트를 참고해 확인.
   테스트: `.\gradlew.bat :composeApp:testAndroidHostTest --tests "*PresentationAndMockTest*"`
3. **Mock 경기로 화면 따라가기**: 개발 빌드의 설정에서 "서버 없이 Mock 경기"(`MockGameBackend`)로 역할 공개 → 지도 → QR 스캔 흐름을 따라가며 각 화면의 ViewModel 이 어떤 Repository 메서드를 부르는지 표로 정리. 이후 `androidApp` 의 UI 흐름 테스트와 비교.
   테스트: `.\gradlew.bat :androidApp:testDebugUnitTest`

---

## 합동 점검(4명이 함께)

1. 한 명이 `RUN_GUIDE.md` 8장(휴대폰 1대 + 봇)으로 경기를 진행하고, 나머지는 자기 영역 파일을 열어 놓고 **같은 요청이 각자 영역을 어떤 순서로 지나가는지** 짚는다(① 로그인·매칭 → ② 판정 → ③ 이벤트 전달·동기화 → ④ 화면 갱신).
2. 각자 "버그 후보" 중 하나를 재현해 보고 결과(재현됨/아님)를 이슈로 남긴다.
3. 발표 때 자기 영역을 3분 안에 설명한다: 입력 → 처리 → 출력, 가장 중요한 클래스 1개, 모르는 점 1개.

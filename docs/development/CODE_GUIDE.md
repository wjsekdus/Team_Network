# CODE_GUIDE — 코드 학습 가이드

처음 읽는 순서 추천: ① `shared`(계약·규칙) → ② 서버 `Application.kt` → ③ 앱 `di/AppModules.kt` → ④ 로그인 흐름(Login 화면 → ViewModel → UseCase → Repository → API) → ⑤ 홈 → ⑥ Mock 게임.

## 1. 패키지 역할

### shared (`shared/src/commonMain/kotlin/com/nettwork/policethief/shared`)

| 패키지 | 내용 |
|---|---|
| `api` | `ApiPaths`(모든 URL 경로 상수), `ApiJson`(공통 JSON 설정) |
| `dto` | 요청/응답 데이터: `AuthDtos`, `LocationDtos`, `GameDtos`, `CommonDtos`(Health, ErrorResponse), `RealtimeMessages`(WebSocket) |
| `model` | 상태 어휘: `PlayerRole`, `GameStatus`, `PlayerStatus`, `GameResult`, `GameEndReason`, `PlayerConnectionState`, `MatchmakingStatus`, `LocationSource`, `GameEventType` |
| `rules` | `GameRules`(규칙 숫자), `LocationValidator`, `NicknamePolicy` |
| `geo` | `GeoMath`(Haversine 거리, 중심점, 원 다각형), `GeoPoint` |

### composeApp (`composeApp/src/commonMain/kotlin/com/nettwork/policethief`)

| 패키지 | 내용 |
|---|---|
| `App.kt` | 최상위 Composable(테마 + 내비게이션) |
| `config` | `AppConfig`(서버 기본 주소, 개발 빌드 여부) |
| `core` | `EpochClock`(테스트 가능한 시계) |
| `domain/model` | Entity(`User`, `PlayerLocation`, `Game`, `GamePlayer`, `QrToken` …), `AppResult`, `AppError`, `RealtimeConnectionState` |
| `domain/repository` | Repository 인터페이스 6개 + `MockGameController` |
| `domain/usecase` | `GuestLoginUseCase`, `RestoreSessionUseCase`, `CheckServerStatusUseCase`, `ShareCurrentLocationUseCase` |
| `data/remote` | `PoliceThiefApi`(REST), `WebSocketRealtimeRepository`(WS + 재연결) |
| `data/local` | `SessionLocalDataSource`(토큰·프로필), `DevSettingsStore`(개발자 설정), `ServerUrlProvider` |
| `data/repository` | `AuthRepositoryImpl`, `ServerStatusRepositoryImpl`, `LocationRepositoryImpl`, `RemoteMatchmakingRepository`, `RemoteGameRepository` |
| `data/mapper` | `DtoMappers.kt` (DTO ↔ Entity) |
| `data/mock` | `MockGameBackend` (매칭·경기·QR 가짜 서버) |
| `platform` | 플랫폼 인터페이스(`PlatformContracts.kt`), Compose 플랫폼 함수(`PlatformComposables.kt`), `location/`(Mock·전환 공급자), `map/`(지도 추상화, 도식 지도) |
| `presentation` | `theme`, `components`, `navigation`, 화면별 패키지(`login`, `permission`, `home`, `matchmaking`, `prepare`, `game`, `qr`, `result`, `settings`) |
| `di` | `AppModules.kt` (Koin), `initKoin()` |

`androidMain`: `platform/AndroidPlatform.kt`(Koin 플랫폼 모듈, 권한, Keystore), `platform/location/RealLocationProvider.kt`, `platform/background/LocationTrackingService.kt`, `platform/map/PlatformMapView.android.kt`(MapLibre), `platform/PlatformComposables.android.kt`(권한 요청, ZXing QR, BackHandler).
`iosMain`: `MainViewController.kt`(`startKoinForIos`, `MainViewController`), `platform/IosPlatform.kt`, `platform/PlatformComposables.ios.kt`, `platform/map/PlatformMapView.ios.kt`.

### server (`server/src/main/kotlin/com/nettwork/policethief/server`)

| 패키지 | 내용 |
|---|---|
| `Application.kt` | `main()`, `ServerDependencies`(수동 DI), `Application.module()`(플러그인 + 라우팅) |
| `config` | `AppConfig.fromEnvironment()` — 환경 변수 읽기 |
| `database` | `AppDatabase`(Hikari + Flyway + Exposed), `Tables.kt` |
| `plugins` | 직렬화, 로깅, 인증(bearer), WebSocket, StatusPages(오류 통일) |
| `common` | `ApiException` 계열(`ValidationException` 422, `ForbiddenException` 403, `NotFoundException` 404, `NotImplementedFeatureException` 501), 시간 변환 |
| `health`, `auth`, `location`, `event`, `notification`, `game` | 기능 영역 (Route → Service → Repository) |

## 2. 주요 인터페이스

| 인터페이스 | 구현 | 바꿔 끼우는 곳 |
|---|---|---|
| `LocationProvider` | `RealLocationProvider`(Android/iOS), `MockLocationProvider`, `DevAwareLocationProvider` | `di/AppModules.kt`, 플랫폼 모듈 |
| `PermissionController` | `AndroidPermissionController`, `IosPermissionController` | 플랫폼 모듈 |
| `SecureTokenStorage` | `KeystoreTokenStorage`, `KeychainTokenStorage` | 플랫폼 모듈 |
| `BackgroundLocationController` | `AndroidBackgroundLocationController`, `IosBackgroundLocationController`(미지원) | 플랫폼 모듈 |
| `AuthRepository` 등 | `*Impl` | `commonModule` |
| `MatchmakingRepository`, `GameRepository` | `MockGameBackend` ↔ `RemoteMatchmakingRepository`, `RemoteGameRepository` | `commonModule` 의 `isMockGame()` |
| 서버 `MatchmakingService`, `GameService`, `QrTokenService`, `CaptureService`, `RescueService` | `NotImplemented*` | `ServerDependencies` 생성자 인자 |
| 서버 `UserRepository`, `AuthTokenRepository`, `LocationRepository` | `Exposed*` | `ServerDependencies.create` |
| 서버 `NotificationService` | `WebSocketNotificationService` | `ServerDependencies.create` |

## 3. 호출 흐름

### UseCase / Repository 호출 흐름 (홈의 "서버로 전송")

```text
HomeScreen [서버로 전송] 클릭
 → HomeViewModel.sendLocation()                  상태: isSending = true
   → LocationRepository.sendLocation(location)   (LocationRepositoryImpl)
     → LocationValidator.validate(...)           shared 규칙으로 1차 검증 (실패 시 서버 호출 안 함)
     → PoliceThiefApi.sendLocation(dto)          POST /api/locations + Bearer 토큰
       → 서버 LocationRoutes → LocationService.submit()  (MOCK 허용 여부, 재검증)
         → ExposedLocationRepository.insert()    player_locations INSERT
     ← AppResult.Success(LocationDto) → toDomain()
   ← 상태: isSending = false, sendMessage = "서버 저장 완료"
```

### ViewModel 상태 변경 흐름 (LoginViewModel)

```text
LoginUiState(nickname="", isSubmitting=false)
 ─ onNicknameChange("경찰왕") ─▶ nickname="경찰왕", 오류 문구 초기화
 ─ submit() ─▶ isSubmitting=true (버튼 비활성, 중복 제출 무시)
     ├─ 검증 실패(Validation) ─▶ isSubmitting=false, fieldError="…"
     ├─ 네트워크/서버 실패     ─▶ isSubmitting=false, submitError="…" (입력 유지)
     └─ 성공                  ─▶ isSubmitting=false, events: LoggedIn(needsPermission)
```

### 서버 통신 흐름

1. `ServerUrlProvider.baseUrl()` — 개발 빌드면 설정 화면 값, 아니면 `AppConfig.defaultServerUrl`(BuildConfig/Info.plist).
2. `PoliceThiefApi.send()` — 메서드·경로·토큰·본문으로 요청, 2xx 면 DTO 역직렬화, 아니면 `ErrorResponse` → `AppError`(401→`Unauthorized`, 422→`Validation`, 501→`NotImplemented` …), 연결 실패·타임아웃 → `AppError.Network`.
3. WebSocket: `WebSocketRealtimeRepository.connect()` → `wss://…/ws` + Bearer → `connectionState`(Connecting/Connected/Reconnecting/Failed) 와 `events` Flow 로 화면에 전달.

## 4. 코드 수정 시 주의사항

1. **API 계약 변경은 `shared` 에서만.** DTO 필드 이름을 바꾸면 서버·앱·문서(API.md)·`SerializationContractTest` 를 함께 수정한다. 필드 추가는 기본값을 주어 구버전과 호환되게 한다.
2. **판정은 서버에서.** 앱에서 "체포 성공"을 먼저 표시하거나 승패를 계산하지 않는다. 서버 결과(또는 Mock)를 표시만 한다.
3. **ViewModel 에서 플랫폼 SDK 금지.** GPS·카메라·권한·저장소는 `platform` 인터페이스를 통해서만 쓴다. 권한 팝업은 Composable 의 `rememberPermissionRequester` 에서 띄운다.
4. **예외를 삼키지 않는다.** Repository 는 `AppResult.Failure(AppError…)` 로 돌려주고, 화면은 원인과 다음 행동(다시 시도/설정 열기)을 보여 준다. `CancellationException` 은 다시 던진다.
5. **Mock 은 Mock 이라고 표시한다.** Mock 데이터를 쓰는 화면에는 `MockBadge` 를 붙이고, 실제 기능이 없는 버튼은 "Mock:" 또는 TODO 문구를 쓴다.
6. **DB 스키마는 Flyway 로만.** 새 컬럼은 `V2__….sql` 추가 → `Tables.kt` 매핑 → DATABASE.md 갱신.
7. **비밀 값은 환경 변수/로컬 파일로.** `server/.env`, `local.properties`, `Local.xcconfig` 는 커밋하지 않는다.
8. **SQL 은 Exposed DSL 로.** 문자열 이어붙이기 SQL 금지(SQL Injection 방지).
9. **사용자 식별은 토큰에서.** 요청 본문의 userId 를 믿지 않는다(`call.requireUser()`).
10. **iOS 코드(iosMain)는 Windows 에서 klib 컴파일만 확인된다.** 실행 검증은 Mac 에서 해야 한다.

## 5. 새 화면 추가 예시

1. `presentation/<기능>/` 에 `XxxUiState`, `XxxViewModel`, `XxxScreen` 작성.
2. `presentation/navigation/Routes.kt` 에 `@Serializable` 경로 추가, `AppNavHost.kt` 에 `composable<…>` 등록.
3. `di/AppModules.kt` 에 `viewModel { … }` 등록(파라미터가 있으면 `params.get()`, 화면에서는 `koinViewModel { parametersOf(id) }`).
4. `commonTest` 에 ViewModel 상태 전이 테스트 추가.

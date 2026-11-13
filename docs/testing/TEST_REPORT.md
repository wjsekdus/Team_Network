# TEST_REPORT — 테스트 결과

판정 기준: **PASS** 실제 실행해 통과 / **FAIL** 실행했으나 실패 / **BLOCKED** 환경 문제로 실행 불가 / **NOT RUN** 아직 실행 안 함.
최종 실행: 2026-11-13 19:41~19:45 (KST), `--rerun-tasks`(캐시 없이 전체 재실행).

## 1. 테스트 환경

| 항목 | 값 |
|---|---|
| OS | Windows 11 (NT 10.0.26200) |
| JDK | Oracle JDK 21.0.12 |
| Gradle / AGP / Kotlin | 9.6.1 / 9.1.1 / 2.4.10 |
| Android SDK | Platform 36.1 설치됨 + Platform 37 빌드 시 자동 설치, Build-Tools 36.x |
| Android 기기·에뮬레이터 | **없음** (시스템 이미지·AVD 없음, `adb devices` 빈 목록) |
| macOS / Xcode | **없음** |
| PostgreSQL 설치본 / Docker | **없음** → 테스트 안에서 zonky 임베디드 PostgreSQL **14.22** 실행, 수동 실행은 H2 파일 DB |
| cloudflared | **없음** |

## 2. 실행 명령과 결과 요약

```powershell
.\gradlew.bat :shared:compileKotlinJvm :server:compileKotlin :composeApp:compileAndroidMain `
  :server:test :shared:jvmTest :composeApp:testAndroidHostTest :androidApp:testDebugUnitTest `
  :androidApp:assembleDebug :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64 --rerun-tasks
# BUILD SUCCESSFUL in 3m 48s, 90 actionable tasks: 90 executed
```

| 모듈 / 태스크 | 테스트 수 | 결과 |
|---|---|---|
| `:shared:jvmTest` | 17 | **PASS** (17/17) |
| `:composeApp:testAndroidHostTest` (commonTest) | 30 | **PASS** (30/30) |
| `:server:test` | 23 | **PASS** (23/23) |
| `:androidApp:testDebugUnitTest` (Robolectric UI 흐름 + 클라이언트–서버 통합) | 2 | **PASS** (2/2) |
| **합계** | **72** | **PASS, 실패·건너뜀 0** |

## 3. 기능별 결과

### 3.1 공통 로직 (요구 11.1)

| 테스트 항목 | 테스트 클래스 | 결과 |
|---|---|---|
| 데이터 모델 직렬화(필드명, 기본값, 알 수 없는 필드 무시, null 생략, WebSocket `type` 구분자) | `SerializationContractTest` (6) | PASS |
| DTO 변환 | `DtoMapperTest` (3) | PASS |
| 위치 데이터 유효성 | `LocationValidatorTest` (4), `LocationRepositoryTest` (3) | PASS |
| 닉네임 규칙 | `NicknamePolicyTest` (3) | PASS |
| 거리·감옥 10m·중심점·구역 다각형 | `GeoMathTest` (4) | PASS |
| API 오류 → AppError 변환, Bearer 헤더, 서버 주소 변경, 네트워크 오류 | `PoliceThiefApiTest` (7) | PASS |
| 세션 저장·복구(무토큰/유효/401 폐기/오프라인 복구) | `AuthRepositoryTest` (5) | PASS |
| UI 상태 전이(빈 입력, 검증 오류, 제출 중·중복 방지, 성공 이벤트, 실패 시 입력 유지) | `LoginViewModelTest` (4) | PASS |
| Repository Mock 동작(2:2 배정, 도둑 위치 필터, 호송 1명 제한, 경찰 승리, 다른 경기 QR, 구출, 기권 무효) | `MockGameBackendTest` (7) | PASS |
| WebSocket 재연결 백오프 | `RealtimeBackoffTest` (1) | PASS |

### 3.2 서버 (요구 11.2)

| 테스트 항목 | 테스트 | 결과 |
|---|---|---|
| Health API | `HealthApiTest` (2) | PASS |
| 게스트 계정 생성, 동일 닉네임 별도 계정 | `AuthApiTest.guestLogin_*`, `sameNickname_*` | PASS |
| 사용자 정보 조회 | `AuthApiTest.usersMe_returnsOwnProfile_*` | PASS |
| 인증 토큰 검증(없음·위조·만료) | `AuthApiTest.usersMe_withoutToken*`, `*ForgedToken*`, `expiredToken_isRejected` | PASS |
| 잘못된 요청(닉네임 422, JSON 400, 위치 422, gameId) | `AuthApiTest`, `LocationApiTest` | PASS |
| WebSocket 기본 연결(welcome, test_event, pong, 잘못된 메시지, 무인증 거부) | `RealtimeApiTest` (3) | PASS |
| 위치 정보 전송(저장, 최신 조회, MOCK 허용/거부, 타인 위치 비노출) | `LocationApiTest` (7) | PASS |
| 미구현 API 가 501 + 인증 필요 | `NotImplementedContractTest` (2) | PASS |
| **실제 PostgreSQL** 에서 Flyway 마이그레이션 + 로그인 + 위치 저장 | `PostgresIntegrationTest` (임베디드 PostgreSQL 14.22) | PASS |

### 3.3 UI 흐름 (요구 11.3)

| 단계 | 방법 | 결과 |
|---|---|---|
| 앱 실행 → 닉네임 입력 → 게스트 로그인 → 위치 권한 확인 → 홈 → 현재 위치 조회 → 지도 표시 | `LoginToHomeFlowTest` (Robolectric, 실제 Compose 화면, 서버는 MockEngine, 권한·GPS 는 가짜) | PASS |
| 지도: MapLibre 실제 타일 렌더링 | Robolectric 에 네이티브 라이브러리 없음 → 도식 지도로 대체된 상태만 확인 | **BLOCKED** (기기 없음) |
| 같은 흐름을 실기기/에뮬레이터에서 | 기기·에뮬레이터 없음 | **BLOCKED** |

### 3.4 클라이언트–서버 통합

| 항목 | 방법 | 결과 |
|---|---|---|
| 앱의 실제 `PoliceThiefApi`·`AuthRepositoryImpl`·`LocationRepositoryImpl`·`WebSocketRealtimeRepository` ↔ 실제 서버 모듈(H2) : health, 로그인, 세션 복구, MOCK 위치 전송, 501 → NotImplemented, WebSocket 연결·welcome·test_event·해제, 로그아웃 후 차단 | `ClientServerIntegrationTest` (Ktor 테스트 엔진) | PASS |

### 3.5 수동 실행 확인 (실제 서버 프로세스, `gradlew :server:run`, H2 파일 DB, 포트 18080)

| 요청 | 기대 | 실제 | 결과 |
|---|---|---|---|
| `GET /api/health` | 200 UP | `200 {"status":"UP","database":"UP",...}` | PASS |
| `POST /api/auth/guest` | 201 + 토큰 | 201, 43자 토큰 + user | PASS |
| `GET /api/users/me` (토큰) / (토큰 없음) | 200 / 401 | 200 / 401 `UNAUTHORIZED` | PASS |
| `POST /api/locations` MOCK / 위도 200 | 201 / 422 | 201 `source:"MOCK"` / 422 `LATITUDE_OUT_OF_RANGE` | PASS |
| `GET /api/locations/me/latest` | 200 | 200 | PASS |
| `GET /api/games/current` | 501 | 501 `NOT_IMPLEMENTED` | PASS |
| WebSocket `/ws` (토큰) | 101 + welcome, test_event, pong | 101, 세 메시지 수신 | PASS |
| WebSocket `/ws` (토큰 없음) | 거부 | `401 Unauthorized` | PASS |

## 4. 빌드 결과

| 대상 | 결과 | 비고 |
|---|---|---|
| Android 디버그 APK (`:androidApp:assembleDebug`) | **PASS** | `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (약 67MB, 디버그·MapLibre 네이티브 포함) |
| Android 앱 실행(설치·화면 표시) | **BLOCKED** | 기기·에뮬레이터 없음 |
| Android Studio 에서 프로젝트 열기 | **NOT RUN** | Gradle 명령줄 빌드로 Sync 와 같은 구성 단계는 통과 |
| 서버 실행 (`:server:run`) | **PASS** | H2 파일 DB |
| 서버 + 설치형 PostgreSQL 16/17 / Docker Compose | **BLOCKED** | 설치본·Docker 없음 (임베디드 PostgreSQL 14 로 대체 검증) |
| iOS Kotlin 컴파일 (`compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`) | **PASS** | Kotlin 2.4 가 Windows 에서 iOS klib 교차 컴파일 — `iosMain` 코드의 문법·타입·플랫폼 API 사용이 검증됨 |
| iOS 프레임워크 링크 (`linkDebugFrameworkIosSimulatorArm64`) | **BLOCKED** | Windows 에서 SKIPPED (macOS 필요) |
| iOS 앱 빌드 (Xcode) / 실기기 | **BLOCKED** | Mac·Xcode 없음. **Android 빌드 성공을 iOS 성공으로 보지 않는다** |
| GitHub Actions `ci.yml` 실제 실행 | **NOT RUN** | 원격 저장소 없음(로컬 `git init` 만). 같은 명령을 로컬에서 실행해 PASS |

## 5. 미검증 항목 (실기기·외부 환경 필요)

| 항목 | 상태 |
|---|---|
| 실제 GPS 위치 수집(Fused Location) | BLOCKED — 코드 컴파일만, 기기 없음 |
| 위치·카메라·알림 권한 팝업 동작 | BLOCKED |
| Android Keystore 토큰 암호화 저장(실기기) | BLOCKED — Robolectric 은 AndroidKeyStore 미지원이라 테스트는 메모리 저장소 사용 |
| MapLibre + OpenFreeMap 국내 지도 렌더링, 마커·구역·감옥 표시, 확대/축소 | BLOCKED — 스타일 URL 응답(HTTP 200)만 확인 |
| ZXing QR 이미지 표시 및 다른 폰으로 인식 | BLOCKED |
| Foreground Service 백그라운드 위치(화면 꺼짐, 15초 주기, Doze) | BLOCKED — **미검증** |
| iOS Core Location 백그라운드 | 미구현 |
| Cloudflare Tunnel 경유 HTTPS/WSS 접속 | NOT RUN — cloudflared 없음 |
| Android 3대 + iPhone 1대 동시 테스트 | BLOCKED |
| TalkBack/VoiceOver, 글자 200% 확대 | NOT RUN |

## 6. 실패 발생과 해결 기록

| 단계 | 실패 내용 | 원인 | 조치 후 재실행 |
|---|---|---|---|
| shared 테스트 1차 | `centroidOfFourPlayers_isAverage` FAIL | 부동소수점 정확 비교 | 허용 오차 비교로 수정 → PASS |
| server 컴파일 | `'source' hides member of supertype 'ColumnSet'` | Exposed 내부 속성과 이름 충돌 | Kotlin 속성명 `locationSource`(DB 컬럼명 유지) → PASS |
| server 테스트 1차 | `usersCannotReadEachOthersLatestLocation` FAIL | 테스트 닉네임 "밥"이 2자 규칙 위반(테스트 오류) | 닉네임 수정 → PASS |
| composeApp 컴파일 | 타입 추론·인자 순서·`pingInterval` 이름 오류 7건 | Ktor 3.5 API 이름(`pingIntervalMillis`), `EmptyState` 위치 인자 | 수정 → PASS |
| composeApp 테스트 1차 | `PoliceThiefApiTest` 2건 FAIL — 요청 URL 이 `http://localhost` | **실제 버그**: 요청 빌더 헬퍼에서 URL 이 적용되지 않음 | `client.request(url) { }` 구조로 변경 → 30/30 PASS |
| 서버 수동 실행 1차 | "PNT_DB_PASSWORD 미설정"으로 시작 거부 | PowerShell 에서 빈 문자열 대입 = 변수 삭제 | 문서화, 임시 비밀번호로 재실행 → PASS |
| 서버 수동 실행 | 콘솔 한글 깨짐 | Windows 콘솔 코드페이지 | 서버 JVM 출력 UTF-8 고정(`applicationDefaultJvmArgs`) |

## 7. 남은 문제

- Gradle 경고: "Using a Project object as a dependency notation has been deprecated"(Gradle 10 에서 오류 예정). 빌드 영향 없음.
- Material3 가 alpha 버전(Compose Multiplatform 1.11 공식 짝).
- Robolectric UI 테스트 1회 약 37초(첫 실행 시 android-all 다운로드로 더 오래 걸림).

# TEST_REPORT — 테스트 결과 (B 방식, `prototype/ai-complete`)

판정 기준: **PASS** 실제 실행해 통과 / **FAIL** 실행했으나 실패 / **BLOCKED** 환경 문제로 실행 불가 / **NOT RUN** 아직 실행 안 함.
**코드만 작성하고 실행하지 않은 항목은 PASS 로 적지 않는다.**
최종 실행: 2026-11-14 (KST), `--rerun-tasks`(캐시 없이 전체 재실행) + 실서버 4인 시나리오.

## 1. 테스트 환경

| 항목 | 값 |
|---|---|
| OS | Windows 11 (NT 10.0.26200) |
| JDK | Oracle JDK 21.0.12 |
| Gradle / AGP / Kotlin | 9.6.1 / 9.1.1 / 2.4.10 |
| Android SDK | Platform 36.1 설치됨 + Platform 37 빌드 시 자동 설치, Build-Tools 36.x |
| Android 기기·에뮬레이터 | **없음** (시스템 이미지·AVD 없음, `adb devices` 빈 목록) |
| macOS / Xcode | **없음** |
| PostgreSQL 설치본 / Docker | **없음** → 자동 테스트는 H2(PostgreSQL 모드) + zonky 임베디드 PostgreSQL, 실서버 검증은 zonky 실행기로 띄운 실제 PostgreSQL(Flyway 로그 기준 **16.4**, 포트 55432) |
| cloudflared | **없음** |
| 실서버 시나리오 도구 | Python 3 + `websocket-client` (REST + WebSocket 을 실제 네트워크로 호출) |

## 2. 실행 명령과 결과 요약

```powershell
.\gradlew.bat :shared:jvmTest :server:test :composeApp:testAndroidHostTest :androidApp:testDebugUnitTest `
  :androidApp:assembleDebug :androidApp:lintDebug `
  :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64 --rerun-tasks --continue
```

| 모듈 / 태스크 | 테스트 수 | 결과 |
|---|---|---|
| `:shared:jvmTest` | 17 | **PASS** (17/17) |
| `:server:test` (H2 + 임베디드 PostgreSQL) | 75 | **PASS** (75/75) |
| `:composeApp:testAndroidHostTest` (commonTest) | 42 | **PASS** (42/42) |
| `:androidApp:testDebugUnitTest` (Robolectric UI 흐름 + 4인 클라이언트–서버 통합) | 3 | **PASS** (3/3) |
| **합계** | **137** | **PASS, 실패·건너뜀 0** |
| `:androidApp:lintDebug` (정적 분석) | — | **PASS** — 오류 0, 경고 1(`DataExtractionRules`: Android 12+ 백업 규칙 파일 미지정) |
| 실서버 4인 시나리오(§3.6) | 40 항목 | **PASS** (40/40) |

A 단계 기준선(72 테스트)에서 B 단계 구현 후 137 테스트로 늘었다. 기존 "501 미구현 계약" 테스트는 실제 게임 API 가 생겨 실제 동작 테스트로 교체했다.

## 3. 기능별 결과

### 3.1 공통(shared) — 17

| 항목 | 테스트 | 결과 |
|---|---|---|
| 직렬화 계약(필드명, 기본값, 알 수 없는 필드 무시, null 생략, WebSocket `type` 구분자) | `SerializationContractTest` (6) | PASS |
| 게임 규칙 상수·거리(Haversine)·중심점·감옥 10m·구역 판정 | `RulesTest` (11) | PASS |

### 3.2 서버 — 75 (`server/src/test/kotlin/com/nettwork/policethief/server/`)

| 영역 | 테스트 클래스 (수) | 검증한 내용 | 결과 |
|---|---|---|---|
| 인증 | `AuthApiTest` (10) | health, 게스트 생성, 같은 닉네임 별도 계정, `/users/me`, 토큰 없음·위조·만료 401, 잘못된 닉네임 422, 잘못된 JSON 400 | PASS |
| 위치 | `LocationApiTest` (7) | 저장·최신 조회, MOCK 허용/거부, 범위 밖 좌표 422, 타인 위치 비노출 | PASS |
| 매칭 | `MatchmakingApiTest` (14) | 4명 정상 매칭(경찰 2·도둑 2), 3명 대기·대기 인원, 10분 만료, 중복 요청, 취소 멱등·취소자 제외, 매칭 후 취소 거부·게임 중 재참가 거부, 먼 사용자 제외, 정확도 낮음·오래된 위치 거부, 오래된 대기자 건너뛰기, **동시 요청 시 모두 정확히 한 경기**, 다른 조합 탐색, 한 사람 중복 배정 없음, 오래 기다린 사람 우선 | PASS |
| 경기 흐름 | `GameFlowApiTest` (10) | 준비 30초·시작 전 행동 거부, 체포→호송→수감→구출→경찰 승리, 잘못된 QR 사유, 같은 requestId 한 번만 판정, **같은 도둑 동시 체포 1건만 성공**, 30분 종료 도둑 승리·종료 후 체포 거부, 종료 직전 체포는 경찰 승리, 기권 무효, 감옥 10m 경계·정확도, 감옥은 최신 경찰 위치 필요 | PASS |
| 진행 규칙·보안 | `GameProgressApiTest` (16) | 구역 이탈 경고→30초 후 실격, 복귀 시 해제, 정확도 낮으면 이탈 판정 안 함, 구역 축소 예고→축소, 미션 도둑에게만 공개·실패 시 축소 앞당김, 미션 성공, 과속 경고 3회→실격, 단발 GPS 튐·순간이동은 경고 없음, MOCK 은 개발 서버에서만 과속 면제, 순서 역전·중복 위치 무시, **도둑은 경찰 위치를 받지 못함**, 비참가자 조회·변경 차단, 토큰 필요, QR 토큰이 상태·이벤트에 없음, 종료 후 위치·QR 차단, 이벤트 역할 필터·증분 조회 | PASS |
| 연결·복구·개발 도구 | `ConnectionAndRecoveryTest` (11) | 끊김→3분 유예→재접속 복구 / 초과 시 무효, 준비 중 끊김도 유예, welcome 에 진행 중 경기·이벤트 푸시·끊김 시 RECONNECTING, **재접속 시 첫 메시지가 welcome**, WebSocket 으로 경찰 위치 미전달, 두 번째 연결이 첫 연결 대체, 서버 재시작 후 지난 마감 처리·유예 시작, 앱 재실행 시 같은 경기, 개발 도구 꺼짐 시 404, 폰 1대 + 봇 3명으로 끝까지 진행, 봇은 개발 도구 사용 불가·사람은 봇처럼 이동 불가 | PASS |
| WebSocket·계약 | `RealtimeAndContractTest` (5) | welcome·test_event, 잘못된 메시지에도 연결 유지, 무인증 거부 등 | PASS |
| 실제 PostgreSQL | `PostgresIntegrationTest` (2) | Flyway V1+V2 마이그레이션·저장소, **행 잠금(FOR UPDATE) 매칭 + 체포** | PASS |

### 3.3 앱 공통(composeApp commonTest) — 42

| 테스트 클래스 (수) | 검증한 내용 | 결과 |
|---|---|---|
| `DataLayerTest` (19) | API 오류→AppError, Bearer 헤더, 서버 주소 변경, 세션 저장·복구·401 폐기, 위치 검증, DTO 매퍼 | PASS |
| `GameSyncTest` (12) | **중복 이벤트 1회만 처리**, 오래된 버전 스냅샷 무시, 첫 로드 이벤트는 알림 재생 안 함, 같은 경기 welcome 시 재동기화, 실시간 위치 최신만 반영, 서비스 꺼져 있을 때 앱이 위치 전송·종료 시 공유 중단, 서비스 켜지면 이중 전송 없음, 내가 체포됨 알림 문구, 위치 갱신은 알림 없음, **기기 시계가 틀려도 서버 시간 기준**, 네트워크 실패 후 재시도 시 같은 requestId | PASS |
| `PresentationAndMockTest` (11) | 로그인 ViewModel 상태 전이, Mock 저장소(서버 없이 화면 확인용) 동작 | PASS |

### 3.4 androidApp — 3

| 테스트 | 방법 | 결과 |
|---|---|---|
| `LoginToHomeFlowTest` | Robolectric + 실제 Compose 화면: 닉네임→게스트 로그인→권한→홈→지도(도식 대체) | PASS |
| `ClientServerIntegrationTest.appClient_talksToRealServerModule_restAndWebSocket` | 앱의 실제 API·저장소 ↔ 실제 서버 모듈(H2) REST + WebSocket | PASS |
| `ClientServerIntegrationTest.fourAppClients_playAFullGameAgainstRealServer` | **앱 클라이언트 4개**가 실제 서버 모듈과 매칭→역할→시작→체포→경기 종료까지 진행 | PASS |

### 3.5 실행하지 않은 화면 단위 테스트

지도·QR 스캔·호송·절전·결과 화면의 Compose UI 는 `compileAndroidMain`·`assembleDebug` 로 **컴파일만** 확인했다. 화면 렌더링·카메라 동작은 기기가 없어 **BLOCKED**.

### 3.6 실서버 4인 시나리오 (실제 Ktor 프로세스 + 실제 PostgreSQL 16.4)

방법: `:server:installDist` 로 만든 `server.bat` 를 `PNT_DEV_TOOLS=true`, `PNT_ALLOW_MOCK_LOCATIONS=true` 로 포트 18080 에서 실행하고, Python 스크립트가 게스트 4명을 만들어 실제 HTTP·WebSocket 으로 경기 전체를 진행했다. 30초 준비시간은 개발자 시간 이동(`POST /api/dev/clock/advance`)으로 건너뛰었다. 이어서 서버 프로세스를 강제 종료하고 다시 띄워 복구를 확인했다.

| 구분 | 확인 항목 | 결과 |
|---|---|---|
| 서버·DB | health `UP` / DB `UP`, Flyway V1+V2 적용 | PASS |
| 인증 | WebSocket 인증 연결(welcome 4명), 토큰 없는 REST 401, 위조 토큰 WebSocket 거부, 다른 경기 조회 403 | PASS |
| 매칭 | 3명 대기 인원 실시간 갱신, 4명째 참가 시 MATCHED, 4명 같은 경기, 경찰 2/도둑 2, 준비 30초·제한 30분·구역 500m·감옥 10m | PASS |
| 위치 공개 | 도둑 WebSocket 에 경찰 위치 없음, 도둑 REST 조회는 도둑 2명만, 경찰은 4명 전원 | PASS |
| QR | 경찰의 QR 조회 403, 상태 응답에 QR 토큰 없음 | PASS |
| 체포·호송 | 체포→ESCORTED, 같은 requestId 재전송 시 같은 결과, 호송 중 구출 거부(`TARGET_ESCORTED`), 경찰 1명 2명 호송 거부(`ESCORT_LIMIT`) | PASS |
| 수감·구출 | 감옥 10m 안 위치 전송→자동 JAILED + 호송 해제, 구출→ACTIVE, 경찰도 구출 알림 수신 | PASS |
| 종료 | 두 도둑 체포→즉시 경찰 승리, GAME_FINISHED 4명 수신, 결과(`ALL_THIEVES_CAPTURED`, 활동 기록), 종료 후 위치 조회 409, 종료 후 체포 거부 | PASS |
| 서버 재시작 | 종료된 경기 결과 유지, 진행 중 경기 PLAYING 복구, 미접속자 RECONNECTING(유예 시작), 재접속 welcome 에 진행 중 경기 ID, 재접속 시 CONNECTED, 기권→무효(`PLAYER_FORFEITED`) | PASS |

**비기능 목표 측정(같은 PC 안 loopback, 실제 기기·인터넷 지연 미포함):**

| 목표 | 측정값 | 결과 |
|---|---|---|
| 매칭 확정 3초 이내 전달 | 4명 WebSocket 수신까지 최대 약 0.3초 | PASS (로컬 기준) |
| QR 판정 2초 이내 | 약 0.1~0.16초 | PASS (로컬 기준) |
| 게임 이벤트 3초 이내 전달 | 체포 이벤트 4명 수신까지 최대 약 0.15초 | PASS (로컬 기준) |
| 지도 반영 2초, QR 결과 화면 표시 2초, 실제 네트워크·터널 경유 지연 | 기기 없음 | **BLOCKED** |
| 위치 15초 주기·30초 지연 안내 | 앱 로직은 `GameSyncTest` 로 확인, 실기기 주기 미측정 | 부분 PASS / 실기기 BLOCKED |

## 4. 빌드 결과

| 대상 | 결과 | 비고 |
|---|---|---|
| Android 디버그 APK (`:androidApp:assembleDebug`) | **PASS** | `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (약 68MB) |
| Android lint (`:androidApp:lintDebug`) | **PASS** | 오류 0, 경고 1 |
| Android 앱 실행(설치·화면 표시) | **BLOCKED** | 기기·에뮬레이터 없음 |
| Android Studio 에서 프로젝트 열기 | **NOT RUN** | 명령줄 Gradle 구성·빌드는 통과 |
| 서버 실행(`installDist` → `server.bat`) + PostgreSQL | **PASS** | 실제 PostgreSQL 16.4(임베디드 실행기). 설치형 PostgreSQL·Docker 는 없음 |
| iOS Kotlin 교차 컴파일 (`compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`) | **PASS** | B 단계에서 추가한 `iosMain`(AVFoundation QR 스캐너, CIQRCodeGenerator, 알림·진동, 백그라운드 위치) 포함 |
| iOS 프레임워크 링크 / Xcode 빌드 / 실기기 | **BLOCKED** | Mac·Xcode 없음. **Android 빌드 성공을 iOS 성공으로 보지 않는다** |
| GitHub Actions `ci.yml` 실제 실행 | **NOT RUN** | 원격 실행 안 함. 같은 명령을 로컬에서 실행해 PASS |

## 5. 미검증 항목 (실기기·외부 환경 필요)

| 항목 | 상태 |
|---|---|
| 실제 GPS 위치 수집(Fused Location / Core Location) | BLOCKED |
| 위치·카메라·알림 권한 팝업 | BLOCKED |
| CameraX + ZXing 카메라 QR 인식, iOS AVFoundation 인식 | BLOCKED — 컴파일만 |
| MapLibre 실제 지도 렌더링(구역·감옥·미션·마커) | BLOCKED |
| Foreground Service 백그라운드 위치(화면 꺼짐, Doze), iOS 백그라운드 위치 | BLOCKED |
| 백그라운드 알림·진동 | BLOCKED |
| Android Keystore 토큰 저장(실기기) | BLOCKED |
| Cloudflare Tunnel 경유 HTTPS/WSS | NOT RUN — cloudflared 없음 |
| Android 3대 + iPhone 1대 실제 경기 | BLOCKED |
| 데이터베이스 일시 중단 중 요청 | NOT RUN |
| 푸시 알림(FCM/APNs) | 미구현 — 앱 실행 중·Foreground Service 동안만 알림 |

## 6. 실패 발생과 해결 기록

### 6.1 B 단계

| 단계 | 실패 내용 | 원인 | 조치 후 재실행 |
|---|---|---|---|
| 서버 컴파일 | `DevTools.kt` smart cast 오류 5건 | Kotlin 이 null 검사 뒤에도 값이 바뀔 수 있다고 판단 | 코드 수정 → `:server:compileKotlin` PASS |
| 서버 테스트 1차 | 75개 중 7개 FAIL | **실제 버그** 포함: `GameEngine.ingest` 가 위치 표본을 판정에 반영하지 않는 경우가 있었음 | 엔진 수정 → 75/75 PASS |
| 앱 컴파일 | 6건 | shared 계약 변경(`LOCATION_TIMEOUT` 제거 등)을 앱이 따라가지 않음 | 새 계약으로 수정 → PASS |
| 절전 화면 컴파일 | smart cast 오류 1건 | 위와 같음 | 수정 → PASS |
| shared 테스트 컴파일 | `GameDto` 필수 인자 5개 누락 | B 단계에서 `GameDto` 에 필드 추가 | 테스트 데이터 보강 → 17/17 PASS |
| 실서버 재시작 시나리오 | "재접속 welcome 에 진행 중 경기 ID" FAIL | **실제 버그**: 재접속 처리(`PLAYER_RECONNECTED` 이벤트)가 `welcome` 보다 먼저 전송되어 "첫 메시지는 welcome" 계약 위반 | `RealtimeRoutes` 에서 welcome 을 연결 등록 전에 전송, 회귀 테스트 추가 → 서버 75/75, 시나리오 40/40 PASS |
| iOS 교차 컴파일 | `Unresolved reference 'defaultDeviceWithMediaType'` | Objective-C 클래스 메서드를 최상위 함수처럼 import | 잘못된 import 삭제(`AVCaptureDevice.defaultDeviceWithMediaType` 그대로 사용) → PASS |
| 구조 점검(문서 작성 중 발견) | Presentation 이 `data.local` 설정 저장소 구현 클래스를 직접 import (지시서 4.1 위반) | 설정용 domain 인터페이스가 없었음 | `DevSettingsRepository`·`UserPreferencesRepository`·`ServerAddressProvider` 추가, DI 연결 변경 → composeApp 42·androidApp 3 PASS, APK·iOS 컴파일 PASS |

### 6.2 A 단계(기록 보존)

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
- 위의 비기능 측정값은 같은 PC 안(loopback)에서 잰 값이다. 휴대전화 네트워크·Cloudflare Tunnel 경유 값은 측정하지 않았다.
- 고정 QR 은 사진·스크린샷으로 복제될 수 있다(확정 규칙이라 유지, 알려진 한계).
- `:androidApp:testDebugUnitTest` 실행 중 콘솔에 `SQL: [Failed on expanding args for SELECT ...]` 한 줄이 찍힌다. 테스트 결과 파일에는 없고 모든 테스트가 통과한다. 테스트 안에서 띄운 서버가 종료될 때 스케줄러가 닫힌 DB 를 조회한 것으로 추정하지만 **원인은 확인하지 않았다.**

## 8. 재현 방법

1. 자동 테스트: §2 의 Gradle 명령을 프로젝트 루트에서 실행한다. 결과 XML 은 각 모듈 `build/test-results/`, HTML 은 `build/reports/tests/`.
2. 실서버 4인 시나리오: `docs/development/RUN_GUIDE.md` 의 4인 테스트 절차로 서버를 개발자 모드(`PNT_DEV_TOOLS=true`, `PNT_ALLOW_MOCK_LOCATIONS=true`)로 띄운 뒤, 앱 1대 + 봇 3명(설정 화면 개발자 도구) 또는 REST/WebSocket 클라이언트 4개로 진행한다. 실제 경기 서버에서는 두 값을 반드시 `false` 로 둔다.

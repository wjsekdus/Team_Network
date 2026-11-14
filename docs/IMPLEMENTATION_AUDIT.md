# IMPLEMENTATION_AUDIT — A 방식 기준 코드 분석 (B 방식 착수 시점)

- 분석 대상: `prototype/ai-complete` 브랜치, 커밋 `c64ccdb` (A 방식 `prototype/base-architecture` 와 동일 시점)
- 작업 폴더: `C:\myLocal\Team_Network_B` (Git worktree. A 폴더 `C:\myLocal\Team_Network` 와 분리)
- 기준선 테스트(변경 전, 2026-11-13 실행): `:server:test` 23, `:shared:jvmTest` 17, `:composeApp:testAndroidHostTest` 30, `:androidApp:testDebugUnitTest` 2 → **72개 PASS**, `:androidApp:assembleDebug` PASS

상태 기준: **IMPLEMENTED** 실제 동작 / **MOCK** 가짜 동작 / **PARTIAL** 일부만 / **MISSING** 없음 / **BROKEN** 오류

## 1. 프로젝트 환경

| 항목 | 상태 | 내용 |
|---|---|---|
| Gradle / Version Catalog | IMPLEMENTED | Gradle 9.6.1, `gradle/libs.versions.toml`, 모듈 `shared`·`composeApp`·`androidApp`·`server` |
| Kotlin / CMP / AGP | IMPLEMENTED | Kotlin 2.4.10, Compose Multiplatform 1.11.1, AGP 9.1.1 |
| KMP 대상 | IMPLEMENTED | shared: jvm·android·iosArm64·iosSimulatorArm64 / composeApp: android·iosArm64·iosSimulatorArm64 |
| Android SDK | IMPLEMENTED | compileSdk/targetSdk 37, minSdk 26 |
| iOS 프로젝트 | PARTIAL | `iosApp/` Xcode 프로젝트·Info.plist 존재. Mac 이 없어 빌드 미검증 |
| Ktor Client / Server | IMPLEMENTED | 3.5.1 (OkHttp / Darwin / Netty) |
| PostgreSQL 연결 | IMPLEMENTED | HikariCP + Flyway(V1) + Exposed 1.6.1. 테스트는 H2·임베디드 PostgreSQL |
| DI | IMPLEMENTED | 앱 Koin 4.2.2, 서버는 `ServerDependencies` 수동 조립 |
| Serialization / Coroutines / Flow | IMPLEMENTED | `ApiJson` 공용 설정, StateFlow 기반 ViewModel |
| GitHub Actions | PARTIAL | `ci.yml`(빌드·테스트), `ios-build.yml`(수동). 실제 GitHub 실행 기록 없음 |

## 2. 기능별 구현 상태

| 기능 | 상태 | 구현 클래스 / 파일 | 실제 동작 | B 방식 수정 필요 사항 |
|---|---|---|---|---|
| 게스트 로그인 | IMPLEMENTED | `server/auth/AuthService.kt`, `composeApp/data/repository/RepositoryImpls.kt`(AuthRepositoryImpl) | 닉네임 → UUID + 256bit 토큰(해시 저장) | 유지. 토큰 무효 시 새 세션 생성 흐름 유지 |
| 세션 복구 | IMPLEMENTED | `AuthRepositoryImpl.restoreSession`, `LaunchViewModel` | 401 이면 토큰 삭제 후 로그인 화면 | 재실행 시 진행 중 경기·매칭 화면 복귀 추가 |
| 보안 저장소 | IMPLEMENTED | `KeystoreTokenStorage`(Android), `KeychainTokenStorage`(iOS) | Android 코드 컴파일만 확인, 실기기 미검증 | 유지 |
| 중복 접속 정책 | MISSING | `event/ConnectionManager.kt` | 한 사용자가 여러 WebSocket 세션을 동시에 가짐 | 단일 활성 세션(새 연결이 이전 연결 대체) |
| Health API | IMPLEMENTED | `server/health/HealthRoutes.kt` | DB 상태 포함 | 유지 |
| 위치 전송 API | IMPLEMENTED | `server/location/LocationService.kt` | 좌표·정확도·시각 검증, MOCK 출처 구분 | 경기·매칭 상태와 연동(구역·과속·수감·미션 판정 입력) |
| 4인 매칭 | MISSING (서버) / MOCK (앱) | `server/game/GameFeatureContracts.kt`(501), `composeApp/data/mock/MockGameBackend.kt` | 서버 501, 앱은 가짜 참가자 3명 | 서버 매칭 알고리즘·대기열·동시성·10분 만료 구현 |
| 역할 배정 | MISSING / MOCK | `MockGameBackend.createGame` | 앱 내부 가짜 배정 | 서버 무작위 2:2 배정(1회만) |
| 준비 30초·게임 시작 | MISSING / MOCK | `MockGameBackend.refreshStatus` | 단말 시각 기준 | 서버 시각 기준 스케줄러, 서버 재시작 복구 |
| 역할별 위치 공유 | MISSING / MOCK | `MockGameBackend.visibleLocations` | 가짜 좌표 | 서버 응답 단계에서 필터(REST·WebSocket·재접속 모두) |
| QR 토큰 발급 | MISSING / MOCK | `qr_tokens` 테이블만 존재 | 앱이 만든 가짜 토큰 | 서버 SecureRandom 토큰, 경기 종료 시 폐기 |
| QR 체포·호송 | MISSING / MOCK | `MockGameBackend.capture` | 앱 내부 판정 | 서버 판정, 호송 1:1, 중복 요청(requestId) 방지 |
| 감옥 자동 수감 | MISSING / MOCK | `MockGameBackend.simulateJailArrival` (버튼) | 수동 버튼 | 위치 갱신 시 서버 판정(10m·정확도·최신성) |
| QR 구출 | MISSING / MOCK | `MockGameBackend.rescue` | 앱 내부 판정 | 서버 판정 |
| 승패·무효 | MISSING / MOCK | `MockGameBackend.finish` | 앱 내부 | 서버 단일 확정(중복 확정 방지), 경계 시점 정책 |
| 기권 | MISSING / MOCK | `GameService.forfeit`(501) | 앱 내부 | 서버 무효 처리 + 전원 통지 |
| 연결 끊김 3분 유예 | MISSING | `PlayerConnectionState` enum 만 존재 | 없음 | WebSocket 연결 추적, 유예 만료 시 무효 |
| 구역 축소·미션 | MISSING | DTO 없음 | 없음 | 서버 스케줄, 설정값(PROVISIONAL) 분리 |
| 과속 경고·실격 | MISSING | `GameRules.SPEED_LIMIT_KMH` 상수만 | 없음 | 연속 표본 기반 판정, 3회 실격 |
| 구역 이탈 경고 | MISSING | 없음 | 없음 | 서버 판정 + 경고 이벤트(설정값 30초) |
| 게임 이벤트 WebSocket | PARTIAL | `ServerMessage.GameEventPush`, `NotificationService` | 메시지 형식만, 실제 발생 이벤트 없음 | 이벤트 저장·버전·재전달·역할별 권한 |
| WebSocket 연결·재연결 | IMPLEMENTED | `WebSocketRealtimeRepository` | 지수 백오프, 401 시 중단 | 재연결 후 상태 재동기화 추가 |
| 지도 (Android) | IMPLEMENTED | `PlatformMapView.android.kt` (MapLibre + OpenFreeMap) | 컴파일·APK 포함, 실기기 렌더링 미검증 | 미션 구역·다음 구역 표시 추가 |
| 지도 (iOS) | PARTIAL | `PlatformMapView.ios.kt` → `SchematicMap` | 타일 없는 도식 지도 | MapKit(무료, OS 내장) 구현 |
| GPS (Android/iOS) | IMPLEMENTED / PARTIAL | `RealLocationProvider` | Android Fused, iOS requestLocation(미검증) | 유지 |
| 개발자 테스트 좌표 | IMPLEMENTED | `MockLocationProvider`, `DevAwareLocationProvider` | 개발 빌드에서만 | 서버 과속 판정 예외 처리(개발 서버 한정) |
| 백그라운드 위치 (Android) | PARTIAL | `LocationTrackingService` | 수동 토글, 실기기 미검증 | 매칭·경기 상태에 따라 자동 시작/종료 |
| 백그라운드 위치 (iOS) | MISSING | `IosBackgroundLocationController`(isSupported=false) | 없음 | Core Location 백그라운드 업데이트 |
| 카메라 QR 스캔 | MISSING | `QrScanScreen` (TODO 문구) | 없음 | Android CameraX + ZXing, iOS AVFoundation |
| QR 이미지 (iOS) | MISSING | `QrCodeImage` iOS = 문자열 표시 | 없음 | CoreImage QR 생성 |
| 알림·진동 | MISSING | 없음 | 없음 | 앱 내 배너 + 진동 + 백그라운드 로컬 알림 |
| 절전 모드 | PARTIAL | `PowerSaveScreen` | 남은 시간·전송 시각만 | 체포 현황·축소까지 시간·연결 상태, 자동 실행 설정 |
| 결과 화면 | MOCK | `GameResultScreen` | Mock 결과 | 서버 결과 + 플레이어별 활동 |
| 단위·통합 테스트 | IMPLEMENTED (기본 기능) | server 23, shared 17, composeApp 30, androidApp 2 | 통과 | 게임 기능 테스트 대량 추가, Mock 테스트 제거 |

## 3. BROKEN 항목

분석 시점에 실행 오류(BROKEN)로 판정된 항목은 없다. 다만 다음은 A 방식의 의도된 한계였다.

- 앱의 `useMockGameData` 기본값이 `true` 라 개발 빌드는 서버 대신 Mock 게임을 사용했다 → B 방식에서 Mock 게임 자체를 제거한다.
- `GameEventType` 은 실제로 발생하는 이벤트가 없어 B 방식 이벤트 목록(지시서 17.1)과 이름이 맞지 않는다 → 재정의한다.
- 준비 단계(READY)에서 Mock 은 도둑에게도 경찰 위치를 보여 줬다 → 지시서 9.4(경찰 위치는 도둑에게 전달 금지)에 따라 준비 단계에도 같은 공개 정책을 적용한다.

## 4. 재사용 / 교체 계획 요약

| 구분 | 대상 |
|---|---|
| 그대로 재사용 | 인증, Health, WebSocket 기반, GPS 공급자, 보안 저장소, 지도 추상화, 공통 UI 컴포넌트·테마, `GeoMath`, `LocationValidator`, Flyway V1 |
| 확장 | `GameDto`·`GamePlayerDto`·`ServerMessage`, `LocationService`(경기 연동), `ConnectionManager`(단일 세션), 각 게임 화면 ViewModel |
| 교체 | `NotImplemented*Service` → 실제 서비스, `MockGameBackend`·`MockGameController` → 서버 연동 + 서버 개발자 도구(봇·시간 이동) |
| 신규 | Flyway V2(경기 진행 테이블), 게임 엔진·스케줄러, 매칭, 미션·구역·과속, 카메라 QR, 알림·진동, iOS MapKit·QR·백그라운드 위치 |

B 방식 구현 결과와 최종 상태는 `docs/testing/TEST_REPORT.md` 와 최종 보고에 기록한다.

# Team_Network
고급 모바일 프로그래밍

# 경찰과 도둑 — 위치 기반 즉시 매칭형 멀티플레이 게임

고급모바일프로그래밍 팀 **넷트워크** 프로젝트. 현재 위치 반경 500m 안의 사용자 4명을 자동으로 묶어 경찰 2 : 도둑 2 로 30분 동안 실제 공간에서 경찰과 도둑 게임을 진행하는 Android / iOS 앱과 Ktor 서버입니다.

> 이 저장소는 **초기 기반 버전(v0.1.0)** 입니다. 공통 구조와 기본 기능(게스트 로그인, 서버 통신, WebSocket, GPS·테스트 좌표, 지도, 전체 화면 UI)은 동작하고, 매칭·체포·구출·승패 같은 핵심 게임 로직은 **인터페이스와 Mock 만 준비된 상태**입니다. 남은 작업은 [`docs/development/TODO.md`](docs/development/TODO.md) 에 있습니다.

## 문서 목록

| 문서 | 내용 |
|---|---|
| [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) | 전체 구조, Clean Architecture, MVVM, 데이터 흐름 |
| [DATABASE.md](docs/architecture/DATABASE.md) | 테이블, ERD, 인덱스, 저장 정책 |
| [API.md](docs/api/API.md) | REST / WebSocket 계약 (구현됨 / 예정 구분) |
| [UI_STRUCTURE.md](docs/development/UI_STRUCTURE.md) | 화면별 Composable, ViewModel, 상태, Mock 여부 |
| [CODE_GUIDE.md](docs/development/CODE_GUIDE.md) | 코드 학습 가이드(패키지·클래스·호출 흐름) |
| [SERVER_SETUP.md](docs/development/SERVER_SETUP.md) | PostgreSQL, 서버 실행, 환경 변수, Cloudflare Tunnel |
| [VERSIONS.md](docs/development/VERSIONS.md) | 라이브러리 버전과 선정 근거 |
| [TODO.md](docs/development/TODO.md) | 미구현 기능과 4인 분담안 |
| [TEST_REPORT.md](docs/testing/TEST_REPORT.md) | 실제 실행한 테스트 결과 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 브랜치·커밋·PR·리뷰 규칙 |
| [AI_GUIDE.md](docs/ai/AI_GUIDE.md) | AI 가 만든 코드의 범위와 검토 방법 |

## 개발 환경

| 항목 | 기준 |
|---|---|
| OS | Windows 10/11 (서버·Android 개발), macOS (iOS 빌드 시 필요) |
| JDK | 21 (Android Studio 내장 JBR 21 또는 Oracle/Temurin 21) |
| IDE | Android Studio 2025.3 이상 (Kotlin Multiplatform 플러그인 권장) |
| Android SDK | compileSdk / targetSdk 37, minSdk 26 (SDK 37 은 첫 빌드 때 자동 설치) |
| DB | PostgreSQL 16/17 (Docker 선택) |
| iOS | macOS + Xcode 필요 — **현재 미확보, iOS 앱 빌드 미검증** |

## 기술 스택 (실제 적용)

Kotlin 2.4.10 · Kotlin Multiplatform · Compose Multiplatform 1.11.1 · Material3 · MVVM + Clean Architecture · Coroutines/Flow · Ktor Client/Server 3.5.1 · WebSocket · kotlinx.serialization · Koin 4.2.2 · Multiplatform Settings · Android Keystore / iOS Keychain · MapLibre Android 13.7.1 + OpenFreeMap · PostgreSQL · Exposed 1.6.1 · Flyway 13.12.2 · HikariCP · JUnit5 / kotlin.test / Ktor Test / Robolectric · Gradle 9.6.1 (Kotlin DSL, Version Catalog) · GitHub Actions. 버전 근거는 [VERSIONS.md](docs/development/VERSIONS.md).

## 프로젝트 구조

```text
PoliceAndThief/
├── shared/        서버·앱 공용 API 계약(DTO), 상태 enum, 게임 규칙 상수, 검증, 거리 계산
├── composeApp/    Android·iOS 공용 앱 코드 (KMP 라이브러리)
│   └── src/
│       ├── commonMain/kotlin/com/nettwork/policethief/
│       │   ├── presentation/  화면(Compose), ViewModel, UiState, Navigation, Theme
│       │   ├── domain/        Entity, Repository 인터페이스, UseCase
│       │   ├── data/          Repository 구현, Remote(API/WebSocket), Local, Mapper, Mock
│       │   ├── platform/      GPS·권한·보안저장소·지도·QR·백그라운드 위치 "인터페이스"
│       │   ├── di/            Koin 모듈
│       │   └── config/, core/ 앱 설정, 시계
│       ├── androidMain/   Android 구현 (Fused Location, Keystore, MapLibre, Foreground Service, ZXing)
│       ├── iosMain/       iOS 구현 (Core Location, Keychain) — 일부 TODO
│       └── commonTest/    공통 로직 테스트
├── androidApp/    Android 실행 앱(Activity, Manifest, BuildConfig) + Robolectric UI 테스트
├── iosApp/        Xcode 프로젝트(SwiftUI 에서 Compose 화면 호출)
├── server/        Ktor 서버 (auth, location, event(WebSocket), game, notification …)
├── docs/          개발 문서
├── .github/workflows/  CI
├── docker-compose.yml  (선택) PostgreSQL
└── gradle/libs.versions.toml  버전 카탈로그
```

> 요청서의 예시 구조(`composeApp` 하나)와 달리 `androidApp` 과 `shared` 모듈이 추가되어 있습니다.
> - `androidApp`: AGP 9 부터 KMP 모듈에 `com.android.application` 을 함께 적용할 수 없어 JetBrains 공식 템플릿처럼 분리했습니다.
> - `shared`: 서버(JVM)와 앱이 **같은 DTO 코드**를 써서 API 계약 불일치를 컴파일 단계에서 막기 위해 분리했습니다.

## 빠른 실행 (Windows)

### 1) 서버

```powershell
cd PoliceAndThief
copy server\.env.example server\.env      # 값 수정 (PNT_DB_PASSWORD 등)
# PostgreSQL 준비 (택1) — 자세한 방법: docs/development/SERVER_SETUP.md
#   a) Docker:  $env:PNT_DB_PASSWORD="비밀번호"; docker compose up -d
#   b) 설치형 PostgreSQL 에서 police_thief DB/사용자 생성
#   c) PostgreSQL 없이 체험: server\.env 의 H2 설정 주석을 풀어 사용
.\gradlew.bat :server:run
```

확인: 브라우저에서 `http://localhost:8080/api/health` → `{"status":"UP","database":"UP",...}`

### 2) Android 앱

1. Android Studio 에서 `PoliceAndThief` 폴더를 엽니다(Gradle Sync).
2. 서버 주소를 `local.properties` 에 지정합니다(없으면 에뮬레이터 기본값 `http://10.0.2.2:8080`).
   ```properties
   pnt.serverUrl=http://192.168.0.10:8080           # 같은 Wi-Fi 실기기 (PC IP)
   # pnt.serverUrl=https://xxxx.trycloudflare.com  # 외부 네트워크 (Cloudflare Tunnel)
   ```
3. 실행 구성 `androidApp` 을 선택하고 Run. 명령줄: `.\gradlew.bat :androidApp:installDebug`
4. 개발 빌드는 앱의 **설정 → 개발자 테스트 모드** 에서 서버 주소, 테스트 좌표, Mock 게임 역할을 바꿀 수 있습니다.

### 3) iOS 실행 준비 (macOS 필요, 미검증)

1. Mac 에 Xcode 와 JDK 21 설치 → `iosApp/iosApp.xcodeproj` 열기.
2. `iosApp/Configuration/Config.xcconfig` 의 `TEAM_ID`, `SERVER_BASE_URL` 설정(`https:/$()/주소` 형식 주의).
3. 시뮬레이터 선택 후 Run (빌드 단계에서 `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` 가 실행됨).
4. iOS 는 지도(도식 지도로 대체), QR 이미지, 백그라운드 위치가 아직 TODO 입니다.

## 테스트

```powershell
.\gradlew.bat :server:test                      # 서버 (H2 + 임베디드 PostgreSQL)
.\gradlew.bat :shared:jvmTest                   # 공유 계약·규칙
.\gradlew.bat :composeApp:testAndroidHostTest   # 앱 공통 로직(commonTest)
.\gradlew.bat :androidApp:testDebugUnitTest     # Robolectric UI 흐름 + 클라이언트–서버 통합
.\gradlew.bat :androidApp:assembleDebug         # Android 빌드
```

최근 실행 결과(72개 테스트 PASS, iOS 앱 빌드 BLOCKED 등)는 [TEST_REPORT.md](docs/testing/TEST_REPORT.md) 를 보세요.

## Git 시작하기

이 폴더에는 `git init` 만 되어 있고 커밋은 없습니다. 팀 저장소에 올릴 때:

```powershell
git add .
git update-index --chmod=+x gradlew     # Linux CI 에서 실행 권한
git commit -m "chore: 초기 프로젝트 구성"
git remote add origin https://github.com/<팀>/<저장소>.git
git push -u origin main
```

`server/.env`, `local.properties`, `iosApp/Configuration/Local.xcconfig` 는 `.gitignore` 로 제외됩니다. 비밀번호·토큰을 다른 파일에 적지 마세요.

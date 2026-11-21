# VERSIONS — 라이브러리 버전과 선정 근거

확인일: 2026-11-13. 정의 위치: `gradle/libs.versions.toml`.

## 선정 방법

1. **핵심 조합**(Kotlin / AGP / Compose Multiplatform / Gradle / Koin / Ktor / Coroutines / Navigation / Activity)은 JetBrains 공식 `Kotlin/KMP-App-Template` main 브랜치의 `libs.versions.toml`·`gradle-wrapper.properties` 에 있는 조합을 그대로 채택했다(공식 CI 에서 함께 쓰이는 조합).
2. 나머지는 Maven Central / Google Maven 의 `maven-metadata.xml` 에서 **안정(stable) 버전**을 확인하고, POM 의 의존성(예: Ktor 3.5.1 → kotlinx-serialization 1.11.0)과 맞췄다.
3. 최신 안정 버전이 더 있어도 위 조합과의 호환이 확인되지 않은 것은 올리지 않았다.
4. 최종 판단은 **실제 빌드·테스트 통과**(TEST_REPORT.md)로 확인했다.

## 적용 버전

| 구분 | 라이브러리 | 버전 | 근거 / 비고 |
|---|---|---|---|
| 빌드 | Gradle | 9.6.1 | 공식 템플릿 wrapper (SHA-256 포함) |
| 빌드 | Android Gradle Plugin | 9.1.1 | 템플릿. 최신 안정 9.5.1 은 미채택 |
| 언어 | Kotlin | 2.4.10 | 템플릿. 최신 안정 2.4.21 은 미채택 |
| UI | Compose Multiplatform | 1.11.1 | 템플릿. 최신 안정 1.12.2 는 미채택 |
| UI | JetBrains Material3 | **1.11.0-alpha07** | ⚠️ 사전 출시(alpha). CMP 1.10 이후 Material3 는 alpha 로만 배포되며 템플릿도 동일 버전 사용 |
| UI | androidx.compose (테스트용 ui-test) | 1.11.2 | CMP 1.11.1 foundation 이 매핑하는 androidx 버전과 동일 |
| UI | lifecycle (JetBrains) | 2.11.0 | 안정판. 템플릿의 2.11.0-beta01 대신 정식판 사용(요구 runtime 1.9.3) |
| UI | navigation-compose (JetBrains) | 2.9.2 | 템플릿 |
| Android | activity-compose | 1.13.0 | 템플릿 |
| Android | core-ktx | 1.19.1 | 안정 |
| Android | play-services-location | 21.4.0 | 안정 (Fused Location) |
| Android | MapLibre Android SDK | 13.7.1 | 안정(13.x 라인). 메타데이터의 `2026.11.2-globe` 류는 별도 라인이라 미채택 |
| Android | ZXing core | 3.5.4 | QR 생성 |
| 공통 | Koin | 4.2.2 | 템플릿 |
| 공통 | Ktor (client/server) | 3.5.1 | 템플릿. 서버도 같은 버전으로 맞춤. 최신 3.6.1 미채택 |
| 공통 | kotlinx-coroutines | 1.11.0 | 템플릿 |
| 공통 | kotlinx-serialization | 1.11.0 | Ktor 3.5.1 의 의존 버전 |
| 공통 | kotlinx-datetime | 0.7.1 | 시간 표시(로컬 시간대 변환) |
| 공통 | multiplatform-settings | 1.3.0 | SharedPreferences / NSUserDefaults / Keychain |
| 서버 | Exposed | 1.6.1 | 안정. 1.x 는 패키지가 `org.jetbrains.exposed.v1.*` |
| 서버 | Flyway | 13.12.2 | `flyway-database-postgresql` 함께 |
| 서버 | PostgreSQL JDBC | 42.7.14 | |
| 서버 | HikariCP | 7.1.0 | |
| 서버 | H2 | 2.5.252 | 테스트·체험용 |
| 서버 | Logback | 1.6.6 | |
| 테스트 | kotlin-test-junit5 | 2.4.10 | JUnit Jupiter 5.10.1 + platform-launcher 포함(Gradle 9 요구사항 충족) |
| 테스트 | zonky embedded-postgres | 2.2.2 | 테스트에서 실제 PostgreSQL(14.x) 실행 |
| 테스트 | Robolectric | 4.17 | `@Config(sdk=[35])` |
| 테스트 | JUnit4 | 4.13.2 | Robolectric/Compose UI 테스트 |
| 테스트 | ktor-client-mock | 3.5.1 | |

## 확인이 더 필요한 항목

- Material3 alpha 사용: 안정판이 나오면 교체.
- AGP 9.1.1 + compileSdk 37: 첫 빌드 때 SDK Platform 37 이 자동 설치된다(이 PC 에서 정상 동작 확인).
- iOS: Xcode 버전 요구사항(템플릿 CI 는 Xcode 26.3)은 Mac 확보 후 확인.
- GitHub Actions 액션 버전(`checkout@v4`, `setup-java@v4`, `setup-gradle@v4`, `upload-artifact@v4`)은 템플릿·일반 사용 기준이며 실제 GitHub 실행은 아직 하지 않았다.
- Gradle 경고 "Using a Project object as a dependency notation has been deprecated"(Gradle 10 에서 오류 예정): 공식 템플릿과 같은 `projects.xxx` 표기에서 발생하는 것으로 보이며 현재 빌드에는 영향 없음. Gradle 10 이전에 재확인.

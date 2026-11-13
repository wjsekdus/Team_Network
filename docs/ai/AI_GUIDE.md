# AI_GUIDE — AI 작성 코드의 범위와 검토 방법

## 1. AI 가 작성한 범위 (v0.1.0)

| 영역 | 내용 | 검증 수준 |
|---|---|---|
| 프로젝트 구성 | Gradle(Version Catalog), 모듈 구조, CI, iosApp 설정 | Android/서버 빌드 PASS, iOS 링크 미검증 |
| shared | DTO, enum, 규칙 상수, 검증기, GeoMath | 단위 테스트 PASS |
| 서버 | health, 게스트 인증, 사용자 조회, 위치 저장, WebSocket, 미구현 API 501, DB 스키마 | 테스트 + 실서버 호출 PASS |
| 앱 | 전체 화면 UI, ViewModel, Repository, Mock 게임, Android 플랫폼 구현 | 단위·Robolectric·통합 테스트 PASS, 실기기 미검증 |
| iOS 플랫폼 코드 | Core Location, Keychain, 권한 | klib 컴파일만 PASS, 실행 미검증 |
| 문서 | docs/ 전체, README, CONTRIBUTING | — |

핵심 게임 로직(매칭 알고리즘, 역할 배정, 체포·수감·구출 판정, 승패, 재접속 유예)은 **의도적으로 구현하지 않았다.** `MockGameBackend` 의 규칙 처리는 화면 확인용 흉내이며 서버 구현의 정답이 아니다.

## 2. 팀원이 AI 코드를 읽을 때 체크리스트

- [ ] 이 클래스는 어느 계층인가? 의존 방향을 어기지 않는가? (ARCHITECTURE.md 3장)
- [ ] 실패하면 어떤 `AppError` 가 되고 화면에 무엇이 보이는가?
- [ ] 테스트가 있는가? 없으면 어떤 입력으로 확인할 것인가?
- [ ] "Mock", "TODO", "미검증" 표시가 실제 상태와 맞는가?
- [ ] 이해하지 못한 코드는 PR 에 질문으로 남기고, 이해 전에는 수정하지 않는다.

## 3. 앞으로 AI 를 쓸 때 규칙

1. 기존 구조(계층, `shared` 계약, `AppResult`)를 먼저 읽게 하고 그 규칙을 프롬프트에 포함한다.
2. 한 번에 한 작업 단위(TODO.md 의 ID 하나)만 요청한다.
3. 생성된 코드는 반드시 빌드·테스트를 직접 실행해 확인하고, 실행하지 않은 것을 "됨"이라고 쓰지 않는다.
4. 비밀 값, 실제 토큰, 개인 위치 데이터를 프롬프트에 넣지 않는다.
5. AI 가 추가한 라이브러리는 VERSIONS.md 기준(안정판, 호환 근거)으로 검토한다.
6. PR 본문에 "AI 사용 범위"를 한 줄로 적는다(예: "M-2 후보 탐색 쿼리 초안 AI 생성, 테스트는 직접 작성").

## 4. 이번 작업에서 AI 가 테스트로 발견·수정한 문제

| 문제 | 발견 방법 | 조치 |
|---|---|---|
| 앱 API 요청 URL 이 설정되지 않고 기본값(`http://localhost`)으로 나감 | `PoliceThiefApiTest` (MockEngine 요청 URL 확인) | 요청 생성 구조를 `client.request(url) { }` 로 단순화 |
| Exposed 컬럼명 `source` 가 내부 멤버와 충돌 | 서버 컴파일 | Kotlin 속성명 `locationSource` 로 변경(DB 컬럼명 유지) |
| PowerShell 빈 환경 변수 = 변수 삭제 | 실서버 실행 | 문서화(.env 파일 사용 안내) |
| Windows 콘솔 한글 로그 깨짐 | 실서버 실행 | 서버 JVM 출력 UTF-8 고정 |

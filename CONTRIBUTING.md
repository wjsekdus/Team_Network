# CONTRIBUTING — 개발 규칙

## 1. 브랜치

| 브랜치 | 용도 |
|---|---|
| `main` | 항상 빌드·테스트가 통과하는 상태. 직접 푸시 금지(PR 로만 병합) |
| `develop` | 기능 통합 브랜치(선택). 팀이 원하면 `main` 하나만 써도 된다 |
| `feature/<영역>-<내용>` | 기능 개발. 예: `feature/matchmaking-radius-search`, `feature/capture-qr-verify` |
| `fix/<내용>` | 버그 수정 |
| `docs/<내용>` | 문서만 수정 |

```powershell
git switch main
git pull
git switch -c feature/matchmaking-radius-search
```

## 2. 커밋 메시지 (Conventional Commits, 한국어 본문)

```text
<type>(<scope>): <요약 50자 이내>

<왜 바꿨는지, 무엇이 달라지는지 (선택)>
```

- type: `feat` 기능, `fix` 버그, `refactor` 동작 변화 없는 구조 개선, `test` 테스트, `docs` 문서, `chore` 빌드·설정
- scope: `server`, `shared`, `app`, `android`, `ios`, `ci`, `docs`
- 예: `feat(server): 반경 500m 매칭 후보 검색 구현`, `fix(app): 매칭 취소 후 홈 중복 이동 수정`

## 3. Pull Request

1. 작업 전 TODO.md 에서 담당 작업 단위를 확인하고, PR 제목에 작업 ID(예: `[M-2]`)를 붙인다.
2. PR 본문 템플릿:
   ```markdown
   ## 무엇을
   ## 왜
   ## 테스트
   - [ ] :server:test  - [ ] :shared:jvmTest  - [ ] :composeApp:testAndroidHostTest  - [ ] :androidApp:assembleDebug
   - 실기기 확인: (기기/OS, 결과)
   ## 스크린샷 (UI 변경 시)
   ## 문서 변경 (API.md / DATABASE.md / TODO.md …)
   ```
3. GitHub Actions CI 가 초록색이어야 병합할 수 있다(저장소 설정 → Branch protection 에서 CI 필수 지정 권장).
4. PR 은 작게: 한 기능·한 목적. 리뷰 시간이 30분을 넘지 않는 크기를 목표로 한다.

## 4. 코드 리뷰

- 리뷰어 최소 1명 승인. `shared`(API 계약)나 DB 마이그레이션을 바꾸는 PR 은 2명 승인.
- 확인 항목
  - 계층 규칙: UI 가 API 를 직접 부르지 않는가, ViewModel 이 플랫폼 SDK 를 쓰지 않는가
  - 서버 판정: 클라이언트 값을 믿고 판정하지 않는가(userId·역할·좌표 재검증)
  - 오류 처리: 빈 catch 없음, 사용자에게 원인·다음 행동 표시
  - 테스트: 새 규칙·상태 전이에 테스트가 있는가
  - 문서: API/DB/화면 변경이 문서에 반영됐는가
- AI 가 생성한 코드도 동일 기준으로 리뷰한다(docs/ai/AI_GUIDE.md).

## 5. 신규 기능 추가 방법

1. **계약**: 필요한 DTO·경로를 `shared` 에 추가(또는 기존 계약 확인) → API.md 갱신.
2. **서버**: `server/<영역>/` 에 Service 구현(인터페이스가 있으면 구현체 작성) → 필요 시 `V2__….sql` → `ServerDependencies` 에서 `NotImplemented…` 교체 → 서버 테스트.
3. **앱 Data**: `RemoteXxxRepository` 확인/수정, Mapper 추가.
4. **앱 Presentation**: ViewModel 상태·이벤트, 화면. Mock 과 실제 서버 모두에서 확인.
5. **문서**: TODO.md 의 해당 항목 상태 갱신, TEST_REPORT.md 에 실행 결과 추가.

## 6. 테스트 작성 규칙

- 위치: 규칙·계약 → `shared/src/commonTest`, 앱 로직 → `composeApp/src/commonTest`, 서버 → `server/src/test`, UI 흐름 → `androidApp/src/test`(Robolectric).
- 이름: `조건_기대결과` 형식(예: `expiredToken_isRejected`).
- 시간·위치·네트워크는 가짜로: `FixedClock`/`TestClock`, `FakeLocationProvider`, Ktor `MockEngine`, 서버는 `serverTest { }`(H2).
- 버그 수정 PR 에는 그 버그를 재현하는 테스트 1개를 포함한다.
- 실행하지 않은 테스트를 통과로 적지 않는다(TEST_REPORT.md 의 PASS/FAIL/BLOCKED/NOT RUN).

## 7. 공통 코드 수정 규칙

- `shared` 와 `composeApp/commonMain/{domain,data,di,platform}` 은 **공통 영역**이다. 수정 PR 은 팀 채널에 먼저 알리고 영향받는 담당자를 리뷰어로 지정한다.
- enum 값 이름 변경·삭제 금지(서버 DB 값과 JSON 이 깨진다). 추가만 허용.
- `GameRules` 숫자 변경은 팀 합의 후 ARCHITECTURE.md 규칙 표와 함께 수정.
- `libs.versions.toml` 버전 변경은 별도 PR(`chore(deps)`)로, 전체 테스트·Android 빌드 통과 확인 후 병합.

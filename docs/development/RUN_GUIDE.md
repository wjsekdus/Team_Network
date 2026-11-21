# RUN_GUIDE — 서버·앱 실행 가이드 (Windows, 초보자용)

이 문서는 **처음 프로젝트를 받은 팀원이 서버를 띄우고 앱으로 접속해 경기를 시험하는 순서**를 설명한다.
명령은 PowerShell 기준이다. 프로젝트 폴더(`gradlew.bat` 이 있는 곳)에서 실행한다.

> **검증 범위(중요)** — 이 문서의 서버 실행·API 동작은 코드와 자동 테스트(75개 통과)로 확인한 것이다. 아래는 **실제로 해 보지 않았다(NOT VERIFIED)**: 실제 휴대폰 3~4대 경기, iPhone/iOS 빌드, Cloudflare Tunnel 접속, GitHub Actions. 해당 절에는 "미검증"이라고 표시했다. 막히면 그대로 팀에 공유해 문서를 고치자.
> 서버 환경 설정 일반은 기존 [`SERVER_SETUP.md`](SERVER_SETUP.md) 와 겹친다. **내용이 다르면 이 문서(RUN_GUIDE.md)를 따른다.**

## 0. 전체 그림

```text
[Android/iPhone 앱] --(HTTPS / WSS, 인터넷)--> [Cloudflare Tunnel] --> [내 PC: Ktor 서버 :8080] --> [PostgreSQL :5432 (내 PC 안에서만)]
```

- 서버는 **내 PC 한 대**에서 돈다. 앱들은 서버 주소만 알면 접속한다.
- DB(PostgreSQL)는 **서버 프로그램만** 접속한다. 앱이나 인터넷에서 DB 포트(5432)로 직접 접속할 수 있으면 안 된다.
- 테이블은 서버가 시작할 때 **Flyway 가 자동으로 만든다**(`V1__initial_schema.sql`, `V2__game_progress.sql`). 직접 `CREATE TABLE` 하지 않는다.

## 1. 준비물 설치

| 항목 | 설치 | 확인 |
|---|---|---|
| JDK 21 | Android Studio 에 내장된 JBR 21 또는 Temurin 21 | `java -version` |
| Android Studio | 앱 빌드·에뮬레이터용 | |
| PostgreSQL 16/17 | §2 | `psql --version` |
| (외부 접속 시) cloudflared | §6 | `cloudflared --version` |

## 2. PostgreSQL 설치와 DB 만들기

### 방법 A. 설치형 PostgreSQL (Docker 없이, 권장)

1. <https://www.postgresql.org/download/windows/> 에서 설치 프로그램을 받아 실행한다. 포트는 기본 **5432**, 관리자(`postgres`) 비밀번호는 기억해 둔다. "Stack Builder" 는 필요 없다.
2. 시작 메뉴의 **SQL Shell (psql)** 을 연다(서버·포트·DB·사용자는 Enter 로 기본값, 비밀번호는 위에서 정한 것).
3. 서버용 사용자와 DB 를 만든다(비밀번호는 직접 정한 값으로 바꾼다. **이 문서의 예시를 그대로 쓰지 말 것**):
   ```sql
   CREATE USER police_thief WITH PASSWORD '여기에_직접_정한_비밀번호';
   CREATE DATABASE police_thief OWNER police_thief;
   ```
4. **DB 포트를 인터넷에 열지 않는다.** Windows 방화벽에서 5432 인바운드 허용 규칙을 만들지 말고, 공유기 포트포워딩도 하지 않는다. 설치 시 기본값은 이 PC 에서만 쓰도록 되어 있으니 그대로 둔다. Cloudflare Tunnel 도 DB 포트는 절대 연결하지 않는다(8080 하나만).

### 방법 B. Docker Desktop (선택)

저장소 루트의 `docker-compose.yml`(postgres:17, `127.0.0.1:5432` 에만 공개)을 쓴다.
```powershell
$env:PNT_DB_PASSWORD = "직접_정한_비밀번호"
docker compose up -d
docker compose ps
```
DB 이름/사용자는 `police_thief`/`police_thief` 로 자동 생성된다. 서버 `.env` 의 `PNT_DB_PASSWORD` 를 같은 값으로 맞춘다.

### 방법 C. PostgreSQL 없이 체험 (H2 파일 DB, 선택)

`server/.env` 에서 PostgreSQL 3줄 대신 H2 줄의 `#` 을 풀면 된다(`server/.env.example` 에 예시가 있다). 빠르게 눈으로 확인하는 용도다. 팀 테스트는 PostgreSQL 을 쓴다(H2 는 호환 모드일 뿐 완전히 같지 않다).

## 3. 서버 환경 변수 (`server/.env`)

```powershell
copy server\.env.example server\.env
notepad server\.env
```

- **`PNT_DB_PASSWORD`** 는 필수다(없으면 서버가 시작을 거부). 방금 정한 비밀번호를 넣는다.
- `server/.env` 는 `.gitignore` 에 포함되어 Git 에 올라가지 않는다. **비밀번호를 `server/.env.example`·문서·채팅에 쓰지 말자.**
- 전체 변수와 설명은 `server/.env.example` 의 한글 주석과 [`GAME_CONFIG.md`](GAME_CONFIG.md) 를 본다.
- `.env` 작성 규칙(Gradle 이 직접 읽는 단순 파서): 한 줄에 `이름=값`, `#` 으로 시작하는 줄은 주석. **값 뒤에 `# 설명`을 같은 줄에 붙이지 말 것**(설명까지 값이 된다). 따옴표도 값에 그대로 포함된다.
- `gradlew :server:run` 으로 실행할 때만 `.env` 가 읽힌다. 이미 Windows 환경 변수가 있으면 그 값이 우선한다.
- PowerShell 에서 `$env:이름=""`(빈 값)은 변수를 **삭제**한다. 빈 값이 필요하면 `.env` 파일에 `이름=` 으로 적는다.

### 개발 모드 vs 운영(실경기) 모드

| 설정 | 개발/시연 서버 | 실제 경기 테스트 서버 |
|---|---|---|
| `PNT_ALLOW_MOCK_LOCATIONS` | `true` — 앱의 테스트 좌표(`source=MOCK`) 허용, 과속 판정에서 MOCK 제외 | **`false`**(기본값) — MOCK 좌표 요청은 403 |
| `PNT_DEV_TOOLS` | `true` — `/api/dev/...` 봇·시간 이동 사용 가능(`PNT_ALLOW_MOCK_LOCATIONS=true` 도 필요) | **`false`**(기본값) — 개발 API 는 404 |
| 위험 | 서버 주소를 아는 누구나 가짜 좌표를 보낼 수 있다 | — |

외부(Cloudflare)에 공개할 때는 시연이 아닌 이상 두 값을 `false` 로 두는 것을 권장한다.

## 4. 서버 실행

```powershell
.\gradlew.bat :server:run
```

- 처음에는 Gradle 이 의존성을 받느라 시간이 걸린다.
- 시작 로그에 `서버 설정: host=…, port=…, db=…(비밀번호는 ****), allowMockLocations=…, devTools=…, game=…` 한 줄이 나온다. 의도한 값인지 확인한다.
- 시작할 때 Flyway 가 마이그레이션을 실행한다. 새 DB 라면 `V1`, `V2` 가 차례로 적용된다. 실패하면 `DB 마이그레이션 실패: …` 로 서버가 멈춘다(원인: DB 주소·비밀번호·권한).
- 확인: 브라우저에서 <http://localhost:8080/api/health> → `{"status":"UP","database":"UP",...}`.
- 콘솔 한글이 깨지면 `chcp 65001` 후 다시 실행.
- 설치형 실행 파일: `.\gradlew.bat :server:installDist` → `server\build\install\server\bin\server.bat`. 이 방식은 `.env` 를 읽지 않으므로 환경 변수를 직접 설정한다(`server/build.gradle.kts` 의 `application` 플러그인).
- 서버 테스트를 돌려 보려면(선택): `.\gradlew.bat :server:test`(H2 + 임베디드 PostgreSQL 사용).

DB 에 테이블이 생겼는지 보려면 SQL Shell 에서 `\c police_thief` → `\dt`. `flyway_schema_history` 에 `1`, `2` 가 보이면 정상이다.

### 서버 종료

- `gradlew.bat :server:run` 을 띄운 터미널에서 **`Ctrl + C`**.
- 포트 8080 이 이미 사용 중이라는 오류가 나면 이전 서버가 남아 있는 것이다: `netstat -ano | findstr :8080` 으로 PID 확인 후 작업 관리자에서 종료하거나 `.env` 의 `PNT_SERVER_PORT` 를 바꾼다.
- Docker 로 DB 를 띄웠다면 `docker compose stop`(데이터 유지) / `docker compose down -v`(데이터까지 삭제).
- 서버를 껐다 켜도 진행 중이던 경기는 DB 에서 복구된다(DATABASE.md §5). 단, 켠 직후 모든 참가자에게 3분 재접속 유예가 새로 시작된다.

## 5. 앱이 서버를 찾는 방법

### Android

서버 주소 우선순위(`androidApp/build.gradle.kts`): 환경 변수 `PNT_SERVER_URL` > 프로젝트 루트 `local.properties` 의 `pnt.serverUrl` > 기본값 `http://10.0.2.2:8080`(에뮬레이터에서 PC 를 가리키는 특수 주소).

| 상황 | 주소 |
|---|---|
| 에뮬레이터 → 같은 PC 서버 | `http://10.0.2.2:8080` (기본값, 설정 불필요) |
| USB 로 연결한 실기기 | `http://localhost:8080` + `adb reverse tcp:8080 tcp:8080` |
| 같은 Wi-Fi 의 실기기 | `http://<PC 의 IPv4>:8080` (`ipconfig` 로 확인, Windows 방화벽에서 **서버 포트 8080 만** 허용) |
| 다른 네트워크(LTE 등) | `https://<임의>.trycloudflare.com` (§6) |

`local.properties` 예시(Git 에 올리지 않는 파일):
```properties
pnt.serverUrl=https://abc-def-ghi.trycloudflare.com
```
수정 후에는 앱을 **다시 빌드**해야 반영된다. 개발(debug) 빌드는 **앱 → 설정 → 개발자 테스트 모드 → 서버 주소**에 URL 을 입력·저장하면 재빌드 없이 바꿀 수 있다(비우면 빌드 기본값). debug 빌드만 `http://`(평문)를 허용하고, release 빌드는 `https://` 만 된다(`androidApp/build.gradle.kts` 의 `usesCleartextTraffic`).

실행: Android Studio 에서 `androidApp` 실행 구성을 선택해 Run, 또는
```powershell
.\gradlew.bat :androidApp:installDebug
```
(앱 모듈은 개발 중이라 빌드가 일시적으로 실패할 수 있다. 서버 문서 작성 시에는 빌드를 실행하지 않았다.)

### iOS (미검증)

- **macOS + Xcode 가 있어야 한다.** Windows 에서는 iOS 앱을 빌드할 수 없다. 이번 문서 작업에서 iOS 빌드·실행은 **하지 못했다(NOT VERIFIED)**.
- 준비 순서(README 기준): Mac 에 Xcode·JDK 21 설치 → `iosApp/iosApp.xcodeproj` 열기 → `iosApp/Configuration/Config.xcconfig` 의 `TEAM_ID`, `SERVER_BASE_URL` 설정 → Run.
- `SERVER_BASE_URL` 은 xcconfig 에서 `//` 가 주석으로 해석되므로 `https:/$()/abc-def-ghi.trycloudflare.com` 처럼 쓴다.
- 실기기에서 서버에 접속하려면 §6 의 `https://` 주소가 필요하다.
- README 기준 iOS 는 지도·QR 이미지·백그라운드 위치가 아직 미완성(TODO)이다. iPhone 이 참가자인 4인 테스트는 **미검증**이다.

## 6. 외부 접속: Cloudflare Tunnel (Quick Tunnel)

(미검증 — 이 환경에는 `cloudflared` 가 없어 실행해 보지 못했다. 서버 로컬 HTTP/WebSocket 동작만 테스트됨.)

같은 Wi-Fi 가 아닌 곳(LTE, 학교 밖)에서 접속하려면 PC 의 서버를 인터넷에 임시로 노출한다. **계정이 필요 없는 Quick Tunnel** 을 쓴다.

```powershell
winget install --id Cloudflare.cloudflared      # 최초 1회
.\gradlew.bat :server:run                       # 터미널 1: 서버
cloudflared tunnel --url http://localhost:8080  # 터미널 2: 터널
```
- 터널 터미널에 `https://<임의 단어들>.trycloudflare.com` 이 출력된다. 이 주소를 앱 서버 주소로 넣는다.
- 확인: 휴대폰 브라우저에서 `https://<주소>/api/health`.
- 앱은 `https://` 주소에서 자동으로 **`wss://<주소>/ws`**(암호화된 WebSocket)로 접속한다.
- **cloudflared 를 끄고 다시 켜면 URL 이 바뀐다.** 팀원 모두 새 주소로 앱 설정(`local.properties` 또는 개발자 테스트 모드의 서버 주소)을 바꿔야 한다.
- URL 을 아는 사람은 누구나 서버에 접속할 수 있다. 토큰 인증이 있지만, 시연이 끝나면 터널을 끈다. 특히 `PNT_ALLOW_MOCK_LOCATIONS`/`PNT_DEV_TOOLS` 가 `true` 이면 외부인도 개발 API 를 쓸 수 있으므로 공개 상태로 두지 않는다.
- Quick Tunnel 은 테스트용이며 가용성 보장이 없다.
- 터널 대상은 `http://localhost:8080` 하나뿐이다. **5432(DB)는 연결하지 않는다.**

## 7. 4명 테스트 절차 (안드로이드 3대 + iPhone 1대)

(실기기 경기는 **한 번도 해 보지 않았다**. 아래는 서버 코드·테스트에서 도출한 절차다. iPhone 포함 4인 경기는 iOS 앱이 준비된 뒤에만 가능하다. iOS 가 안 되면 Android 4대 또는 §8 의 봇 방식으로 대체한다.)

### 사전 준비

1. 서버를 **실경기 설정**(`PNT_ALLOW_MOCK_LOCATIONS=false`, `PNT_DEV_TOOLS=false`)으로 실행하고 §6 터널로 공개한다(같은 Wi-Fi 안이면 PC IP 로도 가능).
2. 4명 모두 같은 서버 주소로 설정한다. 4명이 **실제로 서로 500m 이내**에 있어야 한다(기본 구역 반경 500m).
3. 모두 앱의 **위치 권한**을 허용(정확한 위치)하고, 하늘이 보이는 실외에서 GPS 가 잡힌 뒤 시작한다. 감옥 판정은 정확도 15m 이하가 필요하다.
4. 서버 시계가 맞는지 `/api/health` 의 `serverTimeEpochMillis` 로 확인(서버 판정은 서버 시계 기준이라 폰 시계는 영향이 적음).

### 진행 순서

| 단계 | 할 일 | 서버에서 일어나는 일 |
|---|---|---|
| 1 | 각자 앱에서 게스트 로그인(닉네임 2~12자) | `POST /api/auth/guest` |
| 2 | 각자 홈에서 **매칭 찾기** | 위치 전송 + `POST /api/matchmaking`. 4명이 500m 안이면 모이는 즉시 확정 |
| 3 | 역할 공개 화면 확인(경찰 2, 도둑 2). **준비 30초** | `GAME_PREPARING`. 구역(반경 500m) 중심 = 4명 중심점 = 감옥 |
| 4 | 30초 후 경기 시작, 30분 타이머 | `GAME_STARTED` |
| 5 | **체포**: 경찰이 도둑에게 가서 도둑의 QR 을 스캔 | 도둑 `ESCORTED`. 경찰은 도둑을 호송 |
| 6 | **수감**: 경찰과 도둑이 **함께** 감옥(구역 중심) 10m 안으로 이동 | 두 사람 모두 정확도 ≤15m, 30초 이내 위치일 때 자동 `JAILED` |
| 7 | **구출**: 잡히지 않은 도둑이 수감된 도둑의 QR 스캔 | `JAILED → ACTIVE` |
| 8 | 도둑 둘 다 체포되면 즉시 경찰 승리, 30분이 지나면 도둑 승리 | `GAME_FINISHED` |

참고: 도둑 둘이 모두 체포(호송 중 포함)되면 감옥에 도착하지 않아도 경찰이 이긴다.

### 시험할 항목(체크리스트)

- 경기 중 경찰의 위치가 **도둑 화면에 나오지 않는지**, 도둑끼리는 서로 보이는지.
- 한 사람이 앱을 껐다 켰을 때(3분 이내) 경기로 복귀하는지 / 3분을 넘기면 경기가 무효 되는지.
- 구역 밖으로 30초 이상 나가면 경고 후 실격(무효)되는지. 10분 후 구역 축소 예고(60초 전)와 400m 로의 축소.
- 도둑 미션(3분 후 첫 미션)이 도둑 화면에만 나오는지.
- 같은 QR 을 두 번 스캔했을 때 이중 체포가 안 되는지.
- 기권(게임 나가기) 시 모두에게 무효로 나오는지.

## 8. 휴대폰 1대 + 개발 봇으로 혼자 테스트하기

서버에 개발자 도구를 켜면 봇 3명과 "서버 시계 앞당기기"로 휴대폰 1대로도 4인 경기를 끝까지 볼 수 있다. 봇의 행동도 일반 사용자와 **같은 서버 판정 코드**를 거친다.

### 8-1. 서버 설정(개발 모드)

`server/.env` 에서(둘 다 필요):
```text
PNT_ALLOW_MOCK_LOCATIONS=true
PNT_DEV_TOOLS=true
```
서버를 재시작한다. 시작 로그에 `⚠ 개발자 도구(/api/dev …)가 켜져 있습니다` 경고가 나오면 정상이다. `PNT_DEV_TOOLS=true` 인데 `PNT_ALLOW_MOCK_LOCATIONS` 가 `true` 가 아니면 서버가 시작되지 않는다. 시험이 끝나면 되돌린다.

### 8-2. 앱에서 (개발 빌드)

앱의 **설정 → 개발자 테스트 모드** 에 서버 개발자 도구 버튼이 있다(앱 화면은 개발 중이라 이름이 바뀔 수 있다).

1. 게스트 로그인 후 같은 화면에서 서버 주소/테스트 좌표를 설정하고 **좌표 저장**(MOCK 좌표는 `source=MOCK` 으로 전송됨).
2. **봇 3명 만들기** — 내 마지막 위치 주변 40m 이내에 봇 3명이 만들어져 대기열에 들어간다(`POST /api/dev/bots`). 내 위치가 서버에 한 번도 전송되지 않았다면 409(`NO_ANCHOR_LOCATION`)가 나니 먼저 위치를 보낸다.
3. 홈에서 **매칭 찾기** → 4명이 모여 즉시 매칭된다. 역할은 무작위(2:2)이므로 내가 경찰일 수도 도둑일 수도 있다.
4. **시간 이동**: `+30초`(준비 종료·경기 시작), `+5분`, `+30분`(제한 시간 종료 → 도둑 승리 확인). 한 번에 최대 1시간.
5. **봇 체포 스캔**: 봇 경찰이 봇 도둑을 체포하는 등 봇끼리의 스캔을 서버에 시킨다.

주의: 시간 이동은 되돌릴 수 없다(서버 재시작으로 초기화). 시간을 크게 건너뛰면 그 사이의 구역 축소·미션 마감·재접속 유예 만료가 시간 순서대로 모두 적용되어 경기가 끝날 수 있다. 봇은 메모리에만 있어 서버 재시작 시 목록이 사라진다.

### 8-3. 앱 없이 PowerShell 로 같은 일 하기 (API 확인용)

사람 1명(스크립트) + 봇 3명으로 서버 API 를 직접 확인하는 예. 응답 필드는 `docs/api/API.md` 참고.

```powershell
$base = "http://localhost:8080"
$login = Invoke-RestMethod -Method Post "$base/api/auth/guest" -ContentType "application/json" -Body '{"nickname":"테스터"}'
$h = @{ Authorization = "Bearer $($login.accessToken)" }
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

# 1) 내 위치(MOCK) 한 번 전송 (서울시청 근처 예시 좌표)
$loc = @{ latitude = 37.5665; longitude = 126.9780; accuracyMeters = 5.0; measuredAtEpochMillis = $now; source = "MOCK" } | ConvertTo-Json
Invoke-RestMethod -Method Post "$base/api/locations" -Headers $h -ContentType "application/json" -Body $loc

# 2) 봇 3명 생성 + 나도 매칭 대기열 등록
Invoke-RestMethod -Method Post "$base/api/dev/bots" -Headers $h -ContentType "application/json" -Body '{"count":3}'
$join = @{ location = @{ latitude = 37.5665; longitude = 126.9780; accuracyMeters = 5.0; measuredAtEpochMillis = $now; source = "MOCK" } } | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post "$base/api/matchmaking" -Headers $h -ContentType "application/json" -Body $join

# 3) 경기 확인 → 서버 시간 30초 이동 → 다시 확인(PLAYING 이면 시작됨)
$game = Invoke-RestMethod "$base/api/games/current" -Headers $h
$game.status; $game.players | Format-Table nickname, role, status
Invoke-RestMethod -Method Post "$base/api/dev/clock/advance" -Headers $h -ContentType "application/json" -Body '{"millis":30000}'
(Invoke-RestMethod "$base/api/games/current" -Headers $h).status

# 4) 봇 목록과 도둑 봇의 QR 토큰 (사람의 QR 은 안 내려옴)
Invoke-RestMethod "$base/api/dev/games/$($game.gameId)/bots" -Headers $h | ConvertTo-Json -Depth 4

# 5) 30분 뒤로 시계를 돌려 도둑 승리 확인 (한 번에 최대 1시간)
Invoke-RestMethod -Method Post "$base/api/dev/clock/advance" -Headers $h -ContentType "application/json" -Body '{"millis":1800000}'
Invoke-RestMethod "$base/api/games/$($game.gameId)/result" -Headers $h | ConvertTo-Json -Depth 4
```

`ConvertTo-Json` 이 한글을 깨뜨릴 경우를 대비해 본문이 ASCII 인 JSON 문자열을 쓰는 것이 안전하다(위 예시는 닉네임만 한글). 위 스크립트는 문서 작성 중 **직접 실행하지는 않았다**(서버 통합 테스트 `onePhoneWithThreeBots_canPlayToTheEnd` 가 같은 흐름을 검증한다).

## 9. 자주 겪는 문제

| 증상 | 원인·해결 |
|---|---|
| 서버가 `환경 변수 PNT_DB_PASSWORD 가 설정되지 않았습니다` 로 종료 | `server/.env` 를 만들고 비밀번호를 채운다 |
| `DB 마이그레이션 실패` | DB 주소/사용자/비밀번호/DB 존재 여부 확인. 이미 적용된 `V1`/`V2` SQL 을 수정하면 체크섬 오류 → 수정하지 말고 새 `V3__…sql` 로 추가 |
| `환경 변수 PNT_GAME_… 의 값 '…' 형식이 올바르지 않습니다` | 해당 값의 형식(정수 초 등)을 `GAME_CONFIG.md` 로 확인 |
| `PNT_DEV_TOOLS=true 는 PNT_ALLOW_MOCK_LOCATIONS=true 와 DevClock 이 필요합니다` | 두 값을 함께 `true` 로 |
| 앱이 서버에 연결되지 않음 | 서버 주소(§5), 에뮬레이터는 `10.0.2.2`, 같은 Wi-Fi 는 방화벽 8080, 외부는 터널 URL 이 바뀌지 않았는지 |
| 매칭이 안 잡힘 | 4명 모두 500m 이내인지, 위치 정확도 100m 이하인지, 대기 중 위치를 60초 안에 계속 보내는지, 10분 만료 여부 |
| 위치 전송 403 | 운영 모드 서버에 MOCK 좌표를 보냄 → 개발 모드로 켜거나 실제 GPS 사용 |
| 감옥에서 수감이 안 됨 | 두 사람 모두 감옥(구역 중심) 10m 안 + 정확도 15m 이하 + 30초 이내 위치여야 함. `JAIL_CHECK_PENDING` 알림이 오면 GPS 를 다시 잡는다 |
| 한 명이 나가자 경기가 모두 종료됨 | 정상: 기권·이탈 3분 초과·실격은 경기 **전체 무효** |

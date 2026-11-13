# SERVER_SETUP — 서버 실행과 외부 접속 (Windows 기준)

## 1. PostgreSQL 실행 (셋 중 하나)

### 방법 A. Docker Desktop (선택)

```powershell
cd PoliceAndThief
$env:PNT_DB_PASSWORD = "원하는_비밀번호"
docker compose up -d            # postgres:17, 127.0.0.1:5432 에만 열림
docker compose ps
```
`server/.env` 의 `PNT_DB_PASSWORD` 를 같은 값으로 맞춘다. 데이터는 `police-thief-pgdata` 볼륨에 남는다(삭제: `docker compose down -v`).

### 방법 B. 설치형 PostgreSQL (Docker 불필요)

1. https://www.postgresql.org/download/windows/ 에서 설치 프로그램 실행(포트 5432, 슈퍼유저 비밀번호 기억).
2. 설치 시 "Stack Builder" 는 필요 없다. 방화벽에서 5432 를 외부에 열지 않는다.
3. "SQL Shell (psql)" 실행 후:
   ```sql
   CREATE USER police_thief WITH PASSWORD '원하는_비밀번호';
   CREATE DATABASE police_thief OWNER police_thief;
   ```

### 방법 C. PostgreSQL 없이 체험 (H2 파일 DB)

`server/.env` 에서 PostgreSQL 3줄 대신 H2 설정의 주석을 푼다. 실제 운영·팀 테스트는 PostgreSQL 을 쓴다(H2 는 PostgreSQL 호환 모드일 뿐 완전히 같지 않다).

## 2. 데이터베이스 생성 및 마이그레이션

- 테이블은 **서버 시작 시 Flyway 가 자동 생성**한다(`server/src/main/resources/db/migration/V1__initial_schema.sql`). 직접 CREATE TABLE 하지 않는다.
- 확인: psql 에서 `\c police_thief` → `\dt` → `users, auth_tokens, games, game_players, matchmaking_queue, player_locations, game_events, qr_tokens, flyway_schema_history`.
- 스키마 변경: 새 파일 `V2__설명.sql` 추가 후 서버 재시작. 적용된 파일은 수정 금지(체크섬 오류).

## 3. 서버 환경 변수

```powershell
copy server\.env.example server\.env
notepad server\.env
```

| 변수 | 기본값 | 설명 |
|---|---|---|
| `PNT_SERVER_HOST` | `0.0.0.0` | 0.0.0.0 이면 같은 Wi-Fi 기기에서 접속 가능 |
| `PNT_SERVER_PORT` | `8080` | |
| `PNT_DB_URL` | `jdbc:postgresql://localhost:5432/police_thief` | |
| `PNT_DB_USER` | `police_thief` | |
| `PNT_DB_PASSWORD` | (필수, 기본값 없음) | 비어 있으면 서버가 시작을 거부 |
| `PNT_DB_POOL_SIZE` | `10` | |
| `PNT_ALLOW_MOCK_LOCATIONS` | `false` | `true` 면 앱 테스트 좌표(source=MOCK) 허용 — 개발 서버에서만 |
| `PNT_TOKEN_TTL_DAYS` | `30` | 게스트 토큰 유효 기간 |
| `PNT_APP_VERSION` | `0.1.0` | health 응답 표시용 |

> `.env` 는 `gradlew :server:run` 할 때만 읽힌다. 이미 OS 환경 변수가 있으면 그 값이 우선한다.
> 주의: PowerShell 에서 `$env:PNT_DB_PASSWORD=""` 처럼 **빈 값을 넣으면 변수 자체가 삭제**된다. 빈 비밀번호가 필요하면 `.env` 파일에 `PNT_DB_PASSWORD=` 로 적는다.

## 4. 서버 실행

```powershell
.\gradlew.bat :server:run
```
- 확인: `http://localhost:8080/api/health` → `{"status":"UP","database":"UP",...}`
- 종료: 터미널에서 `Ctrl + C`.
- 배포용 실행 파일: `.\gradlew.bat :server:installDist` → `server\build\install\server\bin\server.bat` (이때는 `.env` 가 자동으로 읽히지 않으므로 환경 변수를 직접 설정).
- 콘솔 한글이 깨지면 `chcp 65001` 후 실행(서버는 UTF-8 로 출력하도록 설정됨).

## 5. Android 앱의 서버 주소 설정

우선순위: 환경 변수 `PNT_SERVER_URL` > `local.properties` 의 `pnt.serverUrl` > 기본값 `http://10.0.2.2:8080`.

| 상황 | 주소 | 비고 |
|---|---|---|
| 에뮬레이터 → 같은 PC 서버 | `http://10.0.2.2:8080` | 기본값 |
| USB 연결 실기기 | `http://localhost:8080` + `adb reverse tcp:8080 tcp:8080` | 방화벽 설정 불필요 |
| 같은 Wi-Fi 실기기 | `http://<PC IPv4>:8080` (`ipconfig` 로 확인) | Windows 방화벽 인바운드 8080 허용 필요 |
| 외부 네트워크 | `https://<임시>.trycloudflare.com` | 아래 Cloudflare Tunnel |

```properties
# PoliceAndThief/local.properties (커밋하지 않음)
pnt.serverUrl=https://abc-def-ghi.trycloudflare.com
```
- `local.properties` 변경 후에는 다시 빌드해야 한다. **개발(debug) 빌드는 앱 설정 → 개발자 테스트 모드 → 서버 주소 변경** 으로 재빌드 없이 바꿀 수 있다(비우면 빌드 기본값).
- debug 빌드만 `http://` 평문 통신을 허용한다. release 빌드는 `https://` 주소만 동작한다.

## 6. iOS 앱의 서버 주소 설정 (Mac)

`iosApp/Configuration/Config.xcconfig`:
```text
SERVER_BASE_URL = https:/$()/abc-def-ghi.trycloudflare.com
```
xcconfig 에서는 `//` 가 주석이라 `/$()/` 로 끊어 적는다. 개인 값은 같은 폴더의 `Local.xcconfig`(커밋 제외)에 적으면 덮어쓴다. 시뮬레이터에서 같은 Mac 의 서버는 `http:/$()/localhost:8080` (Info.plist 의 `NSAllowsLocalNetworking` 으로 허용).

## 7. 외부 네트워크 연결 — Cloudflare Tunnel

### 7-1. 실행 가이드 (Quick Tunnel, 계정 불필요)

```powershell
winget install --id Cloudflare.cloudflared      # 최초 1회
.\gradlew.bat :server:run                       # 터미널 1: 서버
cloudflared tunnel --url http://localhost:8080  # 터미널 2: 터널
```
출력에 나오는 `https://<임의 단어들>.trycloudflare.com` 을 앱 서버 주소로 넣는다. 확인: 휴대폰 브라우저에서 `https://…trycloudflare.com/api/health`. WebSocket 은 앱이 자동으로 `wss://…/ws` 로 접속한다.

### 7-2. 주의사항 (Cloudflare 문서 "Quick Tunnels", 2026-11-13 확인)

- Quick Tunnel 은 **테스트·개발용**이다. 운영에는 계정·도메인이 필요한 Named Tunnel 을 쓴다.
- **URL 을 아는 누구나 서버에 접근할 수 있다.** 서버는 토큰 인증·입력 검증을 하지만, 시연이 끝나면 터널을 끈다.
- `cloudflared` 를 종료하면 URL 이 사라지고, 다시 켜면 **새 URL 이 발급**된다 → 앱 설정(또는 `local.properties`)만 새 URL 로 바꾸면 된다.
- 가용성 보장이 없다. 동시 요청 수 등 추가 제한은 공식 문서의 Limitations 항목을 확인한다(이번 작업에서 상세 수치는 확인하지 못함).
- **DB 포트(5432)는 터널·방화벽 어디에도 공개하지 않는다.** 터널 대상은 `http://localhost:8080` 하나뿐이다.
- 서버의 `PNT_ALLOW_MOCK_LOCATIONS=true` 는 시연용이다. 외부 공개 시 테스트 좌표 위조가 가능하므로 실제 경기 테스트 때는 `false` 를 권장한다.

### 7-3. 고정 URL 이 필요하면

Cloudflare 계정 + 보유 도메인으로 Named Tunnel(`cloudflared tunnel create …`, `config.yml`, DNS route) 을 만든다. 도메인 비용이 들 수 있어 이번 구성에는 포함하지 않았다.

> 이번 작업 환경에는 `cloudflared` 가 설치되어 있지 않아 **터널 경유 접속은 실제로 실행하지 못했다(NOT RUN)**. 로컬 HTTP/WebSocket 동작만 확인했다.

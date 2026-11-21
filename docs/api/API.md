# API — REST / WebSocket 계약

> 이 문서는 서버 구현(`server/src/main/kotlin/com/nettwork/policethief/server/`)과 공유 계약(`shared/src/commonMain/kotlin/com/nettwork/policethief/shared/`)을 읽고 정리한 것이다. **문서와 코드가 다르면 코드가 기준**이다.
> 검증 범위: 서버 단위·통합 테스트(75개 통과 기준). 실제 휴대폰, iOS, Cloudflare Tunnel, GitHub Actions 에서 검증한 내용은 **없다(NOT VERIFIED)**.
> 게임 규칙의 "왜"는 [`docs/development/GAME_LOGIC.md`](../development/GAME_LOGIC.md), 설정값은 [`GAME_CONFIG.md`](../development/GAME_CONFIG.md), 테이블은 [`DATABASE.md`](../architecture/DATABASE.md) 를 본다.

## 0. 용어 (처음 보는 사람용)

- **REST API**: `GET/POST` 같은 HTTP 요청으로 서버에 묻고 시키는 방식. 요청 1번에 응답 1번.
- **WebSocket**: 한 번 연결해 두면 서버가 먼저 소식을 밀어 보내 줄 수 있는 통로(`/ws`). 체포 알림 같은 실시간 이벤트에 쓴다.
- **DTO**: 요청/응답 JSON 한 덩어리를 표현한 Kotlin 클래스. 서버와 앱이 `shared` 모듈의 같은 클래스를 쓴다.
- **Bearer 토큰**: 로그인하면 받는 긴 문자열. 이후 요청마다 `Authorization: Bearer <토큰>` 헤더로 보낸다.
- **epoch millis**: 1970-01-01 UTC 부터 지난 밀리초. 모든 시각 필드(`...EpochMillis`)가 이 형식이다.

## 1. 공통 규칙

- JSON 설정(`shared/.../api/ApiJson.kt`): 모르는 필드는 무시, `null` 인 필드는 **JSON 에서 생략**, 기본값도 내보냄, 다형 메시지는 `"type"` 필드로 구분.
- 시각: UTC epoch milliseconds. ID(`userId`, `gameId`): UUID 문자열. enum: 이름 그대로(`"POLICE"`).
- 인증: `Authorization: Bearer <accessToken>`. 토큰은 `POST /api/auth/guest` 응답에서만 받는다. 서버는 토큰 원문이 아니라 SHA-256 해시만 저장한다(`auth/AuthService.kt`). 유효기간 기본 30일(`PNT_TOKEN_TTL_DAYS`).
- 사용자는 항상 **토큰에서** 결정된다. 요청 본문에 userId 를 넣어 다른 사람인 척할 수 없다.
- 경로 상수는 `shared/.../api/ApiPaths.kt` 에 있고 서버·앱이 같이 쓴다.
- 시간 판정은 모두 **서버 시계**가 한다. 앱이 보내는 시각(`scannedAtEpochMillis` 등)은 판정에 쓰이지 않는다(위치의 `measuredAtEpochMillis` 는 예외 — 위치 판정과 순서 비교에 쓰인다).

### 오류 응답 형식 (`ErrorResponse`, `plugins/Plugins.kt` 의 `configureErrorHandling`)

```json
{ "code": "CONFLICT", "message": "이미 진행 중인 경기가 있습니다.", "details": { "reason": "ALREADY_IN_GAME" } }
```

| HTTP | `code` | 언제 | `details` |
|---|---|---|---|
| 400 | `BAD_REQUEST` | JSON 형식 오류, 필수 필드 누락 | 없음 |
| 401 | `UNAUTHORIZED` | 토큰이 없거나 틀림/만료 | 없음 |
| 403 | `FORBIDDEN` | 참가자 아님, 역할상 불가, MOCK 좌표 금지, 봇이 개발 도구 사용 | `reason` 또는 `source` |
| 404 | `NOT_FOUND` | 없는 경로(개발 API 꺼짐 포함), 없는 경기, 진행 중 경기 없음 | `reason`(일부) |
| 409 | `CONFLICT` | 현재 상태와 안 맞는 요청 | 항상 `reason` |
| 422 | `VALIDATION_FAILED` | 값 검증 실패(닉네임, 위치, requestId, UUID 형식 등) | 필드명 → 사유 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류(상세는 숨김) | 없음 |
| 503 | — | `/api/health` 만: DB 응답 없음(`HealthResponse` 형식) | — |

`ErrorCode.NOT_IMPLEMENTED` 값은 `shared` 에 남아 있지만 **현재 서버는 501 을 돌려주는 경로가 없다**(전부 구현됨).

### `details["reason"]` 값 모음 (409/403/404)

| reason | HTTP | 발생 위치 |
|---|---|---|
| `ALREADY_IN_GAME` | 409 | `POST /api/matchmaking` — 이미 READY/PLAYING 경기에 참가 중 |
| `ALREADY_MATCHED` | 409 | `DELETE /api/matchmaking/me` — 이미 매칭 확정(취소 불가, 기권 사용) |
| `GAME_ENDED` | 409 | `GET .../locations`, `GET .../qr/me` — 종료된 경기 |
| `NO_ACTIVE_GAME` | 404 | `GET /api/games/current` |
| `NOT_A_PARTICIPANT` | 403 | 경기 API 전부, `GET /api/dev/games/{id}/bots` |
| `POLICE_HAS_NO_QR` | 403 | `GET .../qr/me` — 경찰은 QR 이 없음 |
| `NO_ANCHOR_LOCATION` | 409 | (개발) `POST /api/dev/bots` — 기준 좌표 없음 |
| `BOT_NOT_IN_GAME` | 409 | (개발) 봇 스캔 — 봇이 경기 중이 아님 |
| (details.source = `MOCK_LOCATION_NOT_ALLOWED`) | 403 | MOCK 좌표를 MOCK 비허용 서버에 보냄 |

---

## 2. 엔드포인트 상세

### 2-1. `GET /api/health` — 서버 상태 (인증 불필요)

- 200: `HealthResponse { status:"UP", database:"UP", version, serverTimeEpochMillis }`
- DB 가 응답하지 않으면 **503** + `status:"DEGRADED"`, `database:"DOWN"`.
- `version` 은 환경 변수 `PNT_APP_VERSION`(기본 `0.2.0`).

### 2-2. `POST /api/auth/guest` — 게스트 로그인 (인증 불필요)

- 요청 `GuestLoginRequest { nickname }`. 규칙(`shared/rules/NicknamePolicy.kt`): 앞뒤 공백 제거 후 2~12자, 한글 완성형·영문·숫자·`_` 만.
- 201 `GuestLoginResponse { accessToken, tokenType:"Bearer", user: UserDto }`
- 422 `details.nickname` = `BLANK | TOO_SHORT | TOO_LONG | INVALID_CHARACTERS`. 400: JSON 오류.
- 같은 닉네임이어도 **매번 새 계정·새 토큰**이다(닉네임은 표시 이름일 뿐).

### 2-3. `GET /api/users/me`

- 200 `UserDto { userId, nickname, createdAtEpochMillis, lastActiveAtEpochMillis }`. 401. 요청할 때마다 마지막 활동 시각이 갱신된다.

### 2-4. `POST /api/locations` — 내 위치 전송

- 요청 `LocationUpdateRequest { latitude, longitude, accuracyMeters, measuredAtEpochMillis, source = "GPS" }`
  - `source`: `GPS`(기본) 또는 `MOCK`(테스트 좌표 — 서버가 `PNT_ALLOW_MOCK_LOCATIONS=true` 일 때만 허용).
- 201 `LocationDto { userId, latitude, longitude, accuracyMeters, measuredAtEpochMillis, receivedAtEpochMillis, source }`
- 서버 검증(`LocationService.validate` → `shared/rules/LocationValidator.kt`):
  - 위도 −90~90, 경도 −180~180, 정확도 0~1000m, 측정 시각이 서버보다 60초 넘게 미래이거나 5분보다 오래됐으면 거부.
  - 422 `details` 키(값은 `"invalid"`): `LATITUDE_OUT_OF_RANGE`, `LONGITUDE_OUT_OF_RANGE`, `ACCURACY_INVALID`, `MEASURED_IN_FUTURE`, `MEASUREMENT_TOO_OLD`.
  - 403 `details.source = MOCK_LOCATION_NOT_ALLOWED` (운영 모드에서 MOCK).
- 처리(`GameEngine.ingest`): 위치를 DB 에 **항상 저장**한 뒤
  - 경기 중이면 과속 → 구역 → 감옥 → 미션 판정을 하고 공개 범위에 맞는 참가자에게 `player_location` 을 보낸다.
  - 매칭 대기 중이면 대기열 위치를 갱신하고 매칭을 다시 시도한다(정확도가 `PNT_GAME_MATCH_MAX_ACCURACY_M` 보다 나쁘면 대기열은 갱신하지 않는다).
  - 응답은 판정 결과와 무관하게 201 이다(중복·순서 역전 표본도 저장은 하고 판정만 건너뜀 → GAME_LOGIC.md).

### 2-5. `GET /api/locations/me/latest`

- 200 `LocationDto`(내 마지막 저장 위치), 없으면 404.

### 2-6. 매칭

`POST /api/matchmaking` 요청 `JoinMatchmakingRequest { location: LocationUpdateRequest }` → 201 `MatchmakingTicketDto`

| 필드 | 설명 |
|---|---|
| `userId`, `status` | `IDLE`(대기열에 없음) / `WAITING` / `MATCHED` / `CANCELLED` / `EXPIRED` |
| `joinedAtEpochMillis`, `expiresAtEpochMillis` | IDLE 이면 없음. 만료 = 등록 후 10분 |
| `nearbyWaitingCount` | 내 반경 500m 안에서 기다리는 사람 수(나 포함). 타인 좌표는 절대 안 내려감 |
| `gameId` | `MATCHED` 일 때만 |
| `serverTimeEpochMillis` | 서버 시각(남은 시간 계산용) |

오류:
- 403 MOCK 비허용, 409 `ALREADY_IN_GAME`
- 422: 위치 검증 오류 키 / `details.accuracyMeters = ACCURACY_TOO_LOW`(정확도가 `PNT_GAME_MATCH_MAX_ACCURACY_M`, 기본 100m 초과) / `details.measuredAtEpochMillis = LOCATION_TOO_OLD`(측정 후 `PNT_GAME_MATCH_LOCATION_MAX_AGE_SECONDS`, 기본 60초 초과)
- 이미 대기 중인데 다시 요청하면 새 대기를 만들지 않고 **위치만 갱신**하며 처음 만료 시각을 유지한다(그래도 응답은 201).
- 4명이 모이면 이 요청 안에서 곧바로 `MATCHED` 가 될 수 있다.

`GET /api/matchmaking/me` → 200 `MatchmakingTicketDto`. 10분이 지난 대기는 이 호출에서도 `EXPIRED` 로 정리된다. 끝난 경기의 `MATCHED` 기록은 `IDLE` 로 보인다.

`DELETE /api/matchmaking/me` → **204**. 여러 번 보내도 결과가 같다(멱등). 대기 중이면 `CANCELLED`. 이미 `MATCHED` 이고 경기가 진행 중이면 **409 `ALREADY_MATCHED`** → 경기에서 나가려면 `forfeit`.

### 2-7. 경기 조회

모든 경기 조회는 요청 처리 **직전에** 지난 예정 판정(시작·종료·구역 축소 등)을 먼저 적용하므로 응답은 항상 최신이다(예외: `events`).

| 엔드포인트 | 응답 | 비고 |
|---|---|---|
| `GET /api/games/current` | `GameDto` | 내 진행 중 경기. 없으면 404 `NO_ACTIVE_GAME` |
| `GET /api/games/{gameId}` | `GameDto` | 참가자만. 비참가자 403 |
| `GET /api/games/{gameId}/state` | `GameStateDto { game, locations, recentEvents, lastEventId }` | 재접속용 한 번에 받기. `recentEvents` = 내가 볼 수 있는 최근 30개, 종료된 경기의 `locations` 는 빈 배열 |
| `GET /api/games/{gameId}/locations` | `VisibleLocationsResponse { gameId, locations, serverTimeEpochMillis }` | 경찰: 전원 / 도둑: 도둑끼리(본인 포함). 종료된 경기는 **409 `GAME_ENDED`** |
| `GET /api/games/{gameId}/events?after=N` | `GameEventDto[]` | `eventId > N` 인 것만, 오름차순, 최대 200개. `after` 가 숫자가 아니거나 음수면 422(`details.after`). 이 API 만 시간 판정을 먼저 돌리지 않고 DB 기록을 그대로 읽는다(스케줄러가 약 1초 간격으로 반영) |
| `GET /api/games/{gameId}/result` | `GameResultDto` | 진행 중이면 `result=null`. 플레이어별 `capturesMade`, `rescuesMade`, `timesCaptured`, `missionsCompleted`, `speedWarnings`, `finalStatus`. 누적 점수·랭킹은 없음 |
| `GET /api/games/{gameId}/qr/me` | `QrTokenDto { gameId, userId, token, issuedAtEpochMillis, expiresAtEpochMillis? }` | **도둑만**. 경찰 403 `POLICE_HAS_NO_QR`, 종료 후 409 `GAME_ENDED`. 토큰은 경기 동안 고정이고 `PNT1.` 로 시작한다 |

`GameDto` 에서 역할에 따라 달라지는 부분: `missions` 는 **도둑에게만** 내려가고 경찰에게는 빈 배열이다. 플레이어 목록(`players`)의 `status`, `capturedBy`, `escortingUserId`, `warningCount`, `connectionState` 는 참가자 4명 모두에게 보인다(좌표는 아님). QR 토큰은 `GameDto`·`GameStateDto`·이벤트 어디에도 들어가지 않는다(테스트로 확인).

`GameDto.version` 은 서버가 상태를 바꿀 때마다 1씩 증가한다. 앱은 더 작은 `version` 의 응답을 무시해 순서 역전을 막는다.

### 2-8. 체포·구출·기권

**`POST /api/games/{gameId}/captures`** (경찰), **`POST /api/games/{gameId}/rescues`** (미체포 도둑)

요청 `QrScanRequest { token, requestId, scannedAtEpochMillis }`
- `token`: 대상 도둑의 QR 에 담긴 문자열(서버가 DB 에서 경기·소유자를 찾는다. QR 내용은 믿지 않는다).
- `requestId`: 앱이 만든 고유 ID(1~64자). **같은 사용자가 같은 `requestId` 를 다시 보내면 판정하지 않고 처음 결과를 그대로 돌려준다** → 네트워크가 끊겨 재전송해도 이중 체포가 없다. 결과는 DB `scan_requests` 에 저장된다(거절 결과도 저장). 다른 경기에서 쓴 `requestId` 를 재사용하면 422 `details.requestId = REUSED`, 비었거나 64자 초과는 422 `INVALID`.
- `scannedAtEpochMillis`: 서버가 판정에 쓰지 않는다(서버 시계 기준).

응답 200 `QrScanResultDto { accepted, targetUserId?, targetNickname?, targetStatus?, rejectReason?, gameStatus?, gameResult? }`
- 게임 규칙상 불가(거절)는 **HTTP 200 + `accepted:false` + `rejectReason`** 이다. HTTP 오류는 인증(401)·경기 없음(404)·비참가자(403)·형식 오류(422)일 때만.
- 거절 사유(`ScanRejectReason`)와 검사 순서는 GAME_LOGIC.md 의 표를 본다: `GAME_NOT_PLAYING`, `NOT_ALLOWED`, `RESCUER_NOT_ACTIVE`, `INVALID_TOKEN`, `OTHER_GAME`, `SELF_SCAN`, `ALREADY_CAPTURED`, `ESCORT_LIMIT`, `TARGET_ESCORTED`, `TARGET_NOT_JAILED`.
- 마지막 도둑을 체포하면 응답에 `gameStatus:"FINISHED"`, `gameResult:"POLICE_WIN"` 이 들어간다.
- 제한 시간(`endsAt`)과 **정확히 같은 시각**에 도착한 체포는 거절된다(`GAME_NOT_PLAYING`, 도둑 승리가 먼저 확정) — GAME_LOGIC.md "경계 시각 정책".

**`POST /api/games/{gameId}/forfeit`** → 200 `GameDto`. 기권 즉시 경기 `CANCELLED`, `result=INVALID`, `endReason=PLAYER_FORFEITED`. 이미 끝난 경기에서는 변화 없이 현재 상태를 돌려준다. 준비시간(READY) 중에도 기권할 수 있다.

**`POST /api/games/{gameId}/ring`** — "도둑 폰 울리기"(**기본 꺼짐**, `PNT_FEATURE_RING_THIEF_PHONE=true` 일 때만). 요청 `RingThiefRequest { targetUserId }`, 응답 200 `RingThiefResultDto { accepted, rejectReason?, nextAvailableAtEpochMillis? }`. 거절도 200 이며 `rejectReason` 은 문자열: `FEATURE_DISABLED`, `GAME_NOT_PLAYING`, `NOT_ALLOWED`(경찰이 아니거나 활동 중이 아님), `TARGET_NOT_ACTIVE_THIEF`, `COOLDOWN`. `targetUserId` 가 UUID 가 아니면 422. UI 설계안에서 나온 아이디어이며 **확정된 요구사항이 아니다.**

---

## 3. WebSocket `GET /ws`

- 업그레이드 요청에 `Authorization: Bearer <token>` 필요. 없거나 틀리면 핸드셰이크 단계에서 거부된다(401, 테스트에서는 연결 실패로 확인).
- 외부 접속은 `wss://<터널 주소>/ws`(암호화된 WebSocket).
- Ktor 설정(`configureWebSockets`): 서버가 15초마다 ping, 30초 응답이 없으면 종료, 프레임 최대 64KB.
- **단일 활성 세션**: 같은 사용자가 새로 접속하면 이전 연결에 `session_replaced` 를 보내고 닫는다. 앱은 자동 재연결을 멈추고 사용자에게 알려야 한다.
- **재접속 유예 3분**: 연결이 끊기면(새 연결로 대체된 경우 제외) 경기 중인 사용자는 `RECONNECTING` 이 되고 `PLAYER_DISCONNECTED` 이벤트가 전체에 간다. 3분 안에 다시 `/ws` 에 연결하면 `PLAYER_RECONNECTED`, 못 하면 경기 무효 → GAME_LOGIC.md.
- 연결이 성립하면 서버는 `presence.onUserConnected` 로 재접속 처리를 한 뒤 `welcome` 을 보낸다.

### 서버 → 클라이언트 (`ServerMessage`, JSON 의 `type`)

| `type` | 필드 | 언제 |
|---|---|---|
| `welcome` | `userId`, `serverTimeEpochMillis`, `heartbeatIntervalMillis`(=15000), `activeGameId?` | 연결 직후 1회. `activeGameId` 가 있으면 앱은 `GET /api/games/{id}/state` 로 상태를 다시 맞춘다 |
| `pong` | `clientTimeEpochMillis`, `serverTimeEpochMillis` | `ping` 의 응답 |
| `test_event` | `sequence`, `message`, `serverTimeEpochMillis` | `request_test_event` 의 응답 |
| `matchmaking_update` | `event: MatchmakingEventType`, `ticket: MatchmakingTicketDto` | 매칭 상태 변화. `event` = `MATCHING_UPDATED`(주변 인원 수 변화), `MATCHING_COMPLETED`, `MATCHING_TIMEOUT`(10분 만료), `MATCHING_CANCELLED` |
| `game_event` | `event: GameEventDto` | 경기 이벤트(아래). 받는 사람의 역할에 맞게 서버가 걸러 보낸다 |
| `player_location` | `gameId`, `location: LocationDto` | 공개가 허용된 참가자의 새 위치. 저장되지 않는 일시 메시지 → 놓치면 `GET .../locations` |
| `session_replaced` | `message` | 다른 연결에 대체됨. 직후 서버가 연결을 닫는다 |
| `error` | `code`, `message` | 알 수 없는 클라이언트 메시지(`BAD_REQUEST`). 연결은 유지된다 |

### 클라이언트 → 서버 (`ClientMessage`)

| `type` | 필드 | 응답 |
|---|---|---|
| `ping` | `clientTimeEpochMillis` | `pong` |
| `request_test_event` | `message` | `test_event` |

매칭 확정 시 `MATCHING_COMPLETED` 와 `GAME_PREPARING` 이벤트(`eventId` 가 있는 저장 이벤트)가 4명에게 함께 간다. 위치·체포 같은 **경기 조작은 WebSocket 이 아니라 REST** 로 한다(WebSocket 은 서버 → 앱 알림이 주 용도).

---

## 4. 이벤트 (`GameEventDto`)

```json
{ "eventId": 17, "gameId": "…", "type": "THIEF_ARRESTED", "gameVersion": 5,
  "data": { "userId": "…", "nickname": "도둑1", "byUserId": "…", "byNickname": "경찰1" },
  "createdAtEpochMillis": 1794565700000 }
```

| 필드 | 설명 |
|---|---|
| `eventId` | 저장된 이벤트의 증가 번호(전체 순서 보장). **0 이면 저장하지 않는 일시 이벤트**(`JAIL_CHECK_PENDING`) |
| `gameId` | 경기 ID |
| `type` | `GameEventType` |
| `gameVersion` | 이 이벤트가 반영된 뒤의 경기 버전 |
| `data` | 이벤트별 정보. 키는 `GameEventKeys`(아래). **키가 없을 수 있으니 항상 null 을 고려**한다 |
| `createdAtEpochMillis` | 이벤트가 일어난 시각(서버 시계) |

재접속 후 누락 복구: 마지막으로 받은 `eventId` 를 기억했다가 `GET /api/games/{id}/events?after=<eventId>`.

### `data` 키 (`shared/.../model/GameEnums.kt` 의 `GameEventKeys`)

| 키 | 의미 |
|---|---|
| `userId`, `nickname` | 이벤트의 **대상**(체포된 도둑, 경고 받은 사람 등) |
| `byUserId`, `byNickname` | 이벤트를 **일으킨 사람**(체포한 경찰, 구출한 도둑, 미션 완료자) |
| `radiusMeters` | 구역·미션 반경(m) |
| `atEpochMillis` | 이벤트와 관련된 시각(경기 시작 시각, 마감·축소·유예 만료 시각 등 — 이벤트별로 아래 표) |
| `warningCount`, `speedKmh` | 과속 경고 누적 횟수, 계산된 속도 |
| `missionId` | 미션 ID |
| `result`, `reason` | 승패(`GameResult`), 종료/실격 사유(`GameEndReason`) |
| `stage` | 구역 축소 단계 번호(1부터) |
| `distanceMeters` | 구역 중심까지 거리 |
| `accuracyMeters` | 요구되는 GPS 정확도 상한 |

### 이벤트 종류별 공개 범위와 data (서버 `game/rules/*.kt` 기준)

공개 범위(`Visibility`): `ALL`=참가자 4명 전원, `POLICE`=경찰만, `THIEF`=도둑만, `USER`=지정한 1명. 저장된 이벤트는 `GET .../events` 에서도 **같은 규칙으로 걸러진다**(내 역할의 이벤트 + `ALL` + 나에게 온 `USER`).

| 이벤트 | 공개 | `data` 키 | 설명 |
|---|---|---|---|
| `GAME_PREPARING` | ALL | `atEpochMillis`=시작 예정 | 매칭 확정(역할 공개 + 30초 준비) |
| `GAME_STARTED` | ALL | `atEpochMillis`=시작 시각 | 준비 끝, PLAYING |
| `PLAYER_DISCONNECTED` | ALL | `userId`,`nickname`,`atEpochMillis`=재접속 유예 만료 시각 | 연결 끊김(3분 유예 시작) |
| `PLAYER_RECONNECTED` | ALL | `userId`,`nickname` | 재접속 |
| `THIEF_ARRESTED` | ALL | `userId`,`nickname`,`byUserId`,`byNickname` | 체포(호송 시작) |
| `THIEF_JAILED` | ALL | 위와 같음 | 감옥 도착(수감) |
| `THIEF_RESCUED` | ALL | `userId`,`nickname`(구출된 도둑),`byUserId`,`byNickname`(구출한 도둑) | 구출 |
| `JAIL_CHECK_PENDING` | USER(호송 중인 경찰·도둑 각각), **일시**(`eventId=0`) | `userId`,`nickname`,`accuracyMeters` | 감옥 안으로 보이나 GPS 정확도·최신성 부족으로 수감 보류 |
| `ZONE_SHRINK_WARNING` | ALL | `stage`,`radiusMeters`(축소 후 반경),`atEpochMillis`=축소 시각 | 축소 예고(기본 60초 전) |
| `ZONE_SHRUNK` | ALL | `stage`,`radiusMeters` | 축소 적용 |
| `ZONE_OUT_WARNING` | USER(본인) | `userId`,`nickname`,`distanceMeters`,`radiusMeters`,`atEpochMillis`=실격 예정 시각(실격 판정이 꺼져 있으면 없음) | 구역 이탈 경고 |
| `ZONE_RETURNED` | USER(본인) | `userId`,`nickname` | 구역 복귀 |
| `MISSION_STARTED` | THIEF | `missionId`,`radiusMeters`,`atEpochMillis`=마감 시각 | 미션 시작(좌표는 `GameDto.missions` 로 도둑에게만) |
| `MISSION_COMPLETED` | THIEF | `missionId`,`byUserId`,`byNickname` | 미션 성공 |
| `MISSION_FAILED` | THIEF | `missionId` | 미션 실패(다음 구역 축소가 앞당겨짐) |
| `SPEED_WARNING` | ALL | `userId`,`nickname`,`warningCount`,`speedKmh` | 과속 경고(3회면 실격) |
| `PLAYER_ELIMINATED` | ALL | `userId`,`nickname`,`reason` | 실격/이탈 확정(`reason` = `GameEndReason`) |
| `PLAYER_FORFEITED` | ALL | `userId`,`nickname` | 명시적 기권 |
| `GAME_FINISHED` | ALL | `result`,`reason`,`atEpochMillis` | 정상 종료(`POLICE_WIN`/`THIEF_WIN`) |
| `GAME_INVALIDATED` | ALL | `result`(=`INVALID`),`reason`,`atEpochMillis` | 무효 종료 |
| `THIEF_PHONE_RING` | USER(대상 도둑) | `userId`,`nickname`,`byUserId`,`byNickname` | 폰 울리기(기능 켜짐 시) |
| `PLAYER_LOCATION_UPDATED` | — | — | enum 에는 있으나 **서버가 쓰지 않는다**. 위치는 `player_location` 메시지로 간다 |

주의: `SPEED_WARNING`, `PLAYER_DISCONNECTED` 등 `ALL` 이벤트에는 좌표는 없지만 이름·상태는 상대 팀에게도 보인다.

### 위치 공개 규칙 (REST `locations` 와 WebSocket `player_location` 공통)

| 보는 사람 | 볼 수 있는 위치 |
|---|---|
| 경찰 | 경찰 + 도둑 전원(본인 포함) |
| 도둑 | 도둑끼리만(본인 포함). **경찰 위치는 어떤 경로로도 안 내려간다** |
| 경기 종료 후 | 아무도 다른 참가자 위치를 볼 수 없다(409) |

`player_location` 은 위치를 보낸 사람 본인에게는 되돌려 보내지 않는다. 매칭 대기 중에는 다른 사람 좌표가 내려가지 않고 `nearbyWaitingCount` 만 있다.

---

## 5. 멱등성(같은 요청을 두 번 보내도 안전한가)

| 요청 | 동작 |
|---|---|
| `POST .../captures`, `.../rescues` | `(userId, requestId)` 가 같으면 **처음 판정 결과를 그대로 반환**(DB `scan_requests` 의 기본키 `(user_id, request_id)`). 다른 requestId 로 같은 도둑을 동시에 체포하면 한 건만 성공(나머지 `ALREADY_CAPTURED`) |
| `DELETE /api/matchmaking/me` | 여러 번 보내도 같은 결과(204). 확정 후에는 409 |
| `POST /api/matchmaking` | 대기 중 재요청은 위치만 갱신하고 같은 대기를 이어간다 |
| `POST .../forfeit` | 이미 끝난 경기는 변화 없음 |
| `POST /api/locations` | 저장은 매번 한다. 판정은 이미 받은 것과 **같거나 이른 `measuredAtEpochMillis`** 면 건너뜀(순서 역전·중복 수신 방지) |

---

## 6. 상태 enum 정리 (`shared/.../model/GameEnums.kt`)

| enum | 값 |
|---|---|
| `GameStatus` | `READY`(=PREPARING) → `PLAYING` → `FINISHED` / `CANCELLED`(=INVALID, 결과는 항상 `INVALID`) |
| `PlayerStatus` | `ACTIVE`, `ESCORTED`(호송 중), `JAILED`(수감), `ELIMINATED`(실격) |
| `PlayerConnectionState` | `CONNECTED`, `RECONNECTING`, `LEFT` |
| `GameResult` | `POLICE_WIN`, `THIEF_WIN`, `INVALID` |
| `GameEndReason` | `ALL_THIEVES_CAPTURED`, `TIME_OVER_THIEVES_SURVIVED`, `PLAYER_FORFEITED`, `PLAYER_DISCONNECT_TIMEOUT`, `PLAYER_DISQUALIFIED_SPEEDING`, `PLAYER_DISQUALIFIED_ZONE_OUT` |
| `MatchmakingStatus` | `IDLE`, `WAITING`, `MATCHED`, `CANCELLED`, `EXPIRED`(=TIMED_OUT) |
| `MissionStatus` | `ACTIVE`, `COMPLETED`, `FAILED`, `CANCELLED` |
| `LocationSource` | `GPS`, `MOCK` |

---

## 7. 개발자 API (`/api/dev/...`, `PNT_DEV_TOOLS=true` 일 때만)

- 서버 환경 변수 `PNT_DEV_TOOLS=true` 이고 **`PNT_ALLOW_MOCK_LOCATIONS=true` 도 함께** 켜야 서버가 시작된다(둘 중 하나가 빠지면 시작 거부 — `ServerDependencies.create` 의 `require`).
- 꺼져 있으면 이 경로들은 등록되지 않아 **404**(`devApis_doNotExist_whenDisabled` 테스트). 인증은 일반 Bearer 토큰.
- 켜면 서버 시계가 `DevClock`(실제 시간 + 앞으로 감은 시간)이 된다. 게임 판정 코드는 이 시계를 일반 시계와 똑같이 쓰므로 **판정을 우회하지 않는다**.
- **운영/실경기 서버에서는 반드시 꺼 둔다.** 켜져 있으면 서버가 시작 로그에 경고를 남긴다.
- 봇 목록은 메모리에만 있다 → 서버를 재시작하면 봇 목록이 사라진다(DB 에 봇 계정은 남음).
- 봇 계정(개발 도구로 만든 계정)의 토큰으로는 개발 API 를 쓸 수 없다(403). 봇이 아닌 계정을 봇처럼 조작하는 것도 막혀 있다(404 "봇이 아님").

| 메서드 · 경로 | 요청 | 응답 | 설명·오류 |
|---|---|---|---|
| `GET /api/dev/status` | — | 200 `DevStatusDto { enabled, clockOffsetMillis, serverTimeEpochMillis }` | 개발 모드 여부와 시계 이동량 |
| `GET /api/dev/clock` | — | 200 `DevStatusDto` | 위와 같음 |
| `POST /api/dev/clock/advance` | `DevClockAdvanceRequest { millis }` | 200 `DevStatusDto` | 서버 시계를 `millis` 만큼 앞으로. **1ms ~ 1시간**(3,600,000)만 허용, 벗어나면 422. 이동 직후 서버가 `tick()` 을 한 번 실행해 지난 판정을 즉시 적용한다. 되돌릴 수 없다(서버 재시작으로 초기화) |
| `POST /api/dev/bots` | `DevCreateBotsRequest { count=3, latitude?, longitude?, spreadMeters=40.0 }` | 201 `DevBotsResponse { bots: [DevBotDto] }` | 봇(`봇N` 닉네임)을 만들어 MOCK 좌표로 **매칭 대기열에 넣는다**. `count` 1~3(아니면 422), `spreadMeters` 0~200(아니면 422). 좌표를 안 주면 내 마지막 위치를 기준으로 하며, 없으면 409 `NO_ANCHOR_LOCATION`. 봇은 기준점 주위 원 위에 균등 배치 |
| `POST /api/dev/bots/{botId}/location` | `DevBotLocationRequest { latitude, longitude, accuracyMeters=5.0 }` | 204 | 봇 위치 이동(MOCK, 서버 시계의 현재 시각으로 측정). 일반 위치 판정을 모두 거침 |
| `POST /api/dev/bots/{botId}/scan` | `DevBotScanRequest { token }` | 200 `QrScanResultDto` | 봇이 QR 스캔: 봇이 경찰이면 체포, 도둑이면 구출. 봇이 경기에 없으면 409 `BOT_NOT_IN_GAME` |
| `POST /api/dev/bots/{botId}/presence` | `DevBotPresenceRequest { connected }` | 204 | 봇의 연결 끊김/재접속 흉내(3분 유예 테스트) |
| `GET /api/dev/games/{gameId}/bots` | — | 200 `DevBotsResponse` | 내가 참가한 경기의 봇 목록(`role`, `status`). **도둑 봇의 `qrToken` 만** 포함, 사람 참가자의 QR 은 개발 모드에서도 안 내려준다. 비참가자 403 |

MOCK 좌표는 개발 서버(`PNT_ALLOW_MOCK_LOCATIONS=true`)에서 **과속 판정에서 제외**된다(GAME_LOGIC.md). 운영(`false`)에서는 MOCK 이 아예 거부되므로 예외가 존재하지 않는다.

사용 순서 예(휴대폰 1대 + 봇 3): GAME_LOGIC.md 와 `docs/development/RUN_GUIDE.md` 의 "1대 테스트" 절 참고.

---

## 8. 문서화하며 확인한 한계

- 앱이 프로세스 종료·정지 상태일 때 서버가 깨우는 푸시 알림(FCM/APNs)은 **없다**(`notification/NotificationService.kt`). 연결이 살아 있는 동안만 WebSocket 으로 알리고, 재접속하면 `events?after=` 로 따라잡는다.
- `heartbeatIntervalMillis` 는 `GameRules.LOCATION_UPDATE_INTERVAL_MILLIS`(15초) 값을 그대로 보낸다. 이름은 하트비트지만 사실상 "위치 전송 목표 주기"와 같은 숫자다.

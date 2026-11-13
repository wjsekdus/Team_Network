# API — REST / WebSocket 계약

- 계약 코드: `shared/src/commonMain/kotlin/com/nettwork/policethief/shared/` (`api/ApiPaths.kt`, `dto/*.kt`, `model/GameEnums.kt`). **서버와 앱이 같은 코드를 쓰므로 이 문서와 코드가 다르면 코드가 기준**이다.
- JSON 설정(`ApiJson`): 알 수 없는 필드는 무시, null 필드는 생략, sealed 메시지 종류는 `"type"` 필드.
- 시각: UTC epoch milliseconds(`...EpochMillis`). ID: UUID 문자열. enum: 이름 그대로(`"POLICE"`).
- 인증: `Authorization: Bearer <accessToken>`. 토큰은 `POST /api/auth/guest` 응답에서만 받는다.

## 공통 오류 응답

```json
{ "code": "VALIDATION_FAILED", "message": "위치 데이터가 올바르지 않습니다.", "details": { "LATITUDE_OUT_OF_RANGE": "invalid" } }
```

| HTTP | code | 상황 |
|---|---|---|
| 400 | `BAD_REQUEST` | JSON 형식 오류, 필드 누락 |
| 401 | `UNAUTHORIZED` | 토큰 없음·위조·만료·폐기 |
| 403 | `FORBIDDEN` | 권한 없음 (예: MOCK 위치 금지 `details.source=MOCK_LOCATION_NOT_ALLOWED`) |
| 404 | `NOT_FOUND` | 경로 없음, 데이터 없음 |
| 409 | `CONFLICT` | (예정) 이미 대기 중, 이미 처리된 스캔 |
| 422 | `VALIDATION_FAILED` | 값 검증 실패, `details` 에 필드/사유 |
| 501 | `NOT_IMPLEMENTED` | 계약만 있고 서버 구현 전 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류(상세 미노출) |

---

## A. 구현된 API (서버 테스트·실서버 호출로 확인)

### A-1. `GET /api/health` — 서버 상태 (인증 없음)

응답 200 (DB 장애 시 503, `status:"DEGRADED"`, `database:"DOWN"`)
```json
{ "status": "UP", "database": "UP", "version": "0.1.0", "serverTimeEpochMillis": 1794565637974 }
```
용도: 홈·설정 화면의 서버 연결 표시.

### A-2. `POST /api/auth/guest` — 게스트 로그인 (인증 없음)

요청
```json
{ "nickname": "경찰1호" }
```
응답 201
```json
{
  "accessToken": "-M5DyMYD6eygjp_vU8nHXPxGP6MbZhseWYMWgyc6IYY",
  "tokenType": "Bearer",
  "user": { "userId": "66f005b3-…", "nickname": "경찰1호", "createdAtEpochMillis": 1794565638083, "lastActiveAtEpochMillis": 1794565638083 }
}
```
오류: 422 `details.nickname` = `BLANK | TOO_SHORT | TOO_LONG | INVALID_CHARACTERS`, 400 JSON 오류.
규칙: 앞뒤 공백 제거 후 2~12자, 한글·영문·숫자·`_`. **같은 닉네임이어도 매번 새 계정·새 토큰**(닉네임으로 인증하지 않음).

### A-3. `GET /api/users/me` — 내 정보 (인증 필요)

응답 200: `UserDto` (위 `user` 와 같은 형식). 오류: 401.
용도: 앱 시작 시 세션 복구, 홈 사용자 정보. 호출할 때마다 `last_active_at` 갱신.

### A-4. `POST /api/locations` — 위치 전송 (인증 필요)

요청 (`userId` 는 보내지 않는다 — 토큰에서 결정되므로 다른 사람 위치를 대신 보낼 수 없다)
```json
{ "latitude": 37.5665, "longitude": 126.978, "accuracyMeters": 5.0, "measuredAtEpochMillis": 1794565637794, "source": "GPS" }
```
- `source`: `GPS`(기본값, 생략 가능) | `MOCK`(개발자 테스트 좌표)

응답 201: `LocationDto`
```json
{ "userId": "…", "latitude": 37.5665, "longitude": 126.978, "accuracyMeters": 5.0, "measuredAtEpochMillis": 1794565637794, "receivedAtEpochMillis": 1794565638836, "source": "GPS" }
```
검증(`shared/rules/LocationValidator`, 서버가 다시 확인): 위도 −90~90, 경도 −180~180, 정확도 0~1000m, 측정 시각이 서버 기준 60초 이상 미래이거나 5분 이상 과거면 거부.
오류: 401, 403(MOCK 비허용 서버), 422(`details` 키: `LATITUDE_OUT_OF_RANGE`, `LONGITUDE_OUT_OF_RANGE`, `ACCURACY_INVALID`, `MEASURED_IN_FUTURE`, `MEASUREMENT_TOO_OLD`).
범위: 저장까지만. 매칭 반경 계산·경기별 공개 범위·구역 이탈·과속 판정은 TODO.

### A-5. `GET /api/locations/me/latest` — 내 최신 위치 (인증 필요)

응답 200 `LocationDto`, 저장된 위치가 없으면 404. 본인 것만 조회된다.

### A-6. `GET /ws` — WebSocket (인증 필요)

- 업그레이드 요청에 `Authorization: Bearer <token>` 헤더 필수. 없거나 틀리면 **401 로 핸드셰이크 거부**.
- 외부 접속은 `wss://<터널 주소>/ws`.
- 서버 ping 15초, 응답 없으면 30초 후 종료. 프레임 최대 64KB.

서버 → 클라이언트 (`ServerMessage`)

| type | 필드 | 상태 |
|---|---|---|
| `welcome` | `userId`, `serverTimeEpochMillis`, `heartbeatIntervalMillis` | 구현 (연결 직후 1회) |
| `pong` | `clientTimeEpochMillis`, `serverTimeEpochMillis` | 구현 |
| `test_event` | `sequence`, `message`, `serverTimeEpochMillis` | 구현 |
| `error` | `code`, `message` | 구현 (잘못된 메시지에도 연결 유지) |
| `game_event` | `event: GameEventDto` | 형식만 정의, 발송 로직 TODO |

클라이언트 → 서버 (`ClientMessage`)

| type | 필드 | 응답 |
|---|---|---|
| `ping` | `clientTimeEpochMillis` | `pong` |
| `request_test_event` | `message` | `test_event` |

예)
```json
{"type":"request_test_event","message":"live"}
{"type":"test_event","sequence":1,"message":"서버 테스트 이벤트: live","serverTimeEpochMillis":1794565652201}
```
클라이언트 재연결: 1s → 2s → 4s … 최대 30s, 8회 실패 시 중단(`Failed`). 인증 실패(401)는 재시도하지 않는다.

---

## B. 계약만 정의된 API (현재 모두 인증 후 `501 NOT_IMPLEMENTED`)

라우트·DTO·클라이언트 호출 코드(`RemoteGameRepository`, `RemoteMatchmakingRepository`)는 준비되어 있다. 서버 쪽은 `server/.../game/GameFeatureContracts.kt` 의 인터페이스를 구현해 `ServerDependencies` 에서 교체하면 동작한다.

| Method | Endpoint | Request | Response | 목적 / 서버 판정 내용 |
|---|---|---|---|---|
| POST | `/api/matchmaking` | `JoinMatchmakingRequest{location: LocationUpdateRequest}` | 201 `MatchmakingTicketDto` | 대기열 등록. 반경 500m, 만료 10분. 이미 대기 중이면 409 |
| GET | `/api/matchmaking/me` | — | `MatchmakingTicketDto` | 내 대기 상태(인원 수만, 타인 좌표 없음). MATCHED 면 `gameId` |
| DELETE | `/api/matchmaking/me` | — | 204 | 매칭 취소(D-01) |
| GET | `/api/games/current` | — | `GameDto` | 내가 참가 중인 경기(앱 복귀 시 상태 복원) |
| GET | `/api/games/{gameId}` | — | `GameDto` | 참가자만 조회 가능(아니면 403) |
| GET | `/api/games/{gameId}/locations` | — | `VisibleLocationsResponse` | READY: 4명 최초 위치, PLAYING: 경찰=전원 / 도둑=도둑끼리, 종료 후: 403 |
| GET | `/api/games/{gameId}/qr/me` | — | `QrTokenDto` | 경기별 고정 QR 토큰 |
| POST | `/api/games/{gameId}/captures` | `QrScanRequest{token, requestId, scannedAtEpochMillis}` | `QrScanResultDto` | 경찰만. 도둑 ACTIVE → ESCORTED. 호송 중이면 `ESCORT_LIMIT`. 마지막 도둑이면 경찰 승리 |
| POST | `/api/games/{gameId}/rescues` | `QrScanRequest` | `QrScanResultDto` | 미체포 도둑만. JAILED → ACTIVE. ESCORTED 는 `TARGET_ESCORTED` 거부 |
| POST | `/api/games/{gameId}/forfeit` | — | `GameDto` | 명시적 기권 → CANCELLED / INVALID / `PLAYER_FORFEITED` |
| GET | `/api/games/{gameId}/events?after={eventId}` | — | `List<GameEventDto>` | 재접속 시 누락 이벤트 재조회 |

`QrScanResultDto.rejectReason` 예정 값: `INVALID_TOKEN`, `OTHER_GAME`, `ALREADY_CAPTURED`, `ESCORT_LIMIT`, `NOT_ALLOWED`, `TARGET_ESCORTED`, `TARGET_NOT_JAILED`. 같은 `requestId` 재전송은 첫 결과를 그대로 돌려줘야 한다(통신 끊김 후 재검증, 중복 처리 방지).

### 주요 DTO 요약 (`shared/dto/GameDtos.kt`)

```text
MatchmakingTicketDto { userId, status: WAITING|MATCHED|CANCELLED|EXPIRED, joinedAtEpochMillis, expiresAtEpochMillis, nearbyWaitingCount, gameId? }
GameDto { gameId, status, centerLatitude, centerLongitude, currentRadiusMeters, jailRadiusMeters, createdAtEpochMillis,
          startsAtEpochMillis?, endsAtEpochMillis?, endedAtEpochMillis?, result?, endReason?, players: [GamePlayerDto] }
GamePlayerDto { userId, nickname, role: POLICE|THIEF, status: ACTIVE|ESCORTED|JAILED|ELIMINATED, capturedBy?, warningCount, connectionState }
GameEventDto { eventId, gameId, type: GameEventType, data: JSON object, createdAtEpochMillis }
QrTokenDto { gameId, userId, token, issuedAtEpochMillis, expiresAtEpochMillis? (null = 경기 종료까지) }
```

## C. 향후 추가 검토 API

| API | 이유 |
|---|---|
| `POST /api/auth/logout` | 서버 측 토큰 폐기(`revoked_at`) |
| WebSocket `game_event` 푸시 | 체포·구출·구역 축소·종료 알림(NFR-P05 ≤3초) |
| FCM / APNs 푸시 | 앱이 백그라운드일 때 매칭 완료·경기 이벤트 알림 |

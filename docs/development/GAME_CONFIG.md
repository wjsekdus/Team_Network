# GAME_CONFIG — 게임 규칙 상수와 설정값

게임에 쓰이는 숫자는 두 종류다. 헷갈리지 않도록 이 문서에서 **확정(CONFIRMED)** 과 **임시(PROVISIONAL)** 를 분리해 적는다.

| 구분 | 의미 | 어디에 있나 | 바꾸는 방법 |
|---|---|---|---|
| **확정 (CONFIRMED)** | 팀이 정한 규칙. 서버와 앱이 같은 숫자를 써야 한다 | `shared/src/commonMain/kotlin/com/nettwork/policethief/shared/rules/GameRules.kt` | **설정으로 못 바꾼다.** 코드 상수를 고치고 서버·앱을 다시 빌드. 규칙 자체를 바꾸는 일이므로 팀 합의 후에만 |
| **임시 (PROVISIONAL)** | 팀 규칙으로 아직 확정되지 않은 판정 수치와 선택 기능. 테스트로 정한 "일단 이 값" | `server/src/main/kotlin/com/nettwork/policethief/server/game/GameSettings.kt` | 환경 변수 `PNT_GAME_*` 등으로 덮어쓴다(서버 재시작 필요) |

> 아래 "임시" 값의 이유 설명은 코드 주석과 기능 목적에서 가져온 것이다. 실제 휴대폰으로 현장 측정해서 정한 값이 **아니다**(NOT VERIFIED). 실외 테스트 후 팀이 조정해야 한다.

## 1. 확정 규칙 (CONFIRMED) — `GameRules`

| 상수 | 값 | 설명 | 서버 사용 |
|---|---|---|---|
| `PLAYERS_PER_GAME` | 4명 | 한 경기 인원 | 매칭, 경기 생성 |
| `POLICE_COUNT` / `THIEF_COUNT` | 2 / 2 | 경찰 2, 도둑 2(무작위 배정) | 역할 배정 |
| `MATCH_RADIUS_METERS` | 500m | 매칭 후보 반경(쌍 거리 + 중심점) | `MatchFinder`, 대기 인원 수 |
| `MATCH_TIMEOUT_MILLIS` | 10분 | 매칭 최대 대기 시간 | 대기열 `expires_at` |
| `PREPARATION_MILLIS` | 30초 | 매칭 완료 후 역할 공개·준비 시간 | `startsAt` |
| `INITIAL_ZONE_RADIUS_METERS` | 500m | 게임 구역 초기 반경(중심 = 4명 중심점) | 경기 생성 |
| `JAIL_RADIUS_METERS` | 10m | 감옥(구역 중심) 반경 | 자동 수감 |
| `GAME_DURATION_MILLIS` | 30분 | 경기 제한 시간(준비 끝부터) | `endsAt` |
| `LOCATION_UPDATE_INTERVAL_MILLIS` | 15초 | 위치 전송 **목표** 주기(OS 제한으로 보장 안 됨) | WebSocket `welcome.heartbeatIntervalMillis` 에 그대로 실림. 서버가 강제하지는 않음 |
| `LOCATION_STALE_WARNING_MILLIS` | 30초 | 위치 미수신 시 "지연" 경고 기준 | **앱 표시용**(서버 코드에서는 사용하지 않음) |
| `RECONNECT_GRACE_MILLIS` | 3분 | 연결 끊김 후 재접속 허용 시간 | 재접속 유예 → 초과 시 무효 |
| `MAX_ESCORTS_PER_POLICE` | 1명 | 경찰 1명이 동시에 호송 가능한 도둑 수 | 상수 자체는 서버가 읽지 않는다. 대신 경찰 행에 `escorting_user_id` 칸이 하나뿐이고 체포 시 `ESCORT_LIMIT` 로 검사한다 |
| `SPEED_LIMIT_KMH` | 25km/h | 과속 기준 | 과속 판정 |
| `SPEED_WARNINGS_TO_DISQUALIFY` | 3회 | 이 횟수에 도달하면 실격(→ 경기 무효) | 과속 판정 |

그 밖에 코드에 박혀 있어 설정으로 못 바꾸는 값(참고): 위치 검증 한계(정확도 0~1000m, 미래 60초 허용, 5분보다 오래된 측정 거부 — `shared/rules/LocationValidator.kt`), 닉네임 2~12자(`NicknamePolicy.kt`), 이벤트 조회 최대 200개·상태 응답의 최근 이벤트 30개(`GameEngine`), 개발 시계 이동 최대 1시간(`DevTools.kt`), 위치 정리 실행 간격 10분(`GameEngine.CLEANUP_INTERVAL_MILLIS`), `requestId` 최대 64자.

## 2. 임시 값 (PROVISIONAL) — `GameSettings`

시간 값 환경 변수는 단위가 **초(seconds)** 다(예외: `PNT_LOCATION_RETENTION_HOURS` 는 시간). 코드 내부는 밀리초지만 환경 변수는 **정수 초**로 쓴다. 소수점은 안 된다.
값 형식이 틀리면(`abc` 등) **서버가 시작을 거부**하고 어떤 변수가 틀렸는지 알려 준다(`GameSettings.fromEnvironment`).

### 2-1. 위치 품질

| 필드 | 환경 변수 | 기본값 | 단위 | 이유 / 효과 |
|---|---|---|---|---|
| `matchLocationMaxAgeMillis` | `PNT_GAME_MATCH_LOCATION_MAX_AGE_SECONDS` | 60 | 초 | 매칭에 쓰는 위치는 이 시간 안에 측정된 것만. 오래된 위치로 엉뚱한 사람과 매칭되는 것을 막는다. 대기열의 마지막 위치 갱신이 이보다 오래된 대기자는 후보에서 빠진다(대기 상태는 유지) |
| `matchMaxAccuracyMeters` | `PNT_GAME_MATCH_MAX_ACCURACY_M` | 100.0 | m | 매칭 요청 위치의 GPS 정확도 상한. 초과하면 422 `ACCURACY_TOO_LOW`. 낮출수록 엄격 |
| `judgementMaxAccuracyMeters` | `PNT_GAME_JUDGEMENT_MAX_ACCURACY_M` | 30.0 | m | 구역 이탈·미션 판정에 쓸 위치의 정확도 상한. 이보다 부정확하면 판정하지 않고 이전 상태 유지 |
| `jailMaxAccuracyMeters` | `PNT_GAME_JAIL_MAX_ACCURACY_M` | 15.0 | m | 자동 수감 판정 위치의 정확도 상한. 감옥 반경이 10m 로 좁아서 다른 판정보다 엄격. 초과하면 `JAIL_CHECK_PENDING` 만 보내고 수감 보류 |
| `jailSampleMaxAgeMillis` | `PNT_GAME_JAIL_SAMPLE_MAX_AGE_SECONDS` | 30 | 초 | 수감 판정 위치의 최신성(측정 후 이 시간이 지나면 사용 안 함) |

### 2-2. 구역

| 필드 | 환경 변수 | 기본값 | 단위 | 이유 / 효과 |
|---|---|---|---|---|
| `zoneOutDisqualifyMillis` | `PNT_GAME_ZONE_OUT_DISQUALIFY_SECONDS` | 30 | 초 | 연속 구역 이탈 허용 시간. 넘으면 실격(경기 무효). **0 이면 실격을 끈다**(경고만). UI 설계안의 30초를 초기값으로 사용 |
| `zoneShrinkStages` | `PNT_GAME_ZONE_STAGES` | `600:400,1200:300,1500:200` | 초:미터 | 구역 축소 일정(아래 형식 설명) |
| `zoneShrinkWarningMillis` | `PNT_GAME_ZONE_WARNING_SECONDS` | 60 | 초 | 축소 몇 초 전에 예고 이벤트(`ZONE_SHRINK_WARNING`)를 보낼지. 미션 실패로 축소를 앞당길 때 최소 이 시간 뒤로 잡는 데도 쓰인다 |

#### `PNT_GAME_ZONE_STAGES` 형식

```text
"경기시작후_초:반경_미터,경기시작후_초:반경_미터,..."
예) 600:400,1200:300,1500:200
```

뜻: 경기 시작 600초(10분) 후 반경 400m 로 축소, 1200초(20분) 후 300m, 1500초(25분) 후 200m (기본값과 같음).
규칙:
- 항목은 쉼표(`,`)로 나누고, 각 항목은 `초:미터` 정확히 두 값(콜론 1개). 앞뒤 공백은 무시.
- **시간은 점점 커지고 반경은 점점 작아져야** 한다. 어기면 서버 시작 거부("구역 축소 단계는 시간 순서대로, 반경은 점점 작아지도록…").
- 빈 문자열(`PNT_GAME_ZONE_STAGES=`)이면 **구역 축소 없음**. (PowerShell `$env:...=""` 는 변수를 삭제해 기본값이 쓰이므로, 빈 값은 `server/.env` 파일에 적는다.)
- 오프셋이 경기 제한 시간(30분 = 1800초) 이상인 단계는 경기 생성 시 무시된다(저장되지 않음).
- 초기 반경(500m)보다 큰 값을 넣는 것을 막는 검사는 없다. 첫 단계는 500 보다 작게 쓰자.
- 경기 생성 때 일정이 `game_zone_stages` 테이블에 복사되므로, **환경 변수를 바꿔도 이미 시작한 경기의 일정은 바뀌지 않는다.**

### 2-3. 미션

| 필드 | 환경 변수 | 기본값 | 단위 | 이유 / 효과 |
|---|---|---|---|---|
| `missionFirstDelayMillis` | `PNT_GAME_MISSION_FIRST_DELAY_SECONDS` | 180 | 초 | 경기 시작 후 첫 미션까지 대기 |
| `missionIntervalMillis` | `PNT_GAME_MISSION_INTERVAL_SECONDS` | 300 | 초 | 미션 종료(성공/실패) 후 다음 미션까지 |
| `missionDurationMillis` | `PNT_GAME_MISSION_DURATION_SECONDS` | 240 | 초 | 미션 제한 시간. 마감이 경기 종료 이후가 되면 그 미션은 만들지 않는다 |
| `missionRadiusMeters` | `PNT_GAME_MISSION_RADIUS_M` | 30.0 | m | 미션 구역 반경. 판정 정확도 상한(30m)과 같은 크기 |
| `missionMinDistanceFromJailMeters` | `PNT_GAME_MISSION_MIN_DISTANCE_FROM_JAIL_M` | 50.0 | m | 목표 지점은 감옥에서 최소 이만큼 떨어진 곳(구역이 좁으면 가능한 최대값으로 줄어든다) |
| `missionFailureShrinkAdvanceMillis` | `PNT_GAME_MISSION_FAILURE_ADVANCE_SECONDS` | 120 | 초 | 미션 실패 시 다음 구역 축소를 이만큼 앞당긴다(단 "지금 + 예고 시간"보다 이르게는 안 함) |

### 2-4. 과속 (25km/h·3회 실격은 **확정**, 판정 방법만 임시)

| 필드 | 환경 변수 | 기본값 | 단위 | 이유 / 효과 |
|---|---|---|---|---|
| `speedMinSampleIntervalMillis` | `PNT_GAME_SPEED_MIN_INTERVAL_SECONDS` | 5 | 초 | 속도 계산에 쓰는 두 표본의 최소 간격. 너무 짧으면 GPS 흔들림이 속도로 보인다 |
| `speedMaxAccuracyMeters` | `PNT_GAME_SPEED_MAX_ACCURACY_M` | 20.0 | m | 속도 계산에 쓸 표본의 정확도 상한 |
| `speedConsecutiveSamplesRequired` | `PNT_GAME_SPEED_CONSECUTIVE_SAMPLES` | 2 | 회 | 기준 초과가 연속 몇 번이어야 경고 1회인지(1 이상). 단발성 GPS 튐을 걸러냄 |
| `speedWarningCooldownMillis` | `PNT_GAME_SPEED_WARNING_COOLDOWN_SECONDS` | 60 | 초 | 경고 사이 최소 간격(한 번 과속 구간에서 경고가 연달아 쌓이지 않게) |
| `teleportSpeedKmh` | `PNT_GAME_TELEPORT_SPEED_KMH` | 150.0 | km/h | 이보다 빠른 이동은 순간이동(GPS 오류)으로 보고 판정 제외 |

### 2-5. 선택 기능

| 필드 | 환경 변수 | 기본값 | 단위 | 설명 |
|---|---|---|---|---|
| `ringThiefPhoneEnabled` | `PNT_FEATURE_RING_THIEF_PHONE` | **false** | true/false | 경찰의 "도둑 폰 울리기". **UI 설계안에서 나온 아이디어이며 확정된 요구사항이 아니다.** 기본으로 꺼져 있고, 꺼진 상태에서 `POST /api/games/{id}/ring` 은 200 + `accepted:false`, `rejectReason:"FEATURE_DISABLED"`. 켜진 값은 `GameDto.features.ringThiefPhone` 으로 앱에 전달된다 |
| `ringCooldownMillis` | `PNT_GAME_RING_COOLDOWN_SECONDS` | 60 | 초 | 폰 울리기 쿨다운(경찰별, 서버 관리). UI 설계안의 60초 |

불리언 값은 정확히 `true` / `false`(소문자)만 인정한다(`toBooleanStrictOrNull`). `True`, `1`, `yes` 는 오류로 서버 시작 거부.

### 2-6. 운영

| 필드 | 환경 변수 | 기본값 | 단위 | 설명 |
|---|---|---|---|---|
| `locationRetentionMillis` | `PNT_LOCATION_RETENTION_HOURS` | 24 | **시간** | 위치 기록 보관 기간. 지나면 삭제(10분마다 정리). 코드 주석은 "경기에 묶이지 않은 위치"라 쓰지만 실제 삭제 조건은 `received_at` 하나라 경기 위치도 포함한다 |
| `schedulerIntervalMillis` | (환경 변수 없음) | 1000 ms | 밀리초 | 서버 스케줄러(시간 기반 판정) 실행 간격. 테스트에서만 코드로 바꾼다 |

## 3. 서버 실행 설정 (`AppConfig`, 게임 규칙 아님)

| 환경 변수 | 기본값 | 필수 | 설명 |
|---|---|---|---|
| `PNT_SERVER_HOST` | `0.0.0.0` | | 바인딩 주소. `0.0.0.0` = 같은 네트워크의 다른 기기도 접속 가능, `127.0.0.1` = 이 PC 만 |
| `PNT_SERVER_PORT` | `8080` | | 포트(숫자가 아니면 조용히 8080) |
| `PNT_DB_URL` | `jdbc:postgresql://localhost:5432/police_thief` | | DB 주소 |
| `PNT_DB_USER` | `police_thief` | | DB 사용자 |
| `PNT_DB_PASSWORD` | 없음 | **필수** | 없으면 서버가 시작을 거부 |
| `PNT_DB_POOL_SIZE` | `10` | | DB 연결 풀 크기 |
| `PNT_ALLOW_MOCK_LOCATIONS` | `false` | | `true` 면 `source=MOCK` 위치 허용 + MOCK 은 과속 판정 제외. **개발 서버 전용** |
| `PNT_DEV_TOOLS` | `false` | | `true` 면 `/api/dev/...`(봇·시간 이동) 활성. `PNT_ALLOW_MOCK_LOCATIONS=true` 가 함께 필요 |
| `PNT_TOKEN_TTL_DAYS` | `30` | | 게스트 토큰 유효 기간(일) |
| `PNT_APP_VERSION` | `0.2.0` | | `/api/health` 의 `version` 표시 |

주의: 이 중 `PNT_SERVER_PORT`, `PNT_DB_POOL_SIZE`, `PNT_TOKEN_TTL_DAYS`, `PNT_ALLOW_MOCK_LOCATIONS`, `PNT_DEV_TOOLS` 는 값 형식이 틀리면 **오류 없이 기본값**으로 동작한다(예: `PNT_ALLOW_MOCK_LOCATIONS=True` → `false`). 반면 `PNT_GAME_*` 값은 틀리면 시작을 거부한다. 앞의 것은 오타를 조용히 넘기니 시작 로그의 `서버 설정:` 줄로 실제 값을 확인하자.

## 4. 값을 바꿀 때 순서

1. `server/.env` 에서 해당 줄의 주석(`#`)을 풀고 값을 바꾼다(예: `PNT_GAME_MISSION_FIRST_DELAY_SECONDS=30`).
2. 서버를 재시작(`Ctrl + C` 후 다시 `gradlew.bat :server:run`).
3. 시작 로그의 `서버 설정: ... game=GameSettings(...)` 줄에서 값이 반영됐는지 확인한다.
4. 현장 테스트 결과로 확정할 값이 정해지면 팀 합의 후 `GameSettings` 의 기본값이나 `GameRules` 를 고치고 이 문서의 표를 같이 고친다.

짧은 데모/시연용 예(구역 축소와 미션을 빨리 보고 싶을 때, **임시 값이므로 실제 경기에는 쓰지 않는다**):
```text
PNT_GAME_ZONE_STAGES=60:400,120:300,180:200
PNT_GAME_MISSION_FIRST_DELAY_SECONDS=30
PNT_GAME_MISSION_DURATION_SECONDS=120
```
(개발자 도구의 시간 이동(`POST /api/dev/clock/advance`)으로도 같은 효과를 낼 수 있다 — RUN_GUIDE.md)

# GuildUp 운영 모니터링

개발자 모니터링 페이지에서 서버 상태, 최근 24시간 장애 수, 중요 이벤트 이력과 현재 서버의 실시간 로그를 조회한다. 인게임 활동의 단계별 이력/성공·실패·SLOW 검색 및 배포 SQL은 [활동 조회 운영 로그 보고서](ACTIVITY_SYNC_OBSERVABILITY.md)에 정리했다. 전체 조사 및 변경 결과는 [MONITORING_REPORT.md](MONITORING_REPORT.md), 파일별 변경은 [MONITORING_CHANGED_FILES.md](MONITORING_CHANGED_FILES.md)에 정리했다.

## 로그의 역할

| 저장 위치 | 내용 | 제한 |
|---|---|---|
| `logs/guildup.log` | 운영 INFO/WARN/ERROR와 전체 예외 stack trace | 파일 100MB, 30일, archive 총 5GB |
| 서버 메모리 → SSE | 최근 INFO/WARN/ERROR, 안전한 context와 잘린 stack trace | 기본 1,000개, 설정값 500~2,000개로 제한 |
| `monitoring_events` | 중요 장애·경고 및 활동 조회 단계/결과 이력 | 기존 retention 정책 유지, 기본 30일 |

실시간 로그 한 건마다 DB에 INSERT하지 않는다. 메모리 로그 appender는 DB·HTTP 호출을 하지 않는다. DEBUG는 실시간 버퍼에 보관하지 않는다. 파일 로그도 기존 INFO root 수준을 유지한다.

ERROR는 최종 기능 실패와 운영 장애에 사용한다. 부분 데이터 실패, retry, 429, fallback은 WARN, 업무 시작/완료는 INFO, 반복 처리와 계산 중간값은 DEBUG다. 동일 예외가 HTTP로 전파될 때 기능 경계에서 이미 기록했다면 공통 필터가 같은 ERROR를 다시 출력하지 않는다. 업무 이벤트와 `HTTP_5XX`처럼 의미가 다른 DB 이벤트는 함께 기록될 수 있다.

## 요청과 작업 추적

HTTP 응답의 `X-Request-ID`와 서버 로그의 `requestId`로 같은 요청을 찾는다. 입력 ID는 영문·숫자·밑줄·하이픈 8~64자만 허용하며, 없거나 유효하지 않으면 UUID를 생성한다. URI query, 요청 본문, 쿠키와 인증 header는 기록하지 않는다.

관련 로그에는 `communityId`, `userId`, `bingoEventId`, `killCompetitionId`, `matchId`, Discord ID, `jobName`, `stage`, HTTP 상태, retryCount, elapsedMs를 필요한 위치에 기록한다. Bingo/Discord executor와 PUBG 병렬 작업은 MDC를 전달한 뒤 복원한다. 기능 ERROR의 stack trace에서 원인을 확인하고, 중요 이력의 requestId/referenceId로 파일 로그를 연결한다.

PUBG 단계는 Player/Match/Telemetry 조회, `TELEMETRY_PARSE`, `TELEMETRY_FACT_SAVE`, 집계 단계로 구분한다. Bingo 미션과 재집계에는 이벤트·참가자·미션·match context가, Kill Competition에는 claim/interim/final 단계와 RESULT_PENDING 장기 지속 이력이 포함된다.

## 실시간 로그 API와 UI

`GET /api/developer/monitoring/logs/stream`은 `text/event-stream` 응답이다. 기존 개발자 interceptor와 서비스의 `SYSTEM_ADMIN` 검사를 모두 적용한다. 로그인하지 않은 사용자는 401, 일반 사용자와 Community OWNER/ADMIN은 403이다.

- 서버 전체 최대 4개 연결, 초과 시 429. 전용 daemon thread 4개, 대기 작업 queue 없음.
- 최초 최근 200개, 이후 `Last-Event-ID`부터 최대 200개씩 전송. 오래된 ID나 다른 서버 인스턴스 ID면 gap 안내.
- heartbeat/권한 재검사 15초, 정상 stream 60초 후 종료, emitter timeout 65초. 브라우저 EventSource가 3초 간격으로 재연결.
- 응답 초기화 전에는 ready frame 한 건만 허용한다. 느린 연결 때문에 무제한 임시 전송 목록이 생기지 않는다.
- 로그 message 2,000자, stack trace 6,000자에 truncation 표시가 붙는다. category/thread/context도 길이를 제한한다.
- 개발자 페이지가 mount될 때만 연결하고 unmount되면 종료한다. SSE 원문은 JSON 파싱 전에 32,768자로 제한한다. UI도 최근 1,000개만 유지하고 최대 초당 4회 갱신한다.
- ALL/INFO/WARN/ERROR, category·문자열 검색, 자동 스크롤, 일시정지/다시 시작, 화면 초기화, 연결 상태, stack trace 펼치기를 제공한다. 화면 초기화는 서버 로그를 삭제하지 않는다. 일시정지 중에도 연결과 제한된 최신 로그 수신은 유지하며 화면 갱신만 멈춘다.

로그·예외 문자열은 파일 encoder와 메모리 저장 전에 마스킹한다. password/token/secret/API key, Authorization/Bearer/JWT, Cookie/session ID, 이메일, SQL 본문/상세, HTTP 오류 response body와 OAuth 결과 token 경로를 숨긴다. 비밀 환경변수 값도 가능한 범위에서 마스킹한다. 임의의 비밀을 나중에 마스킹하는 방식에 의존하지 말고 로그 호출에 인증 값이나 본문을 전달하지 않는다.

## 중요 이벤트 저장과 장애 격리

기존 `monitoring_events`와 event code를 유지한다. HTTP 5xx, PUBG retry/429/timeout/final failure, Bingo failure/partial/slow, Kill interim/final failure/RESULT_PENDING, Discord API/OAuth/member/JDA, DB/pool, 예상하지 못한 오류를 기록한다. 활동 조회는 `ACTIVITY_SYNC_*` 단계/결과 이벤트를 저장하고 syncId로 공통 PUBG 이벤트와 연결한다. 과거 `COMMUNITY_ACTIVITY_SYNC_FAILED`도 실패 필터로 조회한다.

운영에서는 전용 DB writer 1개와 최대 256개 queue로 중요 이벤트를 비동기 저장한다. 원래 업무 transaction과 분리한 `REQUIRES_NEW`, timeout 3초를 사용한다. 저장 실패/queue 초과는 업무 예외·계산 결과를 바꾸지 않고 WARN을 최대 분당 한 번 출력한다. 종료 시 최대 5초 동안 queue를 비우고 남은 작업은 중단한다. 갑작스러운 종료, DB 장애나 queue 초과에서는 일부 DB 이력이 누락될 수 있으므로 파일 로그도 함께 확인한다.

메타데이터는 전체 object graph에 128 nodes/128 inspections/16,384 characters, container 32개, depth 5 제한을 적용한다. 순환 참조, 비밀 key와 임의 객체 원문은 보관하지 않는다. 운영의 최근 이벤트 중복 확인은 제한된 메모리 cache를 사용하며 DB 조회를 추가하지 않는다.

## 운영 서버 적용

활동 조회 관측 기능 배포 전에 기존 monitoring 테이블에 nullable 조회 컬럼 2개/인덱스와 enum CHECK 확장을 적용한다. 새 로그 테이블은 만들지 않는다. 이전 모니터링 배포가 누락된 DB는 기존 SQL 상태를 먼저 확인하고 활동 조회 migration을 마지막으로 실행한다.

- `src/main/resources/db/manual/add_activity_sync_observability.sql` — 이번 활동 조회 로그/필터 배포 전에 적용.
- `src/main/resources/db/manual/add_monitoring_events.sql`
- `src/main/resources/db/manual/add_user_system_role.sql`
- `src/main/resources/db/manual/add_community_activity_monitoring_event_code.sql` — 과거 Hibernate enum CHECK가 활동 조회 실패 코드를 허용하지 않을 때 필요.

Backend JAR와 frontend production build를 함께 배포한다. 추가 필수 환경변수나 라이브러리는 없다. 선택 설정:

| 설정 | 기본값 | 의미 |
|---|---|---|
| `MONITORING_LOG_CAPACITY` | `1000` | 서버 ring buffer, 500~2,000으로 강제 제한 |
| `MONITORING_EVENTS_ASYNC` | `true` | 중요 이벤트 비동기 writer. 운영은 true 권장 |
| `MONITORING_RETENTION_DAYS` | `30` | 기존 DB 중요 이력 보관 기간 |
| `LOG_PATH` | `logs` | 기존 Logback 파일 경로 |

SSE 경로를 reverse proxy에서 buffer/cache하지 않도록 하고 timeout을 65초보다 길게 설정한다. 응답의 `X-Accel-Buffering: no`와 `Cache-Control: no-store`를 유지한다. 메모리 로그와 로그인 세션은 서버 재시작 시 초기화된다. 여러 서버를 쓰면 실시간 로그는 연결된 서버만 보여 준다.

## 검증

```sh
./mvnw test
./mvnw -DskipTests package
cd frontend
npm test
npm run build
npm run verify:build
```

일반 테스트는 H2와 모의 PUBG/Discord/메일 응답을 사용하며 운영 API를 호출하지 않는다. 별도 PostgreSQL/production dump 테스트는 해당 환경 설정이 있을 때 실행한다. 비동기 writer의 queue 초과/저장 실패, SSE 권한/연결 제한/로그아웃/초기화 대기/느린 연결 종료, ring buffer 제거/마스킹/stack trace와 MDC 복원을 별도 테스트한다. 기존 흐름 테스트에서는 저장 결과를 즉시 검증하기 위해 Maven의 `monitoring.events.async=false`를 사용한다.

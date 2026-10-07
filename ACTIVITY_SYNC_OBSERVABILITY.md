# 인게임 활동 조회 운영 로그 및 개발자 모니터링

## 기존 로그 구조

- 활동 조회는 `CommunityActivitySyncCoordinator.begin()`에서 게임 행을 잠그고 `SYNCING` 및 microsecond 정밀도의 `lastSyncAttemptAt`을 저장한 뒤, 전용 executor의 worker에서 Player → Match → 계산 → 짧은 저장 transaction을 실행했다.
- worker는 `PUBG activity sync completed` 요약(INFO)과 단계·상태·예외·시간이 포함된 실패 로그를 출력했다. 실패 서비스는 기존 `COMMUNITY_ACTIVITY_SYNC_FAILED` 이벤트를 저장했다. 작업별 식별자와 단계별 성공 이력은 없었다.
- 공통 PUBG client는 429·timeout·서버 오류·retry·최종 실패를 이미 기록했다. Match service는 성공 캐시와 in-flight 공유, virtual thread 및 application 전체 semaphore를 사용한다.
- 기존 Logback 파일(`logs/guildup.log`), 회전 archive, 마스킹 converter, 최근 1,000건 SSE 버퍼, `monitoring_events`, 비동기 writer, retention, 개발자 모니터링 페이지를 그대로 사용한다.
- 권한·cooldown 등 `begin()` 이전에 거절되는 요청은 조회 작업을 생성하지 않는다. 접수 후 발생한 설정 오류처럼 실제 `FAILED` 상태로 바뀌는 실패는 원인을 기록한다.

## 추가한 이벤트 로그

| 이벤트 | 수준 | 내용 |
|---|---|---|
| `ACTIVITY_SYNC_QUEUED` | INFO | 요청 접수, 작업 ID와 시작 시각 |
| `ACTIVITY_SYNC_STARTED` | INFO | 커뮤니티 이름/ID, 게임/게임 ID, 사용자 ID, 멤버 수, 대상 계정 수 |
| `ACTIVITY_SYNC_PLAYER_FETCH_STARTED` | INFO | Player 단계 시작 |
| `ACTIVITY_SYNC_PLAYER_FETCH_COMPLETED` | INFO | 대상/확인/미확인 플레이어, 실제 HTTP 시도 수, 단계 시간 |
| `ACTIVITY_SYNC_PLAYER_FETCH_FAILED` | ERROR | Player 단계 원인, 시간, retry, HTTP/timeout 분류 |
| `ACTIVITY_SYNC_MATCH_FETCH_STARTED` | INFO | 고유 경기 수 및 동시 조회 설정 |
| `ACTIVITY_SYNC_MATCH_FETCH_COMPLETED` | INFO | 실제 HTTP 시도 수, cache hit, 공유 조회, 성공/404/실패 경기 수, 단계 시간 |
| `ACTIVITY_SYNC_MATCH_FETCH_FAILED` | WARN / ERROR | 개별 경기 오류 표본 / 전체 Match 단계 실패 |
| `ACTIVITY_SYNC_CALCULATION_STARTED` | INFO | 계산 시작 |
| `ACTIVITY_SYNC_CALCULATION_COMPLETED` | INFO | 계산 시간과 저장 예정 스냅샷 수 |
| `ACTIVITY_SYNC_SAVE_STARTED` | INFO | 저장 예정 스냅샷 수 |
| `ACTIVITY_SYNC_SAVE_COMPLETED` | INFO | transaction commit 이후 저장된 스냅샷 수, SUCCESS 변경, 저장 시간 |
| `ACTIVITY_SYNC_SAVE_FAILED` | ERROR | DB_SAVE 원인, 저장 시간, 저장 수 0 및 SUCCESS 변경 false |
| `ACTIVITY_SYNC_COMPLETED` | INFO | SUCCESS, 전체 시간, 플레이어/경기/스냅샷 수 및 API 호출 요약 |
| `ACTIVITY_SYNC_FAILED` | ERROR | FAILED, 실패 단계, 전체 시간, 오류 분류 및 마스킹된 예외 상세 |
| `ACTIVITY_SYNC_SUPERSEDED` | INFO | 재접수된 새 작업으로 대체되어 기존 작업 결과를 저장하지 않은 경우 |
| `PUBG_RATE_LIMIT_RECOVERED` | INFO | 공통 client의 429 후 복구, API 종류, 상태, retry 및 시간 |

`PUBG_RATE_LIMIT`은 공통 client의 기존 429 로그 메시지를 식별하는 이름이다. DB 이벤트 코드는 기존 `PUBG_API_RATE_LIMIT`을 유지한다. 기존 `PUBG_API_RETRY`, `PUBG_API_TIMEOUT`, `PUBG_API_CLIENT_ERROR`, `PUBG_API_SERVER_ERROR`, `PUBG_API_FAILED`에도 같은 syncId가 연결된다. 429 이벤트를 활동 worker에서 다시 생성하지 않는다.

과거 `COMMUNITY_ACTIVITY_SYNC_FAILED` 이력도 인게임 활동/실패 필터로 계속 조회한다. 새 작업의 최종 실패는 `ACTIVITY_SYNC_FAILED` 한 건으로 기록한다. 실패 상태 저장 자체가 실패하면 기존 `DATABASE_ERROR`에 같은 syncId, `FAILURE_STATE_SAVE`, 원인 및 `failureStateSaved=false`를 기록한다.

## syncId 추적 방식

`UUID.nameUUIDFromBytes(communityGameId + ":" + lastSyncAttemptAt)`로 안정적인 작업 ID를 만든다. 입력 시각은 coordinator가 DB에 저장한 microsecond 정밀도의 attempt 시각이다. 기존 활동 상태 테이블에 새 컬럼을 추가하지 않고도 communityGameId/lastSyncAttemptAt에서 같은 ID를 재구성할 수 있다.

접수 서비스의 scope → executor → worker → 병렬 Match callable → 공통 PUBG client에 MDC 및 제한된 작업 계측 객체를 전달한다. 완료 후 이전 context와 ThreadLocal을 복원한다. requestId도 기존대로 보존하지만, 작업의 기본 연결 키는 syncId다. 세션·인증·transaction은 전달하지 않는다.

기존 `monitoring_events.reference_id`를 syncId로 사용하고 metadata에도 같은 값을 남긴다. 공통 PUBG 이벤트는 API 종류를 metadata.endpoint에 유지하면서 reference_id를 같은 작업 ID로 연결한다. 목록의 syncId나 이벤트를 클릭하면 기존 `/api/developer/monitoring/events` API를 `syncId`, `order=ASC`로 조회한다. 시간 및 id 순서로 정렬하고 100건씩 페이지를 나눈다.

## 백엔드 변경 파일

| 파일 | 변경사항 |
|---|---|
| `src/main/java/com/guildup/community/service/CommunityMemberActivitySyncService.java` | 작업 scope, 접수 이벤트, executor 전달, 최종 실패 기록, 실패 상태 저장 오류의 연결 |
| `src/main/java/com/guildup/community/service/CommunityMemberActivitySyncWorker.java` | 단계 시작/완료/실패, 대상 수/결과 수, commit 이후 성공 기록, superseded 구분 |
| `src/main/java/com/guildup/monitoring/logging/ActivitySyncLog.java` | 작업 단위 진단 helper, 시간/계측/실패 분류, 제한된 오류 표본 및 stack trace, 기존 monitoring writer 호출 |
| `src/main/java/com/guildup/monitoring/logging/LogContext.java` | Runnable/Callable의 작업 계측 context 전달·복원 |
| `src/main/java/com/guildup/monitoring/logging/RealtimeLogBuffer.java` | syncId, communityGameId, gameType context 허용 |
| `src/main/java/com/guildup/monitoring/logging/SafeLogText.java` | 기존 마스킹에 csrf 키 추가 |
| `src/main/java/com/guildup/monitoring/service/SafeMonitoringDataSanitizer.java` | csrf metadata 제거, 공통 전체 예산 내 stackTrace 최대 6,000자 |
| `src/main/java/com/guildup/monitoring/service/MonitoringEventService.java` | 활동 context 포함, 공통 PUBG 이벤트의 reference_id 연결 |
| `src/main/java/com/guildup/monitoring/domain/MonitoringEventCode.java` | 17개 이벤트 코드 추가 |
| `src/main/java/com/guildup/monitoring/domain/MonitoringEvent.java` | nullable `activity_game_type`/`duration_ms` 조회용 projection, reference index |
| `src/main/java/com/guildup/monitoring/service/MonitoringQueryService.java` | 그룹·게임·성공/실패·syncId·최소 시간·순서 필터, 입력 검증, 목록에서 trace 제외 |
| `src/main/java/com/guildup/monitoring/controller/DeveloperMonitoringController.java` | 기존 events API의 필터 확장 |
| `src/main/java/com/guildup/pubg/client/PubgApiClient.java` | 실제 HTTP 시도 계측, 429 안전한 header 정보, 복구 로그, 활동 작업 오류 진단 상한 |
| `src/main/java/com/guildup/pubg/service/PubgMatchService.java` | 기존 조회/캐시/공유 흐름에 카운터와 오류 표본 추가, 실제 concurrency 노출 |
| `src/main/resources/logback-spring.xml` | 기존 파일/console pattern에 syncId/game context 추가 |
| `src/main/resources/db/manual/add_activity_sync_observability.sql` | 기존 테이블의 nullable 컬럼 2개, 인덱스 3개, 기존 enum CHECK 확장 |

JSON metadata 전체를 문자열로 검색하면 게임/느린 작업 조회에서 큰 범위를 검사하게 된다. 이 두 필드만 기존 테이블의 nullable 컬럼으로 projection하고, syncId는 기존 reference_id를 인덱싱했다. 기존 severity/category/community/time/event_code 조회 방식과 pagination을 유지한다. 새 로그 테이블·독립 writer·별도 retention은 만들지 않았다.

활동 판정, Match 집계, Player batch/조회 방식, 스냅샷 계산, cooldown, Bingo/Kill Competition/Discord 업무 로직을 변경하지 않았다. 공통 PUBG retry 대기·횟수·governor 및 Match cache/in-flight/semaphore 정책도 유지한다.

백엔드 테스트 변경 파일:

| 파일 | 검증 |
|---|---|
| `src/test/java/com/guildup/community/CommunityMemberActivitySyncFlowTests.java` | 정상 이벤트 순서/동일 ID, 최종 실패, DB 저장 rollback과 마스킹, 기존 상태/cooldown/superseded 회귀 |
| `src/test/java/com/guildup/community/service/CommunityMemberActivityMonitoringTests.java` | writer 장애 격리, 접수 이후 실패 이유, 상태 저장 장애, 제출 거절 |
| `src/test/java/com/guildup/monitoring/DeveloperMonitoringFlowTests.java` | 활동 필터, 성공/느림, PUBG 포함 시간순 조회, trace 상세 분리, 401/403, 입력 검증 |
| `src/test/java/com/guildup/monitoring/ActivitySyncLoggingTests.java` | Runnable/Callable context 복원, timeout/DB 분류, 오류/진단 상한 및 최종 실패/마스킹 |
| `src/test/java/com/guildup/pubg/client/PubgApiClientTests.java` | 429/retry/복구의 같은 syncId, 안전한 header, 실제 시도 수 |
| `src/test/java/com/guildup/pubg/service/PubgMatchServiceTests.java` | 3,000경기 및 캐시 재조회에서 ID/호출 수/cache hit/요약 이벤트 부하 |

`MONITORING.md`에는 이 보고서와 새 배포 SQL을 연결하고 이벤트 저장 설명을 갱신했다.

## 개발자 웹 변경 파일

| 파일 | 변경사항 |
|---|---|
| `frontend/src/developer/MonitoringPage.jsx` | 전체/에러/인게임 활동/PUBG API 필터, 게임·결과·syncId·시간 필터, 결과/시간/SLOW 목록, 작업 요약 및 시간순 흐름, 접힌 trace, Escape 닫기 |
| `frontend/src/developer/monitoringView.js` | 필터 query, 시간 표시, 60초 SLOW 기준, 작업 상세 필드 변환 |
| `frontend/src/styles/pages.css` | 기존 디자인 변수/패널/배지 재사용, 필터 줄바꿈, 읽기 쉬운 표의 가로 스크롤, 요약/흐름 스타일 |
| `frontend/test/developerMonitoring.test.js` | 실제 React 페이지의 필터 요청, 성공/SLOW 표시, 실패 상세·PUBG 흐름·접힌 trace 검증 |

`DeveloperLayout`, 개발자 interceptor, SYSTEM_ADMIN 판정은 기존 구조를 유지한다.

## 개발자 페이지에서 확인 가능한 정보

경로: `/developer/monitoring`.

- 기존 서버 상태/24시간 집계/실시간 로그 아래 운영 이벤트 목록에서 시간, INFO/WARN/ERROR, 이벤트, 커뮤니티, 게임, syncId, 최종 결과/전체 시간, 간단한 메시지를 본다.
- `SUCCESS / 168.5s / SLOW`, `FAILED / 72.1s / SLOW` 형태로 정상적인 느린 조회도 표시한다. 60초 이상 SLOW, 60초 또는 3분 이상 검색을 제공한다.
- 기간, Severity, Community ID, Game, 최종 성공/실패, syncId, Event Code를 조합한다. 인게임 활동/PUBG 그룹은 처음 선택 시 전일부터 범위를 시작하여 기본 검색 범위를 줄인다. 적용한 필터는 목록 자동 갱신에도 유지된다.
- 최종 성공/실패 필터는 작업 전체 결과 이벤트를 찾는다. 전체 흐름에서는 이 최종 필터를 적용하지 않고 같은 작업의 단계와 공통 PUBG 이벤트를 모두 보여 준다.
- 상세 상단에서 상태/Duration/Players/Matches/Snapshots를 요약한다. 아래에서 작업 ID, 커뮤니티 및 게임 정보, 시작/종료, 수치, 단계/결과, 오류와 전체 흐름을 본다. 흐름의 항목을 클릭하면 해당 이벤트 상세를 연다.
- 없는 값은 만들지 않고 제외하거나 `-`로 표시한다. 실행 중인 흐름은 상세의 새로고침으로 갱신한다.

Chrome에서 production bundle과 **임시 샘플 API**로 목록, SLOW, 성공 요약, 공통 429/복구가 연결된 흐름, 실패 원인과 trace 펼침을 확인했다. 이는 운영 서버의 실제 로그 데이터 검증과 구분한다.

## 에러 상세 정보

실패 단계는 `PREPARATION`, `TASK_SUBMISSION`, `PLAYER_FETCH`, `MATCH_FETCH`, `ACTIVITY_CALCULATION`, `DB_SAVE`로 기록한다. 상태 저장의 별도 장애는 `FAILURE_STATE_SAVE`다.

최종 실패에는 syncId, communityId, communityGameId, 시작/종료/전체 시간, exception class, 마스킹된 message, PUBG upstream HTTP status/서비스 HTTP status(있을 때), PUBG error code, timeout/429, PUBG/DB/내부 오류 구분과 최대 retry 횟수가 포함된다. 개별 경기 오류 표본에는 matchId가 포함된다. 서버 파일 로그에는 Throwable의 전체 trace를 남긴다.

개발자 목록 API에는 stackTrace를 반환하지 않는다. 선택한 이벤트 상세 API와 접힌 `Stack Trace` 항목에서 길이를 제한한 안전한 trace를 본다. 작업 trace는 생성 단계에서 약 5,000자로 제한하고, 공통 sanitizer의 전체 metadata 예산도 적용한다. 전체 trace는 같은 syncId의 서버 로그에서 확인한다.

## 민감정보 보호

공통 `SafeLogText`/Logback converter/monitoring sanitizer를 재사용한다. API Key, Discord/OAuth token, Authorization/Bearer/JWT, Cookie/JSESSIONID/session ID, password/DB 비밀번호, secret, csrf 키/값을 제거·마스킹한다. SQL 본문/상세와 HTTP 오류 응답 본문, OAuth 민감 경로, 이메일 및 해당 환경변수의 비밀 값도 기존 마스킹 대상으로 유지한다.

로그 호출에는 요청 body, session, Cookie, Authorization 또는 API Key 값을 전달하지 않는다. PUBG 429의 header도 Retry-After, X-RateLimit-Remaining/Reset/Limit만 개별적으로 읽는다. 예외 message/trace 역시 저장/표시 전에 같은 마스킹을 적용한다. 일반 사용자와 비로그인 사용자는 기존 개발자 API 및 페이지 권한 검사로 차단된다.

## 로그 부하

- 정상 작업: 경기 수와 무관하게 단계 및 최종 요약 **11건**. 개별 정상 경기 INFO 로그 없음.
- 개별 경기 오류 표본: 작업당 최대 **5건**. 최종 Match 단계 실패와 전체 FAILED는 별도로 항상 기록.
- 공통 PUBG 장애/복구 진단: 활동 작업당 최대 **20개 실패/복구 시도**에 대해 기존 WARN/retry/final API 이벤트를 기록. 한 시도에서 최대 두 DB 이벤트가 생길 수 있다. 초과 진단 수는 최종 요약에 기록한다. 정상 404는 이 예산을 소모하지 않는다.
- 3,000경기 성공 조회와 동일 경기 캐시 재조회를 실행한 테스트에서 API 3,000회, cache hit 3,000건, 요약 이벤트 2건을 확인했다. 오류 3,000건 시뮬레이션에서도 개별 오류는 5건, 전체 최종 실패는 1건이었다.
- 기존 writer queue 최대 256, 이벤트 metadata 전체 16,384자/128 nodes/128 inspections, 파일 100MB rotation/30일/총 5GB, DB retention 기본 30일을 유지한다.
- Match 단계 전체 실패 후 아직 실행 중인 기존 병렬 요청의 정책은 유지한다. 최종 실패의 카운터는 종료 로그 기록 시점의 값이며, 남은 요청 때문에 진단 로그가 무제한 추가되지 않는다.

## 테스트 결과

| 실행 | 결과 |
|---|---|
| `./mvnw -q test` | 전체 실행 당시 786건: 650 통과, 136 조건부 생략, 실패/오류 0 |
| 최종 영향 범위 9개 클래스 재실행 | 67건 통과, 실패/오류 0. 분류 테스트 1건 추가 후 전체 report 합계 787건/651 통과/136 생략 |
| `npm test` | 132건 통과 |
| `npm run build` / `npm run verify:build` | 통과, production route 37개 검증. 기존 큰 bundle 경고 유지 |
| `./mvnw -q -DskipTests package` | 통과 |
| `git diff --check` | 통과 |
| 임시 로컬 PostgreSQL의 SQL 적용/재실행 | 2회 적용 통과, nullable 컬럼 2개/인덱스 3개/검증된 CHECK/42개 enum 코드 INSERT 확인 |
| Chrome + 샘플 API 화면 검증 | 목록, SUCCESS/FAILED 시간 및 SLOW, 작업 수치, PUBG 포함 흐름, 실패 상세 확인 |

생략된 136건은 기존 PostgreSQL/운영 dump 테스트로 `TEST_POSTGRES_URL` 또는 `SPRING_DATASOURCE_URL` 설정이 없는 조건에서 생략됐다. 별도의 임시 PostgreSQL은 이번 migration SQL만 검증하는 데 사용했고 종료·삭제했다.

검증한 핵심 시나리오: 시작→각 단계→저장 commit→완료 순서와 동일 syncId, PUBG Match/Player 실패, DB 저장 rollback과 SAVE_FAILED/FAILED, writer 장애의 업무 격리, wrapper/timeout/DB 분류, context 전달/복원, 429 header 및 복구, 필터·정렬·pagination·입력 검증, 비로그인 401/일반 사용자 403, 3,000경기 요약 부하, 예외 민감값 마스킹.

## 남은 개선사항

배포 전에 `src/main/resources/db/manual/add_activity_sync_observability.sql`을 기존 monitoring schema에 적용해야 한다. Hibernate 자동 schema update만으로는 과거 enum CHECK를 확장하지 않는다. Backend JAR와 frontend production build를 함께 배포한다. 추가 필수 환경변수나 라이브러리는 없다.

기존 비동기 monitoring writer의 DB 장애·queue 초과·프로세스 강제 종료 시에는 DB 이력이 일부 누락될 수 있다. 같은 syncId의 파일 로그를 함께 확인해야 한다. 새 보장성 저장 시스템은 이번 범위에 추가하지 않았다. 보관 기간이 지난 과거 작업과 배포 이전의 상세 수치는 복원하지 않는다.

실제 운영 PUBG API 호출 및 운영 DB 배포는 수행하지 않았다. 이번 목적 외 Match 취소 정책, 알림/통계 확장, bundle 분리는 별도 작업 범위다.

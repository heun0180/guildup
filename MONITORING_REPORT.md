# GuildUp Logging / Monitoring 완료 보고서

검증 기준: 2026-10-02. 운영 배포나 운영 API 호출 없이 로컬 소스·H2·모의 외부 응답으로 검증했다. 작업 시작 시 소스를 먼저 조사하고 분류 결과와 변경 계획을 보고한 뒤 구현했다.

## 1. 전체 조사 결과

변경 전 조사 목록은 **610개**다: Backend main Java **414개**, Frontend src **68개**, 기존 정적 화면 **9개**, Backend 테스트 **72개**, 설정·SQL·빌드/검증 스크립트·Frontend 테스트 등 **47개**. Frontend의 별도 전체 파일 목록은 생성물·lock·이미지 자산을 제외한 작성 파일 97개를 확인했다. 신규 구현 파일은 위 변경 전 기준 수에 포함하지 않는다. [전수 조사 목록](MONITORING_SOURCE_INVENTORY.tsv)에 원래 파일별 catch/throw/log 검색 위치 수를 남겼다. 숫자는 검색 패턴의 발견 횟수이며 오류 개수는 아니다.

Backend 10개 최상위 패키지와 실행 진입점을 확인했다: account 1, user 9, community 187, discord 61, feedback 9, bingo 51, pubg 39, killcompetition 35, monitoring 15, developer 6, application 1. Ranking/Attendance/Notice/Event/Board는 community 내부 파일까지 포함했다. `ExternalAccountProvider.java`는 예외·비밀 값이 없는 enum으로 확인했고 변경하지 않았다.

소스 조사는 24개 묶음으로 진행했다: Controller, Service, Repository/Transaction, Scheduler, Executor/Async/Event Listener, Configuration/환경·파일, Security/Authentication/Authorization, User/Account, Community/Member, Attendance/Score/Ranking, Notice/Event/Board, 문의/메일, Discord OAuth/Bot 설치, JDA/Guild/Member/Role/Voice/DM, PUBG Player, Match, Telemetry/Fact DB, Bingo, Kill Competition, Monitoring/Developer, Frontend 공통 API/인증, React 화면/상태/Promise/polling, 기존 정적 화면, 테스트/SQL/빌드 스크립트. 단순 도메인 파일에는 로그를 추가하지 않고 실패를 관찰하는 경계를 보완했다.

| 분류 | 주요 조사 결과 | 조치 |
|---|---|---|
| 로그 충분 | 기존 Bingo worker, Kill 최종 정산, Discord member event의 주요 실패 경계 | 유지하고 stage/context 및 중복 제어 보강 |
| 로그 부족 | PUBG 단계 구분, 중간 정산·claim, OAuth fallback, 활동 조회 | 원인/단계/ID/stack trace 추가 |
| 로그 없음 | 공통 HTTP 최종 stack trace, 일부 voice·scheduler 진입 실패, 실패한 재집계의 중요 이력 | 공통 경계와 기능별 이력 추가 |
| 로그 중복 | 하위 API 최종 ERROR와 상위 기능 ERROR, HTTP 전파 | retry/부분 실패 WARN, 최종 경계 ERROR와 예외 marker |
| 로그 레벨 부적절 | probe의 잦은 INFO, 내부 정산·insert 상세 INFO | 반복 상세 DEBUG, 주요 시작/완료 INFO |
| Exception swallowing | Discord discovery, 일부 Telemetry/재집계·cleanup, Frontend fallback | 계속 진행하는 기존 정책 유지, 원인 로그/경고/이력 추가 |
| Context 부족 | request 연계, 비동기 MDC, 집계 stage | requestId/MDC 및 기능별 ID 전달 |
| 민감정보 노출 위험 | DTO/config toString, HTTP·DB 예외 원문, 사용자 응답 getMessage | 마스킹, 안전한 응답, 원문 보관 금지 |

`try/catch`, throw/checked·runtime exception, orElseThrow, null/Optional, CompletableFuture join/completion, executor queue, JDA callback, timeout/retry/fallback, HTTP/DB 예외가 최종 HTTP 또는 백그라운드 경계에 도달하는 흐름을 확인했다. 기존 계산·재시도 횟수·트랜잭션 경계·Discord 동기화 결과를 변경하는 리팩토링은 하지 않았다.

## 2. 로그가 없었던 부분

공통 HTTP 5xx는 실제 exception 객체를 보존해 최종 ERROR와 stack trace를 남긴다. 전문 ExceptionHandler가 처리한 5xx도 원래 예외를 공통 필터로 전달한다. 사용자에게는 내부 예외·SQL 대신 안전한 메시지와 requestId를 제공한다. 기존 정상 4xx와 잘못된 JSON의 400 응답은 보존했다.

백그라운드는 HTTP handler에 의존하지 않고 각 작업 경계를 보완했다: Bingo 제출/집계/probe, Kill due 조회/claim/interim/final/RESULT_PENDING 감시, PUBG Telemetry Fact 저장, Discord voice event/복구/reconciliation, monitoring retention/pool 작업. OAuth/DM/메일 실패와 Community 중요 생명주기도 구분한다.

Bingo 재집계의 실패 preview/apply가 HTTP 200 결과로 돌아가는 기존 동작은 유지하면서, 실패 결과마다 `BINGO_AGGREGATION_FAILED` 한 건을 기록한다. 실패 stage·최대 10개 match ID·예외 클래스·상태를 안전한 metadata에 담는다.

## 3. Exception swallowing

| 위치 | 기존 문제 | 수정 |
|---|---|---|
| `CommunityService` Discord discovery | 일부 guild 실패가 정상 목록처럼 사라짐 | guild/작업 context와 WARN stack trace, 남은 조회 계속 |
| `PubgMatchSyncService` | 일부 Telemetry 실패가 단계 구분 없이 계속됨 | FETCH/PARSE/FACT_SAVE 구분, matchId와 trace, 중요 이력 |
| `TemporaryBingoRebuildService` | 실패/누락 결과만 반환해 과거 원인 이력이 부족 | match/stage 로그와 실패 결과 단위 DB 이벤트 |
| `CommunityMemberActivitySyncService`, Kill settlement | 실패 상태 저장 오류가 원래 실패를 덮을 수 있음 | 원래 실패 유지, cleanup 실패 suppressed 및 별도 trace |
| Frontend 여러 페이지 | 목록·설정·요약 fallback이 빈 정상 데이터처럼 보임 | 데이터별 경고/오류 안내, 안전한 공통 API 진단 |
| 개발자 clipboard / 전역 Promise | rejection 처리 또는 사용자 안내 부족 | rejection 처리와 Error Boundary/전역 오류 진단 |

무조건 재throw하지 않았다. 일부 match 실패, 의도한 fallback, 사용자 취소, 연결 종료, invalid cursor, 동시 로그아웃의 diagnostic 조회처럼 계속 진행해야 하는 정책은 유지하고 적절한 경고 또는 정상 종료로 구분했다. 특히 AbortError는 파싱/통신 장애로 바꾸지 않는다.

## 4. 중복 로그

PUBG 재시도·일부 match/telemetry 실패를 WARN으로 두고 최종 기능 실패는 집계·HTTP·작업 경계에서 ERROR로 남긴다. 메일/OAuth/활동 조회처럼 충분히 ERROR를 기록한 예외는 `FailureLogContext` marker로 공통 HTTP ERROR 중복을 방지한다. 원인 체인과 wrapper를 따라 확인하며 weak reference와 최대 4,096개 제한으로 marker가 예외를 영구 보유하거나 무제한 늘어나지 않는다.

`HTTP_5XX`와 `BINGO_AGGREGATION_FAILED`처럼 서로 다른 목적의 중요 이벤트가 함께 존재할 수 있다. 이것은 같은 stack trace를 여러 ERROR로 출력하는 것과 구분한다. Frontend 진단도 같은 오류 객체의 중복 출력과 반복 횟수를 제한한다.

## 5. 로그 레벨 수정

- PUBG retry/429/일부 match 실패와 예상된 Discord DM 제한: WARN. 실제 최종 기능 실패, Fact DB 실패, scheduler 전체 실패: ERROR.
- Bingo runtime probe, match 단위 처리, 정산 내부 단계·계산 상세: INFO → DEBUG. Probe는 5초 주기로 DEBUG에서만 실행한다.
- Bingo/Kill 주요 시작·완료, Community 생성·삭제/Discord 연결의 성공: INFO. Community 성공 로그는 transaction commit 후 출력한다.
- fallback/부분 데이터 누락/RESULT_PENDING 장기 지속/pool pressure: WARN. 예상한 4xx 거절과 사용자 취소는 장애 ERROR로 만들지 않는다.

## 6. 추가된 Context

HTTP `X-Request-ID`/MDC requestId, endpoint/method/status/elapsedMs, communityId/userId, Bingo event/participant/cell/missionType, Kill competition/participant/job/stage, PUBG shard/player/match/HTTP 상태/retry, Discord guild/user/operation을 해당 실패 위치에 추가했다. 메시지에 필요한 ID만 포함한다.

`LogContext` scope와 TaskDecorator/Callable wrapper로 요청→Bingo/Discord executor→PUBG 병렬 작업의 MDC를 전달하고 작업 후 이전 값을 복원한다. 인증 세션이나 transaction을 worker에 전달하지 않는다. requestId는 파일 로그, 실시간 context, 중요 이벤트 metadata에서 연결할 수 있다.

## 7. 실시간 로그

구조는 **Logback → 제한된 Ring Buffer → SSE → Developer UI**다. 로그 버퍼 경로에서 DB나 외부 API를 호출하지 않는다.

기본 1,000개, hard limit 500~2,000개, 오래된 로그 자동 제거, INFO/WARN/ERROR만 보관. message 2,000자/trace 6,000자 및 작은 truncation 표시, context whitelist·길이·검사 횟수를 제한한다. Throwable 객체 자체는 보관하지 않는다.

API: `GET /api/developer/monitoring/logs/stream`. 기존 개발자 interceptor + 서비스 `SYSTEM_ADMIN` 재검사를 적용한다. 일반 User/Community OWNER/ADMIN 권한으로 접근할 수 없다. 서버 전체 최대 4연결, 전용 thread 4개, send task queue 없음. 초기 200개와 ID 기반 최대 200개 replay, 오래된/다른 인스턴스 cursor는 gap 안내다.

15초 heartbeat/권한 재확인, 정상 60초 stream/65초 emitter timeout, 3초 native reconnect, logout/unmount 종료를 구현했다. Spring 응답 초기화 전 ready 한 건만 허용하여 내부 early-send 목록도 제한한다. 느린 socket write가 종료 thread를 막지 않도록 shutdown에서 emitter write lock을 기다리지 않는다.

## 8. Developer Page

기존 서버 상태·24시간 지표·중요 이벤트 검색/상세를 유지하고 실시간 로그 패널을 추가했다. ALL/INFO/WARN/ERROR, category/문자열/context 검색, 자동 스크롤, 일시정지/다시 시작, 화면 초기화, 연결 상태, stack trace 펼치기를 제공한다.

페이지를 열었을 때만 EventSource를 만들고 이동/unmount 때 close한다. 일시정지는 화면 업데이트만 멈추며 서버 로깅은 계속된다. 화면 초기화는 브라우저 표시만 비운다. SSE 원문은 JSON 파싱 전에 32,768자로 제한한다. 브라우저 버퍼도 1,000개이며 갱신 최대 초당 4회, 기본 DOM 200행에서 최대 1,000행까지 표시한다. 모의 API/SSE로 필터·검색·trace·pause/resume·clear·reconnect를 화면에서도 확인했다.

## 9. monitoring_events

기존 테이블·retention·event code를 유지한다. HTTP_5XX, PUBG_API_RATE_LIMIT/TIMEOUT/RETRY/FAILED 및 4xx/5xx, BINGO_AGGREGATION_FAILED/PARTIAL_FAILURE/SLOW_AGGREGATION, KILL_COMPETITION_INTERIM_FAILED/SETTLEMENT_FAILED/RESULT_PENDING/STALE, Discord API/OAuth/guild/member/JDA/rate limit, DATABASE_ERROR/POOL_EXHAUSTED, UNEXPECTED_EXCEPTION, COMMUNITY_ACTIVITY_SYNC_FAILED를 목적에 맞게 기록한다.

실시간 로그마다 INSERT하지 않는다. 중요 이벤트만 전용 writer 1개, queue 최대 256개, 별도 transaction timeout 3초로 저장한다. queue 초과/DB 장애/metadata 실패를 업무로 전파하지 않고 경고는 분당 한 번으로 제한한다. 종료 시 최대 5초 drain. 최근 이벤트 dedup cache는 2,048개와 안전한 reference 길이로 제한한다.

## 10. Secret 보안

Discord AccessTokenResponse/OAuthProperties/PUBG API 설정의 toString에 원문 token/secret/key가 드러날 가능성을 제거했다. HTTP 오류 body·DB SQL/detail·이메일이 stack trace에서 노출되는 경로를 공통 file encoder와 메모리 저장 전 마스킹으로 보호한다. OAuth 결과/설치 token 경로도 가린다. 사용자 응답과 저장된 Kill lastError는 내부 exception 메시지를 직접 노출하지 않는다.

password/API key/Authorization/Bearer/JWT/Discord secret/access·refresh token/Cookie/session ID/SMTP·DB password/secret 환경값을 로그·metadata 검색에서 확인했다. Buffer·metadata의 입력 길이/탐색 예산도 제한하고 cut/escaped quote·미완성 JWT/SQL/body를 테스트한다. Frontend console에는 원래 Error/message/body/user/token을 출력하지 않고 안전한 kind/method/endpoint/status/requestId만 기록한다.

## 11. 성능 영향

추가 PUBG API 호출 **0**, 추가 Discord API 호출 **0**. 계산·조회·재시도 횟수는 유지한다. 실시간 로그의 추가 DB INSERT **0**. 중요 장애 이력을 위한 INSERT는 기존 역할을 유지하며 부족한 실패 지점에 한정해 보완했다. SSE 권한 재검사는 연결당 15초마다 User role 조회 1회가 추가된다. 중요 이벤트 writer의 dedup 확인은 운영에서 메모리로 처리한다.

실시간 append는 짧은 ring 변경만 synchronized 처리하고 마스킹은 lock 밖에서 수행한다. DEBUG를 차단하고 반복 INFO를 줄였다. 메타데이터 전체 graph는 128 nodes/128 inspections/16,384 characters, depth 5/container 32로 제한한다. 마스킹 retained-input 검사도 8,192자로 제한하고 반복 key 입력의 정규식 지연을 회귀 테스트한다.

로컬 합성 측정: 서버 20,000회 append, capacity 1,000 기준 평균 **5.22µs/append**. Frontend 100,000회 ring append 약 **25.8ms**, 1,000개 로그를 100회 filter 약 **45.6ms**. 개발 머신의 제한된 측정이며 운영 PUBG 대량 집계의 실제 throughput/percentile 측정을 의미하지 않는다. 기존 집계 테스트로 API 호출 수와 결과 보존을 확인했다.

## 12. 변경 파일

모든 변경 파일의 설명은 [MONITORING_CHANGED_FILES.md](MONITORING_CHANGED_FILES.md)에 파일별로 작성했다. 공통 logging/filter/stream/config, 기능별 실패 경계, frontend API/Error Boundary/실시간 UI, 관련 회귀 테스트를 포함한다. 원래 파일 전수 목록은 [MONITORING_SOURCE_INVENTORY.tsv](MONITORING_SOURCE_INVENTORY.tsv), 설정 및 배포 절차는 [MONITORING.md](MONITORING.md)에 별도로 정리했다.

소스·테스트·설정 116개, 조사·운영·완료 문서 4개로 총 120개 파일을 변경/추가했다.

| 문서 | 내용 |
|---|---|
| `MONITORING_SOURCE_INVENTORY.tsv` | 변경 전 610개 파일의 조사 범위와 패턴 검색 수 |
| `MONITORING_CHANGED_FILES.md` | 구현·테스트·설정의 파일별 설명 |
| `MONITORING.md` | 현재 구조·설정·배포·검증 가이드 |
| `MONITORING_REPORT.md` | 요청한 14개 항목의 조사·수정·검증·잔여 위험 보고 |

| 최종 검증 | 결과 |
|---|---|
| Backend 전체 + 마지막 자원 제한 회귀 검사 | 82 suites, 총 500개: **482 통과 / 18 skip / 실패·오류 0** |
| Backend `./mvnw -q -DskipTests package` | 성공, 실행 가능한 JAR 생성 |
| Frontend `npm test` | **77/77 통과** |
| Frontend `npm run build` + `npm run verify:build` | 성공, **36 routes** 검증 |
| 변경 공백 검사 | `git diff --check` 통과 |

전체 suite 499개 확인 후 예외 marker의 4,096개 한도 테스트를 추가해 관련 6개 suite를 다시 검증했다.

Global 예외 trace/정상 400/context/중복 억제, 중요 이벤트 저장/실패 격리, SSE 익명·일반 사용자 차단/관리자 허용/연결 제한/로그아웃/초기화 전 목록 제한/느린 send 종료/세션 무효화 경합, Ring Buffer 최대 크기/오래된 제거/replay/DEBUG 차단, 민감 값·잘린 문자열·전체 stack trace 마스킹/처리량 예산, writer queue 초과/종료 drain, MDC 전달/복원과 기존 집계 흐름을 검증했다.

## 13. 운영 서버 적용

새 DB migration이나 event code 추가는 없다. 기존 monitoring schema가 적용된 DB는 추가 SQL 없이 배포할 수 있다. 이전 모니터링 작업이 미적용된 DB는 기존 `add_monitoring_events.sql`, `add_user_system_role.sql`, `add_community_activity_monitoring_event_code.sql` 상태를 확인한다. 특히 기존 Hibernate enum CHECK 제약은 자동 확장되지 않으므로 필요한 기존 SQL만 적용한다.

추가 필수 환경변수·의존성은 없다. 선택 `MONITORING_LOG_CAPACITY=1000`, `MONITORING_EVENTS_ASYNC=true`를 지원한다. 새 Backend JAR와 Frontend dist를 함께 배포한다. Proxy SSE buffering/cache를 끄고 timeout을 65초보다 길게 설정한다. 파일 경로/rotation과 기존 retention 설정은 유지한다. 상세 설명은 [운영 가이드](MONITORING.md)에 있다. 이번 작업에서 운영 서버 배포는 수행하지 않았다.

## 14. 남은 위험 요소

- 실제 PostgreSQL/production dump opt-in 테스트 18개는 환경 미설정으로 skip. H2·모의 API 검증을 실제 deadlock/연결 장애/대규모 집계 부하 측정으로 해석할 수 없다.
- 실시간 로그는 서버 프로세스별이며 재시작 시 초기화된다. 다중 인스턴스에서는 연결한 서버 로그만 보이고 재연결 시 gap이 생길 수 있다.
- 중요 이벤트 queue 초과, DB 장애, 비정상 종료에서는 DB 이력이 일부 유실될 수 있다. 원래 업무 보호를 우선하고 파일 로그를 유지한다. transaction timeout은 드라이버·pool 획득/네트워크를 모두 정확히 3초에 중단하는 보장은 아니다.
- 느린 SSE socket write는 container/proxy timeout 영향을 받는다. 전용 thread 4개로 업무 pool 영향과 backlog를 제한하지만 일시적으로 stream slot을 점유할 수 있다. 종료 thread는 기다리지 않는다.
- 파일 encoder의 전체 trace 마스킹과 기존 파일 appender는 동기다. 측정된 append 비용은 파일 I/O까지 포함하지 않는다. 비정상적으로 거대한 단일 외부 예외 문자열은 로그 생성 자체의 비용이 있을 수 있다.
- 기존 DB 예외를 409로 번역하거나 일부 match 오류를 건너뛰는 정책은 보존했다. unexpected 오류를 로그/이력으로 드러내되 업무 정책을 바꾸는 개선은 별도 작업이다.
- DB 전체 장애 시 개발자 권한 검사/중요 이력/summary 조회도 제한될 수 있다. 접근은 fail closed하며 파일 로그가 독립적인 확인 경로다.
- Frontend 오류는 안전한 브라우저 진단과 사용자 안내로 처리하며 서버로 모든 브라우저 오류를 수집하는 별도 ingestion은 추가하지 않았다. 알려지지 않은 비밀을 완벽하게 추론하는 마스킹은 불가능하므로 인증 값/본문의 로그 입력 금지를 유지해야 한다.
- Vite 기존 500kB bundle 경고가 남아 있다. 이번 범위에서 unrelated bundle 리팩토링은 하지 않았다.

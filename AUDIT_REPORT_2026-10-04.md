# GuildUp 종합 운영 점검 보고서

점검일: 2026-10-04 · 대상 커밋: `070c2e222f95fa9dd07b52fd583a13c18ee63c23`

요청 범위: 기능 추가 없이 인증/인가, community 격리, PUBG·Discord 연동, DB 트랜잭션, 스케줄러/비동기, 예외, 메모리/스레드, rate limit, N+1, 삭제 cascade, 동시성, 운영 로그를 점검했다. 애플리케이션 소스·설정·의존성은 수정하지 않았다. 이 문서만 새로 작성했다.

## 1. 결과와 판단 기준

운영 전에 우선 해결해야 할 경로는 로그인 세션/CSRF, 잘못된 빙고 결과 확정, 계정 연결·커뮤니티 삭제, 실패 정산의 후속 작업 차단, 외부 장애의 DB/스케줄러 전파, 무제한 Discord 이벤트 대기열이다.

| 등급 | 건수 | 기준 |
|---|---:|---|
| Critical | 0 | 무인증 계정/관리 권한 탈취, 광범위 데이터 파괴 등 즉시 악용 가능한 치명 경로 |
| High | 13 | 조건 충족 시 계정 보안·핵심 결과 정합성·서비스 가용성에 중대한 영향 |
| Medium | 21 | 동시성·규모·배포·정책 조건에서 오류·지연·운영 신뢰도 저하 |
| Low | 2 | 영향과 발생 범위가 제한된 오류·관측 공백 |

Critical이 0이라는 것은 무결점 또는 운영 안전 보증을 의미하지 않는다. 여기서는 코드로 확인 가능한 경로와 현실적인 발생 조건을 중심으로 분류했고, 실제 운영 배포·DB·외부 계정이 필요한 부분은 조건부로 표시했다. 같은 원인에서 파생되는 항목은 각 문제의 연결 관계를 설명했다.

## 2. 점검 범위와 검증

- 백엔드 Java 431개 파일, 프런트엔드 `src` 77개 파일을 대상으로 구조·호출·권한·트랜잭션·엔티티/제약·비동기 자원·테스트를 훑고 위험 경로를 상세 추적했다.
- README, 플랫폼 분리 배포 안내, Maven/npm 의존성, application 설정, 로그 설정, 수동 PostgreSQL DDL을 확인했다.
- 백엔드: `env -u TEST_POSTGRES_URL -u SPRING_DATASOURCE_URL ./mvnw -q test` 성공. Surefire 85 suite, 533 test 중 510 통과, 실패/오류 0, 23 skipped.
- 프런트엔드: `npm test` 93 통과, 실패 0.
- `npm audit --json`: 현재 audit 결과 알려진 취약점 0. Maven 전체 CVE 스캔은 실행하지 않았으며 이것이 전체 공급망 안전을 보장하지 않는다.
- 실제 운영 DB를 지정하는 환경변수를 제거하고 기존 mock/test 설정으로 테스트했다. 운영 PostgreSQL에 연결하거나 Discord/PUBG/메일로 실제 요청·발송하지 않았다.
- `/tmp`의 독립 JVM probe로 실제 도메인/서비스 클래스를 호출했다. 빙고 완료 시각 유지, Discord 내부 큐 무제한, PUBG cooldown 재검증 부재를 재현했다. 저장소에 재현 소스를 추가하지 않았다.
- PUBG gzip 응답은 실제 PubgApiConfig의 RestClient와 로컬 HTTP 서버로 확인했고 정상 디코딩되었다. 해당 의심은 결함으로 보고하지 않았다.

테스트 통과는 이 보고서의 경쟁 상태·운영 부하 경로가 검증되었다는 뜻이 아니다. 건너뛴 PostgreSQL integration 및 baseline 검증, DB 잠금/DDL, 다중 노드, 실제 JDA 재연결, heap/부하 시험은 별도 환경이 필요하다. 역방향 프록시·HTTPS cookie/header·운영 profile·실제 테이블 인덱스는 조회하지 않았다.

## 3. 발견 사항 목록

| ID | 등급 | 문제 | 증거 수준 |
|---|---|---|---|
| H01 | High | 로그인 성공 시 세션 ID를 교체하지 않음 | 코드로 확인 |
| H02 | High | 세션 인증 API의 CSRF 방어 부재 | 코드로 확인 |
| H03 | High | 외부 네트워크 대기 동안 DB 트랜잭션·커넥션을 유지 | 코드로 확인 |
| H04 | High | Discord 이벤트의 실제 대기열이 무제한 | 별도 JVM 재현 + 코드로 확인 |
| H05 | High | 느린 킬내기 정산이 전체 기본 스케줄러를 점유 | 코드로 확인 |
| H06 | High | 실패하는 앞선 20개 결과 후보가 후속 대회를 영구적으로 굶길 수 있음 | 코드로 확인 |
| H07 | High | 빙고 재집계에서 취소된 줄·목표·블랙빙고 완료 상태가 남음 | 별도 JVM 재현 + 코드로 확인 |
| H08 | High | 부분 실패한 빙고 집계가 최종 완료되어 재시도를 막음 | 코드로 확인 |
| H09 | High | 빙고 외부 수집 중 변경된 상태·기간·입력을 반영 시 검증하지 않음 | 코드로 확인 |
| H10 | High | 탈퇴 상태의 우승자가 킬내기 최종 정산 전체를 실패시킴 | 코드로 확인 |
| H11 | High | 닉네임 동기화에서 조회 실패 멤버의 기존 PUBG 연결까지 삭제 | 코드로 확인 |
| H12 | High | 커뮤니티 가입 시 삭제 옵션을 무시하고 원본 커뮤니티를 자동 삭제 | 코드로 확인 |
| H13 | High | 킬내기 확정·삭제의 잠금 순서가 반대여서 deadlock 가능 | 코드상 잠금 순환 확인; PostgreSQL 재현 필요 |
| M01 | Medium | 운영 스키마 정합성을 수동 DDL과 ddl-auto=update에 의존 | 배포 조건부 위험; 운영 스키마 미조회 |
| M02 | Medium | Discord OAuth 결과가 시작한 사용자·세션에 바인딩되지 않음 | 코드로 확인 |
| M03 | Medium | 활동 동기화의 stale 작업이 새 작업 상태·결과를 덮을 수 있음 | 코드로 확인 |
| M04 | Medium | 활동 동기화가 준비 당시의 계정 엔티티를 다시 저장 | 코드로 확인 |
| M05 | Medium | 일부 외부 호출에 애플리케이션 시간 제한이 없음 | 코드로 확인 |
| M06 | Medium | PUBG 단일 synchronized가 전체 커뮤니티의 조회를 직렬화 | 코드로 확인 |
| M07 | Medium | 예약된 PUBG 요청이 새 429 cooldown을 무시 | 별도 JVM 재현 + 코드로 확인 |
| M08 | Medium | PUBG 가상 스레드의 대기 작업 수·취소가 제한되지 않음 | 코드로 확인 |
| M09 | Medium | 시즌 목록 30일 캐시로 현재/이전 시즌 선택이 오래 틀릴 수 있음 | 코드로 확인 |
| M10 | Medium | 빙고 조회가 커뮤니티 전체 쓰기 잠금과 N+1 조회를 발생 | 코드로 확인 |
| M11 | Medium | telemetry 후보가 모든 과거 이력을 읽고 players N+1을 발생 | 코드로 확인 |
| M12 | Medium | PUBG fact·음성 이력 등에 보관 상한/정리 경로가 없음 | 코드로 확인 |
| M13 | Medium | 삭제된 이벤트·커뮤니티·Guild의 메모리 상태가 계속 남음 | 코드로 확인 |
| M14 | Medium | 다중 인스턴스에서 세션·OAuth·작업·rate limit·Discord 잠금이 공유되지 않음 | 다중 인스턴스 배포 조건부 위험 |
| M15 | Medium | Discord 음성 이벤트 누락 후 정상 복구 경로가 제한적 | 코드로 확인 |
| M16 | Medium | Discord 탈퇴·역할 제외가 GuildUp 접근 권한을 철회하지 않음 | 정책 확인 필요; 현재 동작은 코드로 확인 |
| M17 | Medium | DM 중복 방지가 10초 만료되며 실행 중 작업을 보호하지 않음 | 코드로 확인 |
| M18 | Medium | 커뮤니티 탐색이 모든 연결 서버의 전체 멤버를 순차 로딩 | 코드로 확인 |
| M19 | Medium | Discord 역할 설정의 집합 교체가 동시 요청에서 섞일 수 있음 | 동시 실행 추론; PostgreSQL 재현 필요 |
| M20 | Medium | 모니터링 큐/DB 실패의 유실량과 권한 변경 이력이 보이지 않음 | 코드로 확인 |
| M21 | Medium | 팀 재편성 입력 크기와 계산 비용에 상한이 없음 | 코드로 확인 |
| L01 | Low | 동일 Discord 계정의 최초 동시 로그인 중 한 요청이 500 실패 가능 | 코드로 확인 |
| L02 | Low | 비동기 HTTP 오류가 HTTP_5XX 모니터링에서 빠질 수 있음 | 코드로 확인 |

## 4. 문제별 상세

### H01 · High · 로그인 성공 시 세션 ID를 교체하지 않음

- **발생 조건:** 공격자가 피해자의 로그인 전 세션 ID를 알고 있거나 세션 고정을 유도할 수 있는 배포 환경에서 피해자가 Discord 로그인을 완료한다.
- **영향:** 로그인 전 세션이 인증 세션으로 승격되어 계정 접근 권한을 탈취할 수 있다.
- **원인/근거:** 콜백은 기존 HttpSession에 USER_ID만 저장한다. 인증 성공에 따른 changeSessionId 또는 새 세션 생성이 없다. OAuth state 검증은 로그인 요청 위조를 막지만 세션 고정을 막지 않는다. 세션 고정이 가능한 전달 경로 자체는 운영 쿠키·컨테이너·프록시 설정에 따라 달라진다.
- **관련 코드:** [DiscordLoginController.java:174](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/user/auth/controller/DiscordLoginController.java:174)
- **수정 방법:** 콜백에서 인증 성공 직후 세션 ID를 회전하고 필요한 값만 유지한다. HTTPS 전용 Secure/HttpOnly, 명시적 SameSite, COOKIE 전용 추적 정책을 운영 설정과 프록시에서 확인한다.
- **재현/검증:** 로그인 전/후 세션 ID가 달라지고 이전 ID로 인증 API에 접근할 수 없는지 검증한다.

### H02 · High · 세션 인증 API의 CSRF 방어 부재

- **발생 조건:** 피해자의 세션 쿠키가 공격 요청에 첨부되는 환경: SameSite=None 구성, 같은 사이트의 다른 origin 장악, 구형 클라이언트 또는 쿠키 정책이 약한 배포.
- **영향:** 피해자 권한으로 상태 변경 요청을 실행할 수 있다. 본문 없는 POST 출석·로그아웃 등은 단순 form 요청으로도 호출 가능하다.
- **원인/근거:** 커뮤니티 interceptor는 로그인·역할을 검사하지만 CSRF 토큰 또는 Origin/Referer 검증은 하지 않는다. Spring Security 의존성/필터도 없으며 세션 쿠키 정책이 저장소에 명시되지 않았다. 모든 JSON API가 임의의 교차 사이트 form으로 실행된다고 주장하는 것은 아니다.
- **관련 코드:** [CommunityWebConfig.java:12](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/config/CommunityWebConfig.java:12), [CommunityScoreController.java:31](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/controller/CommunityScoreController.java:31), [DiscordLoginController.java:279](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/user/auth/controller/DiscordLoginController.java:279), [pom.xml:32](/Users/kwonseonghyeon/Desktop/guildup-backend/pom.xml:32)
- **수정 방법:** 세션으로 인증하는 변경 요청에 CSRF 토큰 검증을 적용한다. 프런트엔드 요청에 토큰을 전달하고 허용 Origin 검증 및 쿠키 정책을 함께 적용한다.
- **재현/검증:** 쿠키가 있는 타 origin의 form/POST와 누락·오류 토큰 요청을 차단하고 정상 프런트엔드 요청은 허용하는지 확인한다.

### H03 · High · 외부 네트워크 대기 동안 DB 트랜잭션·커넥션을 유지

- **발생 조건:** PUBG 429/장애, Discord 대형 서버 조회, SMTP 지연 중 팀 생성·닉네임 동기화·가입 탐색·피드백 요청이 겹친다.
- **영향:** 외부 장애가 DB 풀 고갈과 다른 커뮤니티 API의 대기·타임아웃으로 전파된다. 쓰기 트랜잭션은 잠금 유지 시간도 늘어난다.
- **원인/근거:** @Transactional 메서드에서 DB 조회 후 PUBG·Discord 호출 또는 메일 발송을 수행한다. readOnly=true도 커넥션을 반환하는 의미가 아니다. 팀 생성은 외부 통계 조회와 계산까지 같은 트랜잭션이다.
- **관련 코드:** [CommunityGameNicknameSyncService.java:77](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityGameNicknameSyncService.java:77), [CommunityTeamMakerService.java:101](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityTeamMakerService.java:101), [CommunityMembershipService.java:139](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMembershipService.java:139), [FeedbackService.java:23](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/feedback/service/FeedbackService.java:23), [CommunityGameNicknameRuleService.java:80](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityGameNicknameRuleService.java:80)
- **수정 방법:** 짧은 조회 트랜잭션에서 불변 입력을 준비하고 외부 호출은 트랜잭션 밖에서 실행한다. 반영 단계에서는 별도의 짧은 쓰기 트랜잭션과 입력 버전 검증을 사용한다. 전체 작업 시간 예산을 둔다.
- **재현/검증:** 외부 서버를 느리게 만든 상태에서 동시 요청을 보내고 Hikari active/pending 및 무관한 API 응답 시간을 측정한다.

### H04 · High · Discord 이벤트의 실제 대기열이 무제한

- **발생 조건:** 역할 일괄 변경·입퇴장 이벤트가 급증하거나 DB 처리가 느려진다.
- **영향:** Runnable과 JDA 객체 참조가 계속 쌓여 메모리 고갈, 장시간 지연, 뒤늦은 상태 반영을 일으킬 수 있다.
- **원인/근거:** 256개의 SerialQueue가 각각 제한 없는 ArrayDeque를 사용한다. 실행기에는 각 stripe의 첫 작업만 제출하므로 실행기의 queue-capacity=10000은 이 내부 대기열을 제한하지 않는다.
- **관련 코드:** [DiscordMemberEventDispatcher.java:74](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/bot/DiscordMemberEventDispatcher.java:74), [DiscordMemberEventDispatcher.java:82](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/bot/DiscordMemberEventDispatcher.java:82), [application.properties:36](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/resources/application.properties:36)
- **수정 방법:** 실제 내부 큐에 상한과 과부하 처리 정책을 둔다. 동일 사용자 상태 이벤트는 순서·버전 의미를 보존하면서 합치고, 유실 시 전체 동기화로 복구한다. pending/oldest-age/drop 지표를 기록한다.
- **재현/검증:** 작업을 실행하지 않는 TaskExecutor에 동일 사용자 이벤트 20,000건을 전달: EXECUTOR_SUBMITTED=1, INTERNAL_PENDING=20000.

### H05 · High · 느린 킬내기 정산이 전체 기본 스케줄러를 점유

- **발생 조건:** 자동 결과 발표에서 PUBG 호출·재시도가 오래 걸리거나 후보 대회가 여러 건 존재한다. 저장소의 기본 스케줄러 설정으로 실행한다.
- **영향:** 결과 발표뿐 아니라 DB 풀 감시, 미정산 감시, 모니터링 보관 정리도 지연된다. 장애가 발생한 시점에 감시 작업까지 멈출 수 있다.
- **원인/근거:** fixedDelay 스케줄러가 후보를 순차 처리하며 publishDueResult의 외부 호출 완료까지 기다린다. 별도 TaskScheduler 빈/스케줄링 풀 설정이 없다. Spring Boot 기본 ThreadPoolTaskScheduler는 한 스레드다. 운영 환경에서 별도 설정을 주입했다면 영향은 달라진다.
- **관련 코드:** [KillCompetitionResultScheduler.java:30](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/killcompetition/service/KillCompetitionResultScheduler.java:30), [KillCompetitionSettlementService.java:104](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/killcompetition/service/KillCompetitionSettlementService.java:104), [DatabasePoolMonitor.java:25](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/monitoring/service/DatabasePoolMonitor.java:25), [MonitoringRetentionScheduler.java:29](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/monitoring/service/MonitoringRetentionScheduler.java:29)
- **수정 방법:** 폴링 스케줄러는 짧게 DB claim만 하고 제한된 정산 실행기에 작업을 전달한다. 모니터링 스케줄러를 분리하고 정산 작업의 최대 실행 시간·재시도를 제한한다.
- **재현/검증:** PUBG 지연 중에도 모니터링 주기가 유지되고 대회 하나가 후속 작업 전체를 막지 않는지 확인한다.

### H06 · High · 실패하는 앞선 20개 결과 후보가 후속 대회를 영구적으로 굶길 수 있음

- **발생 조건:** 가장 오래된 RESULT_PENDING 후보 20개가 반복 실패하거나 claim이 아직 유효해 실행할 수 없다. 그 뒤에 새 발표 대상이 생긴다.
- **영향:** 뒤의 정상 대회가 계속 조회 대상에서 제외되어 결과 발표가 진행되지 않는다.
- **원인/근거:** 후보 쿼리는 resultPublishAt/id 순으로 첫 20건만 반환하며 retry 시각·유효 claim·실패 횟수를 필터링하지 않는다. 다음 tick도 같은 앞선 20건을 다시 읽는다. 개별 예외를 catch하는 것만으로 조회 공정성이 확보되지 않는다.
- **관련 코드:** [KillCompetitionRepository.java:37](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/killcompetition/repository/KillCompetitionRepository.java:37), [KillCompetitionSettlementStore.java:92](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/killcompetition/service/KillCompetitionSettlementStore.java:92), [KillCompetitionResultScheduler.java:41](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/killcompetition/service/KillCompetitionResultScheduler.java:41)
- **수정 방법:** 실행 가능한 lease/재시도 시각을 후보 조건에 포함하고 실패 건에는 backoff를 적용한다. 한 번의 순회에서 실패 후보를 건너뛰어 후속 건도 처리하도록 조회·claim 순서를 바꾼다.
- **재현/검증:** 실패 후보 20개 뒤의 성공 후보가 제한된 시간 안에 처리되는지 PostgreSQL에서 확인한다.

### H07 · High · 빙고 재집계에서 취소된 줄·목표·블랙빙고 완료 상태가 남음

- **발생 조건:** 봇 제외·클랜 조건 변경 또는 새 계정/수정 fact로 전체 재집계하여 기존 완료 셀이 미완료로 바뀐다.
- **영향:** 줄 목록과 lineCount가 불일치하고 취소된 목표·블랙빙고 완료 시각이 남아 잘못된 달성자·순위를 표시한다.
- **원인/근거:** 전체 재계산은 셀 snapshot을 감소시킬 수 있지만 updateLines는 신규 줄만 추가한다. BingoParticipant.updateLines는 완료 시각이 null일 때만 설정하고 미완료일 때 비우지 않는다. 정확히 다시 만드는 rebuildLines가 이미 있지만 일반 재계산 경로에서는 사용하지 않는다.
- **관련 코드:** [BingoAggregationCalculationService.java:137](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationCalculationService.java:137), [BingoAggregationCalculationService.java:153](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationCalculationService.java:153), [BingoProgressCompletionService.java:31](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoProgressCompletionService.java:31), [BingoParticipant.java:35](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/domain/BingoParticipant.java:35)
- **수정 방법:** 감소 가능한 전체 재계산에서는 기존 rebuildLines로 파생 상태를 같은 트랜잭션에서 다시 만든다. 완료 기준과 완료 시각을 현재 셀 결과에서 재계산한다.
- **재현/검증:** 실제 도메인 객체에서 완료 후 updateLines(0,false,false,...) 호출 시 lineCount=0인데 목표·블랙빙고 시각이 유지됨을 재현했다.

### H08 · High · 부분 실패한 빙고 집계가 최종 완료되어 재시도를 막음

- **발생 조건:** SETTLING 상태에서 종료 유예가 지난 뒤 match/telemetry 수집이 일부 실패한다. 특히 excludeBotCombatStats=false이면 telemetry 누락을 건너뛴다.
- **영향:** 누락된 경기의 미션 실적을 반영하지 않은 채 COMPLETED가 되고 일반 집계 요청은 더 이상 허용되지 않는다.
- **원인/근거:** calculate에는 수집 성공 여부가 전달되지 않으며 시간·상태만으로 event.complete를 호출한다. telemetry 실패는 계산 후 응답에 경고로 덧붙인다. matchFailures는 일반 집계 응답의 경고에 포함되지 않는다. 경고의 “다음 집계 재시도”와 완료 상태가 충돌한다.
- **관련 코드:** [BingoAggregationService.java:83](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationService.java:83), [BingoAggregationService.java:112](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationService.java:112), [BingoAggregationCalculationService.java:67](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationCalculationService.java:67), [BingoAggregationPreparationService.java:59](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationPreparationService.java:59)
- **수정 방법:** 정산 범위의 미수집·미갱신 fact가 있으면 완료 전환을 보류하고 재시도 가능한 상태를 유지한다. player/match/DB 저장/telemetry 실패를 구분하여 완료 판단에 전달한다.
- **재현/검증:** 마지막 정산에서 telemetry 또는 match 한 건을 실패시킨 뒤 복구 집계가 가능하고 해당 경기가 최종 결과에 포함되는지 확인한다.

### H09 · High · 빙고 외부 수집 중 변경된 상태·기간·입력을 반영 시 검증하지 않음

- **발생 조건:** 집계 준비 이후 외부 PUBG 호출이 진행되는 동안 이벤트를 취소하거나 기간·조건·참가 계정을 바꾼다. 또는 전체/개인 집계가 겹친다.
- **영향:** 취소된 이벤트에 진행률이 반영되거나 오래된 범위의 사실로 최신 진행률을 다시 계산하여 실적을 지울 수 있다.
- **원인/근거:** PreparedAggregation은 호출 전의 범위·옵션·계정을 보관한다. 최종 계산은 최신 엔티티를 읽지만 준비 버전과 비교하지 않고 전달받은 storedFacts를 적용한다. 참가 eligibleFrom 하한은 검사하지만 최종 이벤트 기간 전체와 준비 당시 입력의 일치 여부는 재검증하지 않는다. DB row lock은 외부 입력의 유효성을 보장하지 않는다.
- **관련 코드:** [BingoAggregationPreparationService.java:183](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationPreparationService.java:183), [BingoAggregationService.java:99](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationService.java:99), [BingoAggregationCalculationService.java:48](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationCalculationService.java:48), [BingoAggregationCalculationService.java:98](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationCalculationService.java:98)
- **수정 방법:** 준비 결과에 이벤트/계정/설정 버전을 포함한다. 쓰기 잠금 후 상태·기간·입력 버전을 비교하여 오래된 작업을 거절하거나 최신 입력으로 다시 준비한다. 개인·전체 집계의 겹침도 같은 기준으로 제어한다.
- **재현/검증:** 수집 중 취소·기간 축소·계정 변경과 역순 완료를 강제로 만들고 오래된 작업이 DB 결과를 변경하지 않는지 검증한다.

### H10 · High · 탈퇴 상태의 우승자가 킬내기 최종 정산 전체를 실패시킴

- **발생 조건:** 승인 참가자 4명 이상인 대회에서 우승 멤버가 최종 결과 지급 전에 LEFT로 바뀐다.
- **영향:** 점수 지급 단계의 409가 최종 정산 전체를 rollback시켜 대회가 RESULT_PENDING에 계속 남는다. 다른 참가자의 결과도 확정되지 않는다.
- **원인/근거:** winnerResolver 결과에 대해 addKillCompetitionWinIfEligible을 호출하지만 이 메서드는 ACTIVE 행을 찾지 못하면 예외를 던진다. “지급할 수 없는 우승자”를 건너뛰는 동작이 아니다.
- **관련 코드:** [KillCompetitionSettlementStore.java:123](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/killcompetition/service/KillCompetitionSettlementStore.java:123), [CommunityScoreService.java:47](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityScoreService.java:47)
- **수정 방법:** 대회 결과 확정과 현재 활동 자격에 따른 지급 정책을 일치시킨다. 참가 시점/정산 시점의 자격을 명시하고 지급 대상이 아닌 멤버가 있어도 결과 전체가 영구 실패하지 않도록 처리한다.
- **재현/검증:** LEFT 우승자와 ACTIVE 공동 우승자가 섞인 대회가 정산되고 점수 원장·총점·빙고 결과가 정책에 맞게 일치하는지 검증한다.

### H11 · High · 닉네임 동기화에서 조회 실패 멤버의 기존 PUBG 연결까지 삭제

- **발생 조건:** 규칙으로 닉네임을 추출하지 못하거나 PUBG 조회가 일부 계정을 반환하지 않거나 동일 계정 중복 때문에 일부 멤버를 건너뛴다.
- **영향:** 이미 정상적으로 연결된 계정이 사라져 활동·팀 생성·새 이벤트 참가의 입력이 손실된다. 실패 개수만 표시되어 원인 파악도 어렵다.
- **원인/근거:** 새 계정 목록에는 성공한 멤버만 넣지만 기존 ACTIVE 계정 목록 전체를 삭제하고 새 목록을 저장한다. 전체 API 예외의 rollback과 달리 정상 응답 내 누락은 삭제로 반영된다.
- **관련 코드:** [CommunityGameNicknameSyncService.java:133](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityGameNicknameSyncService.java:133), [CommunityGameNicknameSyncService.java:148](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityGameNicknameSyncService.java:148)
- **수정 방법:** 성공적으로 확인한 멤버의 연결만 변경한다. 조회 불가·추출 실패·중복 충돌은 기존 연결을 보존하고 실패 이유를 분리한다. 명시적인 연결 해제와 조회 실패를 같은 상태로 처리하지 않는다.
- **재현/검증:** 기존 계정이 있는 멤버 한 명을 PUBG 응답에서 누락시키고 연결이 유지되는지 확인한다.

### H12 · High · 커뮤니티 가입 시 삭제 옵션을 무시하고 원본 커뮤니티를 자동 삭제

- **발생 조건:** 생성 1시간 이내, OWNER 가입자 1명, Discord 미연결인 원본 커뮤니티에서 다른 서버 커뮤니티에 신규 가입한다. 원본에 게시물·수동 클랜원 등이 이미 있을 수 있다.
- **영향:** discardSourceCommunity=false여도 원본이 삭제된다. cascade 대상 데이터는 유실되고 다른 자식 FK가 있으면 가입 트랜잭션이 실패할 수 있다.
- **원인/근거:** 신규 가입 성공 경로는 삭제 플래그와 무관하게 discardNewUnconnectedSource를 호출한다. “빈 커뮤니티” 판단은 membership 수·생성 시각·연결 여부만 확인하며 콘텐츠는 확인하지 않는다. canonical CommunityDeletionStore 대신 JPA delete/flush를 직접 사용한다.
- **관련 코드:** [CommunityMembershipService.java:126](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMembershipService.java:126), [CommunityMembershipService.java:228](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMembershipService.java:228), [CommunityMembershipService.java:237](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMembershipService.java:237), [CommunityDeletionStore.java:11](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/repository/CommunityDeletionStore.java:11)
- **수정 방법:** 모든 가입 경로에서 삭제 플래그를 존중한다. 자동 정리는 실제 콘텐츠가 없는 임시 커뮤니티로 제한한다. 삭제를 요청한 경우에는 검증된 공통 삭제 경로를 사용하고 FK 실패를 “이미 가입됨”으로 바꾸지 않는다.
- **재현/검증:** 삭제 옵션 false, 게시물 있음, 수동 멤버 있음, 빙고 자식 있음 각각에서 원본과 가입 결과를 확인한다.

### H13 · High · 킬내기 확정·삭제의 잠금 순서가 반대여서 deadlock 가능

- **발생 조건:** 대회 A 최종 확정과 대회 B 삭제가 동시에 실행되며 같은 우승 멤버·빙고 이벤트를 공유한다. 같은 대회 잠금은 서로 다른 두 대회이므로 직렬화하지 못한다.
- **영향:** PostgreSQL이 한 트랜잭션을 deadlock victim으로 중단한다. 결과 확정/삭제가 실패하고 잠금 대기로 연결과 요청 시간이 증가한다.
- **원인/근거:** 확정은 competition→score member→bingo event/participant 순이다. 삭제는 competition→bingo event→score member 순이다. A가 멤버를 잠근 채 B의 빙고를 기다리고 B가 빙고를 잠근 채 A의 멤버를 기다리는 순환이 가능하다. 삭제 점수 회수의 멤버 순서도 명시적으로 정렬되지 않는다.
- **관련 코드:** [KillCompetitionSettlementStore.java:123](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/killcompetition/service/KillCompetitionSettlementStore.java:123), [KillCompetitionService.java:377](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/killcompetition/service/KillCompetitionService.java:377), [BingoGuildUpContentService.java:90](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoGuildUpContentService.java:90), [CommunityScoreService.java:90](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityScoreService.java:90)
- **수정 방법:** 관련 쓰기 경로 전체에 competition/member/event/participant의 일관된 잠금 순서를 적용하고 같은 종류의 ID는 정렬한다. deadlock 재시도는 멱등성을 보장한 트랜잭션 경계에서만 수행한다.
- **재현/검증:** 서로 다른 두 대회에 barrier를 둔 PostgreSQL 동시 실행으로 순환 대기를 검증한다. 이번 점검에서는 실제 PostgreSQL deadlock을 재현하지 않았다.

### M01 · Medium · 운영 스키마 정합성을 수동 DDL과 ddl-auto=update에 의존

- **발생 조건:** 새 DB 설치 또는 기존 운영 DB 업그레이드에서 수동 migration·부분 unique index·enum CHECK 변경을 누락한다.
- **영향:** 비-PUBG NULL platform 연결의 중복, 기존 CHECK로 인한 상태 저장 실패, 구형 unique 제약으로 인한 Steam/Kakao 동시 연결 실패가 발생할 수 있다.
- **원인/근거:** Flyway/Liquibase가 없고 수동 DDL은 자동 실행되지 않는다. 플랫폼 배포 안내는 앱 시작 전 migration/부분 unique index 적용을 요구한다. Hibernate update만으로 기존 데이터 backfill·제약 교체가 완료되지 않는다. 실제 운영 DB가 누락 상태라는 결론은 내리지 않았다.
- **관련 코드:** [application.properties:11](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/resources/application.properties:11), [enforce_pubg_platform_boundaries.sql:70](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/resources/db/manual/enforce_pubg_platform_boundaries.sql:70), [PUBG_PLATFORM_DEPLOYMENT.md:1](/Users/kwonseonghyeon/Desktop/guildup-backend/PUBG_PLATFORM_DEPLOYMENT.md:1)
- **수정 방법:** 현재 스키마와 기존 수동 절차를 버전 관리되는 migration으로 정리하고 운영은 validate를 사용한다. 배포 전 catalog/데이터 감사로 필요한 unique·CHECK·FK를 확인한다.
- **재현/검증:** 빈 PostgreSQL 설치와 구버전 데이터 upgrade 경로를 모두 검증한다. 실제 운영 pg_catalog와 중복 데이터를 별도로 감사한다.

### M02 · Medium · Discord OAuth 결과가 시작한 사용자·세션에 바인딩되지 않음

- **발생 조건:** 같은 커뮤니티의 다른 사용자에게 유효한 OAuth resultId/install token이 노출되거나 공유된다.
- **영향:** 다른 멤버가 인증자의 Discord 프로필·관리 서버 목록을 읽거나 다른 관리자가 그 인증 결과를 소비해 연결/가입을 진행할 수 있다.
- **원인/근거:** state/result 저장소는 communityId와 난수 ID만 보관하며 시작한 GuildUp userId/session을 저장하지 않는다. 콜백 자체에는 로그인 세션의 state 대조와 관리 권한 검사가 있지만, 인증 결과 조회·소비에는 그 사용자 바인딩이 전달되지 않는다. resultId는 강한 256비트 난수이고 TTL·community 검사는 있어 무작위 추측 공격이나 다른 커뮤니티 IDOR로 분류하지 않는다.
- **관련 코드:** [InMemoryDiscordOAuthSessionStore.java:45](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/oauth/store/InMemoryDiscordOAuthSessionStore.java:45), [InMemoryDiscordOAuthSessionStore.java:68](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/oauth/store/InMemoryDiscordOAuthSessionStore.java:68), [InMemoryDiscordOAuthSessionStore.java:76](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/oauth/store/InMemoryDiscordOAuthSessionStore.java:76), [DiscordOAuthController.java:28](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/oauth/controller/DiscordOAuthController.java:28)
- **수정 방법:** 시작 사용자·세션을 state/result/install token에 기록하고 조회·소비에서 동일한 주체를 검증한다. 사용자에게 result ID를 노출할 때 URL/referrer 전달 경로도 최소화한다.
- **재현/검증:** 관리자 A의 결과를 같은 커뮤니티 멤버 B·관리자 C가 조회하거나 소비할 때 차단되는지 확인한다.

### M03 · Medium · 활동 동기화의 stale 작업이 새 작업 상태·결과를 덮을 수 있음

- **발생 조건:** 활동 작업 A가 30분 이상 지연되어 stale로 간주되고 B가 시작한 뒤 A가 성공 또는 실패한다.
- **영향:** A의 fail이 B를 FAILED로 바꾸거나 A의 오래된 snapshot이 B의 새 결과를 덮는다. 화면상 성공·실패와 실제 실행 작업이 달라진다.
- **원인/근거:** begin은 SYNCING timeout 이후 재시작을 허용하지만 작업별 UUID/세대가 없다. fail은 현재 상태가 SYNCING인지만 검사하고 succeed도 작업 소유권을 검사하지 않는다.
- **관련 코드:** [CommunityActivitySyncPolicy.java:16](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityActivitySyncPolicy.java:16), [CommunityActivitySyncCoordinator.java:42](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityActivitySyncCoordinator.java:42), [CommunityActivitySyncCoordinator.java:68](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityActivitySyncCoordinator.java:68), [CommunityMemberActivitySyncWorker.java:169](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMemberActivitySyncWorker.java:169)
- **수정 방법:** 기존 sync 행에 작업 세대를 기록하고 성공·실패·snapshot 교체는 자신의 lease/token이 현재 소유자인 경우에만 실행한다. 기존 타임아웃 처리도 같은 기준으로 변경한다.
- **재현/검증:** A 시작→시간 경과→B 시작→A 실패/성공 순서를 강제하여 B의 상태와 데이터가 유지되는지 검증한다.

### M04 · Medium · 활동 동기화가 준비 당시의 계정 엔티티를 다시 저장

- **발생 조건:** 활동 외부 수집 중 닉네임 동기화가 계정 연결을 삭제·교체하거나 클랜원 상태·계정을 변경한다.
- **영향:** 오래된 externalUsername이 새 값을 덮거나 삭제된 엔티티 merge/unique 충돌로 전체 활동 동기화가 실패한다. snapshot이 현재 계정 연결과 다른 입력으로 만들어질 수 있다.
- **원인/근거:** 외부 호출 전 읽은 accountsByMemberId 엔티티를 마지막 트랜잭션에서 saveAll한다. 최신 계정 행·멤버 상태·규칙 버전과 비교하지 않는다. 삭제된 행이 반드시 재생성된다고 단정하지 않으며 Hibernate merge 실패도 실제 영향이다.
- **관련 코드:** [CommunityMemberActivitySyncWorker.java:162](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMemberActivitySyncWorker.java:162), [CommunityMemberActivitySyncWorker.java:163](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMemberActivitySyncWorker.java:163)
- **수정 방법:** 외부 수집 입력은 DTO로 보관하고 반영 시 최신 계정 ID·플랫폼·연결 값·상태를 재조회하여 일치할 때만 업데이트한다. 닉네임 변경과 활동 결과 저장을 같은 game/member 버전 규칙으로 제어한다.
- **재현/검증:** 활동 수집 중 연결 삭제·계정 교체·닉네임 변경을 수행하고 오래된 값 덮어쓰기와 전체 rollback 여부를 검증한다.

### M05 · Medium · 일부 외부 호출에 애플리케이션 시간 제한이 없음

- **발생 조건:** Discord OAuth·대형 guild member 로딩·DM rate-limit 대기·SMTP가 지연되거나 Discord 준비가 완료되지 않는다.
- **영향:** 요청/작업 스레드가 서비스 시간 예산 없이 오래 점유된다. JDA 준비에 연결된 앱 시작은 Discord 문제로 지연될 수 있다.
- **원인/근거:** OAuth는 RestClient.create를 사용하고 명시적 connect/read timeout이 없다. guild.loadMembers().get과 DM submit().join에는 애플리케이션 deadline이 없다. SMTP connection/read/write timeout도 설정하지 않았다. 하위 라이브러리의 기본 timeout이 전혀 없다고 단정하는 것은 아니다.
- **관련 코드:** [DiscordOAuthConfig.java:16](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/oauth/config/DiscordOAuthConfig.java:16), [DiscordMemberService.java:67](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/service/DiscordMemberService.java:67), [DiscordDirectMessageClient.java:26](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/service/DiscordDirectMessageClient.java:26), [DiscordConfig.java:42](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/config/DiscordConfig.java:42), [application.properties:21](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/resources/application.properties:21)
- **수정 방법:** 연결·읽기·전체 작업 deadline을 각각 설정한다. timeout 시 남은 future를 취소하고 사용자 응답과 작업 상태를 일관되게 종료한다. Discord startup readiness에도 제한 시간을 정한다.
- **재현/검증:** 응답하지 않는 로컬 서버/완료되지 않는 future로 각 경로가 시간 예산 안에 종료되고 자원이 반환되는지 검증한다.

### M06 · Medium · PUBG 단일 synchronized가 전체 커뮤니티의 조회를 직렬화

- **발생 조건:** 한 요청이 많은 닉네임/계정 또는 시즌 통계를 조회하고 429 재시도·네트워크 대기를 한다.
- **영향:** 다른 커뮤니티와 Steam/Kakao 요청도 같은 모니터 뒤에서 대기하여 전체 PUBG 기능의 지연이 커진다. DB 트랜잭션 안의 호출은 H03 영향도 확대한다.
- **원인/근거:** PubgPlayerService.findInBatches와 PubgSeasonService.getCombinedStats/getSeasons가 HTTP 호출 전체를 synchronized로 감싼다. 캐시 보호 범위를 넘어 모든 batch와 대기 동안 락을 유지한다.
- **관련 코드:** [PubgPlayerService.java:60](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgPlayerService.java:60), [PubgSeasonService.java:54](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgSeasonService.java:54), [PubgSeasonService.java:111](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgSeasonService.java:111)
- **수정 방법:** 캐시 읽기·쓰기만 짧게 동기화하고 외부 호출은 밖에서 수행한다. 같은 키의 중복 조회만 in-flight로 합치며 API governor로 호출 시작 속도를 별도로 제어한다.
- **재현/검증:** 한 shard의 느린 대량 조회 중 다른 shard/커뮤니티의 캐시 조회와 소량 조회가 불필요하게 대기하지 않는지 확인한다.

### M07 · Medium · 예약된 PUBG 요청이 새 429 cooldown을 무시

- **발생 조건:** 요청 B가 acquire에서 실행 시각을 예약하고 잠든 사이 요청 A가 429를 받아 cooldown을 뒤로 연장한다.
- **영향:** B는 새 cooldown 전에 호출되어 429와 재시도를 추가로 유발한다.
- **원인/근거:** acquire는 잠들기 전 한 번만 cooldownUntilMillis를 읽고 sleep 후 다시 검사하지 않는다. cooldownUntil/observe로 예약 이후 제약을 변경해도 이미 기다리는 요청에 적용되지 않는다.
- **관련 코드:** [PubgApiRequestGovernor.java:45](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/client/PubgApiRequestGovernor.java:45), [PubgApiRequestGovernor.java:82](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/client/PubgApiRequestGovernor.java:82), [PubgApiClient.java:313](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/client/PubgApiClient.java:313)
- **수정 방법:** 대기 후 최신 cooldown/허용 시간을 다시 검사하는 획득 루프로 변경한다. 최종 429에서도 공유 cooldown을 갱신하고 서버 Retry-After와 전체 작업 deadline을 일관되게 처리한다.
- **재현/검증:** 실제 governor와 가상 Clock/Sleeper 사용: cooldown을 60,000ms로 바꿔도 acquire가 250ms에 반환하는 것을 재현했다.

### M08 · Medium · PUBG 가상 스레드의 대기 작업 수·취소가 제한되지 않음

- **발생 조건:** 많은 경기/telemetry ID를 가진 집계 요청이 여러 개 겹치거나 일부 조회가 먼저 실패한다.
- **영향:** 동시 HTTP는 제한되어도 가상 스레드·future·입력·결과 참조가 누적된다. 이미 실패한 요청의 나머지 수집이 계속 실행되어 자원과 호출 예산을 쓴다.
- **원인/근거:** Match/telemetry 작업을 모두 submit한 뒤 semaphore로 실제 호출만 제한한다. batch 전체 deadline과 제출 수 상한이 없고 첫 future 오류 시 남은 작업을 모두 취소하지 않는다. virtual thread는 대기 비용을 줄이지만 backlog를 제한하지 않는다.
- **관련 코드:** [PubgMatchService.java:115](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgMatchService.java:115), [PubgMatchService.java:120](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgMatchService.java:120), [PubgMatchSyncService.java:149](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgMatchSyncService.java:149)
- **수정 방법:** 제한된 window만 제출하는 방식으로 pending 수를 제한한다. 전체 deadline·취소 전파를 적용하고 종료·실패 시 미완료 future를 정리한다. 큐 길이와 permit 대기 시간을 측정한다.
- **재현/검증:** 동시 대량 집계와 첫 작업 실패에서 pending task/메모리가 상한을 지키고 나머지 작업이 정책대로 종료되는지 확인한다.

### M09 · Medium · 시즌 목록 30일 캐시로 현재/이전 시즌 선택이 오래 틀릴 수 있음

- **발생 조건:** 시즌 목록 캐시 생성 후 30일 이내에 PUBG 시즌이 전환된다.
- **영향:** 팀 생성이 종료된 시즌을 현재 시즌으로 사용하고 실제 현재 시즌 통계를 누락하여 팀 편성 기준을 왜곡한다.
- **원인/근거:** current 플래그를 포함하는 시즌 목록을 30일 유지하고 이후 요청도 이 목록에서 현재/이전 시즌을 고른다. 플레이어 통계의 30분 TTL로 목록의 current 플래그가 갱신되지는 않는다.
- **관련 코드:** [PubgSeasonService.java:22](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgSeasonService.java:22), [PubgSeasonService.java:40](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgSeasonService.java:40), [PubgSeasonService.java:111](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgSeasonService.java:111)
- **수정 방법:** 현재 시즌 판단에 적합한 짧은 TTL을 적용하거나 시즌 전환·빈 통계 응답 시 목록을 갱신한다. 과거의 고정 시즌 메타데이터와 현재 플래그의 캐시 수명을 분리한다.
- **재현/검증:** API의 current 시즌이 바뀌는 시점에 캐시가 새 시즌을 제한된 시간 안에 선택하는지 확인한다.

### M10 · Medium · 빙고 조회가 커뮤니티 전체 쓰기 잠금과 N+1 조회를 발생

- **발생 조건:** 큰 커뮤니티에서 빙고 current/detail/completion 등을 여러 사용자가 동시에 조회한다.
- **영향:** 조회 요청끼리도 직렬화되고 다른 변경 작업이 지연된다. 멤버·참가자·이벤트 수에 비례해 SQL이 증가한다.
- **원인/근거:** GET 경로가 lockCommunity와 전체 이벤트 상태 갱신/참가자 등록을 실행한다. enrollEligible은 사용자별 exists 조회, detail은 참가자별 progress 조회를 한다. EntityGraph로 사용자 관계를 가져와도 이 별도 progress 쿼리는 없어지지 않는다.
- **관련 코드:** [BingoEventService.java:82](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoEventService.java:82), [BingoEventService.java:196](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoEventService.java:196), [BingoEventService.java:234](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoEventService.java:234), [BingoParticipantEnrollmentService.java:38](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoParticipantEnrollmentService.java:38)
- **수정 방법:** 조회와 상태 변경/등록의 실행 경계를 분리하거나 변경이 필요한 행만 잠근다. 기존 참가자 ID와 progress를 일괄 조회한다. 이벤트/참가자 목록에 필요한 범위와 pagination을 적용한다.
- **재현/검증:** 100/1,000명 규모에서 SQL 수와 동시 GET p95, write lock 대기 시간을 측정한다. 이번에는 실 DB query count/load test를 실행하지 않았다.

### M11 · Medium · telemetry 후보가 모든 과거 이력을 읽고 players N+1을 발생

- **발생 조건:** 누적 경기 이력이 많거나 오래된 telemetry 미수집·fact 버전이 많이 남아 있다.
- **영향:** 짧은 기간의 빙고 집계에도 과거 전체 후보를 읽고 추가 SQL/API를 실행한다. 넓은 집계의 kill IN 목록은 메모리와 bind parameter 부담을 키운다.
- **원인/근거:** findTelemetryMissingForAccounts/UpgradeCandidatesForAccounts는 from/to가 없고 join은 fetch join이 아니다. toMatch에서 각 match.players를 읽어 lazy 추가 조회가 발생한다. 일반 facts 조회는 account ID를 500개씩 나누지만 kills 조회의 match ID 목록은 한 번에 전달한다.
- **관련 코드:** [PubgStoredMatchRepository.java:34](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/repository/PubgStoredMatchRepository.java:34), [PubgStoredMatchRepository.java:44](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/repository/PubgStoredMatchRepository.java:44), [PubgMatchFactQueryService.java:144](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgMatchFactQueryService.java:144), [PubgMatchFactQueryService.java:51](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgMatchFactQueryService.java:51)
- **수정 방법:** 집계 기간을 후보 쿼리에 전달하고 필요한 players를 fetch/batch로 읽는다. 경기 ID도 chunk/pagination하여 조회하고 실행 계획에 맞는 shard/account/date 인덱스를 확인한다.
- **재현/검증:** 오래된 경기 10만 건 중 짧은 이벤트 범위만 조회하도록 SQL 수·조회 rows·telemetry calls·EXPLAIN을 확인한다.

### M12 · Medium · PUBG fact·음성 이력 등에 보관 상한/정리 경로가 없음

- **발생 조건:** 게임 수집과 음성 입퇴장 기록이 수개월·수년 누적된다.
- **영향:** 경기당 player/kill 자식 행, 음성 세션 및 soft-delete 데이터가 계속 증가하여 저장 공간·인덱스·백업·집계 비용이 커진다.
- **원인/근거:** 모니터링 이벤트에는 retention scheduler가 있지만 공유 PUBG match/player/kill fact와 Discord voice session에는 대응하는 정리 작업이 없다. community 삭제도 글로벌 fact를 의도적으로 남긴다. 현재 데이터 양이나 디스크 고갈을 측정한 것은 아니다.
- **관련 코드:** [PubgStoredMatchRepository.java:15](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/repository/PubgStoredMatchRepository.java:15), [DiscordVoiceSessionRepository.java:14](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/repository/DiscordVoiceSessionRepository.java:14), [CommunityDeletionStore.java:11](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/repository/CommunityDeletionStore.java:11), [MonitoringRetentionScheduler.java:29](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/monitoring/service/MonitoringRetentionScheduler.java:29)
- **수정 방법:** 활성/정산 이벤트 참조와 재집계 필요 기간을 보존하는 retention 기준을 정하고 오래된 데이터는 제한된 batch로 정리·보관한다. 테이블 크기와 기간별 증가량을 운영에서 관측한다.
- **재현/검증:** 실제 테이블·인덱스 크기와 증가율을 확인하고 유지할 이벤트 참조를 훼손하지 않는지 삭제 계획을 검증한다.

### M13 · Medium · 삭제된 이벤트·커뮤니티·Guild의 메모리 상태가 계속 남음

- **발생 조건:** 커뮤니티/빙고 생성·삭제, guild 연결·제거가 반복되는 장기 실행 프로세스.
- **영향:** 과거 JobKey·lock·Guild/JDA 객체 참조가 누적된다. 시즌 통계 캐시도 동시 고유 계정 수가 많으면 TTL 안에 큰 메모리를 사용할 수 있다.
- **원인/근거:** 빙고 jobs는 완료/삭제 후 제거 또는 크기 제한이 없고 communityLocks와 guildLoadLocks도 생명주기 정리가 없다. memberSnapshots 자체의 TTL/개수 제한이 guildLoadLocks를 정리하지는 않는다. statsCache는 TTL은 있지만 개수/용량 제한은 없다.
- **관련 코드:** [BingoAggregationJobService.java:44](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationJobService.java:44), [CommunityMemberSyncLockManager.java:13](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMemberSyncLockManager.java:13), [DiscordMemberService.java:29](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/service/DiscordMemberService.java:29), [PubgSeasonService.java:28](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgSeasonService.java:28)
- **수정 방법:** 완료 job에 TTL/개수 상한을 두고 삭제 시 상태를 정리한다. 잠금은 사용 중인 객체를 잘못 교체하지 않는 방식으로 관리하고 Guild 객체 대신 안정적인 ID를 사용한다. 캐시는 최대 용량도 설정한다.
- **재현/검증:** 반복 생성/삭제 후 retained object와 map 크기가 안정되는지 heap 분석으로 검증한다.

### M14 · Medium · 다중 인스턴스에서 세션·OAuth·작업·rate limit·Discord 잠금이 공유되지 않음

- **발생 조건:** 로드밸런서 뒤에 인스턴스 2개 이상을 배치하거나 rolling deployment 중 두 프로세스가 동시에 동작한다.
- **영향:** 인증 상태/결과 조회 실패, 중복 집계·DM·Discord 이벤트 처리, API key 단위 호출 한도 초과, 클랜원/음성 상태 경쟁이 발생할 수 있다.
- **원인/근거:** 세션은 기본 로컬 컨테이너, OAuth/install 결과와 bingo job은 로컬 Map이다. PUBG governor, member/voice locks도 프로세스 내부다. 킬내기 최종 정산의 DB UUID claim은 보호되지만 모든 작업으로 확대되어 있지 않다. 저장소 문서도 일부 저장소를 단일 인스턴스용으로 설명한다.
- **관련 코드:** [InMemoryDiscordOAuthSessionStore.java:37](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/oauth/store/InMemoryDiscordOAuthSessionStore.java:37), [InMemoryDiscordBotInstallStore.java:25](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/oauth/store/InMemoryDiscordBotInstallStore.java:25), [BingoAggregationJobService.java:44](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/bingo/service/BingoAggregationJobService.java:44), [CommunityMemberSyncLockManager.java:13](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMemberSyncLockManager.java:13), [PubgApiRequestGovernor.java:25](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/client/PubgApiRequestGovernor.java:25)
- **수정 방법:** 다중 인스턴스가 필요한 배포에서는 세션/일회용 상태를 공유하고 작업은 DB lease/버전으로 조정한다. API key governor와 Discord 소비자 소유권도 일치시킨다. 단일 인스턴스 운영이라면 그 제약을 배포에서 강제한다.
- **재현/검증:** 로그인 시작/콜백/결과 조회를 서로 다른 노드로 보내고 동일 작업을 두 노드에서 실행한다. sticky session만으로 DB 경쟁·API quota 문제가 해결되는지 별도로 확인한다.

### M15 · Medium · Discord 음성 이벤트 누락 후 정상 복구 경로가 제한적

- **발생 조건:** Gateway 연결 중단 중 음성 퇴장 이벤트를 놓치거나 DB 쓰기가 실패한다. 또는 시작 복구와 실시간 이벤트가 겹친다.
- **영향:** leftAt=null 세션이 계속 열린 상태로 남아 활동 시간이 실제보다 길어지고 채널/연결 상태가 틀릴 수 있다.
- **원인/근거:** voice reconcile 호출은 봇의 ApplicationReady 시작 작업에서 확인되며 Resume/재연결 때의 호출은 없다. unavailable guild는 건너뛴다. 실시간 listener만 로컬 stripe lock을 사용하고 복구 service는 같은 lock 경로를 통하지 않는다. 서비스는 열린 행을 일반 조회 후 수정하며 실패 이벤트의 재시도도 없다.
- **관련 코드:** [DiscordBot.java:54](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/bot/DiscordBot.java:54), [DiscordVoiceRecoveryService.java:42](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/service/DiscordVoiceRecoveryService.java:42), [DiscordVoiceEventListener.java:49](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/bot/DiscordVoiceEventListener.java:49), [DiscordVoiceSessionService.java:40](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/service/DiscordVoiceSessionService.java:40)
- **수정 방법:** 재연결·유실/실패 이후 현재 voice state를 다시 대조한다. 실시간과 복구에 동일한 DB 행 잠금/버전 규칙을 적용하고 처리 시간을 event-time과 구분한다. 복구 불가능한 구간을 연속 접속으로 확정하지 않는다.
- **재현/검증:** Gateway 중단 중 퇴장, DB 일시 오류, 복구와 채널 이동 동시 실행에서 열린 세션의 유일성과 시간 정확성을 검증한다.

### M16 · Medium · Discord 탈퇴·역할 제외가 GuildUp 접근 권한을 철회하지 않음

- **발생 조건:** 운영 정책이 “Discord 서버/클랜 소속을 잃으면 GuildUp 접근도 종료”인데 기존 GuildUp MEMBER/ADMIN이 서버에서 탈퇴·추방되거나 역할에서 제외된다.
- **영향:** 기존 community_users 권한으로 데이터 조회·관리 API에 계속 접근할 수 있다.
- **원인/근거:** Discord 상태 동기화는 CommunityMember ACTIVE/LEFT를 변경한다. 접근 권한은 별도 CommunityUser membership/role만 확인하며 Discord 상태를 참조하지 않는다. README는 두 회원 개념을 명시적으로 분리하므로 정책이 독립 회원권이면 이것은 결함이 아니다.
- **관련 코드:** [CommunityAccessService.java:51](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityAccessService.java:51), [CommunityMemberDiscordStateService.java:31](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMemberDiscordStateService.java:31), [README.md:102](/Users/kwonseonghyeon/Desktop/guildup-backend/README.md:102)
- **수정 방법:** 현재 권한 정책을 먼저 확정한다. Discord 소속 연동이 요구되면 철회 조건과 OWNER 예외를 명시하고 권한 조회/상태 처리에 적용한다. 독립 회원권이 의도라면 운영자가 오해하지 않도록 탈퇴와 권한 철회의 차이를 명시한다.
- **재현/검증:** Discord에서 ADMIN을 추방한 뒤 GuildUp API 권한이 기대 정책과 일치하는지 확인한다.

### M17 · Medium · DM 중복 방지가 10초 만료되며 실행 중 작업을 보호하지 않음

- **발생 조건:** 대량 DM이 10초 이상 걸리는 동안 같은 요청을 다시 보내거나 프록시/브라우저 timeout 후 재시도한다.
- **영향:** 이미 전송한 사용자에게 같은 DM이 중복 발송되고 다중 요청이 JDA 큐와 HTTP 스레드를 점유한다.
- **원인/근거:** registerRequest는 지문·시작 시각만 기록하고 duplicateWindow를 지나면 실행 중이어도 새 요청을 허용한다. 모든 수신자에게 동기 순차 전송하며 서버 전체 작업 예산이나 수신자별 완료 기록이 없다. JDA rate-limit 큐는 중복 전송을 막지 않는다.
- **관련 코드:** [CommunityDiscordDmService.java:120](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityDiscordDmService.java:120), [CommunityDiscordDmService.java:123](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityDiscordDmService.java:123), [application.properties:30](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/resources/application.properties:30), [DiscordDirectMessageClient.java:26](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/service/DiscordDirectMessageClient.java:26)
- **수정 방법:** 기존 전송 요청에 완료 상태와 수신자별 결과를 연결하고 실행 중 동일 요청을 재사용/거절한다. 재시도는 미전송 대상만 처리한다. 최대 수신자 수·실행 시간·동시 작업 수를 제한한다.
- **재현/검증:** 첫 발송을 10초 이상 지연한 뒤 동일 요청과 timeout 재시도를 보내고 성공 수신자 중복 발송이 없는지 확인한다.

### M18 · Medium · 커뮤니티 탐색이 모든 연결 서버의 전체 멤버를 순차 로딩

- **발생 조건:** 연결된 커뮤니티/대형 Discord guild가 늘거나 일반 사용자가 탐색 요청을 반복한다.
- **영향:** 단일 사용자 소속 확인을 위해 많은 JDA 요청·객체를 만들고 가입 확인 SQL도 서버별로 실행한다. 요청 시간이 커지고 공유 member cache/DB 커넥션에 부담을 준다.
- **원인/근거:** discoverByDiscordMembership는 모든 connection을 순회하고 membership exists를 매번 호출한다. containsUser는 getHumanMembers로 전체 guild member snapshot을 로딩한다. 캐시 TTL은 30초이므로 캐시 미스 시 비용이 반복된다.
- **관련 코드:** [CommunityMembershipService.java:139](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMembershipService.java:139), [DiscordMemberService.java:109](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/service/DiscordMemberService.java:109), [DiscordMemberService.java:67](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/discord/service/DiscordMemberService.java:67)
- **수정 방법:** 가입한 community ID를 일괄 조회하고 단일 Discord 사용자 조회 또는 그에 맞는 제한된 캐시를 사용한다. 탐색 후보 수·호출 동시성·요청 빈도를 제한한다.
- **재현/검증:** 많은 guild와 캐시 미스 조건에서 조회 SQL·JDA 호출 수·총 응답 시간과 다른 작업의 지연을 측정한다.

### M19 · Medium · Discord 역할 설정의 집합 교체가 동시 요청에서 섞일 수 있음

- **발생 조건:** 두 ADMIN이 같은 커뮤니티의 역할 집합을 동시에 저장한다. 특히 기존 집합이 비어 있거나 두 요청의 delete가 서로의 insert 전에 실행된다.
- **영향:** 마지막 요청의 집합이 아니라 A와 B의 합집합이 남거나 unique 충돌로 요청이 실패한다. 그 집합을 사용한 클랜원 판정이 의도와 달라진다.
- **원인/근거:** 기존 설정 bulk delete 후 saveAll하지만 커뮤니티/game 수준 잠금이나 version 비교가 없다. 각 트랜잭션의 원자성은 서로의 전체 집합 교체를 직렬화하지 않는다.
- **관련 코드:** [CommunityMemberRoleSettingService.java:52](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMemberRoleSettingService.java:52), [CommunityMemberRoleSettingRepository.java:18](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/repository/CommunityMemberRoleSettingRepository.java:18)
- **수정 방법:** 커뮤니티 설정 행/부모를 같은 순서로 잠그거나 설정 세대를 비교하여 전체 집합 교체를 직렬화한다. 후속 멤버 재동기화도 저장된 설정 세대로 실행한다.
- **재현/검증:** 빈 설정에서 서로 다른 두 역할 집합을 동시에 저장하여 합집합/unique 실패가 아닌 정의된 한 집합만 남는지 확인한다.

### M20 · Medium · 모니터링 큐/DB 실패의 유실량과 권한 변경 이력이 보이지 않음

- **발생 조건:** 업무 장애로 DB 저장이 느려 모니터링 큐 256개가 차거나 오류가 집중된다. 또는 권한 변경의 원인을 사후 조사한다.
- **영향:** 사고가 심한 시점의 이벤트가 누락되어 운영 화면이 실제 장애량보다 낮게 보인다. 누가 누구의 권한을 바꿨는지 독립적인 감사 이력이 부족하다.
- **원인/근거:** MonitoringEventService는 enqueue/write 예외를 잡고 분당 제한 경고만 남긴다. dropped/write-failure 카운터를 상태 API에 노출하지 않는다. changeRole은 역할 저장 후 응답만 반환하며 actor·before/after의 성공 감사 기록이 없다. 기존 requestId·실패 로그·비밀값 정리는 잘 되어 있다.
- **관련 코드:** [MonitoringEventExecutor.java:12](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/monitoring/config/MonitoringEventExecutor.java:12), [MonitoringEventService.java:115](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/monitoring/service/MonitoringEventService.java:115), [MonitoringEventService.java:123](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/monitoring/service/MonitoringEventService.java:123), [CommunityMembershipService.java:216](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityMembershipService.java:216)
- **수정 방법:** 큐 깊이·드롭·저장 실패 수와 마지막 성공을 기록하여 모니터링 신뢰도를 표시한다. 권한 변경·삭제 같은 중요 변경은 actor/target/before/after 결과를 안전하게 기록한다. 장애 이력 자체가 DB 장애와 함께 사라지는 경우를 고려한다.
- **재현/검증:** 큐 포화/DB 실패를 주입하고 드롭 수가 실제 누락량과 맞는지 확인한다. 역할 변경은 성공/실패/주체를 추적할 수 있어야 한다.

### M21 · Medium · 팀 재편성 입력 크기와 계산 비용에 상한이 없음

- **발생 조건:** 권한 있는 사용자가 rebalance에 매우 많은 참가자 또는 중복 참가자를 전달하거나 요청을 반복한다.
- **영향:** CPU와 요청 스레드를 오래 점유하여 같은 프로세스의 다른 커뮤니티 API 응답이 느려질 수 있다.
- **원인/근거:** 재편성은 전달된 참가자 통계를 검증해 계산하지만 전체 참가자 수 상한/ID 중복 제한이 없다. improveBySwapping은 최대 200회 모든 팀 간 멤버 쌍을 비교하고 각 후보마다 전체 score를 다시 계산한다. 일반적인 소규모 참가자에서 문제가 난다는 뜻은 아니다.
- **관련 코드:** [CommunityTeamMakerService.java:164](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityTeamMakerService.java:164), [CommunityTeamMakerService.java:249](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityTeamMakerService.java:249), [TeamBalanceService.java:73](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/TeamBalanceService.java:73), [TeamBalanceService.java:101](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/TeamBalanceService.java:101)
- **수정 방법:** 실제 커뮤니티 규모에 맞는 입력 상한과 memberId 중복 검증을 적용하고 요청 동시성·시간 예산을 제한한다. 점수 합계를 유지하여 후보 교환 비용을 줄일 수 있다.
- **재현/검증:** 정상 최대 규모와 상한 초과·중복 입력에서 처리 시간/CPU를 측정하고 큰 입력이 계산 전에 거절되는지 확인한다.

### L01 · Low · 동일 Discord 계정의 최초 동시 로그인 중 한 요청이 500 실패 가능

- **발생 조건:** 아직 저장되지 않은 동일 Discord 사용자로 여러 콜백이 동시에 findOrCreateUser를 실행한다.
- **영향:** unique 제약이 중복 데이터를 막지만 한 로그인 요청이 실패하여 재시도가 필요하다.
- **원인/근거:** 외부 계정 조회 후 신규 User/계정을 삽입하는 check-then-insert이며 unique 충돌 후 새 트랜잭션으로 기존 계정을 읽는 경로가 없다. 이미 존재하는 계정의 일반 로그인은 해당 조건이 아니다.
- **관련 코드:** [DiscordLoginService.java:65](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/user/auth/service/DiscordLoginService.java:65), [UserExternalAccount.java:23](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/user/domain/UserExternalAccount.java:23)
- **수정 방법:** 동시 생성 unique 충돌 시 rollback 후 별도의 트랜잭션에서 기존 계정을 다시 읽는다. 광범위한 DB 오류를 정상 중복으로 처리하지 않는다.
- **재현/검증:** 동일 계정의 첫 로그인 두 요청을 동시에 실행하여 하나의 계정만 생성되고 두 요청 모두 로그인 가능한지 확인한다.

### L02 · Low · 비동기 HTTP 오류가 HTTP_5XX 모니터링에서 빠질 수 있음

- **발생 조건:** SSE 등 비동기 응답이 async dispatch에서 500 또는 timeout/error로 종료된다.
- **영향:** HTTP 장애 집계와 요청별 오류 추적이 불완전해진다. 현재 SSE는 개발자 전용이어서 일반 사용자 영향 범위는 제한적이다.
- **원인/근거:** HttpServerErrorMonitoringFilter는 최초 finally에서 !isAsyncStarted인 5xx만 기록한다. OncePerRequestFilter의 async dispatch 참여를 별도로 활성화하지 않아 비동기 완료 후 오류를 동일하게 집계하지 않는다.
- **관련 코드:** [HttpServerErrorMonitoringFilter.java:51](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/monitoring/web/HttpServerErrorMonitoringFilter.java:51)
- **수정 방법:** AsyncListener 또는 async dispatch에서 완료/error/timeout을 추적하고 requestId별 중복 기록을 방지한다. 스트림 종료 후에는 응답을 다시 쓰지 않는다.
- **재현/검증:** SSE 처리에서 비동기 예외/timeout을 발생시키고 정상 disconnect와 장애를 구분하여 한 번만 기록하는지 확인한다.

## 5. 확인된 방어와 오탐 제외

- **community 경계:** 커뮤니티 interceptor와 서비스의 membership/role 검사, communityGame 소유권/능력 검사, 부모 리소스와 community/game ID 대조가 존재한다. 점검한 CRUD에서 임의의 다른 community ID로 직접 접근하는 광범위 IDOR는 확인하지 못했다. 관계 전체가 자동으로 안전하다고 단정하는 것은 아니다. [CommunityAccessService.java:1](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityAccessService.java:1), [CommunityGameAccessService.java:1](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityGameAccessService.java:1), [CommunityWebConfig.java:1](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/config/CommunityWebConfig.java:1)
- **PUBG 플랫폼/공유 fact:** 계정 조회가 community/provider/platform으로 제한되고 match 저장·조회·캐시가 shard/platform과 match ID를 구분한다. 공유 경기에서 커뮤니티 의존 클랜 지표를 다시 구성하는 설계도 확인했다. `ExternalAccountProvider` enum 자체에서 격리 결함은 찾지 못했다. 운영 부분 unique index 누락 위험은 M01로 분리했다. [ExternalAccountProvider.java:1](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/account/domain/ExternalAccountProvider.java:1), [PubgGameSupport.java:1](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/support/PubgGameSupport.java:1), [PubgMatchFactQueryService.java:1](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/pubg/service/PubgMatchFactQueryService.java:1)
- **OAuth state:** 로그인 state 검증, 커뮤니티 OAuth state의 256비트 난수·TTL·원자적 소비, result의 community 검사 및 일회성 소비가 있다. H01/H02/M02는 이 방어와 별개의 문제다.
- **출석·점수:** 멤버 행 잠금, 출석 unique, 점수 원장과 총점의 같은 트랜잭션 처리가 존재한다. 단순 동시 출석으로 점수가 무조건 이중 지급된다고 보고하지 않았다. [CommunityScoreService.java:1](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityScoreService.java:1)
- **킬내기 최종 claim:** DB row lock과 UUID claim 소유권 검증이 있으며 오래된 최종 작업의 완료/실패 반영을 차단한다. 활동/빙고 작업에는 같은 보호가 충분하지 않다. [KillCompetitionSettlementStore.java:113](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/killcompetition/service/KillCompetitionSettlementStore.java:113)
- **일반 community 삭제:** OWNER 검사와 community 잠금 후 CommunityDeletionStore에서 해당 community ID의 자식을 순서대로 삭제하는 경로가 있다. 공유 PUBG fact는 보존한다. H12는 별도 가입 정리 경로의 차이이고, 전체 삭제가 무조건 orphan을 만든다는 주장은 하지 않았다. [CommunityService.java:128](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/service/CommunityService.java:128), [CommunityDeletionStore.java:1](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/community/repository/CommunityDeletionStore.java:1)
- **메모리/비동기 방어:** match 캐시 상한·TTL, 동일 match in-flight 합치기, match/telemetry semaphore, 빙고 실행기 제한, monitoring hard queue, 최근 로그 ring 상한이 있다. H04/M08/M13은 이 상한 밖의 실제 대기·보유 객체를 지적한다.
- **로그/관리 endpoint:** 토큰·환경 비밀값·SQL/개인 내용을 줄이는 sanitizer와 requestId, 파일 보관 상한이 있다. SSE·developer API에 개발자 권한 검사가 있고 Actuator HTTP endpoint는 제외된다. 추적된 비밀값 파일은 확인되지 않았으며 로컬 비밀 파일 내용을 읽거나 보고서에 옮기지 않았다. [application.properties:49](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/resources/application.properties:49), [DeveloperAccessInterceptor.java:1](/Users/kwonseonghyeon/Desktop/guildup-backend/src/main/java/com/guildup/developer/config/DeveloperAccessInterceptor.java:1)
- **프런트엔드:** 세션 기반 API 흐름·오류 처리와 JSX 출력 경로를 점검했다. 사용자 입력이 곧바로 실행되는 확정적 XSS 또는 클라이언트에 PUBG API key를 전달하는 경로는 확인하지 못했다. 운영 CSP/프록시 header는 별도 확인 대상이다.
- **PUBG rate limit:** match/telemetry를 player/season과 같은 key rate limit으로 무조건 묶어야 한다는 결론은 내리지 않았다. 공식 API 문서는 match/telemetry의 예외를 설명한다. 무제한 pending, cooldown 경쟁, 다중 프로세스 key quota는 별도 위험이다.

## 6. 수정 우선순위 및 검증 계획

| 순서 | 대상 | 완료 판단 |
|---|---|---|
| 1 | H01–H02 인증 경계, H11–H12 계정/삭제 | 세션 회전·CSRF 거절·조회 실패 시 기존 연결 보존·삭제 옵션 준수 |
| 2 | H07–H10 결과 정합성, M03–M04 stale 활동 작업 | 감소 재집계 파생 상태 일치·부분 실패 후 재시도·취소/변경 중 오래된 반영 차단·LEFT 우승자 정산 |
| 3 | H03–H06 외부 장애/비동기 가용성, H13 잠금 | 외부 대기 중 DB 반환·큐 상한·독립 감시 주기·실패 20건 뒤 후보 진행·일관된 잠금 순서 |
| 4 | M01–M02, M05–M21 운영/규모 조건 | migration 검증·OAuth 주체 검증·deadline·PUBG 캐시/대기·조회 SQL/heap·음성 복구·관측 유실량 |
| 5 | L01–L02 | 최초 로그인 경쟁 복구·async HTTP 오류 단일 기록 |

수정 시 새 기능보다 기존 처리의 경계·멱등성·상태 일관성·실행 상한을 바로잡는 데 집중한다. 먼저 작은 변경별 회귀 검증을 하고, PostgreSQL integration으로 FK/partial unique/lock ordering을 확인한 다음 외부 지연·429·큐 포화·다중 노드 시험을 수행하는 순서가 적합하다. 이 보고서에서는 수정이나 운영 설정 적용을 수행하지 않았다.

## 7. 운영 환경에서 확인할 미해결 항목

- 실제 운영 profile의 ddl-auto/OSIV, Hikari/HTTP/JDA/SMTP timeout, 세션 cookie Secure/HttpOnly/SameSite 및 URL session tracking, 프록시의 Forwarded/Host 신뢰 경계.
- PostgreSQL의 현재 partial unique/CHECK/FK/index와 플랫폼 backfill, 삭제 경로의 실제 제약 순서. H2/mock 성공으로 대체하지 않는다.
- deployment의 단일/다중 인스턴스, sticky session·공유 세션 여부, 동일 Discord bot/API key의 동시 소비자 수.
- 실제 멤버/경기/음성 이력 규모에서 N+1 SQL 수·EXPLAIN·p95/p99·heap retained size·스케줄러 지연·대기열 최대 age.
- 제품 정책: Discord 소속 상실 시 GuildUp 권한 철회, LEFT 우승자 점수 자격, 계정 동기화 실패 시 기존 연결 처리. 정책이 확정되지 않은 조건부 항목은 코드 변경 전에 정책과 맞춰야 한다.

## 8. 공식 문서 근거

- 세션 회전의 일반적인 방어: [Spring Security — Session Management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html). H01에 대응하는 인증 성공 후 세션 관리 기준이다.
- CSRF와 SameSite 방어의 역할: [Spring Security — CSRF](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html). H02에서 쿠키 조건을 구분한 근거다.
- 기본 스케줄러 동작: [Spring Boot — Task Execution and Scheduling](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html). H05의 기본 한 스레드 판단에 사용했다.
- PUBG key 한도와 match/telemetry 예외: [PUBG API — Rate Limits](https://documentation.pubg.com/en/rate-limits.html). 외부 rate limit 범위 판단에 사용했다.

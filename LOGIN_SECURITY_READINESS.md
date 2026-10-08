# GuildUp 정식 오픈 준비 — 로그인 공격 방어 1단계

검증일: 2026-10-08. 작업 범위는 로컬 프로젝트의 이메일 로그인 공격 방어와 보안 모니터링이다. 운영 DB, Nginx, 환경변수, 실행 중인 서버에는 적용하지 않았다. 이메일 인증, 비밀번호 재설정·변경, 2단계 인증은 구현하지 않았다.

## 1. 수정 전 구조 분석

| 확인 대상 | 기존 구현과 보안 기능 | 이번 작업에서의 처리 |
|---|---|---|
| 이메일 가입 | `AuthController.signup` → `CredentialAuthService.register` → 별도 쓰기 트랜잭션. 이메일 정규화, 비밀번호 정책, bcrypt, 이메일·사용자 UNIQUE, 경쟁 시 롤백. 가입 후 공통 세션 인증 | 그대로 유지. 가입 중복 이메일의 기존 409 응답 유지 |
| 이메일 로그인 | `findByEmail`은 사용자까지 EntityGraph 조회. 비밀번호를 bcrypt로 검증하고 비활성 사용자를 거부. 없는 이메일·잘못된 입력도 더미 해시를 검증하며 같은 401 메시지 | 검증 서비스 앞에 제한·예약 계층 추가. 기존 검증 로직 유지 |
| Discord 개인 로그인 | 256비트 state, 10분 TTL, 세션·목적·사용자 바인딩, 콜백 시작 시 state 소비, 토큰 교환 후 사용자 문맥 재검증 | 인증 흐름 유지. 위조 전달 헤더 제거와 실패 모니터링 샘플링만 추가 |
| Discord 커뮤니티 OAuth | 개인 로그인과 별도 저장소. state 10분, 결과 5분, 커뮤니티 ID 및 Discord 관리 가능 서버 검증, 원자적 소비 | 변경 없음. 공통 프록시 신뢰 경계의 보호를 받음 |
| 로그인 세션 | 두 로그인 모두 `AuthSessionService.authenticate`. `changeSessionId`, CSRF 교체, active 사용자 재조회, 로그아웃 무효화, 탈퇴 시 다른 세션 철회 | 그대로 유지 |
| CSRF | 로그인·가입을 포함한 세션 토큰 검증, 상수 시간 토큰 비교, Handler 기준 보호. OAuth GET은 별도 state 검증 | 기존 인터셉터 순서 유지. CSRF 통과 후 로그인 제한 적용 |
| 계정 연결·해제 | 사용자 행 잠금, 다른 계정의 Discord 연결 거부, 마지막 로그인 수단 제거 방지. 이메일 추가와 Discord 연결은 기존 users.id 사용 | 변경 없음. 자동 병합·재가입 정책 변경 없음 |
| 권한 | active 사용자 공통 검증, 커뮤니티 접근·관리 권한, 개발자 API는 `SYSTEM_ADMIN` 확인 | 변경 없음. 보안 조회도 동일한 개발자 권한 적용 |
| 탈퇴 | 재인증·동의·소유 커뮤니티 확인, 기존 탈퇴 트랜잭션과 세션 철회 | 변경 없음. 참조된 `add_account_withdrawal.sql`도 수정하지 않음 |
| 예외 | 인증은 코드·메시지와 no-store. 일반 오류는 안전한 5xx 응답과 요청 ID, 로그 마스킹 | `LOGIN_RATE_LIMITED` 429 및 Retry-After 추가. 기존 401 메시지 유지 |
| 모니터링 | `MonitoringEvent`, JSON metadata, 독립 트랜잭션, 기본 비동기 큐 256개·writer 1개, 실패 격리, 30일 기본 보관 | SECURITY 분류, 공격 코드, 샘플링·전역 이벤트 상한 추가 |
| 개발자 화면 | 기존 이벤트 그룹·분류·위험 등급·상세·페이지 조회, 개발자 접근 제어 | 보안 그룹/카테고리 및 보안 상세 항목 추가 |
| 프론트엔드 | React 로그인/가입, 공통 CSRF API 클라이언트, Discord 버튼. Vite 프록시는 Host 보존 | 같은 디자인에서 429 카운트다운·중복 제출 차단 추가 |
| 배포 | Spring Boot 4.1.1 / Java 21 / PostgreSQL. 기존 설정은 `forward-headers-strategy=framework`. 저장소에 운영 Nginx 설정·프록시 IP 목록 없음 | Boot의 무조건 전달 헤더 처리를 끄고 명시적 신뢰 필터 적용 |
| 테스트 | H2 흐름·실제 Servlet 쿠키·CSRF·Discord·탈퇴·개발자·게임 회귀, 별도 PostgreSQL 환경변수 조건 테스트, React DOM 테스트 | 공격·동시성·만료·IP·저장 장애·SQL 보존 테스트 추가 |

### 발견한 위험과 충돌 가능성

1. 로그인 요청·실패에 제한이 없어서 brute force, credential stuffing, password spraying 및 bcrypt CPU 고갈이 가능했다.
2. 세션별 동기화만으로는 공격자가 세션을 바꿔 보내는 계정 병렬 요청을 제어할 수 없었다.
3. `framework` 전달 헤더 처리는 검증되지 않은 IP·프로토콜·호스트를 요청에 반영할 수 있었다. 실제 운영 Nginx 설정은 저장소만으로 확인할 수 없었다.
4. 공격 요청을 모두 DB에 기록하면 모니터링 자체가 장애 원인이 될 수 있다. 기존 오류 기록과 구분할 분류도 없었다.
5. 가입의 `EMAIL_ALREADY_USED` 응답은 이메일 존재 추측에 사용될 수 있다. 가입 API 호환성을 유지하기 위해 이번 단계에서는 변경하지 않았다. 로그인에서는 같은 401/429 정책을 적용한다.
6. 전달 헤더 신뢰 방식 변경은 OAuth 콜백의 HTTPS 주소 생성에 영향을 준다. 배포 시 실제 Nginx peer IP를 지정하고 아래 전달 헤더를 덮어써야 한다. 이 설정 없이 배포하면 프록시 IP에 제한이 집중되거나 OAuth 콜백이 HTTP로 만들어질 수 있다.
7. Hibernate가 만든 기존 enum CHECK에는 새 값이 없을 수 있다. 수동 SQL로 허용값을 확장한 뒤 배포해야 한다. 새 SECURITY 이벤트를 읽지 못하는 이전 코드로의 단순 롤백도 피해야 한다.

## 2. 적용한 제한 정책

`AuthController.login` → `ProtectedEmailLoginService` → `LoginAttemptStore.begin` → 기존 `CredentialAuthService.login` → 결과 정리 → 기존 세션 인증 순서다. 계정·IP 키는 조회 전에 HMAC으로 생성한다. 존재하지 않는 이메일에도 같은 저장소와 제한을 사용한다.

| 항목 | 기본 정책 |
|---|---|
| 계정 실패 | 1~4회는 대기 없음. 5회째 실패 후 2초, 이후 4·8·16·30초, 그 다음도 최대 30초 |
| 실패 응답 | 임계 실패 요청 자체는 기존 401 `이메일 또는 비밀번호가 올바르지 않습니다.` 유지. 대기 중 다음 요청부터 429 |
| 계정 만료 | 첫 실패에서 15분의 관찰 기간 시작. 기간 만료 후 실패 횟수 초기화. 거부 요청이 실패 횟수·대기·관찰 기간을 연장하지 않음 |
| 성공 | 해당 계정의 실패 상태 제거. IP 요청 예산은 유지 |
| 같은 계정 병렬 요청 | 서로 다른 세션·IP에서도 비밀번호 검증 1개만 예약 가능. 진행 중 다른 요청은 429, Retry-After 1초 |
| IP 단기 | 첫 요청부터 시작하는 고정 1분 창에서 120회까지, 초과는 창 만료까지 429 |
| IP 장기 | 고정 15분 창에서 600회까지. 단기 제한·계정 제한으로 거부된 유효 로그인 요청도 IP 횟수에 포함 |
| 여러 계정 공격 | 5분 내 서로 다른 실패 계정 20개 이상, 실패 20회 이상, 완료된 검증의 실패율 80% 이상이면 IP에 30초 대기 |
| IPv6 | 표준화한 주소를 기본 /64로 묶어서 같은 네트워크의 privacy address 교체를 방어. IPv4는 전체 주소, IPv4-mapped IPv6는 IPv4와 같은 키 |
| 전체 CPU 보호 | 서버 전체 이메일 비밀번호 검증 8개. 슬롯 부족 시 대기 큐 없이 429, Retry-After 1초 |
| 메모리 상한 | 계정 20,000개, IP 10,000개. IP별 실패 계정 집합은 기본 20개까지만 보관 |
| 저장소 포화 | 살아 있는 제한을 삭제해서 새 요청을 허용하지 않음. 새 식별자는 429, Retry-After 1초. 기존 식별자 상태는 보존 |
| 자동 정리 | 1분마다 만료·유휴 기록 정리. 검증 중 기록은 제거하지 않음. 성공 계정은 즉시 제거 |

계정별 장시간 잠금은 없다. 공격자가 거부 요청을 연속 보내도 계정의 30초 대기를 계속 밀어낼 수 없다. 다만 대기가 끝날 때마다 새 실패를 일으키는 지속 공격은 짧은 이용 방해를 반복할 수 있다. CAPTCHA/MFA/신뢰 기기 없이 이 위험을 완전히 없애지는 못한다.

고정 IP 창은 경계 전후의 짧은 버스트를 허용한다. 공유 IP를 고려해 계정 제한보다 여유 있는 IP 예산을 사용하고, 다계정 판정은 실패율 조건까지 요구한다. 공유 IP가 실제 공격에 사용되면 같은 IP의 정상 사용자도 최대 남은 IP 창 동안 제한될 수 있다.

서버 CPU·메모리·실측 트래픽 자료는 저장소에서 확인되지 않았다. 위 수치는 단일 서버의 초기 운영값이다. 429 비율, 로그인 성공률, CPU·heap, 공유 IP 문의를 확인해 조정해야 한다.

### 동시성과 응답 시간

저장소의 검사·카운트·계정 예약·전체 슬롯 예약·정리는 하나의 짧은 synchronized 임계 구역에서 원자적으로 수행한다. DB 조회와 bcrypt는 그 밖에서 실행한다. 완료 처리는 finally에서 슬롯을 반환하며 중복 완료도 상태를 바꾸지 않는다.

제한된 요청은 이메일 존재 조회와 비밀번호 검증을 모두 생략한다. 제한 여부는 계정 존재와 무관한 HMAC 키의 시도 이력으로만 판단한다. 허용된 잘못된 로그인은 기존 더미 bcrypt 검증을 유지한다. 테스트는 동일한 응답 및 같은 bcrypt 검증 경로를 확인했으며, 네트워크·DB 캐시 차이를 포함한 통계적 시간 부채널의 완전 제거를 증명하는 테스트는 아니다.

## 3. 저장 방식 비교와 선택

| 항목 | 제한된 메모리 저장 | DB 저장 |
|---|---|---|
| 정상 요청 부하 | 짧은 임계 구역과 HMAC, 추가 DB 쓰기 없음 | 매 요청 원자적 증가·행 생성·잠금·정리 필요 |
| 병렬 안전성 | 단일 서버에서 예약과 상태 변경 원자화 가능 | UPSERT/행 잠금 및 별도 검증 lease 설계 필요 |
| 메모리·기록 크기 | 하드 상한, 작은 계정 집합, 만료 정리 | TTL만으로는 공격 중 증가를 막지 못해 별도 상한 필요 |
| 공격 시 DB 영향 | 정상 실패/차단은 제한 상태 DB 쓰기 없음 | 공격 트래픽이 DB 부하·행 잠금·WAL 증가로 이어짐 |
| 재시작 | 제한 기록 초기화 | 제한 기록 유지 |
| 다중 서버 | 인스턴스마다 제한 분리 | 공유 상태 가능 |
| 현재 도입 비용 | 새 인프라·라이브러리·인증 테이블 변경 없음 | 수동 DDL·DB 장애 정책·정리 작업·잠금 운영 필요 |

현재 단일 서버에는 메모리를 선택했다. 최대 30,000개의 주 상태와 IP별 최대 20개의 HMAC 식별자를 보관한다. 정확한 heap 사용량은 JVM에 따라 달라지므로 실측해야 하며 무제한 저장이나 활성 제한의 LRU 퇴출은 하지 않는다.

재시작 시 공격자가 다시 초기 시도 예산을 얻는다. HMAC 비밀키를 고정해도 메모리 제한은 복구되지 않는다. Nginx의 독립적인 유입 제한이 이를 보완하며, 재시작은 정해진 운영 절차로 수행해야 한다. 서버를 둘 이상으로 확장하기 전에는 `LoginAttemptStore` 구현을 공유 저장소로 교체해야 한다. 단순 카운트 공유뿐 아니라 계정 검증 예약, 완료, 장애·lease 만료의 원자성도 구현해야 한다. 현재 다중 서버 운영은 지원하지 않는다.

## 4. 프록시 신뢰 경계

`server.forward-headers-strategy=none`을 사용한다. `TrustedProxyFilter`가 가장 먼저 socket peer IP를 확인하고 실제 클라이언트 IP를 속성에 보관한 뒤, 안전한 전달 값만 Spring `ForwardedHeaderFilter`에 넘긴다. `framework` 또는 `native` override는 시작 시 거부해 앞선 주소 재작성으로 신뢰 검증이 우회되는 것을 막는다.

- `AUTH_TRUSTED_PROXIES` 기본값은 빈 목록이다. 모든 전달 헤더를 무시하고 socket IP를 사용한다.
- 목록은 literal IPv4/IPv6 또는 CIDR만 허용한다. DNS 조회, hostname, /0 전체 신뢰는 금지한다.
- `AUTH_CLIENT_IP_HEADER`로 X_FORWARDED_FOR / FORWARDED / X_REAL_IP 중 하나를 선택한다. 다른 헤더로 자동 fallback하지 않는다.
- 신뢰 peer에서 받은 체인은 오른쪽부터 추적하고 최초의 비신뢰 hop에서 멈춘다. 사용자가 왼쪽에 끼운 주소를 client로 채택하지 않는다.
- 중복 헤더, 비정상 주소·chain, 16 hop 초과, 2048자 초과는 socket IP로 fallback한다.
- Forwarded는 IPv4 및 인용된 IPv6/port의 `for` 값을 지원한다. `unknown`·obfuscated·scope 주소는 거부한다.
- Forwarded.host, X-Forwarded-Host, Prefix는 사용하지 않는다. HTTPS는 신뢰 peer의 단일 `X-Forwarded-Proto=http/https` 및 유효 port만 반영한다. `FORWARDED`를 IP 헤더로 선택해도 scheme은 X-Forwarded-Proto로 전달해야 한다.
- OAuth Host는 Nginx가 고정한 원래 Host를 사용한다. 운영 도메인 외의 Host는 Nginx에서 거부해야 한다.

경계 프록시에서 외부 전달 헤더를 제거해야 한다는 원칙은 [Spring ForwardedHeaderFilter 문서](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/ForwardedHeaderFilter.html)에 따른다.

## 5. 개발자 모니터링과 개인정보 보호

`MonitoringEventService`와 기존 executor/writer/retention을 재사용한다. SECURITY 이벤트는 다음과 같다.

| 코드 | 의미 |
|---|---|
| LOGIN_REPEATED_FAILURE | 계정 반복 실패로 다음 요청에 점진 대기가 설정됨 |
| LOGIN_RATE_LIMITED | 계정·IP 제한으로 현재 요청 거부 |
| LOGIN_IP_VOLUME | IP 고정 창의 요청 예산 초과 |
| LOGIN_MULTI_ACCOUNT | 여러 실패 계정·높은 실패율 패턴 |
| LOGIN_ATTACK_SUSPECTED | 전체 검증 슬롯 또는 저장소 상한 포화. 실제 공격 확정이 아닌 위험 신호 |
| DISCORD_OAUTH_FAILED / SECURITY | 개인 Discord 콜백의 state/세션 문맥 검증 실패. 기존 인증 처리는 유지 |

이메일 로그인 이벤트는 WARN severity와 MEDIUM/HIGH 위험 수준을 제공한다. 발생 시각, 종류, 위험 수준, 요청 횟수, 적용 여부, requestId는 보안 상세에서 확인한다. 이메일과 IP 대신 64자리 HMAC-SHA256 식별자만 저장한다. IPv6 IP 식별자는 /64 그룹의 HMAC이다. account와 ip는 다른 namespace를 사용한다.

이메일 이벤트의 `requestCount`는 현재 IP 장기 고정 창의 요청 누적값이고 `distinctAccounts`는 설정 임계값에서 포화하는 실패 계정 수다. `failureCount`는 scope에 따라 계정 실패 또는 IP 관찰 실패 수다. 저장소 포화 이벤트는 요청 횟수 0을 사용하고, Discord 거부 이벤트는 직전 기록 이후의 거부 횟수를 표시한다. 이벤트는 전체 로그인 감사 장부가 아니다. 원문 비밀번호·해시·이메일·IP·세션 ID·CSRF·OAuth 토큰·인증 코드를 새 제한 저장소나 보안 이벤트에 넣지 않는다. 인증 requestId는 서버가 생성하며 사용자 제공 X-Request-ID를 채택하지 않는다.

정상 성공과 단발 실패는 이벤트를 저장하지 않는다. 같은 상태·종류는 기본 1분에 한 번, 이메일 보안 이벤트 전체는 분당 최대 60개만 제출한다. 개인 Discord 거부 이벤트/로그는 서버 전체 분당 한 번만 기록한다. 높은 공격량에서는 이벤트가 샘플링되고, 큐 포화·DB 장애 시 일부가 유실될 수 있다.

운영 기본값 `MONITORING_EVENTS_ASYNC=true`를 유지해야 한다. 저장 실패·큐 포화가 일반 로그인 결과를 바꾸지 않는 테스트를 수행했다. 이 값을 false로 바꾸면 DB 저장을 요청 스레드가 기다린다. 기존 30일 보관 정책은 SECURITY에도 적용한다. HMAC도 연결 가능한 가명 식별자이므로 개발자 접근과 보관 기간을 제한한다.

## 6. 프론트엔드

429에서는 `로그인 시도가 일시적으로 제한되었습니다. 잠시 후 다시 시도해 주세요.`를 표시한다. Retry-After의 초/HTTP-date를 파싱해 남은 시간과 비활성 재시도 버튼을 보여주며, 헤더가 없으면 5초 기다린다. 같은 프레임의 두 제출도 ref로 차단하고 대기 중 Enter/반복 submit도 요청을 보내지 않는다. 자동 재시도와 오류 페이지 새로고침은 없다.

기존 이메일 입력, 가입, 모바일 CSS, Discord 버튼과 성공 redirect를 유지했다. 이메일 제한 대기 중에도 Discord 로그인을 시작할 수 있다. API client의 정상·CSRF 동작은 유지한다. 운영에서는 `frontend/dist` 전체를 배포해야 한다. 백엔드의 레거시 `static/login.html`은 이메일 로그인이 없는 이전 화면이므로 백엔드 JAR만 배포해서는 이 UI 변경이 반영되지 않는다.

## 7. DB 변경

사용자·인증·커뮤니티·게임 테이블 변경은 없다. 새 로그인 제한 테이블도 없다. `src/main/resources/db/manual/add_login_security_monitoring.sql`은 monitoring_events의 기존 category/event_code CHECK에 새 허용값만 추가한다. 기존 조건과 모든 행을 유지하고 재실행 가능하다. PostgreSQL의 enum 컬럼 자체를 사용하는 별도 운영 스키마는 이 스크립트의 전제인 VARCHAR+CHECK와 다르므로 관리자가 먼저 확인해야 한다.

## 8. 신규 설정·환경변수

모두 `application.properties`에서 변경할 수 있다. 특별히 표기하지 않은 기간은 Spring Duration 형식(2s, 15m)을 사용한다.

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| AUTH_TRUSTED_PROXIES | 빈 목록 | 실제 Nginx socket peer IP/CIDR, 쉼표 구분 |
| AUTH_CLIENT_IP_HEADER | X_FORWARDED_FOR | 하나의 IP 전달 헤더 선택 |
| LOGIN_ACCOUNT_FREE_FAILURES | 4 | 대기 없는 실패 횟수 |
| LOGIN_ACCOUNT_BASE_DELAY | 2s | 첫 대기 |
| LOGIN_ACCOUNT_MAX_DELAY | 30s | 계정 최대 대기. 코드가 1분 초과 설정 거부 |
| LOGIN_ACCOUNT_FAILURE_TTL | 15m | 실패 관찰 기간 |
| LOGIN_IP_SHORT_LIMIT | 120 | IP 단기 요청 예산 |
| LOGIN_IP_SHORT_WINDOW | 1m | IP 단기 창 |
| LOGIN_IP_LONG_LIMIT | 600 | IP 장기 요청 예산 |
| LOGIN_IP_LONG_WINDOW | 15m | IP 장기 창 |
| LOGIN_SPRAY_ACCOUNTS | 20 | 서로 다른 실패 계정 임계값 |
| LOGIN_SPRAY_FAILURES | 20 | 최소 IP 실패 수 |
| LOGIN_SPRAY_FAILURE_PERCENT | 80 | 최소 실패율 % |
| LOGIN_SPRAY_WINDOW | 5m | 다계정 관찰 창 |
| LOGIN_SPRAY_DELAY | 30s | 다계정 패턴 대기 |
| LOGIN_MAX_ACCOUNTS | 20000 | 계정 상태 하드 상한 |
| LOGIN_MAX_IPS | 10000 | IP 상태 하드 상한 |
| LOGIN_MAX_CONCURRENT_VERIFICATIONS | 8 | 전체 이메일 검증 슬롯 |
| LOGIN_IPV6_PREFIX_LENGTH | 64 | IPv6 그룹 prefix. 48~128 허용 |
| LOGIN_CLEANUP_INTERVAL | 1m | 정리 스케줄 |
| LOGIN_EVENT_INTERVAL | 1m | 같은 상태/종류 이벤트 간격 |
| LOGIN_MAX_EVENTS_PER_MINUTE | 60 | 이메일 보안 이벤트 전역 상한 |
| LOGIN_HMAC_SECRET | 빈 값 | 운영 권장: 독립적인 32바이트 이상 비밀키. 빈 값이면 프로세스 임의 키 |

이상한 음수·0 정책, 긴 계정 잠금, 과도한 상한은 시작 시 거부한다. 한계를 크게 올려서 차단을 피하기 전에 CPU·메모리·공유 IP 패턴을 검토해야 한다. 키는 저장소·보고서·로그에 기록하지 않는다. 키 교체 시 과거 HMAC과 현재 HMAC의 연결이 끊긴다.

## 9. 운영 적용 방법

아래는 운영자가 검토해 적용할 절차이며 이번 작업에서 실행하지 않았다.

1. 현재 DB 백업·스키마 및 실제 Nginx→Spring socket peer를 확인한다. 특히 monitoring_events의 CHECK/컬럼 타입, 서버 수, HTTPS Host, 앱 직접 노출 여부를 확인한다.
2. 새 수동 SQL을 배포 전 변경 창에서 실행한다. 파일 자체가 트랜잭션을 포함하며 모든 기존 데이터를 보존한다. CHECK 변경은 테이블 잠금/검증이 생길 수 있으므로 트래픽과 테이블 크기를 고려한다.
3. 실제 프록시만 AUTH_TRUSTED_PROXIES에 지정한다. 동일 호스트 Nginx가 127.0.0.1로 접속하는 경우 `127.0.0.1/32`; IPv6 ::1 접속이면 `::1/128`도 지정한다. 전체 private CIDR를 추측해서 신뢰하지 않는다.
4. AUTH_CLIENT_IP_HEADER=X_FORWARDED_FOR, 독립적인 LOGIN_HMAC_SECRET, 기존 HTTPS Secure 쿠키 설정, MONITORING_EVENTS_ASYNC=true를 확인한다. `SERVER_FORWARD_HEADERS_STRATEGY`는 none이어야 한다. 앱 포트는 Nginx 외부에서 직접 접속하지 못하도록 bind/firewall로 제한한다.
5. 아래 Nginx 예시를 실제 도메인·기존 TLS/정적 파일 구성에 맞게 합친 뒤 nginx -t로 검증한다. CDN/다중 proxy가 있으면 해당 체인을 별도로 검증하고 덮어쓰기 규칙을 조정한다.
6. 검증한 백엔드 artifact와 같은 버전의 React `dist/` 전체를 관리자의 기존 배포 절차로 적용한다. 이번 작업은 운영 서버를 재시작하거나 배포하지 않았다.
7. HTTPS 이메일 로그인/실패/429/재시도, Discord 로그인·연결·탈퇴 인증, cookie/CSRF, 개발자 필터를 점검한다. 실제 클라이언트와 서로 다른 IP에서 독립적인 제한이 되는지 확인한다. 이메일·IP 원문을 로그에 추가해서 확인하지 않는다.
8. 초기 429·공유 IP 문의와 CPU/heap를 관찰해 정책을 조정한다. 롤백 코드는 새 SECURITY/LOGIN_* enum 읽기 지원을 유지해야 하며 보안 이력을 삭제해서 이전 코드에 맞추지 않는다.

Nginx 단일 경계 프록시 예시:

```nginx
# http {}. 실제 인증 코드/쿼리/쿠키/IP를 기록하지 않는 별도 auth 로그.
log_format guildup_auth_safe '$time_iso8601 auth $request_method $status '
                            '$request_time $upstream_http_x_request_id';
# 선택 사항: 앱 재시작·CSRF 실패·잘못된 JSON 유입도 제어하는 독립적인 edge 제한.
limit_req_zone $binary_remote_addr zone=guildup_email_login:10m rate=10r/s;

server {
    # 기존 listen 443 ssl, 인증서, server_name, 정적 파일 구성을 유지한다.
    # 아래 Host는 실제 운영 도메인으로 교체하고 미등록 Host는 별도 default_server에서 거부한다.
    proxy_set_header Host guildup.example;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;
    proxy_set_header Forwarded "";
    proxy_set_header X-Real-IP "";
    proxy_set_header X-Forwarded-Host "";
    proxy_set_header X-Forwarded-Prefix "";

    location ^~ /api/auth/ {
        proxy_pass http://127.0.0.1:8080;
        access_log /var/log/nginx/guildup-auth.log guildup_auth_safe;
    }
    location = /api/auth/login {
        proxy_pass http://127.0.0.1:8080;
        access_log /var/log/nginx/guildup-auth.log guildup_auth_safe;
        client_max_body_size 16k;
        limit_req zone=guildup_email_login burst=20 nodelay;
        limit_req_status 429;
        # 선택적인 edge 429 안내. 다른 upstream 오류는 기존 정책을 유지한다.
        error_page 429 = @guildup_login_limited;
    }
    location @guildup_login_limited {
        default_type application/json;
        add_header Cache-Control no-store always;
        add_header Retry-After 1 always;
        return 429 '{"code":"LOGIN_RATE_LIMITED","message":"로그인 시도가 일시적으로 제한되었습니다. 잠시 후 다시 시도해 주세요."}';
    }
}
```

`$proxy_add_x_forwarded_for`로 외부 체인을 그대로 붙이지 않고 경계 Nginx에서 `$remote_addr`로 덮어쓰는 예시다. CDN 앞단이 있다면 `$remote_addr`가 CDN 주소일 수 있다. 검증한 CDN 주소만 `set_real_ip_from`으로 신뢰한 뒤 적용해야 하며 임의 헤더를 신뢰해서는 안 된다. Nginx의 헤더 상속과 real-IP 동작은 [proxy module](https://nginx.org/en/docs/http/ngx_http_proxy_module.html), [realip module](https://nginx.org/en/docs/http/ngx_http_realip_module.html)을 확인한다. 별도 location에서 proxy_set_header를 하나라도 재정의하면 상위 설정 상속이 달라질 수 있다.

## 10. 자동화 검증 결과

| 검증 | 결과 |
|---|---|
| `./mvnw -q test` 최종 전체 실행 | 총 1,081개, 실행 845개 통과, 실패/오류 0, 조건부 236개 skip. 118개 suite |
| 독립 로컬 PostgreSQL | 49개 통과: 기존 인증 23, 기존 탈퇴 17, 새 로그인 방어 8, 새 수동 SQL 1. 실패 0 |
| PostgreSQL 동반 재검증 | 별도 선택 실행 총 65개 통과와 추가 PostgreSQL 방어 8개 통과. 위 49개 DB 검증 외에 H2/단위/실제 HTTP 검증 포함 |
| `npm test` 최종 전체 실행 | 218개 통과, 실패/skip 0 |
| 마지막 필터 변경의 React DOM 검증 | developerMonitoring 4개 통과. 활동 필터에서 보안으로 전환할 때 Game 조건 초기화 확인 |
| `npm run build` | 성공, 43개 route와 asset 검증. 약 646 KB JS chunk 크기 경고는 남아 있음 |
| `git diff --check` | 통과 |

PostgreSQL은 별도로 생성한 임시 cluster의 127.0.0.1:55493, 전용 테스트 DB만 사용했다. 테스트 후 해당 임시 서버를 종료했다. 기존 서버나 DB에는 접속/변경하지 않았다. 전체 실행의 조건부 skip에는 PostgreSQL 전용/추가 자료가 필요한 기존 테스트가 포함된다. 관련 PostgreSQL 테스트는 위와 같이 따로 실행했고 다른 조건부 테스트 전체를 수행했다고 주장하지 않는다. 실제 Discord API 로그인·운영 Nginx·브라우저/모바일 기기 검증은 수행하지 않았으며 Discord 회귀는 기존 mock upstream으로 검증했다.

로그 파일은 로컬 `/tmp`에 보관했고 운영 로그로 전송하지 않았다. `guildup-all-backend-final.log`, `guildup-login-postgres.log`, `guildup-login-protection-postgres.log`, `guildup-all-frontend-final.log`, `guildup-frontend-build-final.log`에서 해당 실행을 확인할 수 있다.

재현 명령(폐기 가능한 테스트 DB에만 지정):

```bash
./mvnw -q test
# 다음 TEST_POSTGRES_URL은 운영 DB로 지정하지 않는다.
TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55493/guildup_login_test \
TEST_POSTGRES_USER=guildup_login_test \
./mvnw -q -Dtest=AuthPostgresTests,AccountWithdrawalPostgresTests,LoginProtectionPostgresTests,LoginSecurityMigrationPostgresTests test
cd frontend
npm test
npm run build
```

- 새 저장소 테스트: 점진 대기·상한, 거부 시 비연장, 성공 정리, 고정 창 만료, 다계정·공유 IP, 병렬 계정/전체 슬롯, 포화·정리·중복 완료·재시작 위험.
- 새 IP 테스트: IPv4·IPv6·mapped IPv4, /64 회전, CIDR, 위조·중복·잘못된 전달 헤더, Forwarded·X-Real-IP, 검증된 프록시 HTTPS/Host, unsafe strategy 시작 거부.
- 새 HTTP 흐름: 정상·실패 후 성공, 기존 메시지, 계정 존재에 무관한 401/429와 bcrypt 경로, IP 예산, IPv6 다계정 공격, 서로 다른 세션/IP 병렬 요청, CSRF 우선 검증, SECURITY 저장·민감정보 제외·관리자 권한.
- 저장 장애 테스트: writer 차단·큐 포화·예외에도 로그인 결과 유지, 이벤트 전역 상한.
- 실제 Servlet HTTP: 신뢰 Nginx HTTPS를 개인 Discord authorize의 redirect_uri에 반영하며 위조된 forwarded Host 무시.
- PostgreSQL 수동 SQL: 두 번 실행, 기존 IDs·인증정보·외부 계정·기존 이벤트 보존, 새 5종 코드 허용, 잘못된 category 계속 거부.
- 기존 전체 회귀: 회원가입·이메일/Discord 인증·연결·로그아웃·탈퇴·CSRF·개발자 접근, 커뮤니티/PUBG/빙고/킬내기/랭킹 관련 기존 테스트 포함.
- React DOM: 카운트다운·헤더 누락 fallback·연속 submit·Discord 유지·보안 필터/상세. 기존 모바일 CSS를 유지했으며 실제 모바일 기기에서의 운영 검증은 별도다.

## 11. 남은 위험과 오픈 평가

이번 범위는 단일 서버의 로그인 공격 방어 1단계로 적합하다. 실제 서비스 계정/세션 구조를 유지하면서 요청 예산·계정 예약·CPU 상한을 추가했고, 성공·공격·권한·기존 기능 회귀와 PostgreSQL 데이터 보존을 자동화로 검증했다. 운영 Nginx 신뢰 설정, SQL, Secure cookie, 비동기 모니터링, React artifact 반영을 확인한 뒤 적용해야 한다. 운영 환경 자체를 검증하거나 전체 서비스 보안을 승인한 결과는 아니다.

남은 위험은 재시작 시 제한 초기화, 다중 서버 우회, 여러 IP·IPv6 prefix를 쓰는 낮은 속도의 분산 공격, 이미 유출된 올바른 비밀번호의 첫 성공, 지속적인 짧은 계정 이용 방해, 공유 NAT의 동반 제한, edge 이전의 대규모 DDoS, 익명 CSRF 세션/Discord authorize/가입 자체의 과다 요청이다. 개인 Discord의 upstream 호출량 제한은 이번 단계에서 추가하지 않았다. state 검증 전 토큰 교환은 하지 않으며 기존 흐름을 유지했다. 커뮤니티 OAuth 저장소의 용량 상한/로그 폭증은 별도 범위로 남는다.

가입 이메일 존재 메시지, 비밀번호 탈퇴 재인증 endpoint의 독립 제한, 메일 소유 확인·비밀번호 복구·MFA·침해 비밀번호 대응도 후속 검토 대상이다. 운영 Nginx/보안 에이전트의 기존 access/debug 로그는 확인하지 못했으므로 OAuth code·쿠키·쿼리·request body 수집 여부를 별도로 확인해야 한다. 공격 이벤트는 샘플링되므로 유실 없는 보안 감사나 자동 경보/차단 관제 시스템을 대신하지 않는다.

계정 잠금을 이용한 DoS를 고려하고 일반 실패 메시지를 유지한 설계 근거: [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html). IP 제한만으로 분산 credential stuffing을 완전히 막을 수 없다는 후속 방어 검토 근거: [OWASP Credential Stuffing Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Credential_Stuffing_Prevention_Cheat_Sheet.html).

## 12. 변경 파일

총 37개 파일. 기존 사용자·게임 엔티티 및 관련 데이터 SQL은 수정하지 않았다. 새 외부 라이브러리도 없다.

- [LOGIN_SECURITY_READINESS.md](LOGIN_SECURITY_READINESS.md)
- [README.md](README.md)
- [frontend/src/api/http.js](frontend/src/api/http.js)
- [frontend/src/developer/MonitoringPage.jsx](frontend/src/developer/MonitoringPage.jsx)
- [frontend/src/developer/monitoringView.js](frontend/src/developer/monitoringView.js)
- [frontend/src/pages/LoginPage.jsx](frontend/src/pages/LoginPage.jsx)
- [frontend/test/authPages.test.js](frontend/test/authPages.test.js)
- [frontend/test/developerMonitoring.test.js](frontend/test/developerMonitoring.test.js)
- [frontend/test/http.test.js](frontend/test/http.test.js)
- [src/main/java/com/guildup/monitoring/domain/MonitoringCategory.java](src/main/java/com/guildup/monitoring/domain/MonitoringCategory.java)
- [src/main/java/com/guildup/monitoring/domain/MonitoringEventCode.java](src/main/java/com/guildup/monitoring/domain/MonitoringEventCode.java)
- [src/main/java/com/guildup/monitoring/service/MonitoringQueryService.java](src/main/java/com/guildup/monitoring/service/MonitoringQueryService.java)
- [src/main/java/com/guildup/monitoring/web/RequestLogContextFilter.java](src/main/java/com/guildup/monitoring/web/RequestLogContextFilter.java)
- [src/main/java/com/guildup/user/auth/controller/AuthController.java](src/main/java/com/guildup/user/auth/controller/AuthController.java)
- [src/main/java/com/guildup/user/auth/controller/DiscordLoginController.java](src/main/java/com/guildup/user/auth/controller/DiscordLoginController.java)
- [src/main/java/com/guildup/user/auth/exception/AuthExceptionHandler.java](src/main/java/com/guildup/user/auth/exception/AuthExceptionHandler.java)
- [src/main/java/com/guildup/user/auth/exception/LoginRateLimitedException.java](src/main/java/com/guildup/user/auth/exception/LoginRateLimitedException.java)
- [src/main/java/com/guildup/user/auth/security/ClientIpResolver.java](src/main/java/com/guildup/user/auth/security/ClientIpResolver.java)
- [src/main/java/com/guildup/user/auth/security/InMemoryLoginAttemptStore.java](src/main/java/com/guildup/user/auth/security/InMemoryLoginAttemptStore.java)
- [src/main/java/com/guildup/user/auth/security/LoginAttemptStore.java](src/main/java/com/guildup/user/auth/security/LoginAttemptStore.java)
- [src/main/java/com/guildup/user/auth/security/LoginIdentityHasher.java](src/main/java/com/guildup/user/auth/security/LoginIdentityHasher.java)
- [src/main/java/com/guildup/user/auth/security/LoginProtectionProperties.java](src/main/java/com/guildup/user/auth/security/LoginProtectionProperties.java)
- [src/main/java/com/guildup/user/auth/security/LoginSecurityEvents.java](src/main/java/com/guildup/user/auth/security/LoginSecurityEvents.java)
- [src/main/java/com/guildup/user/auth/security/ProtectedEmailLoginService.java](src/main/java/com/guildup/user/auth/security/ProtectedEmailLoginService.java)
- [src/main/java/com/guildup/user/auth/security/TrustedProxyFilter.java](src/main/java/com/guildup/user/auth/security/TrustedProxyFilter.java)
- [src/main/java/com/guildup/user/auth/security/TrustedProxyProperties.java](src/main/java/com/guildup/user/auth/security/TrustedProxyProperties.java)
- [src/main/resources/application.properties](src/main/resources/application.properties)
- [src/main/resources/db/manual/add_login_security_monitoring.sql](src/main/resources/db/manual/add_login_security_monitoring.sql)
- [src/test/java/com/guildup/user/auth/LoginProtectionFlowTests.java](src/test/java/com/guildup/user/auth/LoginProtectionFlowTests.java)
- [src/test/java/com/guildup/user/auth/LoginProtectionPostgresTests.java](src/test/java/com/guildup/user/auth/LoginProtectionPostgresTests.java)
- [src/test/java/com/guildup/user/auth/TrustedProxyOAuthIntegrationTests.java](src/test/java/com/guildup/user/auth/TrustedProxyOAuthIntegrationTests.java)
- [src/test/java/com/guildup/user/auth/controller/DiscordLoginControllerTests.java](src/test/java/com/guildup/user/auth/controller/DiscordLoginControllerTests.java)
- [src/test/java/com/guildup/user/auth/security/AuthenticationRequestIdTests.java](src/test/java/com/guildup/user/auth/security/AuthenticationRequestIdTests.java)
- [src/test/java/com/guildup/user/auth/security/ClientIpResolverTests.java](src/test/java/com/guildup/user/auth/security/ClientIpResolverTests.java)
- [src/test/java/com/guildup/user/auth/security/InMemoryLoginAttemptStoreTests.java](src/test/java/com/guildup/user/auth/security/InMemoryLoginAttemptStoreTests.java)
- [src/test/java/com/guildup/user/auth/security/LoginSecurityIsolationTests.java](src/test/java/com/guildup/user/auth/security/LoginSecurityIsolationTests.java)
- [src/test/java/com/guildup/user/auth/security/LoginSecurityMigrationPostgresTests.java](src/test/java/com/guildup/user/auth/security/LoginSecurityMigrationPostgresTests.java)

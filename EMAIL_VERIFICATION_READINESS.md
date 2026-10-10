# GuildUp 정식 오픈 준비 2단계 — 이메일 인증

작업 범위: 이메일 소유 확인, 재발송, 점진적 신규 가입 제한, 기존 계정 보호, 모니터링, 수동 SQL 및 테스트. 비밀번호 찾기/재설정은 구현하지 않았다. 운영 DB SQL 실행, 운영 서버 재시작, 운영 환경변수 변경, 실제 SMTP 발송은 수행하지 않았다.

## 1. 기존 인증 구조 분석

| 확인 대상 | 기존 구조와 이번 작업의 연결 |
|---|---|
| users | GuildUp의 식별자는 항상 `users.id`. ACTIVE/WITHDRAWN, 닉네임/선택 생년월일, 시스템 권한을 보관한다. 이번 기능은 사용자 ID를 바꾸지 않는다. |
| user_credentials | 사용자당 1개, 정규화 이메일 UNIQUE, `{bcrypt}` 해시, `email_verified=false` 기본값. 실제 소유 확인 구현은 없었고 로그인/서비스 권한은 이 값으로 제한하지 않았다. 기존 값을 그대로 보존한다. |
| user_external_accounts | 사용자/provider 및 provider/external ID UNIQUE. Discord 프로필과 로그인 수단을 별도로 보관한다. 이메일 일치로 자동 병합하는 흐름은 없다. |
| 이메일 가입/추가 | `AuthController` → `CredentialAuthService`의 입력 정규화/비밀번호 인코딩 → `CredentialTransactions`의 독립 쓰기 트랜잭션. UNIQUE 경쟁 실패 시 신규 User도 함께 롤백한다. 여기에 토큰 생성을 추가했다. |
| 이메일 로그인 | `ProtectedEmailLoginService`가 로그인 공격 제한을 적용한 뒤 기존 비밀번호 검증을 호출한다. 미인증 이메일의 로그인은 계속 허용한다. |
| Discord OAuth/연결 | Discord state와 세션 검증, 외부 ID로 기존 계정 조회/생성, 기존 User에 연결한다. OAuth 토큰·state·콜백 경로 및 사용자/클랜원 병합 정책을 변경하지 않았다. |
| 계정 설정/프로필 | 서버의 계정 응답에서 이메일, 인증 상태, Discord 연결, 클랜원 연결 충돌을 조회한다. 프로필 수정 API는 인증 상태를 수정하지 않는다. |
| 탈퇴 | 기존 사용자 행 잠금, 재인증/동의/커뮤니티 소유 확인, 인증수단 삭제와 탈퇴 표시, 세션 철회. 토큰은 인증정보 삭제의 FK CASCADE로 함께 제거된다. 참조된 `add_account_withdrawal.sql`은 수정하지 않았다. |
| 세션/CSRF | 공통 세션 키 `LOGIN_USER_ID`, 로그인 시 ID·CSRF 회전, HTTPOnly/SameSite/Secure 설정, ACTIVE 공통 검증. 인증 확인/재발송도 로그인 세션 및 기존 CSRF 헤더를 검증한다. |
| SMTP/문의 메일 | 공통 `JavaMailSender`, `MAIL_HOST`/`MAIL_PORT`의 SMTP 서버와 `MAIL_USERNAME`/`MAIL_PASSWORD` 인증. 문의 전용 `FeedbackMailService`와 커밋 후 listener. `MAIL_FROM`/`MAIL_FROM_NAME`은 인증 메일과 공유하고 문의 알림 수신처는 기존 개인 Gmail을 유지한다. 인증 본문/큐/실패 처리는 별도 서비스다. |
| 1단계 방어 | 계정/IP 로그인 실패 제한, 검증 예약/CPU 상한, 신뢰 프록시 IP 판별, IPv6 묶음, HMAC, 보안 이벤트. 기존 로그인 저장소·카운터는 변경하지 않고 IP 판별/HMAC만 재사용한다. |
| 개발자 모니터링 | 기존 SECURITY 분류, 이벤트 writer의 독립 트랜잭션, 기본 비동기 큐, 마스킹, 개발자 접근 검증, 보관 정책을 재사용한다. |
| 프론트엔드/테스트 | React/Vite가 현재 화면. Spring static에는 이전 UI가 남아 있으므로 React dist 전체도 배포해야 한다. 기존 H2·조건부 PostgreSQL·React DOM 테스트에 이메일 인증 테스트를 추가했다. |

## 2. 구현한 흐름과 API

1. 기존 이메일/비밀번호/닉네임 검증 및 중복 확인을 수행한다.
2. 신규 User, credential(`email_verified=false`, `verification_required=true`), 토큰 해시를 하나의 트랜잭션에 저장한다.
3. 커밋 성공 후 전용 메일 executor에 작업을 전달한다. 요청에는 SMTP 완료를 기다리지 않고 응답한다.
4. 기존 로그인 세션을 발급하고 `/email-verification.html`로 이동한다. 발송 중/발송 완료/발송 실패와 재발송을 안내한다.
5. 메일 링크로 화면을 연다. 가입한 계정으로 로그인한 상태에서 **이메일 인증 완료** 버튼을 누른다.
6. 서버가 사용자 잠금과 토큰/만료/소유/사용 상태를 확인하고 credential만 `email_verified=true`로 변경한다. 토큰 소비와 인증 상태 변경은 같은 트랜잭션이다.
7. 완료 화면에서 내 커뮤니티로 이동한다. 별도 탭의 대기 화면/계정 화면은 **인증 상태 확인**으로 새로고침 없이 서버 상태를 다시 조회할 수 있다.

| API | 동작 |
|---|---|
| `GET /api/auth/email-verification` | 로그인한 본인 계정의 인증/발송 상태 및 남은 대기 시간 조회. 상태 변경 없음. |
| `POST /api/auth/email-verification/resend` | 본인 계정에만 발송. 이메일 입력을 받지 않는다. 로그인 및 CSRF 필요. 정상 접수 202, 한도 초과 429 + Retry-After. |
| `POST /api/auth/email-verification/confirm` | JSON의 token을 검증. 로그인한 사용자가 토큰 소유자여야 한다. 로그인 및 CSRF 필요. 성공 204. |
| `GET /api/auth/account` | 기존 응답에 `emailVerification` 발송/인증 정보 및 `emailServiceRestricted`를 추가한다. |

계정이 없거나 이메일 인증수단이 없는 익명 호출에는 항상 로그인 요구 응답을 제공한다. 로그인한 사용자는 본인의 상태만 확인한다. 기존 회원가입의 `EMAIL_ALREADY_USED` 응답 계약은 유지하므로 그 API의 기존 존재 추측 위험은 남아 있다.

## 3. 토큰 생성·저장·노출 정책

- SecureRandom 32바이트(256비트), URL-safe Base64 43자. 요청 형식을 먼저 검증한다.
- DB에는 SHA-256 64자리 hex만 저장한다. 원문은 커밋 후 메일 큐 메모리에만 잠시 존재한다.
- 기본 유효기간 300초(5분). 발급 시 저장한 DB 만료 시각으로 관리하며 300초 경계부터 거부한다. 서버 재시작 후 미사용 토큰도 저장된 만료 시각까지 유지된다. 기본값 변경은 새로 발급하는 토큰에 적용하고 기존 발급 토큰의 만료 시각은 변경하지 않는다.
- 이메일 인증 전용 테이블/서비스를 사용한다. 다음 단계 비밀번호 재설정 토큰과 공유하지 않는다.
- 성공 시 `used_at` 설정, 재사용 거부. 새 토큰 발급 시 기존 미사용 토큰은 즉시 `invalidated_at` 설정한다. 새 SMTP 발송이 실패해도 이전 링크는 복구하지 않는다.
- 사용자 행 잠금으로 인증·재발송·기존 탈퇴/연결 변경을 직렬화한다. 확인 요청의 토큰 소유자가 로그인 사용자와 다르면 일반적인 잘못된 링크 오류로 처리한다.
- PostgreSQL에는 사용자 인증정보별 활성 토큰 1개인 부분 UNIQUE 인덱스도 적용한다. 기존 토큰 UPDATE를 flush한 뒤 새 INSERT를 수행한다.
- 링크는 설정된 HTTPS URL의 `#token=...` fragment를 사용한다. HTTP 요청과 Referer에 fragment는 전달되지 않는다. HTML 초기 스크립트 및 React 진입에서 `history.replaceState`로 주소를 즉시 정리한다. 페이지의 referrer 메타는 `no-referrer`이다.
- 확인은 POST 버튼 동작만 수행한다. GET/메일 보안 스캐너 방문은 인증을 소비하지 않는다. POST도 로그인/CSRF가 필요하다.
- 페이지를 새로고침하거나 로그인 화면으로 이동하면 메모리 토큰을 잃을 수 있다. 로그인 후 **원래 메일 링크를 다시 열도록** 안내한다. 토큰을 localStorage/sessionStorage/cookie에 보관하지 않는다.
- 링크 자체와 원문은 메일 서비스·브라우저·링크 분석 업체에서 접근할 수 있다. 주소 제거가 브라우저 동기화·메일 링크 재작성 업체의 과거 저장까지 삭제하지는 못한다. 운영에서 링크 추적/외부 스크립트/요청 본문 수집을 비활성화해야 한다.

참고한 보안 기준: [OWASP 일회용 URL 토큰 지침](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html). 소유 확인에도 난수·일회성·HTTPS·Referer 차단 원칙을 적용했다. 비밀번호 재설정은 구현하지 않았다.

## 4. 메일 발송과 실패 정책

`EmailVerificationMailService`는 문의 알림과 공통 SMTP `JavaMailSender` 및 `ApplicationMailProperties` 발신 설정으로 UTF-8 HTML 메일을 보낸다. 기존 보라색(`#5865f2`)과 모바일 가변 폭 테이블, 44px 수준 링크 버튼, 한글 안내, 설정된 유효시간을 표시한다. 제목은 `[GuildUp] 이메일 인증을 완료해 주세요.`이다.

메일 listener는 AFTER_COMMIT에만 동작한다. 전용 executor는 worker 2개, 큐 100개, 포화 시 요청 스레드에서 SMTP를 대신 실행하지 않고 실패로 기록한다. 발송 직전 별도 트랜잭션에서 QUEUED→SENDING을 예약해 같은 이벤트를 중복 처리하지 않는다. SMTP를 수행하는 동안 DB 트랜잭션/사용자 잠금은 유지하지 않는다. 결과는 별도 트랜잭션으로 SENT/FAILED에 반영한다.

[Spring 커밋 단계 이벤트 설명](https://docs.spring.io/spring-framework/reference/7.1/data-access/transaction/event.html)에 따라 커밋 후 전달과 새 쓰기 트랜잭션을 구분했다.

| 실패 상황 | 정책 및 복구 |
|---|---|
| User/credential/토큰 DB 저장 실패 | 가입 전체 롤백. 발송하지 않는다. 회원가입을 다시 요청할 수 있다. |
| SMTP 장애·타임아웃·설정 누락·큐 포화 | 가입 유지, 인증 미완료, 발송 실패 표시 및 모니터링. 60초/횟수 제한 범위 안에서 재발송한다. |
| 발송 성공 후 결과 DB 저장 실패 | 인증 링크 자체는 계속 사용할 수 있다. 안전한 실패 이벤트를 남긴다. 사용자가 재발송하면 기존 링크는 교체된다. |
| 커밋 직후 프로세스 종료/큐 작업 유실 | 계정·토큰 해시는 유지한다. 원문을 DB에 저장하지 않으므로 자동 복구 발송은 하지 않는다. 토큰 만료(기본 5분) 또는 delivery-timeout(기본 10분) 중 빠른 시각에 QUEUED/SENDING을 FAILED로 표시하며 재발송 가능하다. |
| SMTP 타임아웃이지만 업체가 메일을 보낸 경우 | 실제 메일의 링크는 만료/무효화 전까지 사용할 수 있다. SMTP exactly-once는 보장할 수 없으며 자동 재시도는 하지 않는다. |
| 큐 처리 전 탈퇴·인증 완료·토큰 교체 | 원래 메일 작업은 보내지 않는다. 이미 SMTP에 전달 중인 오래된 메일은 도착할 수 있지만 폐기/탈퇴한 토큰은 인증에 사용할 수 없다. |

SMTP 서버는 `MAIL_HOST`/`MAIL_PORT`, 인증 정보는 `MAIL_USERNAME`/`MAIL_PASSWORD`를 사용한다. 호스트 기본값은 `localhost`, 포트 기본값은 `587`이며 업체별 서버/인증 값을 실행 환경에서 지정한다. 공식 From은 SMTP 인증 계정과 독립적인 `MAIL_FROM`(기본 `noreply@guild-up.com`)과 `MAIL_FROM_NAME`(기본 `GuildUp`)을 사용한다. 선택한 SMTP 업체에서 공식 발신 주소 사용 권한/도메인 검증을 완료해야 한다. 문의 알림 To는 기존 `heun0180@gmail.com`을 유지한다. `RESEND_API_KEY`, `EMAIL_VERIFICATION_FROM`은 사용하지 않는다. 실제 HTTPS 도메인의 `EMAIL_VERIFICATION_URL`을 반드시 설정한다. URL의 기본값은 예약된 `.example` 주소이며, 이 값으로는 발송하지 않고 안전한 실패/재발송 안내를 제공한다. 공통 SMTP 연결/읽기/쓰기 타임아웃 기본값은 각각 5초이며 STARTTLS를 필수로 한다. SMTP debug는 꺼둔다. 계정 비밀은 로그/문서/소스에 넣지 않는다. Reply-To 및 기존 Gmail 앱 비밀번호의 정상 전환 후 운영자 직접 폐기 절차는 [SMTP 설정 안내](SMTP_MIGRATION.md)를 참고한다.

### 인증 메일 미수신 추가 점검 (2026-10-08)

아래는 SMTP 전환 전 Gmail을 사용하던 당시의 점검 이력이다. 현재 SMTP 설정은 위 `MAIL_*` 설정과 [설정 안내](SMTP_MIGRATION.md)를 따른다.

점검 당시 로컬 앱과 IntelliJ 실행 설정에는 MAIL_USERNAME/MAIL_PASSWORD가 있지만 EMAIL_VERIFICATION_URL이 없었다. 따라서 기본 `.example` 링크 차단에 걸려 SMTP 호출 전에 발송이 중단되었다. 로그에는 `monitoring_events_category_check`가 SECURITY 이벤트 저장을 거부한 오류도 있었다. 운영 DB는 조회/변경하지 않았고, 실행 환경·로컬 로그만 확인했다.

실제 HTTPS 프론트엔드의 `/email-verification.html` 주소를 EMAIL_VERIFICATION_URL에 지정해야 한다. `EMAIL_VERIFICATION_TOKEN_TTL=300s`는 선택 설정이며 기본값도 300초다. 기존 환경변수에 24h 같은 값이 지정되어 있다면 기본값보다 우선하므로 운영자가 300s로 변경해야 한다. 변경한 앱을 배포한 후 재발송을 요청하면 새 5분 링크가 발급된다. 운영 환경 변경과 서버 재시작은 이번 작업에서 수행하지 않는다.

로컬 실행 설정의 기존 Gmail 자격증명으로 587 STARTTLS 연결과 SMTP 로그인을 확인했고 정상 통과했다. 메일 수신자 지정·메일 전송은 수행하지 않았다. 따라서 계정 인증까지는 확인했지만 수신함 도착·스팸 분류·실제 발신 허용 정책까지 검증한 것은 아니다.

설정 누락은 앱 시작 때 고정 원인 코드로 경고한다. 발송 실패의 `reason`은 URL_NOT_CONFIGURED, SENDER_NOT_CONFIGURED, MAIL_DISABLED, SMTP_AUTHENTICATION_FAILED, SMTP_TIMEOUT, SMTP_CONNECTION_FAILED, SMTP_SEND_FAILED, MESSAGE_CREATION_FAILED, DELIVERY_STORAGE_FAILED, QUEUE_FULL 중 하나다. 이메일·토큰·링크·SMTP 예외 메시지/스택은 기록하지 않는다. 발송 실패 로그와 모니터링 이벤트는 동일한 분당 상한을 공유한다. DB 이벤트 저장이 실패해도 안전한 원인 로그는 확인할 수 있다.

개발자 이벤트 저장 오류는 관리자가 기존 수동 SQL의 적용 상태를 확인하고 `src/main/resources/db/manual/add_email_verification.sql`을 적용하면 SECURITY/이메일 인증 코드 CHECK가 확장된다. SQL은 준비만 했으며 운영 DB에는 실행하지 않았다. 이 CHECK 오류는 메일 미발송의 원인이 아니라 실패 진단 기록의 별도 오류다.

### localhost 개발 화면의 메일 테스트

후속 점검에서 브라우저의 localhost:5173 계정 화면에 이메일 로그인 수단이 등록되어 있고 발송 상태가 실패인 것을 확인했다. 최신 실행 로그에도 URL_NOT_CONFIGURED가 기록되어 있었다. 수신 서버로 SMTP 메일이 전달되기 전의 설정 오류다.

별도 `application-local.properties`를 추가했다. 명시적 `SPRING_PROFILES_ACTIVE=local`에서만 기본 인증 주소가 `http://localhost:5173/email-verification.html`로 설정된다. `allow-local-http`는 기본 false이고 local 파일에서만 true다. URL 검증은 localhost/127.0.0.1/[::1]의 literal loopback 주소에만 HTTP를 허용하며 사용자정보/query/fragment 및 외부 호스트는 거부한다. 추가 부팅 검증은 local 프로필이 없거나 prod와 함께 활성화된 HTTP 설정을 거부한다. 운영 기본값/HTTPS 정책은 유지한다.

`MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD`/`MAIL_FROM`/`MAIL_FROM_NAME`을 실행 환경에 지정하고 IntelliJ 실행 설정의 활성 프로필을 local로 지정하거나 SPRING_PROFILES_ACTIVE=local로 개발 앱을 실행한다. 운영 프로필/환경에는 local을 적용하지 않는다. 설정을 반영한 개발 앱에서 재발송 대기 시간이 끝난 뒤 기존 계정의 재발송 버튼으로 새 메일을 요청한다. 새 가입/계정 ID 변경/기존 인증정보 초기화는 필요 없다. localhost 링크는 개발 서버가 켜진 동일 컴퓨터에서 열고 명시적 인증 버튼을 눌러야 한다.

이번 후속 점검에서는 IntelliJ의 기존 GuildupBackendApplication 실행 구성의 ACTIVE_PROFILES를 local로 저장했다. 로컬 개발 앱만 재실행했고, 20:21 실행 로그에서 local 프로필 활성화/정상 기동/URL 누락 경고 해소를 확인했다. 이 실행에는 SPRING_JPA_HIBERNATE_DDL_AUTO=validate를 한 번만 덮어써 시작 시 스키마 변경을 수행하지 않게 했다. 운영 환경/서버는 변경하지 않았고 기존 DB에 직접 SQL을 실행하지 않았다. 재실행으로 기존 로컬 세션은 종료되어 브라우저가 로그인 화면으로 이동했다. 실제 계정의 로그인 및 재발송은 사용자가 수행해야 하며 수신함 도착은 아직 확인하지 않았다.

## 5. 재발송과 공격 방어

| 항목 | 기본값 |
|---|---|
| 계정 간격 | 60초 |
| 계정 시간당 | 최근 1시간 최대 3회 |
| 계정 일일 | 최근 24시간 최대 5회 |
| IP 메일 요청 | 최근 1시간 20회, 최근 24시간 50회 |
| IP 확인 요청 | 최근 1분 20회 |
| IP 저장소 | 최대 10,000개 키, 살아 있는 예산을 삭제하지 않고 포화 시 거부 |
| 인증 모니터링 | 프로세스 전체 분당 최대 30건 |

첫 가입/로그인 수단 추가 메일과 SMTP 실패도 계정 발송 예산에 포함한다. 승인된 메일 요청은 토큰 행으로 저장되어 재시작 후에도 계정 예산이 유지된다. 거부된 재발송은 새 토큰을 만들거나 이전 링크를 무효화하지 않는다. 인증 완료 계정의 재발송은 메일을 보내지 않고 본인 인증 상태를 반환한다.

IP 예산은 로그인 카운터와 분리된 프로세스 메모리 저장소다. 신뢰 프록시 설정, IPv6 범위, HMAC을 1단계 구성에서 재사용하고 IP 원문은 저장하지 않는다. 가입/이메일 추가/재발송에 동일한 메일 요청 IP 예산을 적용해 여러 신규 계정으로의 발송 공격도 제한한다. 로그인 자체의 카운터·OAuth 로그인에는 이 예산을 적용하지 않는다.

IP 제한은 재시작 시 초기화되고 여러 서버 간 공유되지 않는다. 프록시/WAF의 별도 유입 제한과 본문 크기 제한이 필요하다. 현재 시스템의 기존 세션/로그인 방어도 단일 서버 전제를 갖고 있으므로, 다중 인스턴스로 확장하기 전 공유 IP 저장소를 별도로 구현해야 한다. 공유 IP/NAT 환경은 운영 429 비율로 조정한다.

## 6. 미인증 계정 접근 정책과 기존 회원 보호

`EMAIL_VERIFICATION_ENFORCE_NEW_USERS=false`가 기본이다. 신규 이메일 가입에는 rollout 표식 `verification_required=true`를 저장하지만 이 환경변수가 꺼져 있으면 서비스 기능을 제한하지 않는다. 이를 활성화하면 표식이 true이고 미인증이며 Discord 연결이 없는 계정만 선택된 커뮤니티/게임 API에서 403 `EMAIL_VERIFICATION_REQUIRED`로 제한한다. 프론트엔드도 해당 응답을 인증 안내 화면으로 연결한다.

로그인, 계정 설정/프로필 확인 및 수정, 인증 안내/재발송/확인, Discord 연결, 로그아웃, 기존 회원탈퇴, 공개 도움말·약관·고객지원은 유지한다. 모든 API에 전역 `email_verified` 검사를 추가하지 않았다. 커뮤니티/발견/Discord guild 서비스 경계에만 적용하며 기존 공용 문의 경로는 제외한다.

| 계정 유형 | 처리 |
|---|---|
| 배포 전 기존 이메일 회원, `email_verified=false` 포함 | migration 표식 기본 false. 로그인과 기존 서비스 접근 유지, 본인 계정에서 인증 안내/재발송 제공. 실제 인증 없이 true로 올리지 않는다. |
| 기존 Discord 회원 | OAuth·User ID·연결·멤버십·클랜원·PUBG 기록 유지. 이메일 인증수단이 없으면 인증 필요로 표시하지 않는다. |
| Discord 회원의 이메일 로그인 추가 | 기존 users.id에 credential만 추가, false로 시작하고 인증 메일 발송. 신규 계정 생성 없음. 인증 전후 Discord 서비스 권한 유지. |
| 이번 기능 이후 이메일 단독 신규 가입 | 인증 전 로그인 가능. rollout 활성화 때 주요 커뮤니티/게임 기능 제한. 실제 이메일 인증 후 제한 해제. |
| 이후 Discord를 연결한 이메일 신규 가입 | Discord 연결 계정으로 분류해 이메일 단독 제한에서 제외. email_verified는 별도로 소유 확인 전까지 false. |

rollout 비활성 상태에서 가입한 새 cohort도 표식 true이므로 **나중에 활성화하면 제한 대상**이 된다. 도입 시 이 cohort의 이용/커뮤니티 소유 현황을 확인하고 인증 안내 기간을 두어야 한다. 기존 전체 미인증 계정을 일괄 차단하거나 일괄 인증하는 SQL은 제공하지 않았다.

미인증 이메일은 향후 비밀번호 복구/민감한 이메일 기반 보안 판단의 근거로 사용할 수 없다. 현재 구현에는 그 기능 자체가 없다. 다음 단계에서는 `email_verified=true`를 별도로 확인해야 한다.

## 7. DB 변경과 운영 SQL

실행 파일: [add_email_verification.sql](src/main/resources/db/manual/add_email_verification.sql).

- `user_credentials.verification_required BOOLEAN NOT NULL DEFAULT FALSE` 추가. 기존 `email_verified`는 재사용하되 기존 행의 값은 수정하지 않는다.
- `email_verification_tokens` 신설: credential FK(CASCADE), UNIQUE token_hash, 생성/만료/사용/폐기 시각, QUEUED/SENDING/SENT/FAILED. 이메일/비밀번호/원문/인증 URL 저장 없음.
- credential+created_at 및 expires_at 인덱스, 활성 토큰 1개 부분 UNIQUE, 해시/만료/상태 CHECK.
- 기존 monitoring category/code CHECK를 기존 조건을 보존하며 새 코드만 허용하도록 확장한다. 반복 실행 가능.
- SQL에는 5초 lock_timeout/60초 statement_timeout을 두었다. 잠금 대기가 길면 롤백하고 운영자가 트래픽을 검토한 뒤 다시 적용한다.
- 기본 매일 04:40 서버 시각에 만료 후 7일 지난 토큰을 500행씩 최대 10배치 삭제한다. 최근 하루 발송 예산은 보존한다. 누적 만료 행이 5,000/일보다 많으면 cron 빈도/운영 정리 정책을 조정한다. 오래 정리된 링크는 세부 사용/만료 구분 대신 일반 잘못된 링크 안내를 받는다.

기존 credential/monitoring/withdrawal SQL 적용 여부를 먼저 확인한다. 신규 설치의 순서는 기존 프로젝트 migration → `add_user_credentials.sql` → `add_monitoring_events.sql` → 필요 시 기존 profile/withdrawal/1단계 SQL → `add_email_verification.sql`이다. 이미 적용한 과거 SQL을 임의로 다시 적용하지 않는다.

## 8. 신규 환경변수

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| EMAIL_VERIFICATION_MAIL_ENABLED | true | 인증 메일 전송 활성화 |
| MAIL_HOST | localhost | 공통 SMTP 서버; 실제 발송 서버를 지정 |
| MAIL_PORT | 587 | 공통 SMTP 포트 |
| MAIL_USERNAME | 없음 | 공통 SMTP 인증 사용자명 |
| MAIL_PASSWORD | 없음 | 공통 SMTP 인증 비밀번호/API key |
| MAIL_FROM | noreply@guild-up.com | 공통 공식 발신 주소 |
| MAIL_FROM_NAME | GuildUp | 공통 발신 표시 이름; 빈 값은 이름 생략 |
| FEEDBACK_MAIL_TO | heun0180@gmail.com | 문의/건의 알림 수신처; 기존 주소 유지 |
| MAIL_REPLY_TO | 미설정 | 실제 수신 가능한 별도 회신 주소 |
| EMAIL_VERIFICATION_URL | https://guildup.example/email-verification.html | 실제 운영 HTTPS 주소로 반드시 설정 |
| EMAIL_VERIFICATION_TOKEN_TTL | 300s | 새 인증 링크 유효기간 5분 |
| EMAIL_VERIFICATION_RESEND_INTERVAL | 60s | 계정 간격 |
| EMAIL_VERIFICATION_ACCOUNT_HOURLY_LIMIT | 3 | 계정 1시간 한도 |
| EMAIL_VERIFICATION_ACCOUNT_DAILY_LIMIT | 5 | 계정 24시간 한도 |
| EMAIL_VERIFICATION_IP_HOURLY_LIMIT | 20 | IP 메일 요청 1시간 한도 |
| EMAIL_VERIFICATION_IP_DAILY_LIMIT | 50 | IP 메일 요청 24시간 한도 |
| EMAIL_VERIFICATION_CONFIRM_PER_MINUTE | 20 | IP 인증 확인 1분 한도 |
| EMAIL_VERIFICATION_MAX_IP_ENTRIES | 10000 | IP 저장소 메모리 상한 |
| EMAIL_VERIFICATION_MAX_EVENTS_PER_MINUTE | 30 | 모니터링 저장 상한 |
| EMAIL_VERIFICATION_RETENTION | 7d | 만료 후 보관 기간 |
| EMAIL_VERIFICATION_DELIVERY_TIMEOUT | 10m | 작업 유실 발송 상태 표시 기준. 토큰 만료가 먼저면 즉시 실패 표시 |
| EMAIL_VERIFICATION_CLEANUP_CRON | 0 40 4 * * * | 정리 스케줄 |
| EMAIL_VERIFICATION_ENFORCE_NEW_USERS | false | 점진적 신규 이메일 단독 가입 제한 |
| MAIL_CONNECTION_TIMEOUT_MS | 5000 | SMTP 연결 시간 제한 |
| MAIL_READ_TIMEOUT_MS | 5000 | SMTP 응답 시간 제한 |
| MAIL_WRITE_TIMEOUT_MS | 5000 | SMTP 쓰기 시간 제한 |

공통 `MAIL_*` 설정, 기존 문의 수신처, LOGIN_HMAC_SECRET, AUTH_TRUSTED_PROXIES/AUTH_CLIENT_IP_HEADER, MONITORING_EVENTS_ASYNC, Secure 세션 쿠키 설정을 함께 확인한다. 동일 항목의 `SPRING_MAIL_*`/`APP_MAIL_*`/실행 인수와 중복 설정하지 않는다. 새 발송 URL을 요청의 Host 헤더로 만들지 않는다.

## 9. 개발자 모니터링

기존 개발자 페이지의 보안(SECURITY) 그룹에서 다음 코드로 조회한다. Event Code 입력의 추천 목록에도 추가했다.

| 코드 | 심각도 | 의미 |
|---|---|---|
| EMAIL_VERIFICATION_MAIL_FAILED | ERROR | SMTP/설정/큐/발송 결과 저장 실패 |
| EMAIL_VERIFICATION_INVALID | WARN | 잘못된/다른 계정/사용·교체된 링크 |
| EMAIL_VERIFICATION_EXPIRED | WARN | 유효기간 초과 |
| EMAIL_VERIFICATION_RATE_LIMITED | WARN | 계정 재발송 제한 |
| EMAIL_VERIFICATION_ABUSE | WARN | IP 요청/메모리 상한 차단 |
| EMAIL_VERIFICATION_COMPLETED | INFO | 소유 확인 커밋 완료 |

최소 사용자 ID·고정 메시지만 사용한다. 토큰 원문/전체 링크/이메일/비밀번호/세션 ID/SMTP 비밀/Discord OAuth 토큰은 metadata와 로그에 전달하지 않는다. SMTP 예외 메시지와 스택도 출력하지 않는다. 성공은 커밋 후 기록한다. 요청 ID는 기존 모니터링 컨텍스트가 있는 경우만 안전하게 전달된다.

기존 MONITORING_EVENTS_ASYNC=true와 bounded writer를 유지한다. 별도로 분당 30건 상한을 두어 공격으로 인한 DB 로그 부하를 줄인다. 샘플링으로 일부 이벤트가 생략될 수 있다. 토큰 정리 DB 장애는 DATABASE/DATABASE_ERROR로 고정 메시지만 기록한다.

## 10. 테스트 결과

300초 변경 및 미수신 진단 보완 후 전체 백엔드 자동화 검증도 통과했다. 실제 Gmail 전송·운영 프록시·운영 서버 재시작은 검증하지 않았다. 테스트 SMTP는 mock이다. 이번 재검증은 H2만 사용했고 PostgreSQL 환경변수는 비어 있었다. 초기 2단계에서 직접 만든 폐기용 loopback PostgreSQL의 검증 결과는 별도로 표시한다.

| 실행 | 결과 |
|---|---|
| 전체 백엔드 `./mvnw -q test` (Java 21, TEST_POSTGRES_URL 미설정) | 1,156개 중 890개 실행/통과, 266개 조건부 skip, 실패·오류 0 |
| 인증 H2 흐름/보안 단위 테스트 | 위 실행에 포함: 29 + 16 = 45개 통과. 299초 성공/300초 만료, 5분 HTML, 미설정 URL 차단·원인 분류·로그 비밀 미노출/상한·만료 큐 상태 확인 및 loopback 전용 local 프로필/운영 HTTP 거부/로컬 발송 HTML 검증 포함 |
| 초기 2단계 별도 폐기 PostgreSQL 인증/호환성 실행 | 77개 통과, 실패·오류 0. 이번 300초 변경 후에는 PostgreSQL 재실행하지 않음 |
| `cd frontend && npm test` | 228개 통과, 실패 0 |
| `cd frontend && npm run build` | 44개 경로 및 번들 확인 통과 |
| `./mvnw -q -DskipTests package` (Java 21) | backend JAR 생성 통과 |
| `git diff --check` | 통과 |

PostgreSQL은 `/tmp` 아래에 별도 initdb로 생성하고 `127.0.0.1:55483/guildup_email_disposable`만 사용했다. 기존 로컬/운영 DB에 연결하지 않았다. PostgreSQL 실행 대상은 `EmailVerificationPostgresTests`(28), `AuthPostgresTests`(23), `AccountWithdrawalPostgresTests`(17), `LoginProtectionPostgresTests`(8), `LoginSecurityMigrationPostgresTests`(1)이다. 새 SQL을 별도 schema에 두 번 적용해 ID/비밀번호 해시/미인증 값/Discord/멤버십 보존, enum CHECK 확장, 활성 토큰 UNIQUE, FK 삭제를 검증했다. 전체 실행에서 skip한 다른 PostgreSQL 및 조건부 데이터셋 테스트까지 실행한 것은 아니다. 검증용 DB 프로세스와 로컬 UI mock 서버·브라우저 탭은 작업 후 종료했다.

- 신규 흐름: 가입/해시/커밋 후 전송, 정상 인증, 만료/형식/없는 링크/다른 계정/재사용, 동시에 한 번 성공, 재발송 간격/시간/일 제한, 교체, 중복 메일 이벤트, SMTP 실패/지연/큐 포화, DB 저장 롤백, 서버 메모리와 독립된 토큰, 탈퇴·확인 경쟁, 정리 정책, CSRF/익명 호출, 신규 접근 제한, 기존 계정 및 Discord 이메일 추가.
- 기존 호환성: 기존 Auth·OAuth·프로필·탈퇴·로그인 공격 방어·커뮤니티/게임/모니터링 회귀. 1단계 LoginProtectionFlowTests는 SMTP mock을 추가해 로그인 이벤트만 검증하도록 독립성을 유지했다.
- 프론트엔드: 가입 후 안내 이동, 발송 상태, 명시적 확인/CSRF/주소 정리, 성공/실패/세션 만료, 재발송 중복 클릭·대기 시간, 기존 Discord only 안내.
- 빌드: 신규 HTML 진입 및 전체 경로 산출물 확인. 기존 단일 JS 번들의 500kB 경고는 남아 있다.
- 실제 Chrome 모바일 375×812에서 로컬 mock API 화면을 확인했다. 가로 콘텐츠 375px로 넘침 없음, 주요 버튼 높이 44px, referrer meta=no-referrer를 확인했다. 실제 모바일 메일 앱/웹뷰 검증은 배포 전 별도 수행한다.

## 11. 운영 배포 순서

1. 기존 DB/백업 및 계정 수/미인증 수/Discord 연결/주요 커뮤니티 이용 현황을 운영자가 확인한다. 1단계 신뢰 프록시·로그인 방어·HTTPS 세션 설정을 확인한다.
2. staging의 폐기 가능한 DB에서 새 SQL을 적용하고 중복 실행/기존 로그인·탈퇴·Discord·기록 보존을 확인한다.
3. 운영자가 `add_email_verification.sql`을 **새 앱 시작 전에** 수동 적용한다. 운영 데이터 UPDATE/DELETE 없이 새 컬럼/테이블/인덱스/CHECK만 추가한다.
4. 선택한 SMTP 업체의 발신 권한/인증 정보, 공통 `MAIL_*` 설정, 실제 EMAIL_VERIFICATION_URL, 방화벽의 SMTP host/port egress, timeout을 staging에서 확인한다. 문의 수신처는 기존 개인 Gmail을 유지한다. 기본 신규 제한 false 및 비동기 모니터링 true를 유지한다.
5. backend JAR와 `frontend/dist/` 전체를 운영자의 기존 배포 절차로 함께 배포한다. `/email-verification.html`은 전용 HTML을 제공해 초기 fragment 제거 스크립트와 referrer 메타를 유지한다. 이전 Spring static UI로 이 경로를 대체하지 않는다.
6. 프록시/CDN에서 인증 페이지에 `Referrer-Policy: no-referrer`, 인증 API에 `Cache-Control: no-store`를 유지한다. POST 본문/쿠키/인증 링크의 access/error/APM 로그 수집을 금지하고, 외부 분석·메일 링크 추적을 검토한다. 앱 포트 직접 접근과 과대 본문/대량 유입은 경계에서 제한한다.
7. 운영 승인된 테스트 계정으로 실제 인증 메일 수신/스팸함/모바일 표시/5분 문구/300초 만료/명시적 확인/세션 만료/SMTP 실패 후 재발송과 문의 알림의 기존 개인 Gmail 수신을 함께 확인한다. 두 기능 모두 From은 `noreply@guild-up.com`이어야 한다. 새 사용자 ID가 인증 전후 동일한지 확인한다. 정상 전환 확인 후 운영자가 기존 Gmail 앱 비밀번호를 직접 폐기한다.
8. 개발자 SECURITY 그룹의 새 코드, SMTP 실패율, 발송 상태, 가입 완료율, 재발송 429 비율과 사용자 문의를 관찰한다. 기존 미인증/Discord 계정의 로그인·커뮤니티·클랜원·PUBG 기록을 재확인한다.
9. 안내 기간 후 feature 도입 이후 cohort의 이용 현황을 검토하고 필요한 경우 EMAIL_VERIFICATION_ENFORCE_NEW_USERS=true로 활성화한다. false로 되돌리면 신규 기능 제한만 해제되며 email_verified/ID/기록은 보존된다.

이전 binary는 새 MonitoringEvent enum을 읽지 못할 수 있다. 단순 binary rollback에 앞서 새 코드의 읽기 호환성을 확인한다. 이상 발생 시 먼저 신규 제한을 false로 하고 SMTP 문제를 복구한다. 사용자/토큰/credential 테이블을 삭제해 롤백하지 않는다. 저장소에는 실제 운영 배포 정의가 없으므로 특정 systemd/Nginx/서버 재시작 명령을 실행하거나 확정하지 않았다.

## 12. 남은 위험과 다음 단계

- 실제 운영 도메인/SMTP 인증 정보·발신 권한/도메인 검증·발송 한도·SPF/DKIM/DMARC·모바일 메일 클라이언트·스팸함 전달은 운영 전 검증 필요. 기존 개인 Gmail은 문의 알림 수신처로 유지한다.
- 단일 서버 메모리 IP 예산/메일 큐의 재시작·다중 서버 한계. 엄격한 발송 복구가 필요하면 원문 비밀을 보호하는 durable outbox를 별도 설계해야 한다. 현재는 사용자의 재발송으로 복구한다.
- 메일 보안 서비스가 POST를 실행하거나 자격을 가진 세션까지 시뮬레이션하는 환경은 별도 검증 필요. 단순 GET 미리보기는 인증을 소비하지 않는다.
- 기존 회원가입의 이메일 중복 응답은 존재 추측 위험이 남는다. 이번 단계는 기존 계약을 유지했고 새 재발송/확인 API로 타 계정 상태를 조회하지 못하게 했다.
- 발송/이벤트 상한은 초기 운영값이다. NAT 사용자, 실제 가입량, 큐 지연·DB 크기·반복 실패를 보고 조정해야 한다.
- 비밀번호 찾기/재설정, 이메일 변경, MFA, CAPTCHA, 공유 rate limiter는 구현 범위 밖이다. 다음 단계에서는 이메일이 verified인지를 별도 보안 조건으로 확인해야 한다.

## 13. 변경 파일

기존 Discord 로그인·회원탈퇴 구현 및 참조된 기존 DB SQL은 수정하지 않았다. 아래 파일에서 구현·연동·테스트·배포 안내를 변경했다.

- `AUTHENTICATION.md`
- `EMAIL_VERIFICATION_READINESS.md`
- `README.md`
- `frontend/email-verification.html`
- `frontend/src/App.jsx`
- `frontend/src/api/http.js`
- `frontend/src/components/EmailVerificationPanel.jsx`
- `frontend/src/developer/MonitoringPage.jsx`
- `frontend/src/emailVerification.js`
- `frontend/src/pages/AccountSettingsPage.jsx`
- `frontend/src/pages/EmailVerificationPage.jsx`
- `frontend/src/pages/LoginPage.jsx`
- `frontend/src/styles/onboarding.css`
- `frontend/test/authPages.test.js`
- `frontend/vite.config.js`
- `pom.xml`
- `src/main/java/com/guildup/monitoring/domain/MonitoringEventCode.java`
- `src/main/java/com/guildup/user/auth/config/SessionCsrfWebConfig.java`
- `src/main/java/com/guildup/user/auth/exception/AuthExceptionHandler.java`
- `src/main/java/com/guildup/user/auth/service/AccountSettingsService.java`
- `src/main/java/com/guildup/user/auth/service/CredentialTransactions.java`
- `src/main/java/com/guildup/user/domain/UserCredential.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationAccessInterceptor.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationAccessPolicy.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationConfig.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationController.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationDeliveryTransactions.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationEvents.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationIpLimiter.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationMailFailure.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationMailListener.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationMailService.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationProperties.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationRateLimitedException.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationRequestInterceptor.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationRetention.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationService.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationToken.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationTokenRepository.java`
- `src/main/java/com/guildup/user/verification/EmailVerificationTokens.java`
- `src/main/resources/application.properties`
- `src/main/resources/application-local.properties`
- `src/main/resources/application-prod.properties`
- `src/main/resources/db/manual/add_email_verification.sql`
- `src/test/java/com/guildup/user/auth/LoginProtectionFlowTests.java`
- `src/test/java/com/guildup/user/verification/EmailVerificationFlowTests.java`
- `src/test/java/com/guildup/user/verification/EmailVerificationPostgresTests.java`
- `src/test/java/com/guildup/user/verification/EmailVerificationSecurityTests.java`
- `frontend/README.md`

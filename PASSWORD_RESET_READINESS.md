# GuildUp 정식 오픈 준비 3단계 완료 보고

작업일: 2026-10-09. 로컬 프로젝트에서 구현·검증했다. 운영 DB 접속, 운영 SQL 실행, 운영 서버 재시작, 운영 환경변수 변경, 실제 SMTP 발송은 수행하지 않았다. 시작 시 존재하던 이메일 인증·SMTP 작업과 기타 미커밋 변경은 보존했다.

재설정 메일은 **기본/prod 비활성화**다. `local` 프로필은 수동 메일 테스트를 위해 기존 SMTP 발송을 활성화하며, `PASSWORD_RESET_MAIL_ENABLED=false`로 차단할 수 있다. 자동화 테스트는 Surefire 설정으로 발송을 차단하고 SMTP를 mock한다. 운영자는 아래 SQL·프론트엔드·헤더·URL 설정을 적용한 뒤 `PASSWORD_RESET_MAIL_ENABLED=true`로 활성화해야 한다.

## 1. 기존 인증 구조 분석

| 대상 | 확인 결과 및 적용 방식 |
|---|---|
| `users` | GuildUp의 영속 사용자 ID. `ACTIVE/WITHDRAWN`, 시스템 권한, 프로필을 보존하고 인증 버전만 추가한다. |
| `user_credentials` | 사용자당 하나의 이메일 로그인 수단. 정규화 이메일 UNIQUE, `{bcrypt}` 비밀번호, `email_verified`, `verification_required`를 사용한다. |
| `user_external_accounts` | Discord 연결을 별도 저장하며 사용자/provider 및 provider/external ID UNIQUE를 유지한다. Discord 이메일로 계정을 찾거나 병합하지 않는다. |
| 가입·로그인 | `AuthController` → `CredentialAuthService`/`CredentialTransactions`. 가입 및 로그인 수단 추가는 원자적 트랜잭션을 유지한다. |
| 비밀번호 | 기존 `PasswordEncoder`와 `CredentialPolicy` 재사용. 영문·숫자 포함 8자 이상, UTF-8 최대 72바이트, 원문 trim 금지. |
| 이메일 인증 | `EmailVerificationService` 및 별도 토큰 테이블. 기본 300초, 해시 저장, 재발송 제한, 커밋 이후 별도 메일 큐. 기존 hash와 확인 API는 유지한다. |
| 인증 전 사용자 | 이메일 로그인은 허용하고 신규 가입의 주요 기능 제한은 기존 선택 정책을 따른다. 재설정으로 인증 상태나 제한을 해제하지 않는다. |
| 로그인 공격 방어 | `ProtectedEmailLoginService`, 제한된 메모리 저장소, 신뢰 프록시 `ClientIpResolver`, HMAC 식별자를 유지한다. 재설정 IP 판별에 resolver/hasher를 재사용한다. |
| Discord OAuth·연결·해제 | 기존 state, 목적, 세션 바인딩, 사용자 충돌 거부, 마지막 로그인 수단 보호를 그대로 사용한다. |
| 세션·CSRF | Servlet HttpSession, 세션 ID/CSRF 회전, cookie-only, HttpOnly/SameSite 및 prod Secure 유지. 기존 전역 사용자 interceptor를 인증 버전 검사로 확장한다. |
| 메일 | 기존 Resend SMTP `spring.mail.*`, `JavaMailSender`, `ApplicationMailProperties`를 공유한다. 인증 HTML 발송 부분만 공통 `ApplicationMailService`로 추출했다. 문의 메일의 수신처·정책은 유지한다. |
| 모니터링 | `MonitoringEventService`의 기존 비동기·제한 큐와 `SECURITY` 카테고리 재사용. 개발자 접근 권한 유지. |
| 프론트엔드·테스트 | React/Vite가 현재 화면이며 Spring static은 이전 화면이다. H2, PostgreSQL, 실제 Servlet 쿠키, React DOM 및 실제 Chrome 검증을 사용한다. |

기존 비밀번호 찾기·재설정 구현은 없었다. 인증 시스템이나 Spring Security 필터 체인을 전면 재작성하지 않았다. 계정 비노출, 난수 일회용 토큰, 일반 로그인으로 복귀하는 정책은 [OWASP Forgot Password 가이드](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html)와 대조했다.

## 2. 구현한 흐름

1. 로그인 화면의 `비밀번호를 잊으셨나요?` → 이메일 입력 → CSRF를 포함한 요청.
2. IP 예산 확인 후 제한된 요청 큐에 넣고 동일한 **202 접수 안내**를 반환한다. HTTP 스레드에서 계정 조회·DB 잠금·SMTP를 기다리지 않는다.
3. 작업자가 이메일 로그인 수단이 있는 활성 사용자를 조회하고 사용자 행을 잠근다. 계정 제한 및 DB의 전역 메일 예산을 확인한다.
4. 이전 미사용 링크를 무효화하고 256비트 난수의 목적별 해시를 저장한다. 커밋 이후 별도 메일 큐에서 발송한다.
5. 링크를 열면 fragment를 주소에서 제거한다. CSRF POST 사전 검증은 토큰을 소비하지 않는다.
6. 새 비밀번호와 확인을 제출하면 기존 정책·encoder를 사용한다. 최종 트랜잭션에서 사용자를 잠그고 토큰과 credential을 **DB에서 다시 읽는다**.
7. 토큰의 목적·대상·유효기간·사용/폐기 여부·발급 당시 인증 버전을 재검증하고, 새 비밀번호 저장·토큰 사용·인증 버전 증가·미사용 토큰 폐기를 함께 커밋한다.
8. 기존 세션을 차단하고 성공 안내를 표시한다. 자동 로그인하지 않는다.

존재하지 않는 이메일, Discord 전용, 탈퇴 계정, 미인증 계정, 계정/IP/전역 제한, 큐 거절, 비동기 DB/SMTP 실패는 요청 API에서 같은 접수 응답을 갖는다. 형식이 잘못된 이메일도 계정 조회 없이 같은 응답으로 처리한다. CSRF 실패나 잘못된 JSON 같은 계정과 무관한 요청 오류는 기존 403/400 정책을 따른다. 정확히 동일한 네트워크 지연을 보장한다는 의미는 아니며, 계정 유무에 따른 조회·메일 대기 시간을 응답 경로에서 제거했다.

## 3. 새 API

모두 기존 세션 CSRF 보호를 받는 익명 사용 가능 API다. 현재 로그인 사용자를 재설정 대상으로 사용하지 않는다. 응답에 `Cache-Control: no-store` 및 `Referrer-Policy: no-referrer`를 적용한다.

| API | JSON 입력 | 응답 |
|---|---|---|
| `POST /api/auth/password-reset/request` | `email` | 202 `{message: "입력하신 이메일로 재설정 안내를 보낼 수 있는 경우 잠시 후 이메일이 발송됩니다."}` |
| `POST /api/auth/password-reset/validate` | `token` | 유효하면 204. 검증만 하며 소비·변경하지 않는다. |
| `POST /api/auth/password-reset/confirm` | `token`, `password`, `passwordConfirmation` | 변경 성공 204. 로그인 세션을 생성하지 않는다. |

토큰 오류는 400 `PASSWORD_RESET_INVALID/EXPIRED/REUSED`, 비밀번호 오류는 기존 `INVALID_PASSWORD/PASSWORD_MISMATCH`, 토큰 검사 IP 제한은 429다. 메일 요청의 제한은 계정 비노출을 위해 202 안내로 통일한다. `GET`으로 재설정하는 API는 없다. 브라우저가 임의의 `userId`를 보내도 대상은 바뀌지 않는다.

## 4. 프론트엔드 페이지·경로

| 경로 | 구성 |
|---|---|
| `/forgot-password.html` | `비밀번호 찾기`, 지정 안내 문구, 이메일 주소, `재설정 이메일 보내기`, 제출 중 표시/중복 방지, 동일 접수 안내 |
| `/password-reset.html` | `새 비밀번호 설정`, 새 비밀번호/확인, 표시·숨기기, 정책 안내, 일치 상태, 제출 중 표시/중복 방지, 오류·만료·재요청 안내 |
| 성공 화면 | `비밀번호가 변경되었습니다. 새로운 비밀번호로 로그인해 주세요.`, `로그인하러 가기` |

기존 로그인 카드·헤더·푸터·색상·컨트롤을 재사용한다. 360/390/1280px에서 가로 넘침과 44px 이상 컨트롤을 확인했다. 같은 탭에서 새 메일 링크를 여는 fragment 이동도 새 링크를 검증하고 이전 입력/성공 상태를 지운다. 이전 제출의 늦은 응답이 새 링크 화면을 덮어쓰지 않도록 요청 취소와 세대 검사를 적용했다.

## 5. Discord 버튼 변경

React 로그인/회원가입 공통 버튼과 이전 Spring static 로그인 버튼을 **`디스코드 로그인`**으로 통일했다. React의 Discord SVG 아이콘, 버튼 클래스/색상/위치, 반응형, `/api/auth/discord/authorize`는 그대로다. SVG는 `aria-hidden=true`이고 접근성 이름은 변경된 표시 문구다. 별도의 오래된 접근성 라벨은 없었다. 일반 안내 문장의 `Discord로 로그인한 뒤…` 등은 변경하지 않았다.

## 6. 변경 파일

작업 시작 시 상태와 비교한 이번 단계의 변경 파일을 문서 끝에 열거한다. 이전 단계에서 이미 변경되어 있던 파일의 전체 git diff를 이번 작업으로 계산하지 않았다.

## 7. 이메일 발송 방식·복구

기존 SMTP와 `JavaMailSender`를 그대로 사용한다. 기본 발신자는 `GuildUp <noreply@guild-up.com>`, 제목은 `[GuildUp] 비밀번호 재설정 안내`다. 공통 인증 메일 템플릿의 GuildUp 브랜드, 모바일 HTML, 버튼, 유효기간, 요청하지 않은 경우 안내를 사용한다. 새 업체 SDK나 유료 서비스는 추가하지 않았다. `MAIL_*`, 문의 수신처, 기존 인증 메일 제목·정책은 유지한다.

DB 커밋 → 제한 큐 → 짧은 발송 상태 트랜잭션 → **트랜잭션 없는 SMTP** → 짧은 완료 트랜잭션으로 처리한다. 토큰 상태는 `QUEUED/SENDING/SENT/FAILED`다. SMTP 실패는 원래 비밀번호·인증 상태·계정 상태를 바꾸지 않는다. 실패 링크는 폐기하고 발송 시도 예산은 되돌리지 않는다. 자동 재시도는 없으며, 설정 복구 후 사용자가 제한 시간이 지난 뒤 새 링크를 요청할 수 있다.

서버 재시작으로 메모리 큐의 원문이 사라진 경우 같은 토큰을 복구 발송하지 않는다. 새 요청이 새 토큰을 발급한다. 이미 발송한 링크와 DB의 제한 기록은 재시작 후에도 유지된다. 메일은 provider 자체 quota 초과를 포함한 실패를 안전한 고정 원인으로 기록하며 외부 예외 본문·스택을 전달하지 않는다.

자동화 테스트에서 SMTP는 전부 mock이며, Surefire 설정으로 실제 발송을 방지했다. `local` 프로필의 수동 요청은 기존 SMTP로 발송하며 loopback 프론트엔드 링크를 사용한다. 이번 작업 중 직접 SMTP 요청이나 실제 메일 발송은 수행하지 않았다.

## 8. 토큰 저장·검증

`SecureRandom` 32바이트 → URL-safe Base64 43자. 기존 이메일 인증의 난수 생성·형식 검사·SHA-256 유틸리티를 재사용한다. 재설정 해시는 `SHA-256("PASSWORD_RESET:" + raw)`로 목적을 분리한다. 기존 이메일 인증 hash 계산은 바꾸지 않아 이미 발급된 인증 링크도 유지한다.

인증 토큰은 `email_verification_tokens`와 `EMAIL_VERIFICATION`, 재설정 토큰은 `password_reset_tokens`와 `PASSWORD_RESET`만 허용한다. 별도 저장소, DB 목적 CHECK, 서버 목적 검사, 목적별 hash로 양방향 혼용을 거부한다.

재설정 데이터는 credential FK를 통해 **credential → users.id**에 연결된다. DB에는 원문, 이메일 주소, 전체 링크를 저장하지 않는다. 해시 UNIQUE와 사용자 잠금, credential별 미사용 토큰 partial UNIQUE를 함께 적용한다. 최종 저장 전 refresh는 OSIV 캐시로 폐기된 토큰을 다시 쓰거나 최근 이메일 인증 상태를 덮어쓰는 경합을 방지한다.

링크는 서버 설정의 고정 HTTPS 페이지 URL로 생성한다. 요청 Host/Forwarded Host는 사용하지 않는다. 토큰은 query/path 대신 fragment에 넣어 HTTP 요청·Referer에서 제외하고 전용 HTML의 초기 스크립트가 bundle 전에 제거한다. 이후 메모리에만 보관하며 local/session storage, router state, DOM, 분석 URL, 로그에는 넣지 않는다. 요청 본문은 서버 검증에 필요한 POST로만 전달한다.

## 9. 유효기간

기본 **30분**이며 `PASSWORD_RESET_TOKEN_TTL=30m`이다. 만료 시각과 같거나 이후면 거부한다. validate/페이지 GET은 소비하지 않고, 비밀번호 변경 성공 트랜잭션에서 즉시 사용 처리한다. 새 토큰 발급 시 이전 미사용 링크를 폐기하며, 변경 성공 시 해당 credential의 모든 미사용 링크를 폐기한다. 기존 이메일 인증의 기본 300초는 변경하지 않았다.

## 10. 요청·발송 제한

| 범위 | 기본 정책 | 저장·동시성 |
|---|---|---|
| 계정 | 60초 간격, rolling 1시간 3회, rolling 24시간 5회 | 토큰 발급 기록과 users 행 잠금. 실패 발송 포함, 재시작 유지 |
| IP의 메일 요청 | 1시간 20회, 24시간 50회 | 신뢰 프록시 판별 후 HMAC 키. 여러 이메일 요청을 합산하고 synchronized 예약 |
| IP의 토큰 검사·확정 | 분당 합계 20회 | 메일 요청 예산과 별도 |
| 전역 재설정 메일 | UTC 하루 30회, UTC 월 600회 | singleton DB 행 잠금, 여러 계정/서버/동시 요청과 재시작에도 유지 |
| 메모리·큐 | IP 키 최대 10,000개; 요청 2 worker/100 queue; 메일 2 worker/30 queue | 포화 시 추가 작업 거부. 요청 안내는 동일 |
| 기록 보존 | 토큰 만료 후 7일; 매시간 최대 5,000행 정리 | 최근 제한 근거와 예산을 보존. IP 기록은 시간 창 만료 후 정리 |

공유 IP에 즉시 계정 차단이나 장기 잠금을 걸지 않고 요청량만 제한한다. IP 제한은 계정별 제한보다 넓고 환경변수로 조정 가능하다. 로그인 방어·이메일 인증 발송·문의 정책과 예산은 독립적이다.

사용자가 제시한 Resend 무료 한도(100/일, 3,000/월)에 대해 재설정 공격만으로 전체 한도를 쓰지 못하도록 기본 예산을 일부로 제한했다. **전체 SMTP 합계 예산은 아니다.** 인증/문의 등의 기존 발송량까지 합해 무료 한도 이하인지 운영자가 확인해야 한다. 요청 제한과 실패 시 보수적 예산 소모로 무한 재시도를 막는다.

## 11. 전체 기기 세션 무효화

`users.authentication_version`을 기본 0으로 추가하고 공통 `AuthSessionService.authenticate`가 세션에 검증 당시 버전을 저장한다. 로그인 직후 DB 버전과 인증을 통과한 User 버전이 다르면 세션을 승격하지 않아 변경 전 비밀번호로 검사된 늦은 로그인을 막는다.

비밀번호 변경 트랜잭션에서 버전을 증가시킨다. 같은 서버에서 추적 중인 이전 버전 세션은 커밋 후 실제 invalidate한다. **다른 서버나 레지스트리에 없는 세션은 매 API 요청의 DB 버전 검사로 401 및 로그아웃 처리**한다. 다른 서버의 모든 HttpSession 객체를 직접 삭제했다고 주장하지 않는다. 배포 전 버전 속성이 없는 세션은 버전 0일 때만 인정한다.

개발자 실시간 SSE 로그도 DB 버전을 250ms poll에서 검사해 다른 서버에서 열린 스트림을 종료한다. 새 비밀번호로 이미 재로그인한 새 버전 세션은 뒤늦은 폐기 작업에서 보존한다. 자동 로그인은 없다.

앞으로 일반 비밀번호 변경 API가 생겨도 `UserCredential.changePasswordHash`를 사용자 잠금 안에서 사용해야 한다. 이 메서드가 인증 버전도 회전시키므로 기존 재설정 링크가 거부된다. 현재 일반 비밀번호 변경 API는 기존 소스에 없었다.

## 12. Discord 사용자 영향

Discord 전용 사용자에게 credential을 생성하지 않는다. 이메일+Discord 계정은 이메일 비밀번호만 바꾸고 Discord 연결·사용자 ID·커뮤니티 기록은 유지한다. 이전 Discord 로그인 세션도 같은 인증 버전 정책으로 차단되지만 새 Discord OAuth 로그인은 동일 계정으로 가능하다. 자동 병합·연결 해제·재가입은 없다. 다른 사용자로 로그인한 브라우저에서는 토큰 소유자의 비밀번호만 바꾸며 그 다른 사용자의 세션은 유지한다.

## 13. 이메일 사용자 영향

기존 사용자 ID와 비밀번호는 요청만으로 바뀌지 않는다. 변경 성공 이후 기존 비밀번호는 거부되고 새 비밀번호로 로그인한다. 기존 인증된 이메일은 인증 상태를 유지하고, 미인증 이메일은 **false 그대로**이며 `verification_required`와 기존 제한도 유지한다. 재설정 링크를 이메일 인증이나 권한 근거로 사용하지 않는다. 기존 이메일 인증 토큰은 별도 목적을 유지한다.

## 14. 개발자 모니터링

개발자 페이지 기존 보안 탭·이벤트 조회 권한·검색에 다음 코드를 연동했다.

| 코드 | 기록 |
|---|---|
| `PASSWORD_RESET_MAIL_FAILED` | SMTP 인증/연결/시간 초과/발송 실패, 큐·발송 상태 저장 실패의 고정 reason |
| `PASSWORD_RESET_RATE_LIMITED` | 계정 또는 전역 발송 예산 제한 |
| `PASSWORD_RESET_INVALID` | 잘못된·교체된·인증 버전이 바뀐 링크 |
| `PASSWORD_RESET_EXPIRED` | 만료 링크 |
| `PASSWORD_RESET_REUSED` | 사용한 링크 재사용 |
| `PASSWORD_RESET_ABUSE` | 반복 IP/토큰 요청 또는 요청 큐 포화 |
| `PASSWORD_RESET_COMPLETED` | 커밋된 변경 성공 |
| `PASSWORD_RESET_STORAGE_FAILED` | 비동기 발급/예산 초기화/정리 저장 장애 |

기존 비동기 MonitoringEvent 저장을 사용하고 추가 모니터링 상한은 인스턴스당 분당 30건이다. 정상 접수·일반 발송마다 이벤트를 만들지 않는다. 사용자 ID와 고정 코드/원인만 직접 전달한다. 비밀번호·hash·토큰·링크·이메일·SMTP 인증·API Key·세션 ID·OAuth token은 기록하지 않는다. 모니터링 실패는 잡아서 인증 결과와 분리한다.

## 15. DB 변경

- `users.authentication_version BIGINT NOT NULL DEFAULT 0`
- `email_verification_tokens.purpose VARCHAR(32) NOT NULL DEFAULT 'EMAIL_VERIFICATION'`
- `password_reset_tokens`: credential FK, 목적, hash UNIQUE, 발급 당시 인증 버전, 생성/만료/사용/폐기 시각, 발송 상태
- `password_reset_mail_quota`: singleton 키, UTC 일/월, 발급 시도 카운터
- credential/생성 시각 및 만료 인덱스, 미사용 토큰 partial UNIQUE, 목적/hash/시각/비음수 CHECK
- 기존 monitoring event CHECK의 새 코드 허용 확장

Hibernate가 먼저 만든 테이블에도 동일한 제약이 추가되도록 수동 SQL을 작성했다. 기존 계정/인증 정보와 Discord/커뮤니티 데이터의 삭제·ID 변경·병합·일괄 credential 생성은 없다. Hibernate 자동 변경만으로 partial UNIQUE와 운영 제약 적용을 대체하지 않는다.

## 16. 운영 DB SQL 및 적용 순서

이번 단계의 SQL은 [add_password_reset.sql](src/main/resources/db/manual/add_password_reset.sql)이다. 운영 DB에는 실행하지 않았다.

1. 기존 `users`, `user_credentials`, `monitoring_events`, `email_verification_tokens` 및 1·2단계 선행 SQL이 적용되어 있는지 확인한다. 2단계 미적용이면 [이메일 인증 보고서](EMAIL_VERIFICATION_READINESS.md)에 따른 선행 SQL과 `add_email_verification.sql`부터 적용한다.
2. 운영자의 기존 백업·배포 절차로 `add_password_reset.sql`을 새 backend 시작 전에 적용한다.
3. version 기본 0, 기존 이메일 purpose, 새 FK/UNIQUE/CHECK/인덱스 및 예산 행을 확인한다.

SQL은 트랜잭션, 5초 lock timeout, 60초 statement timeout을 사용하며 재실행 시 기존 카운터나 인증 정보를 초기화하지 않는다. 기존 데이터와 SQL 두 번 적용을 격리 PostgreSQL 테스트에서 검증했다.

## 17. 신규 환경변수

| 변수 | 기본값 |
|---|---|
| `PASSWORD_RESET_MAIL_ENABLED` | 기본/prod `false`, local 수동 테스트 `true` |
| `PASSWORD_RESET_URL` | `https://guild-up.com/password-reset.html` — 실제 운영 프론트엔드 주소로 운영자가 확인/지정 |
| `PASSWORD_RESET_TOKEN_TTL` | `30m` |
| `PASSWORD_RESET_REQUEST_INTERVAL` | `60s` |
| `PASSWORD_RESET_ACCOUNT_HOURLY_LIMIT` | `3` |
| `PASSWORD_RESET_ACCOUNT_DAILY_LIMIT` | `5` |
| `PASSWORD_RESET_IP_HOURLY_LIMIT` | `20` |
| `PASSWORD_RESET_IP_DAILY_LIMIT` | `50` |
| `PASSWORD_RESET_TOKEN_REQUESTS_PER_MINUTE` | `20` |
| `PASSWORD_RESET_MAX_IP_ENTRIES` | `10000` |
| `PASSWORD_RESET_MAX_EVENTS_PER_MINUTE` | `30` |
| `PASSWORD_RESET_MAIL_DAILY_BUDGET` | `30` |
| `PASSWORD_RESET_MAIL_MONTHLY_BUDGET` | `600` |
| `PASSWORD_RESET_RETENTION` | `7d` |
| `PASSWORD_RESET_CLEANUP_CRON` | `0 20 * * * *` |

새 SMTP 자격 증명은 없다. 기존 `MAIL_*`를 사용한다. 로컬 profile만 loopback HTTP URL 예외를 허용하며 `prod`에서는 거부한다. 토큰 TTL은 1분~24시간, retention은 최소 2일 등 설정 유효성을 검증한다. 기존 로그인 HMAC 설정을 재사용하고 원문 IP를 저장하지 않는다.

## 18. 테스트·빌드 결과

백엔드 전체 1,328개 중 1,326개 통과·2개 건너뜀·실패/오류 0건, 마지막 재설정 테스트 68개 통과, 프론트엔드 241개 통과다. 전체 테스트는 격리된 임시 PostgreSQL `127.0.0.1:55483/stage3_test`를 명시해 PostgreSQL 회귀도 실행했다. 직접 생성한 임시 서버는 검증 후 종료했고 해당 포트의 listener가 없음을 확인했다. `create-drop`을 쓰므로 운영 DB를 테스트 대상으로 지정하면 안 된다.

검증 범위:

- 정상 요청·존재하지 않는 주소·Discord 전용·탈퇴·미인증·비노출 응답과 HTTP 경로의 비동기 분리
- 256비트/hash/30분 경계/미리보기 비소비/잘못된·사용된·교체된 링크/양방향 인증 토큰 혼용
- 동일 토큰 동시 사용, 계정 동시 발급, 여러 계정의 전역 예산 경합, 서버 재시작 후 제한 유지
- 정책·확인·BCrypt 저장·기존 비밀번호 실패·새 비밀번호 성공·잔여 토큰 폐기·DB rollback
- 사전 검증 이후 다른 요청이 링크를 교체하거나 이메일을 인증하는 OSIV 경합
- 현재/다른 기기/다른 서버/기존 버전 없는 세션/실제 Servlet 쿠키/재로그인/Discord 및 커뮤니티 보존/SSE 폐기
- SMTP authentication/send/connection/timeout 분류, 메일·요청 큐 포화, 저장 실패, 원문 없는 이벤트, 모니터링 실패 격리
- React UI 각 상태, CSRF, 표시·숨기기, 정책/일치, 중복 제출, 새 링크 탭 이동, 모바일 실제 Chrome
- 기존 가입·인증·로그인 공격 방어·Discord OAuth/연결/해제·탈퇴·메일/문의·개발자 권한 및 전체 커뮤니티 회귀

실제 Discord 동의/토큰 교환과 Resend SMTP는 mock으로 검증했다. 운영 환경이나 실제 메일 클라이언트의 발송·수신을 검증한 결과는 아니다.

초기 검증 실패는 새 공통 메일 bean의 제한 테스트 컨텍스트 등록, 인증 버전 조회 mock, 브라우저 탭 이동/테스트 이벤트 바인딩을 수정해 해소했다. 동시성 검증은 운영과 같은 비동기 모니터링으로 실행했다. 전체 PostgreSQL 첫 실행의 `too many clients`는 캐시된 테스트 컨텍스트들이 임시 DB 기본 연결 한도를 넘은 환경 문제였다. 임시 서버 한도만 늘린 후 재실행했다. 운영 설정은 바꾸지 않았다.

## 19. 운영 서버 적용 순서

1. 1·2단계 선행 상태 확인 → 위 수동 SQL 적용. 새 binary와 섞여 동작할 이전 binary는 배포 계획에서 제외한다.
2. backend JAR 및 같은 빌드의 `frontend/dist/` 전체 준비. `/password-reset.html`의 전용 HTML을 유지하고 일반 index fallback으로 바꾸지 않는다.
3. 동일 출처 HTTPS/API proxy, 쿠키 및 신뢰 프록시 기존 정책 확인. 고정 `PASSWORD_RESET_URL`과 공식 발신 헤더를 확인한다.
4. 민감 HTML의 **`Cache-Control: no-store`, `Referrer-Policy: no-referrer`**, 캐시/CDN 우회 및 POST body 비로그 정책을 정적 호스트에서도 적용한다. Spring 제공 시 코드 필터가 적용되지만 외부 정적 호스트 설정은 별도다. 단순 HTML 메타로 HTTP cache 정책을 대체하지 않는다.
5. 기존 비동기 모니터링 설정을 유지하고 초기에는 재설정 메일 비활성화 상태로 화면/API/새 DB 제약을 확인한다.
6. 운영자의 기존 배포 절차로 적용 후 `PASSWORD_RESET_MAIL_ENABLED=true`로 활성화한다. 기존 MAIL/인증/문의 설정은 유지한다.
7. 운영자가 통제하는 검증 계정으로 링크 수신·30분 정책·재사용 거부·기존 기기 로그아웃·새 이메일/Discord 로그인 및 보안 이벤트를 확인한다. 이번 작업에서 실제 발송/운영 검증은 수행하지 않았다.

저장소에 실제 운영 배포 정의가 없어 Nginx/systemd/클라우드 변경 명령을 실행하거나 임의로 확정하지 않았다. binary rollback 시 새 MonitoringEvent enum의 읽기 호환성을 먼저 확인해야 하며 사용자/credential/토큰 테이블 삭제로 롤백하지 않는다.

## 20. 남아 있는 위험·한계

- 정적 호스트의 HTTPS/실제 주소/헤더/CDN/body 로그, Resend 설정·무료 한도와 실제 Discord 서비스는 운영 확인이 남아 있다.
- IP 카운터는 서버 메모리이므로 재시작·여러 인스턴스에 걸쳐 공유되지 않는다. 계정 제한과 전역 재설정 메일 예산은 DB에서 유지하므로 메일 증폭을 별도로 막는다.
- 전역 예산은 재설정만 포함한다. 인증·문의까지 포함한 전체 provider 사용량은 별도 관리가 필요하다.
- 발송 큐는 영속 outbox가 아니다. 재시작/큐 손실 시 새 요청으로 복구하며, 자동 무한 재시도는 하지 않는다.
- 변경 전에 이미 권한 검사를 통과해 실행 중인 요청/네트워크에 쓰인 SSE 프레임은 되돌릴 수 없다. 이후 API는 DB 버전으로 거부하고 SSE는 다음 poll에서 종료한다. 이전 binary가 남은 혼합 배포에서는 이 정책이 완전하지 않다.
- 메일함이나 유효한 링크가 탈취되면 30분 동안 bearer 권한이 있다. 비밀번호 재설정은 별도의 Discord 인증 수단을 제거하지 않으므로 Discord 자체가 침해된 경우 그 복구는 별도다.
- 모니터링 상한·큐 포화 시 일부 이벤트가 저장되지 않을 수 있다. 인증 결과는 모니터링 저장 성공에 의존하지 않는다.

## 검증 기록

| 실행 | 결과 | 근거 |
|---|---|---|
| `TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55483/stage3_test TEST_POSTGRES_USER=stage3_test TEST_POSTGRES_PASSWORD='' ./mvnw -q test` | 1,328개: 통과 1,326 / 건너뜀 2 / 실패 0 / 오류 0 | Surefire XML 합계; `/tmp/guildup-stage3-full-backend.log` |
| 같은 임시 DB에서 `./mvnw -q -Dtest='PasswordResetFlowTests,PasswordResetPostgresTests,PasswordResetSecurityTests' test` | 최종 수정 후 68/68 통과: H2 27 + PostgreSQL 29 + 보안 12 | `/tmp/guildup-stage3-password-tests.log` |
| `./mvnw -q -DskipTests package` | 성공. 72MiB 실행 JAR 생성 | `target/guildup-backend-0.0.1-SNAPSHOT.jar`; `/tmp/guildup-stage3-package.log` |
| `cd frontend` 후 `npm test` | 241/241 통과 | `/tmp/guildup-stage3-frontend.log` |
| `npm run build` | 성공, 46 route와 전용 재설정 HTML 확인 | `/tmp/guildup-stage3-build.log`; `frontend/dist/` |
| `RESET_BROWSER_ARTIFACTS=/tmp/guildup-stage3-browser node scripts/verify-password-reset-browser.mjs` | 360/390/1280px: 입력·접수·재설정·성공·만료 15개 화면 통과. Discord 문구/아이콘/경로, URL 토큰 제거, 가로 넘침 없음, 터치 크기 확인 | `/tmp/guildup-stage3-browser.log`; `/tmp/guildup-stage3-browser/` PNG 15개 |
| `git diff --check` | 성공, 공백 오류 없음 | 최종 diff 검사 |

건너뛴 두 테스트는 `BingoProductionDumpBaselineTests`다. 이 테스트의 조건인 운영 덤프 대상 `SPRING_DATASOURCE_URL`을 설정하지 않았으므로 실행되지 않았다. 나머지 PostgreSQL 조건부 회귀는 직접 만든 임시 DB에서 실행했다. 프론트엔드 빌드에는 기존 단일 bundle이 500kB를 넘는 Vite 경고가 있으며 빌드 실패는 없다.

브라우저 검증 스크립트는 임시 정적 서버·mock API·별도 Chrome 임시 profile을 직접 생성하고 종료한다. 로컬 실행 중인 backend나 OAuth·SMTP를 호출하지 않는다. `CHROME_PATH`로 설치 위치를 지정할 수 있다.

주요 코드 근거는 `PasswordResetFlowTests`의 동시 토큰 사용·purpose 양방향 혼용·old/new 로그인·다른 계정 세션·OSIV 경합, `SessionCookieIntegrationTests.passwordResetRevokesRealCookiesForBothBrowsersAndRequiresNewPasswordLogin`의 두 브라우저 실제 쿠키, `PasswordResetPostgresTests`의 기존 스키마 backfill/SQL 재실행, `RealtimeLogStreamServiceTests`의 다른 서버 버전 변경 후 스트림 종료, `frontend/test/authPages.test.js`의 UI 상태·CSRF·중복 제출·새 링크 이동 검사다.


## 이번 단계 변경 파일 목록

시작 시 source hash와 비교한 변경 29개·신규 29개다. 이 목록에서 수정은 git 추적 여부가 아니라 작업 시작 시 파일 존재 여부를 기준으로 한다. 원래 존재하던 미커밋 변경이나 삭제는 보존했다. 생성물 `target/`, `frontend/dist/` 및 임시 로그·화면 PNG는 소스 목록에 포함하지 않는다.

수정 파일:

```text
AUTHENTICATION.md
README.md
frontend/README.md
frontend/src/App.jsx
frontend/src/authValidation.js
frontend/src/developer/MonitoringPage.jsx
frontend/src/pages/LoginPage.jsx
frontend/src/styles/onboarding.css
frontend/test/authPages.test.js
frontend/vite.config.js
pom.xml
src/main/java/com/guildup/mail/config/ApplicationMailConfig.java
src/main/java/com/guildup/monitoring/domain/MonitoringEventCode.java
src/main/java/com/guildup/monitoring/service/RealtimeLogStreamService.java
src/main/java/com/guildup/user/auth/config/SessionCsrfInterceptor.java
src/main/java/com/guildup/user/auth/service/AuthSessionService.java
src/main/java/com/guildup/user/domain/User.java
src/main/java/com/guildup/user/domain/UserCredential.java
src/main/java/com/guildup/user/repository/UserRepository.java
src/main/java/com/guildup/user/verification/EmailVerificationMailService.java
src/main/java/com/guildup/user/verification/EmailVerificationService.java
src/main/java/com/guildup/user/verification/EmailVerificationToken.java
src/main/resources/application-local.properties
src/main/resources/application-prod.properties
src/main/resources/application.properties
src/main/resources/static/login.html
src/test/java/com/guildup/monitoring/RealtimeLogStreamServiceTests.java
src/test/java/com/guildup/user/auth/SessionCookieIntegrationTests.java
src/test/java/com/guildup/user/auth/controller/DiscordLoginControllerTests.java
```

신규 파일:

```text
PASSWORD_RESET_READINESS.md
frontend/password-reset.html
frontend/scripts/verify-password-reset-browser.mjs
frontend/src/pages/ForgotPasswordPage.jsx
frontend/src/pages/PasswordResetPage.jsx
frontend/src/passwordReset.js
src/main/java/com/guildup/mail/ApplicationMailService.java
src/main/java/com/guildup/user/auth/token/AuthTokenPurpose.java
src/main/java/com/guildup/user/reset/PasswordResetConfig.java
src/main/java/com/guildup/user/reset/PasswordResetController.java
src/main/java/com/guildup/user/reset/PasswordResetDeliveryTransactions.java
src/main/java/com/guildup/user/reset/PasswordResetEvents.java
src/main/java/com/guildup/user/reset/PasswordResetMailListener.java
src/main/java/com/guildup/user/reset/PasswordResetMailQuota.java
src/main/java/com/guildup/user/reset/PasswordResetMailQuotaRepository.java
src/main/java/com/guildup/user/reset/PasswordResetMailService.java
src/main/java/com/guildup/user/reset/PasswordResetPrivacyFilter.java
src/main/java/com/guildup/user/reset/PasswordResetProperties.java
src/main/java/com/guildup/user/reset/PasswordResetRequestLimiter.java
src/main/java/com/guildup/user/reset/PasswordResetRequests.java
src/main/java/com/guildup/user/reset/PasswordResetRetention.java
src/main/java/com/guildup/user/reset/PasswordResetService.java
src/main/java/com/guildup/user/reset/PasswordResetToken.java
src/main/java/com/guildup/user/reset/PasswordResetTokenRepository.java
src/main/java/com/guildup/user/reset/PasswordResetTokens.java
src/main/resources/db/manual/add_password_reset.sql
src/test/java/com/guildup/user/reset/PasswordResetFlowTests.java
src/test/java/com/guildup/user/reset/PasswordResetPostgresTests.java
src/test/java/com/guildup/user/reset/PasswordResetSecurityTests.java
```

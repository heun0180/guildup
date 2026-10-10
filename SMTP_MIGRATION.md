# GuildUp 공통 SMTP 설정

SMTP 인증 계정, 사용자에게 보이는 발신 주소, 문의/건의 알림 수신처는 서로 다른 설정이다.

| 역할 | 설정 | 적용 값 |
|---|---|---|
| 공통 SMTP 호스트 | `MAIL_HOST` → `spring.mail.host` | 기본 `localhost`; 실제 SMTP 서버를 지정 |
| 공통 SMTP 포트 | `MAIL_PORT` → `spring.mail.port` | 기본 `587`, STARTTLS 필수 |
| SMTP 인증 사용자 | `MAIL_USERNAME` → `spring.mail.username` | 선택한 SMTP 서버의 인증 사용자명; From과 별개 |
| SMTP 인증 비밀번호 | `MAIL_PASSWORD` → `spring.mail.password` | 선택한 SMTP 서버의 비밀번호/API key |
| 공식 발신 주소 | `MAIL_FROM` → `app.mail.from` | 기본 `noreply@guild-up.com` |
| 발신 표시 이름 | `MAIL_FROM_NAME` → `app.mail.from-name` | 기본 `GuildUp`; 빈 값이면 이름 생략 |
| 문의/건의 알림 수신처 | `FEEDBACK_MAIL_TO` → `app.mail.feedback-to` | 기존 `heun0180@gmail.com` 유지 |
| 별도 회신 주소 | `MAIL_REPLY_TO` → `app.mail.reply-to` | 기본 미설정; 회신을 받을 실제 수신함이 필요할 때만 지정 |

기존 IntelliJ 환경변수 여섯 개를 그대로 사용한다. 호스트/포트/인증 사용자명/비밀번호를 특정 업체 값으로 고정하지 않는다. `RESEND_API_KEY`에 대한 설정 의존성이나 대체 비밀번호 경로는 없다. 선택한 SMTP 업체의 API key를 사용하는 경우에도 변수 이름은 `MAIL_PASSWORD`다. 공식 발신 주소 사용 권한/도메인 검증은 선택한 업체의 정책에 따라 확인한다.

추가 선택 설정은 `MAIL_CONNECTION_TIMEOUT_MS`, `MAIL_READ_TIMEOUT_MS`, `MAIL_WRITE_TIMEOUT_MS`(각각 기본 5000ms), `MAIL_REPLY_TO`, `FEEDBACK_MAIL_TO`다. 운영 인증 링크는 `EMAIL_VERIFICATION_URL`로 지정하고, 인증 메일 활성화는 `EMAIL_VERIFICATION_MAIL_ENABLED`(기본 true)로 관리한다. 기본 연결은 인증/587 STARTTLS를 사용한다. SMTPS 등 다른 전송 방식이 필요한 경우 Spring의 `spring.mail.properties.mail.smtp.*` 설정으로 전송 보안만 해당 SMTP 서버에 맞춘다.

## 발송 경로 확인

- 문의/건의: `FeedbackMailListener` → `FeedbackMailService` → 공통 `JavaMailSender`. 알림 수신자는 기존 개인 Gmail이다. 접수 저장과 SMTP 실패 처리 정책을 유지한다.
- 회원가입 및 Discord 계정의 이메일 로그인 수단 추가: `CredentialTransactions` → `EmailVerificationService.issueInitial` → 커밋 후 `EmailVerificationMailListener` → `EmailVerificationMailService` → 같은 `JavaMailSender`.
- 인증 재발송: `EmailVerificationService.resend` → 같은 인증 listener/service.
- 모든 구현된 경로의 From/표시 이름/Reply-To는 `ApplicationMailProperties`를 공유한다. 표시 이름은 UTF-8 메일 헤더 문법으로 인코딩해 한글과 쉼표도 지원한다. 인증 메일 수신자는 가입자의 이메일이며 문의 알림 수신처와 섞이지 않는다.
- 비밀번호 찾기/재설정 메일은 현재 구현되어 있지 않다. 해당 경로를 추가할 때도 같은 `JavaMailSender`와 `ApplicationMailProperties`를 사용한다.

## 중복 설정 확인

`application.properties`는 SMTP/From을 모두 기존 `MAIL_*` 변수에 연결한다. `application-local.properties`, `application-prod.properties`에는 별도의 SMTP/From override가 없다. 인증 서비스는 `EMAIL_VERIFICATION_FROM`이나 SMTP 사용자명을 발신 주소로 사용하지 않는다.

같은 항목의 Spring 표준 override인 `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `APP_MAIL_FROM`, `APP_MAIL_FROM_NAME` 또는 JVM/실행 인수가 함께 설정되면 Spring 우선순위에 따라 `MAIL_*` 매핑을 덮어쓸 수 있다. 실행 환경에서는 동일 항목의 중복 설정을 피한다. 이번 작업에서는 실제 IntelliJ 자격증명, 프로세스 환경 또는 운영 설정의 값을 읽지 않았다.

## Reply-To 검토

`noreply@guild-up.com`은 수신용 메일함이 아니다. 인증 메일은 링크 확인 방식이며 회신을 요구하지 않으므로 Reply-To 기본값은 비워 둔다. 문의 답변은 기존 서비스의 답변 기능을 사용한다. 사용자에게 이메일 회신이 필요한 운영 정책을 적용할 경우 운영자가 실제 모니터링하는 수신함을 `MAIL_REPLY_TO`로 명시한다. 지정하면 두 메일 경로에 Reply-To 헤더가 적용된다. 이 설정은 문의 알림의 To나 공식 From을 바꾸지 않으며, 문의 작성자의 주소를 자동으로 지정하지 않는다. 개인 Gmail 수신처를 사용자에게 노출되는 Reply-To로 자동 재사용하지 않는다.

## 운영 전환 순서

1. 선택한 SMTP 업체에서 `noreply@guild-up.com` 발신 권한/도메인 검증을 완료하고 SMTP 접속 정보를 준비한다. 기존 Gmail 수신함과 수신 설정은 유지한다.
2. 기존 여섯 `MAIL_*` 변수와 실제 프론트엔드 HTTPS 주소의 `EMAIL_VERIFICATION_URL`을 확인한다. 공식 From은 `MAIL_FROM=noreply@guild-up.com`, 표시 이름은 `MAIL_FROM_NAME=GuildUp`을 사용한다. 문의 알림 수신처는 환경변수를 생략해 기존 주소를 유지하거나 `FEEDBACK_MAIL_TO=heun0180@gmail.com`으로 명시한다. 회신이 필요할 경우만 `MAIL_REPLY_TO`를 설정한다.
3. 동일 항목의 중복 SMTP/From override를 정리하고 새 앱을 운영자의 기존 절차로 배포한다. 방화벽에서 선택한 SMTP host/port 연결을 허용하고 TLS/5초 타임아웃 설정을 확인한다. 이번 작업에서는 운영 서버/DB/환경변수를 변경하지 않는다.
4. 운영자가 테스트 계정으로 문의/건의를 접수해 **기존 개인 Gmail**에 알림이 도착하는지 확인한다. 이어 회원가입, 이메일 로그인 수단 추가, 인증 재발송/인증 완료를 확인한다. 실제 메일의 From이 `noreply@guild-up.com`인지, 인증 링크/5분 만료/스팸함/설정한 Reply-To가 정상인지 확인한다.
5. 정상 전환과 두 기능의 실제 수신을 확인한 뒤 **운영자가 기존 개인 Gmail 앱 비밀번호를 직접 폐기한다.** 앱 비밀번호 폐기는 SMTP 접근 권한 제거이며 Gmail 계정/수신함 삭제가 아니다. 이 작업에서 비밀번호를 폐기하지 않는다.

로컬 개발은 `SPRING_PROFILES_ACTIVE=local`에서 localhost 인증 링크를 사용할 수 있다. SMTP는 local/prod 모두 같은 `MAIL_*` 설정을 공유한다. 소스 변경을 실행 중인 로컬 앱에 적용하려면 앱을 재시작해야 한다.

## 자동 회귀 검증

```bash
./mvnw -Dtest=MailConfigurationTests,FeedbackMailServiceTests,FeedbackFlowTests,EmailVerificationSecurityTests,EmailVerificationFlowTests,AuthFlowTests test
```

설정 테스트는 실제 Spring 메일 자동 설정의 `MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD` 매핑과 인증/TLS를 확인한다. 업체별 키가 없거나 값이 달라도 비밀번호는 `MAIL_PASSWORD`만 사용한다. 발송 테스트는 SMTP를 mock하여 공통 From/표시 이름, 한글/쉼표 인코딩, 표시 이름 생략, 기존 문의 수신처, 가입자 수신처 분리, 명시적 수신처 override, 선택적 Reply-To 및 이전 인증 From 미사용을 함께 검증한다. 기존 흐름 테스트는 문의 접수/SMTP 실패와 회원가입/인증/재발송/만료/메일 실패의 회귀를 검증한다.

2026-10-09 환경변수 통일 후 96개 테스트가 통과했다 (실패 0, 오류 0, 건너뜀 0). 테스트 프로세스에는 가짜 SMTP 값만 주입하고 업체별 키 환경변수는 제거했다. H2 테스트 DB와 mock SMTP를 사용했으며 실제 API key 조회, 외부 SMTP 발송, 운영 서버/DB 변경 및 Gmail 앱 비밀번호 폐기는 수행하지 않았다.

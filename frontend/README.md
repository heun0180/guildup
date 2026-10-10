# GuildUp React frontend

기존 Spring Boot REST API와 HttpSession을 사용하는 Vite 기반 React 화면입니다. 기존 정적 HTML의 URL과 디자인을 유지해 OAuth 콜백과 화면 간 링크가 그대로 이어집니다.

## 실행

터미널 하나에서 Spring Boot를 실행합니다.

```bash
cd ..
./mvnw spring-boot:run
```

다른 터미널에서 React 개발 서버를 실행합니다.

```bash
cd frontend
npm install
npm run dev
```

브라우저에서는 `http://localhost:5173/login.html`로 접속합니다. `/api/**` 요청은 Vite가 `http://localhost:8080`으로 프록시하므로 브라우저 기준으로 같은 출처가 유지되고 기존 `JSESSIONID` 쿠키가 사용됩니다.

이메일 인증을 로컬에서 테스트할 때는 기존 Gmail SMTP 환경변수와 함께 백엔드의 `local` 프로필을 활성화합니다. IntelliJ의 기존 실행 설정에는 `SPRING_PROFILES_ACTIVE=local`을 추가하면 됩니다. CLI에서는 다음과 같이 실행합니다.

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

`local` 프로필의 인증 링크는 `http://localhost:5173/email-verification.html`이며 새 링크는 300초(5분) 동안 유효합니다. `MAIL_USERNAME`/`MAIL_PASSWORD`는 기존 실행 환경에서 제공해야 합니다. 설정 변경 전 실패한 가입 정보는 유지되므로 로그인한 계정 화면에서 재발송을 요청합니다. 기존 발송 제한이 남아 있다면 화면의 대기 시간이 지난 뒤 요청합니다.

로컬 메일의 localhost 링크는 이 개발 서버가 실행 중인 컴퓨터에서 열어야 합니다. 운영에는 `local` 프로필을 사용하지 않고 실제 HTTPS 인증 주소를 `EMAIL_VERIFICATION_URL`로 설정합니다. 로컬 HTTP 예외는 loopback 주소와 명시적 `local` 프로필에서만 허용하며 `prod` 프로필에서는 거부합니다.

## 프로덕션 빌드

```bash
npm ci
npm run build
```

배포 대상은 `dist/` 전체다. 빌드 후 스크립트가 React Router에 등록된 모든 `.html` 경로의 진입 파일과 해시 asset을 검증한다. 백엔드 JAR만 재배포하면 React 변경은 운영 화면에 반영되지 않는다. 정적 파일 서버에는 반드시 같은 빌드에서 생성된 `dist/*.html`과 `dist/assets/*`를 함께 교체해야 한다.

운영 배포 직전에는 다음 명령으로 산출물 누락을 다시 검사할 수 있다.

```bash
npm run verify:build
```

## Discord OAuth 개발 설정

React 개발 서버에서 로그인과 Community 연결 콜백까지 받으려면 실행 환경과 Discord Developer Portal에 아래 두 Redirect URI를 등록합니다.

```text
http://localhost:5173/api/auth/discord/callback
http://localhost:5173/api/discord/oauth/callback
```

Community Discord OAuth에 사용하는 Spring Boot 환경 변수도 다음 값으로 실행합니다.

```bash
DISCORD_REDIRECT_URI=http://localhost:5173/api/discord/oauth/callback ./mvnw spring-boot:run
```

로그인 콜백은 요청 호스트를 기준으로 생성되므로 React의 `/api/auth/discord/authorize`를 통해 시작하면 `localhost:5173` 콜백을 사용합니다. Vite 프록시의 `changeOrigin: false` 설정이 요청 호스트를 보존합니다.

## 화면 URL

- `/login.html`: Discord 로그인
- `/communities.html`: 내 Community 목록과 생성
- `/community-dashboard.html?communityId={id}`: Community 대시보드
- `/discord-connect.html?communityId={id}`: Discord 서버 연결
- `/members.html?communityId={id}`: 수동 클랜원 관리
- `/members.html?communityId={id}&discordRoles=true`: Discord 역할별 멤버

프로덕션 배포 방식은 아직 추가하지 않았습니다. 개발 단계에서는 Spring Boot와 Vite를 각각 8080, 5173 포트로 실행합니다.

## 비밀번호 찾기·재설정

- `/forgot-password.html`: 이메일 입력 및 계정 종류와 무관한 접수 안내
- `/password-reset.html`: 메일 링크 검증, 새 비밀번호/확인, 표시·숨기기, 성공·만료 안내
- 로그인·회원가입의 OAuth 버튼은 `디스코드 로그인`이다. OAuth 경로와 스타일은 유지한다.

`password-reset.html`은 전용 빌드 진입점이다. URL fragment를 bundle 실행 전에 제거하므로 일반 `index.html`로 대체하지 않는다. 운영 정적 호스트는 두 페이지에 `Cache-Control: no-store`, 재설정 페이지에 `Referrer-Policy: no-referrer`를 제공해야 한다. Spring에서 제공할 때는 필터가 적용되며, 별도 정적 호스트 설정은 운영자가 확인한다.

재설정 메일은 기본/prod 설정에서는 비활성화이며, `local` 프로필은 직접 요청하는 메일 테스트를 위해 기존 SMTP 발송을 활성화한다. 변경한 설정을 적용하려면 로컬 백엔드를 다시 실행한다. `PASSWORD_RESET_MAIL_ENABLED=false`가 실행 환경에 있으면 이를 제거하거나 수동 테스트 시 `true`로 지정한다. 로컬 메일 발송을 차단하려면 `false`로 설정한다. 자동화 테스트는 Surefire에서 발송을 차단하고 Mock SMTP와 로컬 Mock API만 사용한다. 배포 설정과 SQL 순서는 [3단계 보고서](../PASSWORD_RESET_READINESS.md)를 참고한다.

```bash
npm test
npm run build
node scripts/verify-password-reset-browser.mjs
```

마지막 명령은 격리된 Chrome에서 360px·390px·1280px 화면을 확인하고 스크린샷을 임시 폴더에 저장한다. 운영 백엔드·SMTP·Discord를 호출하지 않는다. macOS Google Chrome 기본 경로 이외에는 `CHROME_PATH`를 지정한다. 스크린샷 저장 위치는 `RESET_BROWSER_ARTIFACTS`로 변경할 수 있다.

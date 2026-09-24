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

# GuildUp 로그인 및 계정

GuildUp의 사용자 식별자는 항상 `users.id`다. 이메일/비밀번호 인증정보는 `user_credentials`, 개인 Discord 인증정보는 기존 `user_external_accounts`에 저장한다. 두 수단은 같은 사용자를 참조하고 같은 `JSESSIONID` / `LOGIN_USER_ID` 인증을 사용한다. `users`, 기존 외부 계정, 커뮤니티 권한 및 기록을 재생성하거나 이름/이메일로 계정을 자동 병합하지 않는다.

## 화면

- `/login.html`: 이메일 로그인, Discord로 계속하기, 회원가입 링크
- `/signup.html`: 이메일, 비밀번호, 비밀번호 확인, 닉네임; 기존 Discord 사용자에게 기존 계정에서 이메일 로그인을 추가하도록 안내
- `/account.html`: 개인 Discord 연결 상태, 이메일 로그인 등록 상태, 이메일 로그인 추가, Discord 연결
- 사용자 메뉴에 **로그인 및 계정** 추가
- 비밀번호 찾기/변경, 이메일 소유 인증, 로그인 수단 해제, 계정 병합은 이번 구현에 포함하지 않는다. 미구현 비밀번호 찾기 링크는 표시하지 않는다.

React 화면은 `frontend/src/`에 구현했다. 기존 Spring 정적 HTML은 이전 UI이며 새 기능의 운영 화면 제공에는 **backend JAR와 `frontend/dist/` 전체를 함께 배포**해야 한다. `npm run build`가 새 `.html` 경로까지 생성하고 검증한다.

## API

| 메서드/경로 | 인증/CSRF | 동작 |
| --- | --- | --- |
| `GET /api/auth/csrf` | 공개 | 익명 또는 로그인된 HttpSession에 토큰 발급, `Cache-Control: no-store` |
| `POST /api/auth/signup` | 익명 + CSRF | 새 User와 인증정보를 원자적으로 저장하고 즉시 로그인, 201 |
| `POST /api/auth/login` | 익명 + CSRF | 기존 이메일 인증정보 확인 후 같은 User로 로그인, 200 |
| `GET /api/auth/me` | 로그인 | 기존 응답 형태 `id`, `nickname`, `systemAdmin` 유지 |
| `GET /api/auth/account` | 로그인 | 이메일/인증 여부, Discord 연결 여부, 클랜원 연결 충돌 조회; hash 반환 없음 |
| `POST /api/auth/credentials` | 로그인 + CSRF | 현재 User에 이메일 로그인 추가, 새 User 생성 없음, 201 |
| `GET /api/auth/discord/authorize` | 공개, OAuth state | 기존 개인 Discord 로그인 시작; 이미 로그인 중이면 커뮤니티로 이동 |
| `POST /api/auth/discord/link` | 로그인 + CSRF | 현재 User에 묶인 개인 Discord 연결 시작, `authorizationUrl` 반환 |
| `GET /api/auth/discord/callback` | 세션 OAuth state | 저장된 `LOGIN` 또는 `LINK_ACCOUNT` 목적에 따라 처리 |
| `POST /api/auth/logout` | 로그인 시 CSRF | HttpSession 무효화, 204 |

회원가입 JSON은 `email`, `password`, `passwordConfirmation`, `nickname`; 이메일 로그인은 `email`, `password`; 이메일 로그인 추가는 `email`, `password`, `passwordConfirmation`이다. 로그인된 상태에서 회원가입/일반 로그인 요청으로 다른 User를 만들거나 전환하는 동작은 409로 거부한다.

이메일은 양끝 공백 제거 후 `Locale.ROOT` 소문자로 정규화한다. 내부 공백/잘못된 형식은 거부하고 최대 254자, local part 최대 64자로 제한한다. Gmail의 점/플러스 주소 등 제공자별 별칭은 자동 합치지 않는다. 비밀번호는 변경/trim하지 않고 영문과 숫자를 포함한 8자 이상, UTF-8 기준 72바이트 이하를 요구한다. BCrypt의 입력 제한을 초과하면 잘라 저장하지 않고 400을 반환한다. 닉네임은 양끝 공백 제거 후 1~50자다.

비밀번호는 Spring Security Crypto의 `PasswordEncoderFactories.createDelegatingPasswordEncoder()`로 저장한다. 현재 저장 형식은 `{bcrypt}` hash이며 salt는 encoder가 생성한다. Spring Security 필터 체인을 새로 도입하지 않아 기존 세션/커뮤니티 권한 체계는 유지된다. 로그인 실패는 존재하지 않는 이메일과 잘못된 비밀번호에 동일한 401 메시지를 반환하며 dummy hash 검증을 수행한다. 인증 요청 DTO의 `toString()`은 원문을 표시하지 않는다.

`email_verified`는 false로 생성한다. 인증되지 않은 이메일로 현재 로그인은 허용하지만 이메일 소유를 증명했다고 표시하지 않는다. 현재 SMTP 사용처는 문의 발송이고 이메일 소유 인증 토큰/발송/확인 흐름은 별도 구현이 필요하다.

## 세션과 CSRF

`AuthSessionService.authenticate()`는 Discord 로그인, 이메일 로그인, 회원가입 후 로그인, 인증수단 추가 성공에 공통 적용된다:

1. 기존 HttpSession 문맥을 보존하면서 `request.changeSessionId()` 호출
2. `LOGIN_USER_ID = users.id` 저장
3. 미완료 개인 Discord OAuth 시도 제거
4. 세션 CSRF 토큰 회전

HttpOnly, SameSite=Lax, Path=/, cookie-only 세션 추적과 prod의 Secure 쿠키 설정은 유지한다. 로그아웃은 세션 전체를 무효화한다.

프론트 `api()`의 기존 CSRF 선조회를 그대로 사용한다. 공개 `/api/auth/csrf`는 익명 세션을 만들 수 있지만 인증 권한을 부여하지 않는다. 회원가입/로그인 변경 요청도 세션 토큰을 반드시 검증한다. 실제 매핑된 HandlerMethod를 기준으로 보호하여 세미콜론/인코딩 경로로 우회하지 못하도록 한다. 기존 로그인 API의 POST/PUT/PATCH/DELETE 토큰 검증과 커뮤니티 접근 검증은 그대로 유지한다.

개인 Discord state는 CSRF 토큰과 별개로 32바이트 난수이며 세션에 목적, callback URI, 연결할 User ID, 생성 시각을 저장한다. 10분 만료와 단일 사용, 상수시간 state 비교, 시작 시점/콜백 시점/토큰 교환 후 현재 User 일치 검사를 적용한다. LINK_ACCOUNT는 로그인용 `findOrCreateUser()`를 호출하지 않는다. 다른 User가 소유한 Discord ID는 `DISCORD_ACCOUNT_CONFLICT`로 거부하며 병합하지 않는다. 이미 같은 User에 연결한 재시도는 성공으로 처리한다. 기존 Discord 서버 연결 OAuth는 별도의 기능으로 유지한다.

## 커뮤니티/클랜원 보존

로그인과 인증수단 연결은 `community_users`, `community_members` 또는 게임 기록을 수정하지 않는다. 기존 Discord ID 기반 클랜원 조회도 지원한다.

Discord 없는 사용자가 초대로 가입하거나 커뮤니티 서버 OAuth를 통해 가입하면 기존 `CommunityNativeMembershipService`로 내부 클랜원을 준비한다. `community_member_id`가 이미 존재하면 교체하지 않는다. 커뮤니티 발견은 개인 Discord 미연결 시 빈 목록을 반환한다. 개인 Discord 닉네임이 필요한 규칙 조회/미리보기/저장은 외부 Discord 호출 전에 `DISCORD_NOT_LINKED`(409)와 연결 안내를 반환한다. 프론트는 정상 미연결 상태와 계정 설정 링크를 표시한다.

개인 Discord를 나중에 연결하고 커뮤니티 역할 동기화를 실행할 때:

- 해당 Discord ID로 기존 클랜원이 없고 현재 User의 내부 클랜원이 있으면 **같은 클랜원 ID에 Discord 계정만 연결**한다. 출석/PUBG/랭킹/게임 데이터의 FK는 유지된다.
- 해당 Discord ID로 이미 다른 클랜원이 있으면 기존 내부 클랜원 연결과 두 기록 모두 유지한다. `/api/auth/account`의 `memberLinkConflicts`로 안내하며 자동 병합/재연결/삭제하지 않는다. 이 경우 운영자 확인 또는 향후 별도 병합 기능이 필요하다.

## DB 적용

프로젝트는 Hibernate `ddl-auto=update`와 `src/main/resources/db/manual/`의 수동 PostgreSQL SQL을 사용한다. 이번 작업은 운영 DB에 접속하거나 SQL을 실행하지 않았다.

배포 전에 `src/main/resources/db/manual/add_user_credentials.sql`을 적용한다. 새 인증정보 테이블, 사용자 FK, 사용자당 1개 인증정보 UNIQUE, 이메일 UNIQUE, 정규화 CHECK와 표현식 UNIQUE 인덱스를 준비한다. 기존 외부 계정의 `(provider, external_user_id)` 및 `(user_id, provider)` UNIQUE 계약도 보장한다. 기존 중복이 있다면 SQL은 실패하고 자동 정리하지 않는다. 기존 사용자에 인증정보를 일괄 생성하지 않는다.

기존 `community_users.community_member_id` 및 non-PUBG 외부 계정 UNIQUE 등 이전 프로젝트 migration이 적용된 DB를 전제로 한다. 이전 migration 미적용 운영 DB는 해당 기존 배포 문서에 따라 먼저 확인해야 한다. 이번 SQL은 커뮤니티 데이터 backfill을 수행하지 않는다.

## 검증

- `./mvnw test`: 기존 전체 회귀 테스트 + `AuthFlowTests`(Entity/실제 트랜잭션/MockMvc), 기존 Discord 단위 테스트, 실제 servlet cookie 테스트
- 실제 cookie 테스트는 이메일 회원가입/로그인과 Discord 로그인 양쪽에서 구 쿠키 거부, ID 회전, CSRF 회전, 로그아웃 후 인증 불가와 prod Secure 쿠키를 검증한다.
- `AuthFlowTests`는 동시 이메일 가입과 동시 Discord 최초 로그인, 로그인 수단 추가/연결, 계정 충돌, state 만료/재사용/사용자 변경, 권한, Discord 미연결 화면, 클랜원 출석/PUBG 기록 보존을 검증한다.
- `AuthPostgresTests`: 폐기 가능한 격리 PostgreSQL에서 위 흐름과 수동 SQL 반복 적용/기존 데이터 보존 검증. `TEST_POSTGRES_URL` 환경변수가 있을 때만 실행되며 create-drop을 사용하므로 운영 DB를 지정하지 않는다.
- `cd frontend && npm test`: 폼 동작/오류/CSRF/계정 연결/프로필 메뉴 회귀
- `cd frontend && npm run build`: 새 로그인/회원가입/계정 경로를 포함한 전체 배포 산출물 검증

실제 Discord 서비스의 동의/토큰 교환은 테스트에서 mock으로 대체했다. 메일 소유 인증 및 비밀번호 재설정, 로그인 시도 rate limit, 명시적 계정/클랜원 병합은 후속 범위다.

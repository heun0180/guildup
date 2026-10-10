# 관리자 회원 관리

## 구현 전 구조 분석

운영 DB에 연결하지 않고 현재 JPA 매핑, 저장소, 인증 컨트롤러/서비스, PostgreSQL 수동 SQL, 관리자 React 화면을 확인했다. 실제 로컬 테스트 DB의 스키마와 조회 동작은 통합 테스트로 검증한다.

- `users`: `id`, `nickname`, `birth_date`, `system_role`, `status`, `withdrawn_at`, `authentication_version`, `created_at`, `updated_at`. `UserStatus`는 `ACTIVE / WITHDRAWN`, 시스템 권한은 `USER / SYSTEM_ADMIN`이다. `updated_at`은 프로필 등의 변경 시각이며 접속 기록으로 사용할 수 없다.
- `user_credentials`: 사용자당 1개(`user_id` UNIQUE), 정규화된 이메일 UNIQUE, `password_hash`, `email_verified`, `verification_required`, `created_at`, `updated_at`. 이메일은 users에 없고 이 테이블의 이메일이 로그인 ID다. 생성 시각은 이메일 인증수단의 연결일로 사용할 수 있다.
- `user_external_accounts`: 사용자+provider UNIQUE 및 provider+external_user_id UNIQUE. `external_user_id`, `external_username`, `external_display_name`, `external_avatar_url`이 있다. 개인 Discord 연결에는 토큰이나 연결일 컬럼이 없다. PUBG 계정은 개인 로그인 수단이 아니라 커뮤니티의 게임 계정이다.
- 이메일 가입/로그인: `AuthController → CredentialAuthService / ProtectedEmailLoginService → CredentialTransactions`. BCrypt와 로그인 보호를 유지한다. 가입도 즉시 세션 인증을 수행한다.
- Discord 로그인/연결: `DiscordLoginController`가 일회용 OAuth state와 `LOGIN / LINK_ACCOUNT / WITHDRAWAL` 목적을 검증한다. `DiscordAccountTransactions`는 기존 users 행을 잠그고 인증수단을 연결하며 이메일/닉네임으로 자동 병합하지 않는다. 연결은 재로그인이 아니므로 마지막 로그인 시각을 변경하면 안 된다.
- 공통 인증: `AuthSessionService`의 세션 ID/CSRF 회전, `LOGIN_USER_ID`, 인증 버전 검사. `ActiveUserInterceptor`가 `/api/**`에서 잔존/탈퇴/비밀번호 변경 세션을 차단한다. 새 보안 필터 체인은 도입하지 않는다.
- `community_users`: `(community_id, user_id)` UNIQUE, `role`, nullable `joined_at`, `ended_at`, 선택적 `community_member_id`. 종료된 행은 과거 참가/작성자 FK 보존을 위해 남는다. 활성 접근은 `ended_at IS NULL` 및 사용자 ACTIVE 조건이다.
- 역할은 커뮤니티마다 `OWNER / ADMIN / MEMBER`이며 플랫폼 관리자와 독립적이다. `community_member_role_settings`의 Discord 역할은 클랜원 동기화 조건이지 플랫폼/커뮤니티 관리 권한이 아니다.
- 커뮤니티 닉네임은 내부 `community_member_id` 연결을 우선하고, 미연결 시 기존 `CurrentCommunityMemberService`와 같은 커뮤니티 내 Discord ID 매칭을 따른다. 클랜원 상태 `ACTIVE / LEFT`와 GuildUp 멤버십 종료 상태를 구분해야 한다.
- 탈퇴: `AccountWithdrawalService`가 이메일/외부 계정을 삭제하고 닉네임을 익명화하며 커뮤니티 관계를 종료한다. 과거 로그인 방식·삭제된 이메일을 복원하거나 보존하지 않는다.
- 관리자: `/developer/**`, `/api/developer/**`의 `DeveloperAccessInterceptor → DeveloperAccessService`가 활성 `SYSTEM_ADMIN`만 허용한다. `DeveloperLayout`의 UI 검사와 별도로 서버가 검증한다. 기존 접근 거부 모니터링(`add_access_denied_monitoring.sql`)을 그대로 이용한다.
- 현황: `DeveloperController / DeveloperQueryService.dashboard`, `DeveloperResponses.Dashboard`, `frontend/src/developer/DeveloperPages.jsx`. 기존 최근 사용자 쿼리는 Discord ID와 닉네임, 가입일만 반환하고 8명으로 제한한다. 기존 화면 디자인은 `pages.css`의 developer 공통 스타일을 사용한다.

## 구현 계약

회원 조회는 users 기준으로 페이지를 먼저 제한하고, 해당 페이지의 활성 커뮤니티 수만 일괄 집계한다. 인증수단 JOIN은 기존 UNIQUE 제약을 사용하여 회원 중복을 만들지 않는다. 상세는 모든 community_users 관계와 현재 역할/종료 상태를 반환한다. 과거 역할은 기존 코드가 종료 시 MEMBER로 바꾸므로 만들어내지 않는다.

새 시각은 `users.last_login_at`, `users.last_active_at`, `user_external_accounts.linked_at`만 추가하며 모두 nullable이다. 기존 시각을 추측하여 채우지 않는다. 정상 로그인/가입 성공 후만 로그인 시각을 기록하고, 인증된 API 요청의 활동 갱신은 세션당 10분에 한 번 시도하며 DB 조건으로 다른 세션/서버의 중복 갱신을 차단한다. 인증수단 추가는 로그인 시각을 변경하지 않는다.

통계는 users에서 한 번 집계하여 인증/커뮤니티 연결 수에 따른 중복을 피한다. 전체/신규 가입은 보존된 탈퇴 행을 포함하고 정상/최근 활동은 ACTIVE만 집계한다. 오늘과 최근 7일 신규 가입은 한국 시간 달력(오늘 포함 7일), 최근 30일 활동은 현재 시각에서 30일 전 기준이다.

## 구현한 기능

- 서비스 현황: 최근 회원 10명의 ID, 닉네임, 현재 연결된 로그인 방식, 이메일/Discord 이름과 ID, 가입일, 마지막 로그인, 상태. 기존 응답의 `id / nickname / discordUserId / createdAt`은 보존했다. 전체 회원 보기 및 회원 상세 링크를 추가했다.
- `/developer/users`: 회원 통계 5개, ID 정확 일치/닉네임 및 이메일 부분 일치 검색, 로그인 방식 필터, 가입일/마지막 로그인 정렬, 서버 페이지네이션. 기본 20명, API 최대 100명. ID 동률 정렬을 추가하여 페이지 경계를 안정적으로 유지한다. 날짜가 없는 행은 정렬 방향과 무관하게 마지막에 둔다.
- `/developer/users/:userId`: 기본 정보, 이메일/Discord 각각의 로그인 계정, 모든 커뮤니티 관계, 커뮤니티별 역할/닉네임/가입일/종료일/상태, 기존 관리자 커뮤니티 상세 링크.
- 정상/탈퇴 및 이메일/Discord/복합 연결 배지, 빈 목록/없는 커뮤니티/기록 없는 시각/로딩/오류 및 재시도 화면. URL에 검색 조건을 보존하고 필터 변경 시 첫 페이지로 돌아간다. 잘못된 페이지 북마크는 마지막 유효 페이지로 이동한다.
- 기존 디자인을 재사용했고, 모바일에서는 회원 테이블을 항목별 카드로 표시한다. 상세 계정/커뮤니티 테이블은 내부 가로 스크롤을 지원한다.
- API는 기존 `DeveloperAccessInterceptor`의 활성 `SYSTEM_ADMIN` 검사로 보호된다. 일반 회원, 커뮤니티 OWNER/ADMIN, 탈퇴 관리자와 익명 요청을 차단한다. 응답은 조회 전용 DTO이며 비밀번호 해시, 인증 버전, 생년월일, 이메일 인증/재설정/OAuth 토큰을 포함하지 않는다. 회원 조회와 현황 응답에 `Cache-Control: no-store`를 적용했다.
- 활동 기록은 인증 검증 후 시도한다. 세션의 다음 갱신 시각을 검사한 후에만 트랜잭션을 열며, DB의 조건부 UPDATE로 사용자당 중복 기록을 제한한다. 최근 활동 시각은 최대 약 10분 지연될 수 있다. 기록 실패는 유형만 로그로 남겨 기존 인증에 영향을 주지 않는다.

## 추가/개선 API

모든 API는 `JSESSIONID`와 활성 플랫폼 `SYSTEM_ADMIN`이 필요하다.

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | `/api/developer/users` | 전체 회원 검색/필터/정렬/페이지 |
| GET | `/api/developer/users/statistics` | 전체/오늘 신규/7일 신규/30일 활동/정상 회원 통계 |
| GET | `/api/developer/users/{userId}` | 회원·로그인 계정·커뮤니티 관계 상세, 없으면 404 |
| GET | `/api/developer/dashboard` | 기존 API 개선: 최근 사용자 10명, 인증 방식·식별정보·로그인·상태 추가 |

목록 파라미터:

- `q`: 최대 254자, ID 정확 일치 또는 닉네임/이메일 대소문자 무시 부분 검색. `% / _ / !`는 리터럴로 이스케이프한다.
- `loginMethod`: `ALL`(기본), `EMAIL`(이메일 단독), `DISCORD`(Discord 단독), `EMAIL_DISCORD`(두 수단 연결), `NONE`(현재 연결 없음). 탈퇴 후 삭제된 로그인 수단은 재구성하지 않는다.
- `sort`: `createdAt`(기본) 또는 `lastLoginAt`; `direction`: `desc`(기본) 또는 `asc`.
- `page`: 0부터 시작; `size`: 1~100, 기본 20. 유효하지 않은 검색/필터/정렬/페이지 크기는 400.
- 응답: `content, page, size, totalElements, totalPages`. 회원 수와 커뮤니티 집계는 서로 독립적이다.

목록의 SQL은 회원 COUNT, LIMIT/OFFSET 회원 SELECT, 해당 페이지 ID들의 커뮤니티 수 GROUP BY로 구성한다. 상세는 회원 SELECT + 이메일 SELECT + Discord SELECT + 커뮤니티 일괄 SELECT로 구성한다. 회원 수나 커뮤니티 수만큼 별도 쿼리를 실행하지 않는다.

## DB 변경과 배포

`src/main/resources/db/manual/add_admin_user_management.sql`을 별도로 제공했다. 운영 DB에 직접 연결하거나 실행하지 않았다.

| 테이블 | 추가 컬럼 | 기존 데이터 |
| --- | --- | --- |
| users | `last_login_at TIMESTAMP WITH TIME ZONE NULL` | NULL 유지 |
| users | `last_active_at TIMESTAMP WITH TIME ZONE NULL` | NULL 유지 |
| user_external_accounts | `linked_at TIMESTAMP WITH TIME ZONE NULL` | NULL 유지 |

회원 가입일/ID, 마지막 로그인/ID, ACTIVE 회원 활동 시각, 커뮤니티 사용자/종료 시각 인덱스 4개를 추가했다. 기존 UNIQUE/FK/계정 상태 정책을 변경하지 않으며 UPDATE/DELETE/가짜 과거 일자 보정이 없다. 기존 Entity 수정으로 조건부 SQL의 최신 접속 기록을 덮어쓰지 않도록 users의 두 새 컬럼은 JPA 쓰기에서 제외했다. Discord 프로필 갱신도 최초 연결일을 변경하지 않는다.

운영 적용 순서:

1. 운영 담당자가 백업/기존 계정 마이그레이션 적용 상태를 확인한 후 새 SQL을 적용한다. 마지막 `CREATE INDEX CONCURRENTLY` 구문은 **트랜잭션 밖에서 실행**해야 한다. 파일 전체를 단일 트랜잭션으로 감싸지 않는다. 인덱스 작업이 중단됐다면 invalid index 여부를 확인한 후 재실행한다.
2. `./mvnw package`로 생성한 backend JAR와 `npm --prefix frontend run build`의 `frontend/dist/` 전체를 함께 배포한다. 기존 정적 HTML만 배포하면 새 React 화면이 제공되지 않는다.
3. 기존 SPA fallback 설정이 `/developer/users/:userId` 직접 접근/새로고침에도 `index.html`을 반환하는지 확인한다. 목록 `/developer/users/index.html`은 기존 빌드 스크립트가 자동 생성한다. `/api/**`는 기존 backend 프록시로 유지한다.
4. 기존 SYSTEM_ADMIN으로 현황→회원 목록→상세→커뮤니티 상세 이동, 검색/페이지 이동, 일반 회원의 403을 점검한다. 새로운 관리자 권한 부여는 필요 없다.
5. 배포 후 로그인/API 활동부터 기록이 쌓인다. 기존 회원의 과거 접속/Discord 연결일은 계속 “기록 없음”이며 초기 30일 활동 통계는 새로 기록된 활동만 집계한다.

인덱스로 기본 정렬과 페이지의 커뮤니티 집계를 지원한다. 닉네임/이메일 임의 부분 검색과 전체 COUNT는 대량 데이터에서 스캔 비용이 남으며, 아주 깊은 OFFSET 페이지도 비용이 증가한다. 현 단계에서 불필요한 PostgreSQL 확장/검색용 테이블은 추가하지 않았다.

## 검증

- 회원 조회 테스트: 이메일 단독/Discord 단독/복합 연결, 커뮤니티 0개/여러 개, 커뮤니티별 OWNER/ADMIN/MEMBER, 내부 닉네임 및 기존 Discord 매칭, 종료 관계/탈퇴/기록 없는 시각, 민감 필드 제외, 익명/일반/커뮤니티 운영진/탈퇴 관리자 차단, KST 통계 경계, 505명의 페이지 경계/정렬/쿼리 수를 검증했다.
- 인증/활동 테스트: 이메일 가입·성공 로그인·실패 로그인, Discord 로그인, 양방향 이메일/Discord 인증수단 추가에서 로그인 시각을 불필요하게 바꾸지 않는지, 인증된 `/api/auth/me`에서 활동 시각 갱신, 반복 100회 요청의 갱신 제한, 다른 세션/시간 역행/탈퇴/인증 버전 불일치/기록 DB 오류를 검증했다.
- 로컬 PostgreSQL 16 테스트 DB를 새로 생성하여 운영 DB와 분리했다. 마이그레이션을 두 번 실행하고 기존 사용자/이메일/해시/Discord ID/가입일이 그대로이며 과거 접속·연결 시각은 NULL인지, 인덱스 4개가 존재하는지 검증했다.
- 기존 전체 백엔드 회귀 테스트를 실행했다. 기존 비동기 활동 테스트의 클라이언트 요청 ID 기대값은 현재 보안 필터의 서버 발급 UUID 정책과 충돌하여, 응답 `X-Request-ID`가 worker의 MDC에 동일하게 전달되는지 확인하도록 수정했다. 보안 필터의 동작은 변경하지 않았다.
- 프론트엔드 전체 테스트 252개 통과. 빌드 성공 및 운영 진입 경로 47개 검증. 기존 번들 크기 경고(500KB 초과)는 남아 있다.
- 로컬 샘플 API/운영 빌드로 브라우저에서 목록·상세를 확인했다. 1440px 데스크톱의 표/통계/필터, 390px 모바일의 회원 카드와 상세 페이지 가로 넘침을 점검했다. 실제 회원 데이터는 사용하지 않았다.

최종 실행 결과:

| 검증 | 결과 |
| --- | --- |
| 기존/신규 백엔드 전체 (`env -u TEST_POSTGRES_URL -u SPRING_DATASOURCE_URL ./mvnw -q test`) | 1,266개 중 962개 통과, 304개 환경 조건으로 제외, 실패/오류 0 |
| 회원 관리 및 인증/활동 집중 재검증 | H2 12개 + PostgreSQL 8개 통과, 실패/오류 0 |
| 프론트엔드 전체 (`npm --prefix frontend test`) | 252개 통과 |
| 프론트엔드 운영 빌드 | 성공, 진입 경로 47개 검증 |
| 백엔드 패키징 (`./mvnw -q -DskipTests package`) | 테스트 검증 후 JAR 생성 |

환경 조건으로 제외된 테스트에는 별도 PostgreSQL/운영 덤프 baseline 등이 포함된다. 이번 기능의 PostgreSQL 8개는 새로 만든 폐기 가능한 로컬 DB에서 별도 실행했다. 테스트 종료 후 해당 PostgreSQL 서버와 샘플 UI 서버를 중지했다. 테스트 결과 로그는 `target/admin-users-all-tests.log`, `target/admin-users-postgres-tests.log`, `target/admin-users-all-frontend.log`에 있다.

## 변경 파일 전체

- `frontend/src/App.jsx`
- `frontend/src/developer/DeveloperLayout.jsx`
- `frontend/src/developer/DeveloperPages.jsx`
- `frontend/src/styles/pages.css`
- `src/main/java/com/guildup/developer/controller/DeveloperController.java`
- `src/main/java/com/guildup/developer/dto/DeveloperResponses.java`
- `src/main/java/com/guildup/developer/service/DeveloperQueryService.java`
- `src/main/java/com/guildup/user/auth/config/ActiveUserInterceptor.java`
- `src/main/java/com/guildup/user/auth/controller/AuthController.java`
- `src/main/java/com/guildup/user/auth/controller/DiscordLoginController.java`
- `src/main/java/com/guildup/user/auth/service/AuthSessionService.java`
- `src/main/java/com/guildup/user/domain/User.java`
- `src/main/java/com/guildup/user/domain/UserExternalAccount.java`
- `src/test/java/com/guildup/community/CommunityMemberActivitySyncFlowTests.java`
- `ADMIN_USER_MANAGEMENT.md`
- `frontend/src/developer/DeveloperUsersPage.jsx`
- `frontend/src/developer/userView.jsx`
- `frontend/test/developerUsers.test.js`
- `src/main/java/com/guildup/developer/controller/DeveloperUserController.java`
- `src/main/java/com/guildup/developer/dto/DeveloperUserResponses.java`
- `src/main/java/com/guildup/developer/service/DeveloperUserQueryService.java`
- `src/main/java/com/guildup/user/auth/service/UserActivityService.java`
- `src/main/resources/db/manual/add_admin_user_management.sql`
- `src/test/java/com/guildup/developer/AdminUserMigrationPostgresTests.java`
- `src/test/java/com/guildup/developer/DeveloperUserManagementPostgresTests.java`
- `src/test/java/com/guildup/developer/DeveloperUserManagementTests.java`
- `src/test/java/com/guildup/developer/UserLoginActivityFlowTests.java`
- `src/test/java/com/guildup/developer/UserLoginActivityPostgresTests.java`
- `src/test/java/com/guildup/user/auth/service/UserActivityServiceTests.java`

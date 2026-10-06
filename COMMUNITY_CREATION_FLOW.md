# 커뮤니티 선택·생성 플로우 개선 보고서

2026-10-06

## 수정 전 조사

| 조사 항목 | 기존 구현 및 실제 동작 |
|---|---|
| 프론트 생성 진입점 | `frontend/src/pages/CommunitiesPage.jsx`의 목록 하단 생성 폼. 이름·PUBG 플랫폼 입력 후 곧바로 생성 API를 호출하고 `discord-connect.html?communityId=...`로 이동. Spring 레거시 `static/communities.html`에도 직접 생성 코드가 존재. 현재 운영 프론트는 React/Vite 산출물. |
| Community 생성 API | `POST /api/communities`, `CommunityController` → `CommunityService.createCommunity`. 이름·게임 검증 후 생성. 생성자의 userId는 로그인 세션에서 가져옴. |
| Discord OAuth 시작 | `GET /api/communities/{communityId}/discord/oauth/authorize`. 관리 권한 및 기존 Community 존재 확인, 서버 메모리에 10분짜리 state를 만들고 HTTP 세션에도 바인딩. |
| OAuth 완료 | `GET /api/discord/oauth/callback`. 세션 state 및 관리 권한 확인, Discord 코드 교환과 프로필/서버 조회. 관리 가능 서버만 5분짜리 결과 저장소에 보관. Community INSERT는 수행하지 않음. |
| 봇 설치 확인 | `DiscordBotInstallService`: OAuth 결과에 포함된 Guild ID 검증, Guild ID가 고정된 기존 봇 초대 URL 사용. JDA 캐시에서 해당 Guild가 보이는지 기본 5회/500ms 간격으로 확인. |
| Community INSERT 시점 | 생성 API의 `communityRepository.save(new Community(...))`. IDENTITY ID 발급으로 실제 INSERT가 발생. 따라서 이전 UI에서는 OAuth 시작 전에 이미 영구 생성 트랜잭션이 완료됨. |
| OWNER 생성 시점 | 같은 생성 트랜잭션에서 게임/활동 규칙 저장 후 `memberships.save(new CommunityUser(..., OWNER))`. |
| Discord 연결 INSERT 시점 | 봇이 이미 있으면 설치 authorize 중 즉시 `connectionService.connect`; 없으면 설치 confirm 후 `saveAndFlush`. Community와 별도 요청/트랜잭션. |
| 실패 cleanup | OAuth 취소·실패·페이지 이탈에 대한 일반 cleanup은 없음. 기존 커뮤니티 참여 API의 `discardSourceCommunity=true`에 한해, 생성 1시간 이내·OWNER 한 명·미연결·콘텐츠 없음 조건을 만족하는 source를 삭제하는 호환 경로가 있음. |
| 동일 Guild 중복 방지 | 서비스 사전 조회와 `discord_community_connections.discord_guild_id UNIQUE`, `community_id UNIQUE`. 동시 요청 보호는 이미 존재하나 JDBC 오류 메시지 안의 INSERT 컬럼 목록을 읽어 Guild 충돌을 Community 충돌로 오분류하는 문제가 있었음. |
| 기존 테스트 | `CommunityFlowTests`, `CommunityMembershipCleanupFlowTests`, `CommunityDomainTests`, OAuth/봇 설치 서비스·저장소 테스트, `DiscordLoginControllerTests`, 출석/랭킹·게시판·공지·이벤트·빙고·킬내기 테스트. 프론트는 node:test와 실제 React 앱을 마운트하는 jsdom 테스트. |

`users`, `user_external_accounts`, `communities`, `community_users`, `discord_community_connections`는 이미 분리되어 있다. 현재 Community 엔티티 및 실행 중인 Java/React 경로에는 `community.discord_guild_id` 의존성이 없다. 문서 백업의 이전 마이그레이션 SQL에는 레거시 필드가 등장한다. 이번 변경은 레거시 필드를 삭제하지 않는다.

추가 조사에서 `CurrentCommunityMemberService`가 Discord 계정 매칭만 지원해, Discord 없는 커뮤니티의 출석/게임 참여가 막히는 경로를 확인했다. 또한 기존 게임 닉네임 규칙 설정은 Discord 닉네임 분석을 전제로 한다. 이를 해결할 내부 멤버 연결과 본인 PUBG 계정 등록 경로를 함께 추가했다.

## 변경된 동작

```text
로그인 → 내 커뮤니티 목록(게임·GuildUp 가입자 수·입장하기)
      → 보조 영역: 초대 코드 참여 / 직접 운영하는 새 커뮤니티 생성

새 커뮤니티 만들기
  STEP 1: 이름·기존 PUBG 플랫폼 선택
  STEP 2: Discord OAuth → 관리 가능한 서버 선택 → 중복 확인 → 봇 설치 검증
          또는 "나중에 연결하기"
  STEP 3: 이름·게임·Discord 서버 또는 "연결하지 않음" 최종 확인
  "커뮤니티 만들기" → 한 트랜잭션으로 실제 생성
```

- 입력값·단계·임시 설치 토큰·생성 요청 키는 로그인 사용자별 브라우저 `sessionStorage`에 보관한다. OAuth를 다녀온 뒤 복원할 수 있다.
- 생성 전 OAuth는 기존 OAuth 서비스·메모리 저장소를 재사용한다. Community ID 없는 생성 컨텍스트를 세션 및 로그인 사용자에 바인딩한다.
- 생성 전 봇 설치는 검증된 임시 토큰만 만들거나 검증 표시한다. 기존 Discord 연결 페이지의 React 컴포넌트와 봇 설치 URL/JDA 확인 코드를 공유한다.
- 최종 생성 요청에서 Community, 게임, 기본 활동 규칙, OWNER, 내부 클랜원 연결, 선택한 Discord 연결을 함께 커밋한다. 실패하면 전체를 롤백한다.
- Guild 중복은 서버 선택·설치 준비·최종 생성에서 확인하며, DB UNIQUE 제약이 동시에 들어온 요청도 차단한다. 이미 연결된 커뮤니티 이름을 표시한다.
- 연속 클릭은 즉시 동작하는 제출 가드와 버튼 비활성화로 방지한다. 네트워크/서버 오류로 결과가 불확실하면 입력 변경을 잠시 막고 원래 요청 키·본문으로 재시도한다.
- 생성 요청 키는 사용자 ID와 함께 DB에 저장한다. 동일 사용자·키·본문은 기존 결과를 반환하고, 같은 키에 다른 본문은 409로 거부한다. 성공 결과 재전송은 임시 Discord 토큰이 사라진 뒤에도 처리한다.
- 새로 최종 확인해 만든 Community는 이전 `discardSourceCommunity` 호환 경로에서도 삭제되지 않는다. 설정에서 기존 커뮤니티 참여 시 source 삭제를 요청하지 않는다.
- Discord 없이도 초대 코드로 MEMBER 가입, 입장, 공지/이벤트/게시판, 출석/랭킹 및 기존 게임 기능을 이용할 수 있다. 게임 통계에는 기존대로 유효한 플랫폼별 게임 계정이 필요하다.
- 미연결 커뮤니티 대시보드의 "내 게임 계정"에서 본인 PUBG 닉네임을 등록한다. 기존 PUBG 조회 서비스로 확인하며, 플랫폼별 계정과 중복 계정 제약을 유지한다.
- 내부 멤버 연결은 Discord 계정 없이 동작한다. 이미 검증된 Discord 사용자 계정이 있으면 해당 계정을 같은 클랜원에 연결해 추후 서버 동기화가 기존 출석/게임 기록을 재사용하게 한다.
- Discord DM·음성 활동·역할 설정·Discord 닉네임 분석 화면은 미연결 안내와 연결 버튼을 표시한다. GuildUp 공통 기능과 게임 활동 화면은 별도로 유지한다.
- 생성 후 설정 또는 연동 기능에서 기존 Discord OAuth/설치/연결 API를 계속 이용할 수 있다. 지원 게임·OWNER/ADMIN/MEMBER 정책과 계정당 생성 개수는 유지한다.

## API 변경

| API | 변경 |
|---|---|
| `POST /api/communities` | `Idempotency-Key` 필수(16–64자의 영문/숫자/`_`/`-`, 프론트는 UUID 사용). 기존 `name`, `gameType` 유지. 선택적인 `discordInstallToken` 추가. 생성/재전송 응답은 기존 201 DTO 유지. |
| `GET /api/auth/me/communities`, `GET /api/communities` | 기존 응답에 GuildUp 멤버십 기준 `memberCount` 추가. |
| `GET /api/community-creation/discord/oauth/authorize` | Community 없이 생성용 OAuth 시작. |
| `GET /api/community-creation/discord/oauth/results/{resultId}` | 생성 세션에 바인딩된 임시 인증 결과 조회. |
| `POST /api/community-creation/discord/guild-selection/inspect` | 인증 결과 안의 Guild를 검증하고 이미 연결된 커뮤니티 확인. |
| `POST /api/community-creation/discord/bot-install/authorize` | 생성 전 설치 준비. 기존 설치 URL과 검증 로직 재사용. 영구 연결 없음. |
| `POST /api/community-creation/discord/bot-install/confirm` | 설치 검증만 완료. 영구 연결 없음. |
| `GET /api/discord/oauth/callback` | 기존 경로/redirect URI 유지. 생성용 결과는 생성 화면으로 복귀. 취소/API 실패도 화면으로 복귀. |
| `POST /api/communities/{communityId}/invitation` | OWNER/ADMIN의 초대 코드 조회/기존 Community의 최초 발급. |
| `POST /api/communities/join-by-invitation` | 초대 코드로 로그인 사용자의 MEMBER 가입. 반복 가입은 같은 멤버십 반환. |
| `GET`, `PUT /api/communities/{communityId}/games/{communityGameId}/pubg-account/me` | 본인 게임 계정 조회/닉네임 등록. 기존 게임·플랫폼 구조를 사용하고 다른 클랜원/커뮤니티의 계정은 수정할 수 없음. |

기존 Community ID 기반 Discord 시작·결과·봇 설치·설정 API는 계속 제공한다. 생성 전 임시 토큰은 사용자에 바인딩하며, 다른 사용자나 기존 연결 API에서 사용할 수 없다. 기존 CSRF 보호를 그대로 적용한다.

## DB 및 배포

새 테이블은 없다. 다음 nullable 컬럼과 관련 UNIQUE/FK만 추가한다.

| 테이블 | 컬럼/제약 | 목적 |
|---|---|---|
| `communities` | `creation_request_key VARCHAR(100)` UNIQUE | 성공한 생성 요청을 재시작/동시 요청에서도 식별 |
| `communities` | `creation_request_hash VARCHAR(64)` | 동일 키의 다른 본문 차단. 설치 토큰 원문은 영구 저장하지 않음 |
| `communities` | `invite_code VARCHAR(36)` UNIQUE | Discord와 무관한 초대 코드 가입 |
| `community_users` | `community_member_id BIGINT` nullable UNIQUE FK, ON DELETE SET NULL | GuildUp 사용자와 내부 게임/출석 클랜원의 선택적 연결 |
| `discord_community_connections` | 기존 Guild/Community UNIQUE 보장 | Community 1 → Discord 연결 0..1 유지 |

배포 전에 `src/main/resources/db/manual/add_community_creation_safety.sql`을 실행한다. 기존 미연결 Community의 사용자 멤버십에도 내부 클랜원 연결을 보충한다. 검증된 외부 사용자 ID로 기존 클랜원이 확인되면 재사용하고, 닉네임만으로 사용자를 추측해 연결하지 않는다. 기존 LEFT 상태도 유지한다. SQL 재실행은 이미 처리된 연결을 반복 생성하지 않는다.

백엔드와 `frontend/dist/` 전체를 함께 배포한다. `/community-create.html` 진입 파일은 Vite 빌드 후 기존 production-build 스크립트가 자동 생성한다. Spring 레거시 목록의 직접 생성 코드는 제거했으며 새 생성 화면으로 연결한다. 현재 프로젝트의 React 정적 파일 제공 계약을 유지해야 한다.

새 환경변수나 Discord redirect URI 변경은 없다. 기존 Discord OAuth/봇 및 PUBG 설정을 사용한다. 기존 OAuth/봇 설치 임시 저장소는 메모리 기반이다. 서버 재시작 시 아직 완료하지 않은 Discord 인증은 다시 진행하며 Community는 남지 않는다. 최종 생성에 성공한 요청의 중복 방지는 DB에 영구 저장된다.

## 테스트

수정/추가한 테스트:

- `CommunityFlowTests`: 기존 생성 요청에 요청 키 추가. 신규 13개 회귀 테스트로 OAuth 취소/API 실패, 최종 생성 전 DB 미생성, 봇 설치 실패/재시도, Discord 연결 후 생성, 임시 토큰의 사용자/세션 바인딩, Guild 중복, 연결 INSERT 실패 전체 롤백, 같은 키 재전송/다른 본문 거부, 동시 생성/동시 Guild 연결, Discord 없는 초대 가입·출석, 자체 기능 조회·게임 계정 등록·킬내기 참여, 사후 Discord 동기화 시 동일 클랜원/출석 기록 유지, 플랫폼별 게임 계정 중복·권한을 검증.
- `CommunityCreationPostgresTests`: 위 58개 테스트를 실제 임시 PostgreSQL에서도 실행. 배포 SQL 재실행 및 기존 미연결 멤버십 보충 테스트 1개 추가.
- `CommunityDomainTests`: 새로운 GuildUp 자체 필드 허용. Discord 식별 필드가 Community에 없다는 검증 유지.
- `frontend/test/communityCreationPages.test.js`: 실제 React 앱으로 10개 테스트. 단계/뒤로가기/나중에 연결 시 생성 요청 없음, 최종 버튼 생성, OAuth 취소, 설치 성공/실패, Guild 중복 및 선택 즉시 차단, 이탈 후 초안 복원, 연속 클릭/네트워크 재시도 동일 키, 목록 동선, 일반 MEMBER의 직접 게임 계정 등록, 미연결 Discord 전용 화면 차단을 검증.
- `frontend/test/pubgPlatformPages.test.js`: Discord 닉네임 분석과 설정 화면의 플랫폼 전환 fixture를 실제 선행 조건에 맞게 Discord 연결 상태로 수정.

최종 실행 결과:

| 실행 | 결과 |
|---|---|
| `./mvnw -q test` | 전체 백엔드 테스트 성공, exit 0. 환경변수 조건이 필요한 별도 PostgreSQL 테스트는 기본 실행에서 skip. |
| `TEST_POSTGRES_URL=... ./mvnw -q -Dtest=CommunityCreationPostgresTests test` | 실제 격리 PostgreSQL 59/59 성공, 실패/오류 0. |
| `cd frontend && npm test` | 115/115 성공. |
| `cd frontend && npm run build` | 성공. 새 생성 화면 포함 37개 배포 경로 검증. 기존 500KB 번들 크기 알림은 유지됨. |
| `git diff --check` | 성공. |

Discord/PUBG 외부 호출은 회귀 테스트에서 mock으로 검증했다. 실제 사용자 Discord 계정으로 OAuth/봇 설치를 수행하거나 운영 데이터를 수정하지 않았다. PostgreSQL 검증은 새 임시 DB에서 수행했으며 테스트 서버는 종료했다.

## 변경 파일

- `COMMUNITY_CREATION_FLOW.md`
- `frontend/src/App.jsx`
- `frontend/src/components/CommunityGameAccountForm.jsx`
- `frontend/src/components/CommunityRouteGuard.jsx`
- `frontend/src/components/IntegrationServiceCard.jsx`
- `frontend/src/pages/CommunitiesPage.jsx`
- `frontend/src/pages/CommunityCreatePage.jsx`
- `frontend/src/pages/CommunityDashboardPage.jsx`
- `frontend/src/pages/CommunitySettingsPage.jsx`
- `frontend/src/pages/DiscordConnectPage.jsx`
- `frontend/src/pages/HelpPages.jsx`
- `frontend/src/styles/pages.css`
- `frontend/test/communityCreationPages.test.js`
- `frontend/test/pubgPlatformPages.test.js`
- `src/main/java/com/guildup/community/controller/CommunityController.java`
- `src/main/java/com/guildup/community/controller/CommunityPubgAccountController.java`
- `src/main/java/com/guildup/community/domain/Community.java`
- `src/main/java/com/guildup/community/domain/CommunityUser.java`
- `src/main/java/com/guildup/community/dto/CommunityCreateRequest.java`
- `src/main/java/com/guildup/community/dto/MyCommunityResponse.java`
- `src/main/java/com/guildup/community/repository/CommunityRepository.java`
- `src/main/java/com/guildup/community/repository/CommunityUserRepository.java`
- `src/main/java/com/guildup/community/service/CommunityMembershipService.java`
- `src/main/java/com/guildup/community/service/CommunityNativeMembershipService.java`
- `src/main/java/com/guildup/community/service/CommunityPubgAccountService.java`
- `src/main/java/com/guildup/community/service/CommunityService.java`
- `src/main/java/com/guildup/community/service/CurrentCommunityMemberService.java`
- `src/main/java/com/guildup/community/service/DiscordCommunityConnectionService.java`
- `src/main/java/com/guildup/discord/oauth/controller/DiscordBotInstallController.java`
- `src/main/java/com/guildup/discord/oauth/controller/DiscordCommunityMembershipController.java`
- `src/main/java/com/guildup/discord/oauth/controller/DiscordOAuthController.java`
- `src/main/java/com/guildup/discord/oauth/exception/DiscordOAuthExceptionHandler.java`
- `src/main/java/com/guildup/discord/oauth/service/DiscordBotInstallService.java`
- `src/main/java/com/guildup/discord/oauth/service/DiscordOAuthService.java`
- `src/main/java/com/guildup/discord/oauth/store/DiscordBotInstallSession.java`
- `src/main/java/com/guildup/discord/oauth/store/DiscordBotInstallStore.java`
- `src/main/java/com/guildup/discord/oauth/store/DiscordOAuthSessionStore.java`
- `src/main/java/com/guildup/discord/oauth/store/InMemoryDiscordBotInstallStore.java`
- `src/main/java/com/guildup/discord/oauth/store/InMemoryDiscordOAuthSessionStore.java`
- `src/main/java/com/guildup/user/repository/UserRepository.java`
- `src/main/resources/db/manual/add_community_creation_safety.sql`
- `src/main/resources/static/communities.html`
- `src/test/java/com/guildup/community/CommunityCreationPostgresTests.java`
- `src/test/java/com/guildup/community/CommunityFlowTests.java`
- `src/test/java/com/guildup/community/domain/CommunityDomainTests.java`

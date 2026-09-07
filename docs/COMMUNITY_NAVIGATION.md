# Community 중심 사용자 흐름

## 화면과 이동

```text
/ → /login.html → Discord User OAuth 로그인
  → /communities.html (0개/1개/여러 개 모두 이 화면)
  → 사용자가 Community 선택
  → /community-dashboard.html?communityId={id}
      ├─ 클랜원 관리 → /members.html?communityId={id}
      ├─ Discord 연결됨 → /members.html?communityId={id}&discordRoles=true
      └─ Discord 미연결 → /discord-connect.html?communityId={id}
          → Community OAuth → 서버 선택 → 봇 설치/확인
          → Community 대시보드
```

Community 생성 후에도 목록만 갱신한다. Community가 하나라는 이유로 자동 진입하지 않는다.
게임 관리와 설정은 준비 중으로 표시한다. User 로그인 OAuth와 Community 연결 OAuth의
콜백 주소와 서비스는 기존대로 분리되어 있다.

## API

| 메서드/경로 | 동작 |
| --- | --- |
| `GET /api/auth/me/communities` | 신규. Session 사용자의 Community 목록 `{id, name, role}[]` |
| `GET /api/communities/{communityId}` | 신규. 접근 가능한 Community의 이름·역할·Discord 연결 상태 |
| `GET /api/communities` | 기존 API. 전체 목록 대신 현재 사용자의 목록만 반환 |
| `POST /api/communities` | 기존 API. `{name}`으로 생성하고 Session 사용자를 OWNER로 연결 |

대시보드 응답 예:

```json
{
  "id": 1,
  "name": "치즈 클랜",
  "role": "OWNER",
  "discordConnected": true,
  "discordGuildId": "123456",
  "discordGuildName": "Cheeeze"
}
```

연결이 없으면 `discordConnected=false`, 서버 ID와 이름은 `null`이다.
연결 여부는 `discord_community_connections`에서 조회하며 JDA 연결 여부를 뜻하지 않는다.
봇이 서버에서 제거되었거나 일시적으로 오프라인이면 기존 역할 조회 오류 처리가 적용된다.

## Session과 접근 검사

- 기존 `LOGIN_USER_ID`를 `CurrentUserSession.requireUserId()`로 읽는다. 미로그인은 401이다.
- `CommunityUserRepository.findByUserIdOrderByCommunityIdAsc()`로 로그인 사용자의 관계만 조회한다.
  Community를 함께 로드하고 DTO로 반환한다.
- `CommunityAccessInterceptor`가 Community 하위 API의 `communityId`에 대해
  `community_users(community_id, user_id)` 관계를 검사한다. 관계가 없으면 403이다.
- 기존 `/api/discord/guilds/{guildId}/roles` 및 역할별 멤버 API도 연결된 Community의 관계를 검사한다.
  연결되지 않은 Guild를 이 API로 직접 조회할 수 없다.
- Community OAuth 콜백은 인증 시작 Session의 state와 Community를 확인하고 관계를 다시 검사한 후
  기존 코드 교환을 실행한다. 같은 Session에서 새 연결 인증을 시작하면 이전 인증 시작 정보는 대체된다.
- 봇 설치 확인은 서버에 보관된 installToken에서 Community ID를 얻어 권한을 검사한 후 기존 설치 확인을 수행한다.
- 모든 Community 소속 역할(OWNER/ADMIN/MEMBER)은 이번 단계에서 같은 기능 접근 권한을 갖는다.
  세분화된 역할 정책은 추가하지 않았다.

## 생성 트랜잭션

`CommunityService.createCommunity(name, userId)`의 단일 `@Transactional` 안에서:

1. 이름 및 로그인 User 존재 여부 확인
2. Community 저장
3. `CommunityUser(community, user, OWNER)` 저장

OWNER 저장 실패 시 Community 저장도 롤백된다. 요청 본문의 `userId`는 사용하지 않는다.
기존 Community에 로그인 사용자를 임의로 배정하는 마이그레이션은 하지 않는다.
기존 데이터에 `community_users` 관계가 없다면 내 목록에 표시되지 않는다.

## 유지한 Discord 기능

관리 가능한 Guild 조회·필터, OAuth 결과 저장소, 선택 Guild 검증, 봇 초대 URL 생성,
JDA 기반 설치 확인 및 재시도, DiscordCommunityConnection 저장, 역할 및 역할별 멤버 조회를 재사용한다.
연결 성공 UI의 목적지만 대시보드로 변경했다. Discord 멤버를 DB에 저장하거나 User에 자동 연결하지 않는다.

## 이번 작업 파일

### 백엔드 추가

- `src/main/java/com/guildup/user/auth/service/CurrentUserSession.java`
- `src/main/java/com/guildup/community/config/CommunityAccessInterceptor.java`
- `src/main/java/com/guildup/community/config/CommunityWebConfig.java`
- `src/main/java/com/guildup/community/service/CommunityAccessService.java`
- `src/main/java/com/guildup/community/controller/MyCommunityController.java`
- `src/main/java/com/guildup/community/dto/MyCommunityResponse.java`
- `src/main/java/com/guildup/community/dto/CommunityDashboardResponse.java`

### 백엔드 수정

- `src/main/java/com/guildup/user/auth/controller/DiscordLoginController.java`
- `src/main/java/com/guildup/community/controller/CommunityController.java`
- `src/main/java/com/guildup/community/service/CommunityService.java`
- `src/main/java/com/guildup/community/repository/CommunityUserRepository.java`
- `src/main/java/com/guildup/discord/oauth/controller/DiscordOAuthController.java`
- `src/main/java/com/guildup/discord/oauth/controller/DiscordBotInstallController.java`

### 화면

- 추가: `src/main/resources/static/index.html`
- 추가: `src/main/resources/static/communities.html`
- 추가: `src/main/resources/static/community-dashboard.html`
- 추가: `src/main/resources/static/community.css`
- 추가: `src/main/resources/static/community-ui.js`
- 수정: `src/main/resources/static/login.html`
- 수정: `src/main/resources/static/discord-connect.html`
- 수정: `src/main/resources/static/members.html`

### 테스트·빌드·문서

- `pom.xml`: 테스트 전용 H2, Mockito 시작 에이전트 설정
- `src/test/java/com/guildup/community/CommunityFlowTests.java`: 신규 DB/MVC 통합 테스트
- `src/test/java/com/guildup/GuildupBackendApplicationTests.java`: 개발 DB 대신 격리 H2 사용
- `src/test/java/com/guildup/user/auth/controller/DiscordLoginControllerTests.java`: 로그인 목적지 갱신
- `src/test/java/com/guildup/discord/oauth/exception/DiscordOAuthExceptionHandlerTests.java`: 로그인·접근 검사 의존성 반영
- `docs/COMMUNITY_NAVIGATION.md`: 이 문서
- `docs/API.md`, `docs/PROJECT_FLOW.md`: 최신 Community 흐름 안내 링크

## 검증

`./mvnw test`로 전체 테스트를 실행한다. 테스트 DB는 H2 메모리 DB이며 Discord/JDA 외부 호출은 mock이다.
OWNER 저장 실패 테스트는 실제 DB CHECK 제약 위반을 발생시켜 트랜잭션 롤백을 확인한다.
CommunityFlowTests는 목록 0개/1개/여러 개, 다른 사용자 데이터 제외, 생성 OWNER,
잘못된 이름, 미로그인·권한 없음, 연결 유무, Guild 직접 조회 우회 차단,
콜백 Session/소속 재검증, 봇 확인 토큰 권한 및 정상 Discord 연결 전체 흐름을 검증한다.
실제 Discord 승인 화면 및 모바일/데스크톱 렌더링은 별도의 브라우저 확인이 필요하다.

최종 실행 결과: `./mvnw test` 63개 통과, 실패/오류/건너뜀 0개.
화면 스크립트는 V8의 간단한 DOM/API 대역으로 목록 0/1/2개 및 연결 상태별 링크 분기 5개를 확인했고, 모든 HTML 인라인 스크립트의 구문 검사도 통과했다. 실제 브라우저 렌더링 검증과는 별개다.
초기 Mockito/Byte Buddy self-attach 실패는 Maven Surefire의 명시적 `-javaagent` 설정으로 해결했다.

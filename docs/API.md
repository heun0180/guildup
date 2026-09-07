> Community 선택·대시보드 및 Session 접근 권한의 최신 흐름은 [COMMUNITY_NAVIGATION.md](COMMUNITY_NAVIGATION.md)를 참고하세요. 기존 전체 Community 목록 API도 이제 로그인 사용자의 소속만 반환합니다.

# GuildUp API 명세

이 문서는 현재 컨트롤러 구현을 기준으로 GuildUp 백엔드가 제공하는 전체 REST API를 정리한다.

- 로컬 기본 주소: `http://localhost:8080`
- API 기본 경로: `/api`
- 요청/응답 형식: 별도 표기가 없으면 `application/json`
- 현재 별도의 로그인 인증이나 API 권한 검사는 구현되어 있지 않다.
- Discord 역할과 멤버 조회 결과는 Discord REST API가 아니라 봇의 JDA 캐시를 기준으로 한다.

## 1. 전체 API 목록

| 번호 | Method | 경로 | 기능 |
|---:|---|---|---|
| 1 | `POST` | `/api/communities` | 새 커뮤니티를 생성한다. |
| 2 | `GET` | `/api/communities` | 전체 커뮤니티를 생성 순서대로 조회한다. |
| 3 | `PUT` | `/api/communities/{communityId}/discord-member-role` | Discord 연결의 멤버 역할을 설정한다. |
| 4 | `POST` | `/api/communities/{communityId}/members` | 커뮤니티 멤버를 닉네임으로 직접 등록한다. |
| 5 | `GET` | `/api/communities/{communityId}/members` | 커뮤니티에 저장된 전체 멤버를 조회한다. |
| 6 | `GET` | `/api/communities/{communityId}/discord/roles` | 커뮤니티에 연결된 Discord 서버의 역할을 조회한다. |
| 7 | `GET` | `/api/communities/{communityId}/discord/roles/{roleId}/members` | 연결 서버에서 특정 역할을 가진 일반 사용자를 조회한다. |
| 8 | `GET` | `/api/discord/guilds/{guildId}/roles` | Discord 서버 ID를 직접 사용해 역할을 조회한다. |
| 9 | `GET` | `/api/discord/guilds/{guildId}/roles/{roleId}/members` | Discord 서버 ID와 역할 ID로 역할 멤버를 직접 조회한다. |
| 10 | `GET` | `/api/communities/{communityId}/discord/oauth/authorize` | Discord 사용자 OAuth 인증을 시작한다. |
| 11 | `GET` | `/api/discord/oauth/callback` | Discord가 호출하는 OAuth 콜백을 처리한다. |
| 12 | `GET` | `/api/communities/{communityId}/discord/oauth/results/{resultId}` | OAuth로 확인한 사용자와 관리 가능한 서버 목록을 조회한다. |
| 13 | `POST` | `/api/communities/{communityId}/discord/bot-install/authorize` | 선택한 Discord 서버에 봇 설치 또는 즉시 연결을 시작한다. |
| 14 | `POST` | `/api/discord/bot-install/confirm` | 봇 설치 여부를 확인하고 커뮤니티와 서버의 연결을 확정한다. |

## 2. 커뮤니티 API

### 2.1 커뮤니티 생성

`POST /api/communities`

새 커뮤니티를 생성한다. 외부 서비스 연결 정보는 커뮤니티 생성 요청에 포함하지 않는다.

요청:

```json
{
  "name": "GuildUp 클랜"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | 예 | 생성할 커뮤니티 이름 |

성공 응답: `201 Created`

```json
{
  "id": 1,
  "name": "GuildUp 클랜"
}
```

### 2.2 전체 커뮤니티 조회

`GET /api/communities`

DB의 전체 커뮤니티를 ID 오름차순, 즉 생성 순서대로 반환한다.

성공 응답: `200 OK`

```json
[
  {
    "id": 1,
    "name": "GuildUp 클랜"
  },
  {
    "id": 2,
    "name": "레이드 파티"
  }
]
```

### 2.3 Discord 멤버 역할 설정

`PUT /api/communities/{communityId}/discord-member-role`

Discord 연결 정보에 멤버 역할 ID를 저장한다. 먼저 커뮤니티에 Discord 서버가 연결되어 있어야 한다.

경로 변수:

| 변수 | 타입 | 설명 |
|---|---|---|
| `communityId` | number | 역할을 설정할 커뮤니티 ID |

요청:

```json
{
  "roleId": "987654321098765432"
}
```

성공 응답: `200 OK`

```json
{
  "id": 1,
  "name": "GuildUp 클랜"
}
```

주요 실패 조건:

- 커뮤니티가 없으면 `404 Not Found`
- Discord 연결이 없으면 `404 Not Found`
- `roleId`가 없거나 빈 값이면 요청을 처리할 수 없다.

## 3. 커뮤니티 멤버 API

### 3.1 멤버 직접 등록

`POST /api/communities/{communityId}/members`

외부 계정 연결 없이 닉네임만으로 GuildUp 자체 관리 멤버를 추가한다. Discord 실시간 조회 결과와는 독립된 데이터다.

요청:

```json
{
  "nickname": "길드원A"
}
```

성공 응답: `201 Created`

```json
{
  "id": 10,
  "nickname": "길드원A"
}
```

### 3.2 저장된 멤버 목록 조회

`GET /api/communities/{communityId}/members`

커뮤니티에 저장된 GuildUp 자체 관리 멤버를 ID 오름차순으로 반환한다. Discord 역할 멤버는 이 응답에 자동으로 합쳐지지 않는다.

성공 응답: `200 OK`

```json
[
  {
    "id": 10,
    "nickname": "길드원A"
  }
]
```

현재 구현은 목록 조회 전에 커뮤니티 존재 여부를 별도로 검사하지 않으므로, 존재하지 않는 `communityId`도 빈 배열을 반환할 수 있다.

## 4. Discord 역할과 역할별 멤버 API

동일한 조회 기능을 두 가지 경로로 제공한다.

- 커뮤니티 기반 경로는 저장된 연결에서 Discord 서버 ID를 찾는다.
- 직접 조회 경로는 URL로 받은 `guildId`를 바로 사용한다.
- 역할과 멤버는 요청할 때 JDA에서 조회하며 `CommunityMember`나 `CommunityMemberAccount`에 저장하지 않는다.

### 4.1 연결된 서버의 역할 조회

`GET /api/communities/{communityId}/discord/roles`

성공 응답: `200 OK`

```json
[
  {
    "id": "987654321098765432",
    "name": "클랜원"
  },
  {
    "id": "876543210987654321",
    "name": "게스트"
  }
]
```

Discord의 `@everyone` 역할은 제외하며, 서버에서 역할 위치가 높은 순서대로 반환한다.

### 4.2 연결된 서버의 역할 멤버 조회

`GET /api/communities/{communityId}/discord/roles/{roleId}/members`

성공 응답: `200 OK`

```json
[
  {
    "id": "111111111111111111",
    "username": "discord_user",
    "displayName": "서버 별명",
    "avatarUrl": "https://cdn.discordapp.com/avatars/..."
  }
]
```

봇 계정은 제외하며 `displayName`의 대소문자를 구분하지 않는 오름차순으로 반환한다. 서버, 역할 또는 커뮤니티 연결을 찾지 못하면 `404 Not Found`가 될 수 있다.

### 4.3 서버 ID로 역할 직접 조회

`GET /api/discord/guilds/{guildId}/roles`

응답과 정렬 규칙은 4.1과 같다. 봇이 참여하지 않았거나 JDA에서 찾을 수 없는 서버라면 `404 Not Found`를 반환한다.

### 4.4 서버 ID로 역할 멤버 직접 조회

`GET /api/discord/guilds/{guildId}/roles/{roleId}/members`

응답과 정렬 규칙은 4.2와 같다. 서버 또는 역할을 찾지 못하면 `404 Not Found`를 반환한다.

## 5. Discord OAuth API

### 5.1 OAuth 인증 시작

`GET /api/communities/{communityId}/discord/oauth/authorize`

커뮤니티 존재 여부와 OAuth 환경 설정을 확인하고 Discord 인증 화면으로 이동시킨다. 사용자 프로필을 위한 `identify`와 참여 서버 및 권한 확인을 위한 `guilds` 범위를 요청한다.

성공 응답: `302 Found`

- 본문 없음
- `Location`: Discord OAuth 인증 URL

주요 실패 응답:

- 커뮤니티가 없으면 `404 Not Found`
- OAuth 환경변수가 누락되면 `503 Service Unavailable`

### 5.2 OAuth 콜백

`GET /api/discord/oauth/callback?code={code}&state={state}`

Discord가 인증 후 호출하는 서버용 콜백이다. 일반 화면에서 직접 호출하는 API가 아니다.

1. 일회용 `state`를 소비해 원래 커뮤니티를 확인한다.
2. `code`를 Discord 액세스 토큰으로 교환한다.
3. 로그인 사용자와 참여 서버를 Discord API에서 조회한다.
4. 서버 소유자이거나 `Administrator` 또는 `Manage Guild` 권한이 있는 서버만 남긴다.
5. 결과를 메모리에 5분간 저장한다.
6. 연결 화면으로 다시 이동시킨다.

성공 응답: `302 Found`

```text
Location: /discord-connect.html?communityId=1&oauthResult={resultId}
```

`state`가 잘못됐거나 만료 또는 재사용된 경우 `400 Bad Request`를 반환한다.

### 5.3 OAuth 결과 조회

`GET /api/communities/{communityId}/discord/oauth/results/{resultId}`

콜백이 임시 저장한 Discord 사용자 정보와 연결 가능한 서버 목록을 반환한다. 결과 ID는 커뮤니티와 일치해야 하며 생성 후 5분 동안 유효하다.

성공 응답: `200 OK`

```json
{
  "user": {
    "discordUserId": "111111111111111111",
    "username": "discord_user",
    "globalName": "길드장",
    "avatarUrl": "https://cdn.discordapp.com/avatars/..."
  },
  "guilds": [
    {
      "id": "123456789012345678",
      "name": "GuildUp Discord",
      "iconUrl": "https://cdn.discordapp.com/icons/...",
      "owner": true
    }
  ]
}
```

결과가 없거나 만료됐거나 다른 커뮤니티의 결과이면 `404 Not Found`를 반환한다.

## 6. Discord 봇 설치 및 연결 API

### 6.1 봇 설치 시작

`POST /api/communities/{communityId}/discord/bot-install/authorize`

사용자가 OAuth 결과에서 선택한 서버가 실제 관리 가능한 서버인지 검증한다. 이 요청에서 OAuth 결과를 일회성으로 소비하므로 같은 `oauthResultId`를 다시 사용할 수 없다.

요청:

```json
{
  "oauthResultId": "OAuth 결과 ID",
  "guildId": "123456789012345678"
}
```

봇이 이미 설치된 경우 바로 연결한 응답: `200 OK`

```json
{
  "alreadyInstalled": true,
  "authorizationUrl": null,
  "installToken": null,
  "communityId": 1,
  "guildName": "GuildUp Discord"
}
```

봇 설치가 필요한 경우의 응답: `200 OK`

```json
{
  "alreadyInstalled": false,
  "authorizationUrl": "https://discord.com/oauth2/authorize?...",
  "installToken": "설치 확인용 임시 토큰",
  "communityId": 1,
  "guildName": "GuildUp Discord"
}
```

클라이언트는 `authorizationUrl`에서 봇 설치를 완료한 뒤 `installToken`으로 설치 확인 API를 호출해야 한다. 설치 토큰은 생성 후 10분 동안 유효하다.

주요 실패 응답:

- OAuth로 검증되지 않은 서버를 선택하면 `400 Bad Request`
- OAuth 결과가 없거나 만료됐으면 `404 Not Found`
- OAuth/봇 설치 환경변수가 누락되면 `503 Service Unavailable`
- 이미 다른 Discord 서버가 연결된 커뮤니티에 새 서버를 연결하면 `409 Conflict`

### 6.2 봇 설치 확인 및 연결 확정

`POST /api/discord/bot-install/confirm`

설치 토큰에서 커뮤니티와 Discord 서버 정보를 복원한다. JDA에서 봇 참여 여부를 500ms 간격으로 최대 5회 확인하고 성공하면 1:1 연결을 DB에 저장한다. 성공한 설치 토큰은 제거되어 다시 사용할 수 없다.

요청:

```json
{
  "installToken": "설치 확인용 임시 토큰"
}
```

성공 응답: `200 OK`

```json
{
  "connected": true,
  "communityId": 1,
  "guildName": "GuildUp Discord"
}
```

주요 실패 응답:

- 토큰이 잘못됐거나 만료됐으면 `400 Bad Request`
- 아직 봇 설치가 확인되지 않으면 `409 Conflict`
- 커뮤니티가 이미 다른 서버에 연결되어 있으면 `409 Conflict`

## 7. 오류 응답 참고

OAuth 환경 설정 오류는 다음 공통 JSON 형태로 반환한다.

```json
{
  "status": 503,
  "message": "Discord OAuth 설정이 완료되지 않았습니다. DISCORD_CLIENT_ID를 확인해주세요."
}
```

그 밖의 `@ResponseStatus` 예외는 Spring의 기본 오류 응답 형식을 사용한다. 현재 모든 입력 DTO에 Bean Validation과 통합 오류 핸들러가 적용된 상태는 아니므로, 빈 값이나 DB 제약 위반의 상태 코드와 본문은 엔드포인트별로 균일하지 않을 수 있다.

| 상태 | 대표 원인 |
|---|---|
| `400 Bad Request` | 잘못된/만료된 OAuth state, 잘못된 서버 선택, 잘못된/만료된 설치 토큰 |
| `404 Not Found` | 없는 커뮤니티, Discord 연결 없음, JDA에서 서버·역할 없음, OAuth 결과 없음/만료 |
| `409 Conflict` | 기존 연결과 다른 Discord 서버 연결 시도, 봇 설치 미확인 |
| `503 Service Unavailable` | Discord OAuth 또는 봇 설치 환경변수 누락 |

## 8. 기능별 권장 호출 순서

### 커뮤니티와 Discord 연결

```text
POST /api/communities
  → GET /api/communities/{communityId}/discord/oauth/authorize
  → Discord 인증 및 GET /api/discord/oauth/callback
  → GET /api/communities/{communityId}/discord/oauth/results/{resultId}
  → POST /api/communities/{communityId}/discord/bot-install/authorize
  → 필요한 경우 Discord 봇 설치
  → POST /api/discord/bot-install/confirm
```

### Discord 역할과 멤버 실시간 조회

```text
GET /api/communities/{communityId}/discord/roles
  → GET /api/communities/{communityId}/discord/roles/{roleId}/members
```

저장된 GuildUp 멤버는 별도로 `GET /api/communities/{communityId}/members`에서 조회한다.

## 9. `CommunityRepository`의 역할

`CommunityRepository`는 외부에 노출되는 HTTP API가 아니라 서비스에서 사용하는 Spring Data JPA 저장소다.

| 저장소 메서드 | 기능 |
|---|---|
| `save(community)` | 커뮤니티 생성 및 저장 |
| `findById(communityId)` | ID로 커뮤니티 조회 |
| `existsById(communityId)` | OAuth 시작 전 커뮤니티 존재 확인 |
| `findAll(sort)` | 전체 커뮤니티 정렬 조회 |

모든 메서드는 `JpaRepository<Community, Long>`에서 기본 제공한다. Discord 연결은 `DiscordCommunityConnectionRepository`에서만 조회한다.

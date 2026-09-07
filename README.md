# GuildUp

Discord 기반 게임 커뮤니티·클랜 운영을 더 편하게 만들기 위한 웹 서비스입니다.

클랜 운영자가 Discord 역할과 멤버 정보를 직접 오가며 확인해야 하는 불편함을 줄이고, 커뮤니티 선택부터 Discord 서버 연결, 역할·멤버 조회까지 하나의 흐름으로 관리하는 것을 목표로 개발하고 있습니다.

## 현재 구현된 흐름

```text
Discord 로그인
    ↓
내 Community 목록
    ↓
Community 선택
    ↓
Community 대시보드
    ├─ Discord 연결 완료 → 역할 / 멤버 조회
    └─ Discord 미연결
          ↓
       Discord OAuth
          ↓
       관리 가능한 서버 선택
          ↓
       GuildUp Bot 설치 및 확인
          ↓
       Community와 Discord Guild 연결
```

Community가 하나뿐이어도 자동으로 진입시키지 않고 사용자가 직접 Community를 선택하도록 구성했습니다.

## 주요 기능

### Community 관리
- 로그인 사용자의 Community 목록 조회
- Community 생성 시 사용자를 `OWNER`로 연결
- Community별 대시보드 제공
- Session 사용자와 `CommunityUser` 관계를 기반으로 접근 권한 검사
- 다른 사용자의 Community 직접 접근 차단

### Discord 로그인 / OAuth
- Discord OAuth2 기반 사용자 로그인
- 로그인용 OAuth와 Community 서버 연결용 OAuth 흐름 분리
- `state` 기반 요청 검증
- OAuth 결과를 서버에서 단기 보관하고 1회성으로 사용
- Discord Access Token을 브라우저에 직접 노출하지 않음

### Discord 서버 연결
- 사용자가 관리 가능한 Discord Guild만 조회
- 서버 소유자 또는 `Administrator`, `Manage Guild` 권한이 있는 서버만 선택 가능
- 한 Community에 하나의 Discord Guild 연결
- 동일한 Discord Guild의 중복 Community 연결 방지

### Discord Bot 연동
- Discord Bot 초대 URL 생성
- JDA를 이용해 실제 Guild 참여 여부 확인
- 설치 직후 반영 지연을 고려한 재시도 처리
- Bot 설치가 확인된 뒤 `DiscordCommunityConnection` 저장

### 역할 / 멤버 조회
- 연결된 Discord Guild의 역할 목록 조회
- 역할별 멤버 조회
- JDA `GUILD_MEMBERS` Intent 및 Member Cache 사용
- Discord 멤버를 GuildUp DB에 무조건 복제하지 않고 필요한 정보는 실시간 조회

## 기술 스택

### Backend
- Java 21
- Spring Boot 4.1.1
- Spring Web MVC
- Spring Data JPA
- PostgreSQL
- JDA 6.4.1
- Maven

### Frontend
- React 19
- Vite 7
- JavaScript
- CSS

### Test
- JUnit 기반 Spring Boot Test
- H2 In-Memory Database
- Discord / JDA 외부 호출 Mock

## 프로젝트 구조

```text
guildup/
├── frontend/                       # React + Vite Frontend
│   └── src/
│       ├── api/
│       ├── components/
│       ├── pages/
│       └── styles/
│
├── src/main/java/com/guildup/
│   ├── account/                    # 외부 계정 관련 도메인
│   ├── community/                  # Community / 권한 / 멤버 관리
│   ├── discord/
│   │   ├── bot/
│   │   ├── config/
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── exception/
│   │   ├── oauth/
│   │   └── service/
│   └── user/                       # GuildUp 사용자 / 로그인
│
├── src/main/resources/
│   ├── application.properties
│   └── static/                     # 기존 Spring 정적 화면
│
└── docs/
    ├── API.md
    ├── COMMUNITY_NAVIGATION.md
    └── PROJECT_FLOW.md
```

백엔드는 기능을 하나의 클래스에 몰아넣기보다 도메인과 책임을 기준으로 `Controller`, `Service`, `Repository`, `Domain`, `DTO`를 분리하는 방향으로 구성했습니다.

## 주요 데이터 모델

```text
User
 ├─ UserExternalAccount
 └─ CommunityUser ───── Community
                         ├─ CommunityGame
                         ├─ CommunityMember
                         │    └─ CommunityMemberAccount
                         └─ DiscordCommunityConnection
```

### User와 Discord Member를 분리한 이유

Discord 서버에 존재하는 사용자가 반드시 GuildUp 회원인 것은 아니기 때문에 두 개념을 동일하게 저장하지 않았습니다.

- `User`: GuildUp에 로그인한 사용자
- `CommunityMember`: Community에서 관리하는 클랜원
- `UserExternalAccount`: GuildUp 사용자와 외부 서비스 계정 연결
- `CommunityMemberAccount`: 클랜원과 외부 서비스 계정 연결

Discord 역할 멤버를 조회하는 것만으로 `CommunityMember`를 자동 생성하지 않아 외부 데이터와 GuildUp 내부 데이터의 책임을 분리했습니다.

## 구현하면서 중점적으로 본 부분

### 1. OAuth 요청 검증

OAuth 시작 시 생성한 `state`를 서버에 저장하고 Callback에서 한 번만 소비합니다. 인증 결과 역시 제한된 시간 동안만 보관해 이전 인증 결과가 계속 재사용되지 않도록 구성했습니다.

### 2. 클라이언트 입력을 그대로 신뢰하지 않는 구조

Bot 설치 시 브라우저에서 전달한 Guild ID만으로 연결하지 않고, OAuth 단계에서 확인한 관리 가능한 Guild 목록과 다시 대조합니다.

### 3. Discord 데이터 동기화 최소화

Discord 역할·멤버 정보를 모두 DB에 복제하면 실제 Discord 상태와 불일치할 가능성이 있습니다. 따라서 GuildUp에서 직접 관리해야 하는 연결 정보는 DB에 저장하고, 역할·멤버 정보는 JDA를 통해 조회하는 방향을 선택했습니다.

### 4. Community 단위 접근 제어

로그인 여부뿐 아니라 사용자가 요청한 Community에 실제로 소속되어 있는지 확인합니다. URL의 `communityId`를 변경해 다른 Community API를 직접 호출하는 경우에도 접근을 차단하도록 구성했습니다.

## 테스트

```bash
./mvnw test
```

현재 문서화된 최종 테스트 기준으로 **63개 테스트가 통과**했으며, 다음과 같은 흐름을 검증하고 있습니다.

- Community 0개 / 1개 / 여러 개 조회
- Community 생성과 OWNER 연결 트랜잭션
- 미로그인 요청 `401`
- 다른 Community 접근 `403`
- OAuth Session / Community 관계 재검증
- Discord Guild 직접 조회 우회 차단
- Bot 설치 확인 및 Discord 연결 흐름
- 잘못된 데이터 발생 시 트랜잭션 롤백

## 로컬 실행

### 요구 환경

- JDK 21
- PostgreSQL
- Node.js / npm
- Discord Application 및 Bot

### Discord 환경 변수

```bash
export DISCORD_CLIENT_ID=...
export DISCORD_CLIENT_SECRET=...
export DISCORD_REDIRECT_URI=...
export DISCORD_BOT_TOKEN=...
```

PostgreSQL 접속 정보는 `src/main/resources/application.properties`에서 로컬 환경에 맞게 설정합니다.

### Backend

```bash
./mvnw spring-boot:run
```

기본 Backend 주소:

```text
http://localhost:8080
```

### Frontend

```bash
cd frontend
npm install
npm run dev
```

기본 Frontend 주소:

```text
http://localhost:5173
```

Vite 개발 서버의 `/api` 요청은 `http://localhost:8080`으로 Proxy됩니다.


## 개발 방향

현재는 Discord 기반 Community 관리의 기반 기능을 구현하고 있으며, 이후 다음 기능으로 확장할 계획입니다.

- GuildUp 자체 클랜원 관리 고도화
- Discord 사용자와 GuildUp 회원 연결
- 게임 플랫폼 계정 연결
- PUBG API 연동
- 클랜원 활동 및 통계 기능
- 이벤트 / 내전 관리

단순 기능 구현보다 **외부 시스템 연동, 데이터 책임 분리, 접근 권한, 예외 처리와 테스트를 함께 고려하는 것**을 목표로 개발하고 있습니다.

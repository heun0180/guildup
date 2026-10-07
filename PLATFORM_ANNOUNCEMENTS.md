# GuildUp 서비스 공지

GuildUp 개발자/관리자가 전체 로그인 사용자에게 게시하는 독립 공지 기능이다. 기존 커뮤니티 공지의 API, 엔티티, 데이터, 권한 검사는 변경하지 않았다. 첫 버전은 전체 사용자 대상이며, 게임/커뮤니티 대상 지정은 향후 구현용 필드만 준비했다.

## 작업 전 확인한 구조

| 항목 | 기존 구현 | 이번 적용 |
| --- | --- | --- |
| 프로젝트 | Java 21, Spring Boot MVC/JPA, PostgreSQL, Maven; `frontend/`의 React 19, React Router, Vite | 새 라이브러리 없이 기존 계층/스타일 유지 |
| 커뮤니티 공지 | `CommunityNotice`, `CommunityNoticeRepository/Service/Controller`, `community_notices`; `/api/communities/{communityId}/notices` | 변경 없음 |
| 공지 화면 | `CommunityNewsPage`, `CommunityNewsForm/Meta`, `/community-news.html?communityId=…` | 서비스 공지는 별도 `/announcements` |
| 사용자/로그인 | `users`, `User`, Discord OAuth, `HttpSession.LOGIN_USER_ID`, `CurrentUserSession` | 같은 GuildUp 사용자와 세션 사용; Discord/게임 계정 불필요 |
| 커뮤니티 권한 | `community_users`의 OWNER/ADMIN/MEMBER, `CommunityAccessService` | 서비스 공지의 관리 권한에 사용하지 않음 |
| 개발자 권한 | `users.system_role = SYSTEM_ADMIN`, `DeveloperAccessInterceptor`, `DeveloperAccessService`, `/api/auth/me.systemAdmin` | 기존 정책 재사용; 서비스 계층에서도 재검사 |
| 공통 레이아웃 | 커뮤니티 선택 전 `public-page + AppHeader + SiteFooter`; 내부는 `CommunityShell → DashboardLayout + Sidebar + CommunityRouteGuard` | 공지는 CommunityProvider/RouteGuard 밖에 배치; 같은 AppHeader로 모든 로그인 화면에서 알림 접근 |
| 개발자 화면 | `DeveloperLayout`, 기존 조회/모니터링 메뉴 | 독립 `GuildUp 공지 관리` 메뉴 추가; 기존 기능 유지 |
| 알림/읽음 | 사용자별 알림·공지 읽음 테이블 없음 | 별도 희소 읽음 테이블 추가 |
| 디자인 | CSS 색상/간격/반경 변수, panel, Badge, 폼/버튼, Modal 스타일; 현재 light 테마 | 같은 변수/컴포넌트 패턴 사용; 독립 dark 테마는 추가하지 않음 |
| DB 변경 | 수동 DDL + `ddl-auto=update`; Flyway/Liquibase 없음 | 기존 `src/main/resources/db/manual/` 방식 유지 |

## 화면과 동작

- `/announcements`: 로그인 사용자가 커뮤니티 가입/선택 없이 확인하는 목록. `?id={id}`로 상세를 연다. 20개 단위 페이지, 고정 → 중요 → 작성일 내림차순 → ID 내림차순이다.
- 유형: NOTICE(공지), UPDATE(업데이트), MAINTENANCE(점검), INCIDENT(장애), EVENT(이벤트). 유형별 Badge, 중요/고정/NEW/읽음 표시를 제공한다.
- 공통 헤더의 알림: 공개중인 공지의 읽지 않은 총 개수, 작성일 기준 최근 5개, 전체 공지 보기. 처음 진입/알림 열기/창 활성화/60초마다 갱신한다. 알림 요청 실패가 기존 화면을 중단하지 않게 처리했다.
- `/developer/announcements`: 작성/수정/삭제, 공개/비공개, 중요, 고정, 게시 기간, 팝업. 비공개를 새 공지의 기본값으로 사용한다. 기존 개발자 메뉴의 READ ONLY 문구는 쓰기 기능을 반영해 GuildUp 관리로 바꿨다.
- 게시 상태: PRIVATE(비공개), SCHEDULED(예약), PUBLISHED(게시중), ENDED(종료). 상태를 중복 저장하지 않고 Clock으로 계산한다.
- 게시 시작/종료는 선택이다. 시작 생략은 즉시 게시, 종료 생략은 무기한이다. `시작 <= 현재 < 종료`를 사용하므로 종료 시각부터 노출하지 않는다. 종료가 시작과 같거나 이전이면 400이다.
- 날짜는 기존 폼처럼 기기의 현지 시각으로 입력하고 API에서는 ISO UTC Instant를 사용한다. 한국 기기에서 `2026-10-10 09:00` 입력 시 `2026-10-10T00:00:00Z`로 전송한다.
- NEW는 최초 공개 시각과 게시 시작 시각 중 늦은 시각부터 24시간이다. 오래된 초안을 처음 게시한 경우에도 NEW가 표시되고, 읽어도 24시간 내에는 유지된다. 본문 수정이나 단순 재공개로 최초 공개 시각을 초기화하지 않는다.
- 본문은 일반 텍스트로 렌더링한다. 줄바꿈을 보존하며 사용자 HTML/script를 실행하지 않는다.
- 생산 빌드의 기존 route 생성기가 `/announcements/index.html`, `/developer/announcements/index.html`을 자동 생성한다. 상세도 같은 URL의 query를 사용하므로 별도 동적 경로 fallback을 추가할 필요가 없다.

## API

모든 API는 기존 로그인 세션을 사용한다. 비로그인 401, 개발자 API의 일반 사용자/커뮤니티 OWNER/ADMIN은 403이다. 일반 조회에서 비공개·예약·종료·미지원 대상의 ID는 404이다. **시스템 관리자라도 일반 API는 공개 조건을 우회하지 않는다.**

| Method | 경로 | 용도 |
| --- | --- | --- |
| GET | `/api/announcements?page=0&size=20` | 사용자 목록 |
| GET | `/api/announcements/{id}` | 공개중인 상세, GET 자체는 쓰기 없음 |
| GET | `/api/announcements/notifications` | `{unreadCount, recent}`; 최근 최대 5개 |
| GET | `/api/announcements/popup` | 아직 확인하지 않은 중요 팝업 1개; 없으면 204 |
| POST | `/api/announcements/{id}/read` | 자신의 읽음 기록; 멱등 |
| POST | `/api/announcements/{id}/popup-confirmation` | 자신의 팝업 확인 및 읽음 기록; 멱등 |
| GET | `/api/developer/announcements?page=0&size=20` | 모든 상태 목록 |
| GET | `/api/developer/announcements/{id}` | 관리용 상세/비공개 조회 |
| POST | `/api/developer/announcements` | 생성, 201 |
| PUT | `/api/developer/announcements/{id}` | 수정 |
| DELETE | `/api/developer/announcements/{id}` | 삭제, 204 |

`size`는 1~100, `page`는 0 이상의 정수이다. 목록 응답은 `{content, page, size, totalElements, totalPages}`, 상세 응답은 `{announcement: 목록의 Item, content: 본문}`이다. 목록/알림에는 본문과 관리자 사용자 식별 정보를 포함하지 않는다.

작성/수정 예시:

```json
{
  "title": "서버 점검 안내",
  "content": "2026년 10월 10일 점검을 진행합니다.",
  "type": "MAINTENANCE",
  "important": true,
  "pinned": true,
  "popup": true,
  "published": true,
  "publishStartAt": "2026-10-10T00:00:00Z",
  "publishEndAt": "2026-10-20T14:59:00Z"
}
```

제목 1~200자, 내용 1~20,000자, 유형 필수이다. 중요하지 않은 팝업 설정은 400이다. 작성자는 세션의 SYSTEM_ADMIN 사용자로 저장하며 클라이언트가 작성자·읽음 사용자·시스템 권한·공지 대상을 변경할 수 없다.

## 관리자 권한 / 읽음 / 팝업

`/api/developer/**`의 기존 `DeveloperAccessInterceptor`를 통해 컨트롤러 진입 전에 권한을 확인한다. 추가로 관리 서비스 메서드 모두 `DeveloperAccessService.requireSystemAdmin`을 호출한다. 프론트는 기존 `DeveloperLayout`에서 `/api/auth/me.systemAdmin`을 확인한다. 프론트의 메뉴 숨김 여부와 무관하게 백엔드에서 접근을 차단한다.

관리 쓰기, 읽음 POST, 팝업 확인 POST 모두 기존 `SessionCsrfInterceptor`와 공통 `api()`의 CSRF 토큰 흐름을 사용한다. 상세 화면은 GET 성공 후 POST로 읽음을 저장하므로 링크 사전 조회나 외부 GET만으로 읽음이 바뀌지 않는다.

사용자를 모두 순회하거나 공지별 모든 사용자 row를 생성하지 않는다. 상세를 실제로 연 사용자/팝업을 확인한 사용자만 `platform_announcement_reads`에 저장한다. 사용자-공지 UNIQUE와 기존 `UserRepository.findForUpdate`를 사용해 동시 최초 읽음을 직렬화한다. 최초 읽음/확인 시각은 반복 요청으로 덮어쓰지 않는다. 미읽음 수는 **공개중인 전체 공지 중 자신의 read_at 기록이 없는 개수**로 계산한다. 새 공지를 생성하면 자동으로 모든 기존 사용자에게 미읽음이 된다.

팝업은 공개중 + 중요 + popup 설정 + 자신의 popup_confirmed_at 기록 없음의 조건이다. native dialog로 표시하고 확인 버튼/ESC로 확인을 저장한다. 같은 사용자가 같은 공지를 확인하면 다른 기기·재접속·재공개 이후에도 다시 표시하지 않는다. 단순 상세 읽음은 팝업 확인과 구분한다. 확인은 읽음도 저장하고 현재 목록과 헤더에 즉시 반영한다. `오늘 다시 보지 않기`는 이번 최소 구현에 포함하지 않았다. 여러 미확인 팝업이 있으면 우선순위대로 한 번에 하나씩 확인한다.

## DB / 운영 적용

운영 DB와 운영 서버에는 접속하거나 SQL을 실행하지 않았다. 새 SQL은 자동 실행되지 않는다. 기존 테이블 변경/사용자 권한 부여/커뮤니티 공지 데이터 이관은 없다.

| 테이블 | 추가 컬럼 |
| --- | --- |
| `platform_announcements` | `id`, `created_by`, `title`, `content`, `type`, `is_important`, `is_pinned`, `is_popup`, `is_published`, `publish_start_at`, `publish_end_at`, `published_at`, `target_type`, `target_game_type`, `target_community_id`, `created_at`, `updated_at` |
| `platform_announcement_reads` | `id`, `announcement_id`, `user_id`, `read_at`, `popup_confirmed_at` |

- `created_by`는 users FK, 읽음의 announcement/user FK는 삭제 CASCADE이다.
- 읽음 UNIQUE `(announcement_id, user_id)`, 조회 인덱스 `(user_id, announcement_id)`를 둔다.
- 공지 feed 인덱스는 `(target_type, is_published, is_pinned DESC, is_important DESC, created_at DESC, id DESC)`이다.
- 게시 기간, 유형, 중요 팝업 조건, 대상 구조, 최초 공개 시각 CHECK는 운영용 SQL에 포함되어 있다. 로컬 Hibernate 스키마 생성에서는 서비스 검증을 사용한다.
- `target_type`은 ALL/GAME/COMMUNITY, `target_game_type`은 기존 GameType 이름(BATTLEGROUNDS_KAKAO/BATTLEGROUNDS_STEAM), `target_community_id`는 communities FK를 위한 준비 필드이다. 작성 API는 ALL만 생성한다. 모든 사용자 조회는 ALL만 노출하므로 미구현 대상 공지가 전체 사용자에게 잘못 전달되지 않는다. 향후 대상별 작성/조회 시 권한·멤버십 정책을 함께 구현해야 한다.

운영 스키마 변경은 **새 코드 배포 전에**, 검토한 PostgreSQL 대상에서 아래 파일 전체를 실행한다:

```text
src/main/resources/db/manual/add_platform_announcements.sql
```

기존과 같이 로컬 `ddl-auto=update`는 JPA에서 새 테이블/인덱스를 생성한다. 이미 Hibernate로 만들어진 테이블에 `CREATE TABLE IF NOT EXISTS`를 실행하면 CHECK가 추가되지 않으므로, 운영 반영은 먼저 수동 DDL을 적용하는 순서를 따른다. SQL은 기존 테이블을 수정하지 않는다. 프론트 빌드 산출물도 기존 절차로 배포한다.

## 수정/추가 파일

수정 5개:

```text
frontend/src/App.jsx
frontend/src/main.jsx
frontend/src/components/AppHeader.jsx
frontend/src/components/Icon.jsx
frontend/src/developer/DeveloperLayout.jsx
```

추가 백엔드 11개:

```text
src/main/java/com/guildup/announcement/domain/PlatformAnnouncement.java
src/main/java/com/guildup/announcement/domain/PlatformAnnouncementRead.java
src/main/java/com/guildup/announcement/domain/PlatformAnnouncementType.java
src/main/java/com/guildup/announcement/domain/PlatformAnnouncementTarget.java
src/main/java/com/guildup/announcement/repository/PlatformAnnouncementRepository.java
src/main/java/com/guildup/announcement/repository/PlatformAnnouncementReadRepository.java
src/main/java/com/guildup/announcement/dto/PlatformAnnouncementRequest.java
src/main/java/com/guildup/announcement/dto/PlatformAnnouncementResponses.java
src/main/java/com/guildup/announcement/service/PlatformAnnouncementService.java
src/main/java/com/guildup/announcement/controller/PlatformAnnouncementController.java
src/main/java/com/guildup/announcement/controller/DeveloperAnnouncementController.java
```

추가 프론트 9개:

```text
frontend/src/announcements.js
frontend/src/announcement/AnnouncementContext.jsx
frontend/src/components/AnnouncementBell.jsx
frontend/src/components/AnnouncementMeta.jsx
frontend/src/components/AnnouncementPopup.jsx
frontend/src/components/AnnouncementForm.jsx
frontend/src/pages/AnnouncementsPage.jsx
frontend/src/developer/DeveloperAnnouncementsPage.jsx
frontend/src/styles/announcements.css
```

추가 SQL/테스트/문서:

```text
src/main/resources/db/manual/add_platform_announcements.sql
src/test/java/com/guildup/announcement/PlatformAnnouncementFlowTests.java
frontend/test/announcements.test.js
frontend/test/announcementPages.test.js
PLATFORM_ANNOUNCEMENTS.md
```

## 검증 결과

- 백엔드 전체: `env -u TEST_POSTGRES_URL ./mvnw -q test` → 892개 중 735개 통과, 157개 환경 조건에 따라 skip, 실패/오류 0. PostgreSQL 환경 조건 테스트 155개와 기존 운영 덤프 baseline 2개는 실행하지 않았다.
- 추가 백엔드 통합 테스트 15개: 게시중/예약/종료/비공개, 모든 상태 관리 조회, OWNER/ADMIN 포함 권한 차단, 서비스 직접 호출 차단, CSRF, 희소/멱등 읽음, 미읽음 계산, 사용자별 팝업, 삭제 CASCADE, 고정/중요 정렬, 최근 5개, 페이지, 정확한 시간 경계, NEW, 입력 검증, 미래 대상 비노출, 커뮤니티 공지 독립성.
- 기존 `CommunityNewsFlowTests` 12개, `DeveloperAccessAndQueryTests` 5개, `DiscordLoginControllerTests` 10개 통과. 전체 suite에 커뮤니티/Discord/PUBG/빙고/킬내기/랭킹 회귀 검증을 포함했다.
- 프론트 전체: `npm test` → 172개 통과, 실패 0. 추가 15개(입력/날짜/URL 3개, DOM 동작 12개) 포함. 목록↔상세 전환, 지연 응답 무시, 상세 읽음/실패 재시도, NEW 독립성, CSRF 헤더, XSS, 알림, 잘못된 알림 응답, 팝업 확인/현재 목록 갱신, 관리 상태/폼, 일반 사용자 접근 차단을 확인했다.
- `npm run build` → 통과, 39개 라우트의 생산 HTML/asset 검증 통과. Vite의 기존 단일 JS 번들 크기 경고는 남아 있다.
- 브라우저 QA: DB에 연결하지 않는 로컬 fixture로 데스크톱 및 320/390px 화면의 목록/알림/상세/관리 폼/팝업을 확인했다. native dialog 확인 후 새로고침에서 재노출되지 않는 것도 확인했다. 이 검증은 실제 운영 데이터나 로그인으로 수행하지 않았다.
- `git diff --check` 통과. 새 의존성은 없다.
- 기존 Clock Mockito 기반 일부 suite의 백그라운드 retention scheduler 로그가 테스트 시작 시 남았으나 테스트 실패는 없었다. 이번 테스트에서는 해당 cleanup cron을 비활성화했다.

## 실제 화면 수동 확인 순서

1. 로컬/스테이징 스키마 준비 후 기존 SYSTEM_ADMIN 계정으로 로그인한다. 개발자 → GuildUp 공지 관리 → 공지 작성으로 들어간다.
2. 일반 공지를 공개로 저장하고, 중요+고정+팝업 공지도 하나 작성한다. 5개 유형, 수정/삭제, 비공개 기본값과 모든 입력 필드를 확인한다.
3. 일반 계정으로 커뮤니티 선택 전 화면에 로그인한다. 중요 팝업을 확인하고 새로고침한다. 같은 팝업이 다시 뜨지 않고 헤더 미읽음 수/목록 읽음 표시가 줄어드는지 확인한다.
4. 헤더 알림에서 유형·제목·작성일·읽음 상태와 최근 최대 5개, 전체 공지 보기 → `/announcements` 이동을 확인한다. 커뮤니티 안에서도 같은 경로에 접근한다.
5. 고정/중요가 상단인지 확인한다. 상세를 열고 목록으로 돌아온다. 읽음과 미읽음 수가 갱신되고 NEW는 남는지 확인한다.
6. 새 공지를 추가해 기존 일반 계정에서도 자동으로 미읽음이 증가하는지 확인한다. 60초 또는 알림 열기/창 활성화로 새 알림을 갱신한다.
7. 미래 시작/지난 종료/비공개 공지를 각각 만든다. 일반 목록·알림·팝업·직접 상세 주소에는 보이지 않고, 관리자에는 예약/종료/비공개로 남는지 확인한다.
8. 일반 계정 및 커뮤니티 OWNER/ADMIN으로 `/developer/announcements`와 관리 API를 직접 요청한다. 조회/작성/수정/삭제가 403인지 확인한다. 로그아웃 상태 API는 401이다.
9. 320~390px 작은 화면에서 Badge 줄바꿈, 긴 제목/본문, 알림 스크롤, 관리 폼과 팝업 확인 버튼을 확인한다.
10. 기존 커뮤니티 공지/이벤트를 조회·작성·수정하고 로그인/Discord/PUBG/빙고/킬내기/랭킹 및 기존 개발자 메뉴를 확인한다.

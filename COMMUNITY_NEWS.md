# GuildUp 공지 · 이벤트

커뮤니티 자체의 공지와 이벤트 일정 관리 기능이다. Community와 GuildUp User를 직접 참조하며 Discord 연결, API, JDA, Bot 전송을 사용하지 않는다. 참가 신청 등 후속 기능은 포함하지 않는다.

## 기존 구조 재사용

- `CommunityAccessService.requireAccess`: 커뮤니티 가입 여부 검증.
- `CommunityAccessService.requireManagementAccess`: OWNER/ADMIN 관리 권한 검증.
- `CurrentUserSession.requireUserId`: 기존 로그인 세션에서 사용자 식별.
- `CommunityAccessInterceptor`: 기존 `/api/communities/**` 경로 보호.
- 기존 `Clock` Bean과 `Instant`, JPA Repository, 서비스 트랜잭션, record DTO, `ResponseStatusException` 패턴 재사용.
- React의 pathname → Page 매핑, `communityId` 쿼리, `api`/`redirectToLogin`, `DashboardLayout`, `Sidebar`, 공통 CSS 토큰·카드·탭·폼 스타일 재사용.

## 변경 파일

아래 경로는 프로젝트 루트 기준이며, 작업 시작 전에 존재하던 Discord 음성 활동 관련 변경은 포함하지 않는다.

| 구분 | 파일 |
| --- | --- |
| 수정 | `frontend/src/App.jsx` |
| 수정 | `frontend/src/components/Sidebar.jsx` |
| 수정 | `frontend/src/pages/CommunityDashboardPage.jsx` |
| 추가 | `frontend/src/pages/CommunityNewsPage.jsx` |
| 추가 | `frontend/src/components/CommunityNewsForm.jsx` |
| 추가 | `frontend/src/components/CommunityNewsMeta.jsx` |
| 추가 | `frontend/src/components/CommunityNewsSummary.jsx` |
| 추가 | `frontend/src/communityNews.js` |
| 추가 | `frontend/test/communityNews.test.js` |
| 추가 | `src/main/java/com/guildup/community/domain/CommunityNotice.java` |
| 추가 | `src/main/java/com/guildup/community/domain/CommunityEvent.java` |
| 추가 | `src/main/java/com/guildup/community/domain/CommunityEventType.java` |
| 추가 | `src/main/java/com/guildup/community/domain/CommunityEventStatus.java` |
| 추가 | `src/main/java/com/guildup/community/repository/CommunityNoticeRepository.java` |
| 추가 | `src/main/java/com/guildup/community/repository/CommunityEventRepository.java` |
| 추가 | `src/main/java/com/guildup/community/service/CommunityNoticeService.java` |
| 추가 | `src/main/java/com/guildup/community/service/CommunityEventService.java` |
| 추가 | `src/main/java/com/guildup/community/service/CommunityNewsSummaryService.java` |
| 추가 | `src/main/java/com/guildup/community/service/CommunityNewsValidation.java` |
| 추가 | `src/main/java/com/guildup/community/controller/CommunityNoticeController.java` |
| 추가 | `src/main/java/com/guildup/community/controller/CommunityEventController.java` |
| 추가 | `src/main/java/com/guildup/community/controller/CommunityNewsSummaryController.java` |
| 추가 | `src/main/java/com/guildup/community/dto/CommunityNoticeRequest.java` |
| 추가 | `src/main/java/com/guildup/community/dto/CommunityNoticeResponse.java` |
| 추가 | `src/main/java/com/guildup/community/dto/CommunityEventRequest.java` |
| 추가 | `src/main/java/com/guildup/community/dto/CommunityEventResponse.java` |
| 추가 | `src/main/java/com/guildup/community/dto/CommunityNewsSummaryResponse.java` |
| 추가 | `src/main/java/com/guildup/community/exception/CommunityNewsExceptionHandler.java` |
| 추가 | `src/main/resources/db/manual/add_community_news.sql` |
| 추가 | `src/test/java/com/guildup/community/CommunityNewsFlowTests.java` |
| 추가 | `COMMUNITY_NEWS.md` |

## DB

기존 PostgreSQL 수동 DDL 관리 방식을 유지한다. Flyway/Liquibase를 새로 도입하지 않았다. 기존 `docs/`는 `.gitignore`에서 제외되므로 배포용 SQL은 버전 관리 가능한 `src/main/resources/db/manual/add_community_news.sql`에 둔다. 이 SQL은 자동 실행되지 않는다.

현재 `spring.jpa.hibernate.ddl-auto=update` 환경에서는 앱 시작 시 엔티티를 통해 테이블/인덱스를 생성한다. 운영 환경에서 스키마를 수동 관리하는 경우 위 SQL을 적용한다. 이번 작업에서 실제 PostgreSQL/운영 DB에는 적용하지 않았다.

| 테이블 | 컬럼 |
| --- | --- |
| `community_notices` | `id BIGSERIAL`, `community_id BIGINT`, `author_id BIGINT`, `title VARCHAR(200)`, `content TEXT`, `is_important BOOLEAN`, `is_pinned BOOLEAN`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ` |
| `community_events` | `id BIGSERIAL`, `community_id BIGINT`, `author_id BIGINT`, `title VARCHAR(200)`, `content TEXT`, `type VARCHAR(30)`, `start_at TIMESTAMPTZ`, `end_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ` |

모든 컬럼은 NOT NULL이다. 이벤트 설명 생략 시 빈 문자열을 저장한다. 커뮤니티 FK는 `communities(id)`를 참조하고 커뮤니티 삭제 시 CASCADE한다. 작성자 FK는 `users(id)`를 참조한다. 작성자를 다른 사용자로 변경하는 API는 없다.

- 공지 인덱스: `(community_id, is_pinned DESC, created_at DESC, id DESC)`.
- 이벤트 인덱스: `(community_id, start_at, id)`, `(community_id, end_at)`.
- 이벤트 날짜 CHECK: `end_at >= start_at`.
- 이벤트 상태는 DB에 중복 저장하지 않는다. 요청 시 UTC Clock으로 시작 전 UPCOMING(예정), 시작~종료 시각 포함 ONGOING(진행 중), 종료 후 ENDED(종료)를 계산한다. 열어 둔 화면은 재조회 시 최신 상태가 반영된다.

## API

모든 API는 기존 로그인 세션을 사용한다. 목록은 기존 API 스타일처럼 배열로 반환한다.

| Method | 경로 | 동작 |
| --- | --- | --- |
| GET | `/api/communities/{communityId}/notices` | 공지 목록 |
| GET | `/api/communities/{communityId}/notices/{id}` | 공지 상세 |
| POST | `/api/communities/{communityId}/notices` | 공지 생성, 201 |
| PUT | `/api/communities/{communityId}/notices/{id}` | 공지 수정, 200 |
| DELETE | `/api/communities/{communityId}/notices/{id}` | 공지 삭제, 204 |
| GET | `/api/communities/{communityId}/events` | 이벤트 목록 |
| GET | `/api/communities/{communityId}/events/{id}` | 이벤트 상세 |
| POST | `/api/communities/{communityId}/events` | 이벤트 생성, 201 |
| PUT | `/api/communities/{communityId}/events/{id}` | 이벤트 수정, 200 |
| DELETE | `/api/communities/{communityId}/events/{id}` | 이벤트 삭제, 204 |
| GET | `/api/communities/{communityId}/news-summary` | 대시보드 공지/다가오는 이벤트 각각 최대 3개 |

공지 POST/PUT 본문:

```json
{"title":"클랜 규칙 안내","content":"본문","important":true,"pinned":true}
```

이벤트 POST/PUT 본문:

```json
{"title":"1주년 내전","content":"이벤트 설명","type":"CLAN_MATCH","startAt":"2026-09-28T11:00:00Z","endAt":"2026-09-28T14:00:00Z"}
```

제목은 필수 1~200자, 공지 본문은 필수 1~20,000자, 이벤트 설명은 선택 20,000자 이하이다. 제목/본문 앞뒤 공백은 제거한다. 이벤트 종류·시작·종료는 필수이며 종료가 시작보다 빠르면 400이다. 동일 시작/종료는 허용한다.

종류는 GENERAL(일반), CLAN_MATCH(내전), ACTIVITY(활동 이벤트), LOTTERY(추첨), ETC(기타)이다.

응답 공통 필드는 `id`, `communityId`, `authorId`, `authorName`, `title`, `content`, `createdAt`, `updatedAt`이다. 공지는 `important`, `pinned`, 이벤트는 `type`, `startAt`, `endAt`, `status`가 추가된다.

공지 정렬은 고정 → 작성일 내림차순 → ID 내림차순이다. 이벤트는 진행 중/예정 이벤트를 시작일 오름차순으로 먼저 표시하고 종료 이벤트를 최근 시작일 순으로 뒤에 표시한다.

## 권한과 오류

OWNER/ADMIN은 모든 CRUD가 가능하고 MEMBER는 목록·상세·요약 조회만 가능하다. 서비스 메서드에서도 권한을 검사하므로 UI와 무관하게 쓰기를 차단한다. 작성자와 커뮤니티는 요청 본문이 아닌 검증된 로그인 사용자의 멤버십에서 결정한다.

상세·수정·삭제는 `findByIdAndCommunityId`로 조회하여 두 커뮤니티의 OWNER인 경우에도 URL의 커뮤니티와 게시물이 다르면 404가 된다. 커뮤니티 미가입은 403, 미로그인은 401이다. 신규 컨트롤러 전용 예외 처리와 프론트 오류 매핑으로 사용자용 메시지를 표시한다. 본문은 React의 일반 텍스트 렌더링을 사용하며 HTML을 실행하지 않는다.

## 프론트와 대시보드

- 사이드 메뉴: `공지 · 이벤트`, Discord 연결 여부와 무관하게 노출.
- 공지: `/community-news.html?communityId=1&tab=notices`.
- 이벤트: `/community-news.html?communityId=1&tab=events`.
- 상세: 각 주소에 `&id=게시물ID` 추가. 작성/수정은 페이지 내 폼.
- 활성 탭, 고정 공지 강조, 중요 배지, 이벤트 종류·상태 배지, 빈 목록, 로딩, 재시도 제공.
- OWNER/ADMIN에게만 작성·수정·삭제 버튼 표시. 삭제 전에 브라우저 확인창 표시.
- 날짜는 기기 현지 시각으로 입력/표시하고 API에는 UTC ISO 시각으로 전송.
- 대시보드 요약 카드: 고정 우선 최근 공지 최대 3개, 시작일이 현재 이후인 이벤트 최대 3개. 본문은 표시하지 않고 제목/날짜와 더보기·상세 링크 제공.
- 기존 React만 수정했으며 Spring static HTML에는 중복 구현하지 않았다.

## 검증

- `./mvnw -q -Dtest=CommunityNewsFlowTests test`: 신규 통합 테스트 12개 통과.
- `./mvnw -q test`: 기존 테스트 포함 194개 통과, 실패/오류/스킵 0개.
- `cd frontend && npm test`: 25개 통과(신규 5개 포함).
- `cd frontend && npm run build`: Vite 프로덕션 빌드 성공.
- `git diff --check`: 통과.
- 통합 테스트는 H2에서 실행했다. 실제 PostgreSQL 대상 DDL 실행은 수행하지 않았다.
- 신규 테스트는 Discord 연결 없는 CRUD, 목록 정렬/범위, 일반 회원 쓰기 차단, 미가입·미로그인 차단, 다른 커뮤니티 ID 접근 차단, 입력 길이, 이벤트 날짜/종류 검증, 상태 경계, 요약 제한을 검증한다.
- 임시 로컬 API 데이터로 브라우저에서 공지 작성/수정, 이벤트 작성 후 상세와 현지 시간 표시, 활성 탭/상태 배지를 확인했다. 삭제 확인창 표시까지 확인했으며 확인창 이후 브라우저 자동화가 응답하지 않아 UI 삭제 완료 점검은 수행하지 않았다. 실제 삭제 API는 통합 테스트에서 검증했다.

## 추후 확장

공지 원본은 `CommunityNotice`에 유지한다. 향후 별도의 Discord 전송 서비스/어댑터가 권한 확인 후 저장된 공지를 읽어 선택한 채널로 보내도록 추가하면 된다. 공지 저장 성공 여부가 외부 전송 성공에 종속되지 않도록 별도 호출 또는 커밋 후 처리로 확장한다. 이번에는 외부 전송 코드나 관련 설정을 추가하지 않았다.

참가 신청은 필요할 때 `community_events.id`를 참조하는 `community_event_participants` 엔티티/테이블과 별도 서비스로 추가할 수 있다. 현재 Event에는 참가·팀·상품 처리를 넣지 않았다.

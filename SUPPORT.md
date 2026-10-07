# GuildUp 공용 문의 / 건의

작업 및 검증일: 2026-10-08. 운영 DB 변경, 운영 배포, 운영 서버 재시작은 수행하지 않았다.

## 1. 조사한 기존 구조

- 프론트엔드: `frontend/src/pages/FeedbackPage.jsx`, `feedbackForm.js`.
- 기존 경로: `/feedback.html?communityId=…`. `App.jsx`의 `CommunityRoutes` 안에 있어 `CommunityProvider`/`CommunityRouteGuard`의 커뮤니티 선택·가입 검사를 통과해야 했다.
- 메뉴: `Sidebar.jsx`의 공통 메뉴에 문의/건의 링크가 있었다. 프로필 메뉴에는 없었다.
- API: `POST /api/communities/{communityId}/feedback` 한 개뿐이었다.
- 백엔드: `feedback/controller/FeedbackController`, `service/FeedbackService`, `FeedbackMailService`, DTO 및 `FeedbackType` enum.
- 서비스는 커뮤니티 존재와 `CommunityAccessService.requireCommunityMember`를 확인했다. OWNER/ADMIN/MEMBER는 가능했지만 미가입자는 불가능했다.
- 기능 건의(FEATURE), 버그 제보(BUG), 기타 문의(ETC)를 검증하고 로그인 계정·커뮤니티·Discord 식별 정보와 함께 SMTP 메일을 보냈다.
- 문의 Entity/Repository/DB 테이블/내역 조회/상세/답변/상태 API는 없었다. 개발자 화면에도 문의 관리 기능은 없었다.
- 기존 사용자: `users`의 `User`, 로그인: 기존 `LOGIN_USER_ID` 세션. 개발자 권한: 기존 `SystemRole.SYSTEM_ADMIN`, `DeveloperAccessInterceptor`/`DeveloperAccessService`.
- DB는 PostgreSQL, 개발 설정은 `ddl-auto=update`, 기존 배포 SQL은 `src/main/resources/db/manual`에서 수동 적용하는 구조다. 자동 migration 프레임워크는 없다.
- 로컬 `localhost:5432/guildup`를 READ ONLY 트랜잭션으로 조사했다. public schema에서 feedback/inquiry/support 이름의 테이블 및 컬럼은 0건이었다. IDE에도 연결된 DB datasource가 없었다.
- 운영 DB와 실제 메일함은 조회하지 않았다. 저장소 구현 및 로컬 DB 기준으로 기존 문의 기록은 이메일이며, 운영 환경에 별도 비표준 테이블이 있는지는 검증 범위 밖이다.

## 2. 변경한 동작

- 프로필 메뉴에 `문의 / 건의`를 추가했다. 기존 메뉴 디자인, 키보드 이동, Escape/외부 클릭 닫기와 개발자 항목을 유지했다.
- 공용 `/support`를 `CommunityRoutes` 밖에 두었다. 커뮤니티 선택 화면/계정/커뮤니티 화면 어느 곳에서도 접근할 수 있다.
- 커뮤니티 사이드바에서 문의 메뉴를 제거했다.
- 예전 `/feedback.html` 주소도 동일한 공용 페이지로 열리며 커뮤니티 검사를 요구하지 않는다.
- 유형: 서비스 문의(SERVICE), 오류 신고(BUG), 기능 건의(FEATURE), 기타(ETC). 기존 FEATURE/BUG/ETC 코드값을 유지했다.
- 제목 100자, 내용 3000자와 기존 줄바꿈/필수 입력 검증을 유지했다.
- 사용자는 같은 페이지의 `내 문의 내역`에서 본인 문의 내용·처리 상태·답변만 볼 수 있다.
- 기본 상태는 RECEIVED. SYSTEM_ADMIN은 IN_PROGRESS/ANSWERED/CLOSED로 변경하고 답변을 저장할 수 있다. ANSWERED는 답변이 필수이며 답변은 3000자 이하이다.
- 기존 답변/상태 관리 기능은 없었다. 이번에 최소 관리 기능을 추가했다. 답변 이메일 발송은 추가하지 않았으며, 사용자가 본인 내역에서 확인한다.

## 3. 저장 구조 및 context

새 테이블 `feedbacks`:

| 컬럼 | 용도 / 제약 |
| --- | --- |
| id | 문의 PK, identity |
| user_id | 기존 users.id FK, NOT NULL, 계정 귀속 |
| author_nickname | 서버가 읽은 접수 당시 사용자 닉네임 |
| type / title / content | 문의 유형 / 제목 / 내용 |
| community_id / community_name | 선택적 접수 당시 커뮤니티 context, 모두 nullable |
| page_route | 이용하던 페이지의 경로만 저장, nullable |
| created_at / updated_at | 서버 Clock으로 기록한 시각 |
| status | RECEIVED / IN_PROGRESS / ANSWERED / CLOSED |
| answer / answered_at / answered_by_user_id | 답변 / 답변 시각 / 기존 답변자 계정 |
| version | JPA optimistic lock, 동시 수정·오래된 화면의 덮어쓰기 방지 |

- community_id는 Community 관계의 필수 FK가 아니라 선택적 참고 ID이다. FK/cascade를 두지 않아 커뮤니티가 삭제돼도 문의와 접수 당시 ID/이름이 남는다.
- 새 User 테이블이나 별도 인증 구조는 만들지 않았다.
- 프로필 메뉴를 누르기 전 페이지의 pathname과 선택적 communityId만 React Router state에 전달한다. 쿼리/해시는 전달하지 않는다.
- 직접 `/support`를 열면 page_route는 `/support`, community_id는 null이다. 직접 예전 주소를 열면 유효한 communityId는 참고 정보로 사용할 수 있다.
- communityId가 전달된 경우 서버가 현재 활성 멤버십을 확인해 실제 커뮤니티 ID/이름을 스냅샷으로 저장한다. 클라이언트 communityName/닉네임/사용자 ID는 신뢰하거나 저장하지 않는다.
- 커뮤니티 미가입, 탈퇴, 삭제, 잘못된 context ID는 등록을 차단하지 않고 community_id/name을 null로 저장한다.
- context의 URL에서 호스트·사용자정보·쿼리·해시를 제거한다. 허용한 route 형태가 아니거나 지나치게 길면 null로 저장한다.
- 자동 context에 Access/Refresh/Discord Token, 비밀번호, Authorization Header, Cookie, User-Agent를 수집하지 않는다. 기존 Discord ID/닉네임 메일 정보는 유지하며 토큰은 읽지 않는다.
- 사용자 탈퇴 후 조회에서는 작성자 닉네임을 기존 `User.WITHDRAWN_NAME`으로 표시한다. 기존 계정의 soft withdrawal 구조와 FK가 호환된다.

## 4. API와 개발자 화면

| API | 권한 / 동작 |
| --- | --- |
| POST /api/feedback | 활성 로그인 사용자 누구나 등록 |
| POST /api/communities/{communityId}/feedback | 기존 클라이언트 호환. 커뮤니티는 선택 context로만 처리 |
| GET /api/feedback?page=0&size=20 | 본인 작성 문의 목록만 조회 |
| GET /api/feedback/{id} | 본인 문의 상세만 조회. 타인 문의는 404 |
| GET /api/developer/feedback?page=0&size=20 | SYSTEM_ADMIN 전체 목록 |
| GET /api/developer/feedback/{id} | SYSTEM_ADMIN 상세 |
| PUT /api/developer/feedback/{id} | SYSTEM_ADMIN 답변/상태 관리. status, answer, version 전달 |

- 목록은 최신 접수 시각·ID 순, page >= 0, size 1~100으로 제한한다.
- 개발자 메뉴의 `문의 / 건의 관리`에서 `/developer/feedback`으로 이동한다.
- 목록: 유형, 제목, 작성자/사용자 ID, 접수 시각, 상태, 관련 커뮤니티.
- 상세: 문의 내용, 사용자 ID/닉네임, 접수 시각, 등록 당시 페이지, 선택적 커뮤니티, 답변.
- 개발자 API는 기존 interceptor와 서비스의 `requireSystemAdmin` 두 곳에서 보호한다. 커뮤니티 OWNER/ADMIN은 시스템 관리자 권한을 얻지 않는다.
- 기존 활성 사용자 검사와 세션 CSRF를 유지했다. 로그인하지 않거나 탈퇴한 사용자는 접수할 수 없다.
- SMTP 알림은 DB commit 이후 `FeedbackMailListener`에서 보낸다. 메일 전송 실패는 기존 `FeedbackMailService`가 기록하며, DB 접수는 유지되고 클라이언트에 SMTP 비밀정보가 노출되지 않는다.

## 5. 기존 기록 보존과 운영 적용

배포 전에 별도로 적용할 SQL: `src/main/resources/db/manual/add_global_feedback.sql`.

- 새 feedbacks 테이블과 인덱스를 생성하며 기존 users를 참조한다.
- 동일한 feedbacks 스키마에 기존 문의가 있으면 community_id 값은 그대로 두고 NOT NULL만 해제한다.
- DELETE/TRUNCATE/DROP TABLE, 기존 문의의 ID/제목/내용/communityId를 변경하는 UPDATE는 없다.
- 새 문의는 community_id/name을 null로 저장할 수 있다.
- 기존 SMTP 문의 이메일은 삭제·변경하지 않는다. 이메일에만 있던 기록을 자동으로 DB에 가져오지는 않으므로 새 개발자 목록에는 DB에 접수한 문의부터 표시된다.
- 운영 DB가 저장소와 다른 별도 문의 테이블을 가지고 있다면 이 SQL은 해당 테이블을 건드리지 않는다. 실제 운영 스키마에 대한 별도 대조가 필요하다.
- `add_account_withdrawal.sql`은 변경하지 않았다. 기존 users/탈퇴 구조와 인증·인가 정책을 재설계하지 않았다.
- 본 작업 중 SQL은 별도 로컬 테스트 DB `guildup_support_test_20261007`와 그 내부의 테스트 schema에만 적용했다. 원래 로컬 guildup DB는 읽기 전용 조사만 했다. 검증 후 격리 테스트 DB와 이번 작업에서 실행한 Vite 프로세스는 정리했다.
- 프론트엔드 프로덕션 빌드는 `/support/index.html`, `/developer/feedback/index.html`, 기존 `/feedback.html` 진입 파일을 생성한다. 운영 배포 시 새 정적 파일도 포함해야 한다.

## 6. 변경 파일

기존 파일 수정:

- `frontend/src/App.jsx`
- `frontend/src/components/Sidebar.jsx`
- `frontend/src/components/UserProfileMenu.jsx`
- `frontend/src/developer/DeveloperLayout.jsx`
- `frontend/src/feedbackForm.js`
- `frontend/src/pages/FeedbackPage.jsx`
- `frontend/src/styles/pages.css`
- `frontend/test/feedbackForm.test.js`
- `frontend/test/userProfileMenu.test.js`
- `src/main/java/com/guildup/community/config/CommunityWebConfig.java`
- `src/main/java/com/guildup/feedback/controller/FeedbackController.java`
- `src/main/java/com/guildup/feedback/domain/FeedbackType.java`
- `src/main/java/com/guildup/feedback/dto/FeedbackRequest.java`
- `src/main/java/com/guildup/feedback/exception/FeedbackExceptionHandler.java`
- `src/main/java/com/guildup/feedback/service/FeedbackService.java`
- `src/test/java/com/guildup/feedback/FeedbackFlowTests.java`
- `src/test/java/com/guildup/user/auth/SessionCsrfFlowTests.java`
- `README.md`

새 파일:

- `frontend/src/components/FeedbackHistory.jsx`
- `frontend/src/developer/DeveloperFeedbackPage.jsx`
- `frontend/test/supportPage.test.js`
- `src/main/java/com/guildup/feedback/controller/DeveloperFeedbackController.java`
- `src/main/java/com/guildup/feedback/domain/Feedback.java`
- `src/main/java/com/guildup/feedback/domain/FeedbackStatus.java`
- `src/main/java/com/guildup/feedback/dto/FeedbackManagementRequest.java`
- `src/main/java/com/guildup/feedback/dto/FeedbackResponses.java`
- `src/main/java/com/guildup/feedback/repository/FeedbackRepository.java`
- `src/main/java/com/guildup/feedback/service/FeedbackMailListener.java`
- `src/main/resources/db/manual/add_global_feedback.sql`
- `src/test/java/com/guildup/feedback/FeedbackPostgresTests.java`
- `SUPPORT.md`

## 7. 실행한 검증

| 테스트 | 결과 |
| --- | --- |
| FeedbackFlowTests | 19 통과 |
| FeedbackMailServiceTests | 2 통과 |
| FeedbackPostgresTests | 20 통과 (문의 flow 19 + 데이터 보존 migration 1) |
| SessionCsrfFlowTests | 14 통과 |
| PlatformAnnouncementFlowTests | 15 통과 |
| AccountWithdrawalTests | 16 통과 |
| DeveloperAccessAndQueryTests | 5 통과 |
| CommunityDeletionFlowTests | 4 통과 |
| 프론트엔드 npm test | 213 통과, 실패/skip 0 |
| npm run build | 성공, 정적 진입 경로 43개 검증 |
| git diff --check | 통과 |

백엔드 합계 95개 통과, 실패/오류/skip 0. 전체 백엔드 테스트를 전부 실행했다는 의미는 아니며 위 관련 suite를 실행했다.

실제 PostgreSQL 검증:

- 테스트 suite는 새 격리 DB만 사용했다.
- 기존 community_id가 있는 synthetic 문의를 생성하고 NOT NULL을 설정한 후 SQL을 두 번 적용했다.
- 문의 ID/내용/커뮤니티 ID·이름이 보존됨을 확인했다.
- 이후 community_id 없는 문의 저장 성공 및 information_schema의 is_nullable=YES를 확인했다.
- 빈 테스트 schema에서도 SQL로 테이블을 최초 생성한 뒤 재실행하고, 기존 문의 및 신규 null context 문의 두 행을 조회해 확인했다.

실제 브라우저 검증:

- 로컬 Vite + 실제 Chrome의 API fixture로 1280×900, 375×900, 320×900에서 확인했다. 운영 API에는 요청하지 않았다.
- 커뮤니티 없는 문의 등록, 사용자 드롭다운 위치/화면 경계, 개발자 답변·상태 저장을 확인했다.
- 페이지 document.scrollWidth가 화면 너비와 같아 가로 넘침이 없었고 pageerror는 0건이었다.
- 표는 좁은 화면에서 자체 영역을 가로 스크롤하며, 폼 버튼/상세 context는 모바일 레이아웃으로 배치된다.
- 스크린샷을 직접 검토했다. 스크린샷/결과는 임시 `/tmp/guildup-support-qa`에 두었으며 저장소에 브라우저 테스트 의존성을 추가하지 않았다.

요청 시나리오 대응:

| 시나리오 | 확인 |
| --- | --- |
| 1. 커뮤니티 미가입 로그인 사용자 등록 | H2/PostgreSQL flow + Chrome 등록 |
| 2. 커뮤니티 선택 화면 접근/등록 | profile → support UI 테스트 |
| 3. 커뮤니티 이용 중 등록 | profile context UI 테스트 + 실제 멤버 context 서버 테스트 |
| 4. communityId 없는 저장 | H2/PostgreSQL flow + Chrome 등록 |
| 5. 기존 communityId 있는 문의 조회 | PostgreSQL migration/기존 context 조회, 삭제 후 context 보존 |
| 6. 개발자 신규 문의 조회 | SYSTEM_ADMIN 목록/상세 테스트 |
| 7. 타인 문의 조회 차단 | 본인 목록 조건, 타인 상세 404, 개발자 API 403 |
| 8. 답변/상태 관리 | 신규 관리 API flow/UI/Chrome 검증. 기존 관리 기능은 없었음 |
| 9. 사이드바 메뉴 제거 | Sidebar 컴포넌트 테스트 |
| 10. 프로필 메뉴 접근 | profile 키보드/클릭 테스트, support 라우팅 테스트 |
| 11. 좁은 화면 레이아웃 | 375px/320px Chrome 스크린샷·overflow 검증 |

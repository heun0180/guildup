# GuildUp 헤더 프로필 메뉴

## 구현 범위

기존 헤더의 닉네임, 커뮤니티 이동, 개발자 이동, 로그아웃을 프로필 드롭다운으로 모았다.
공통 Dropdown 컴포넌트가 없어 기존 알림 메뉴의 닫힘 패턴과 디자인 변수, Avatar, Icon, AppLink를 사용했다.
새 라이브러리, API, DB 변경은 없다.

현재 소스의 종 컴포넌트는 `AnnouncementBell`이다. 이번 변경에서 종 컴포넌트, 알림 조회 API,
읽음 처리, 팝업 처리에는 변경이 없으며, 프로필 메뉴와 별도의 상태로 동작한다.
프로필 메뉴의 공지 항목은 게시판 이동 링크다.

## 변경 파일

| 파일 | 변경 내용 |
| --- | --- |
| `frontend/src/components/AppHeader.jsx` | 기존 인증/로그아웃 처리를 유지하며 종 오른쪽에 프로필 메뉴 연결 |
| `frontend/src/components/UserProfileMenu.jsx` | 새 프로필 드롭다운, 이동 링크, 관리자 조건, 키보드/닫힘 처리 |
| `frontend/src/components/HelpLayout.jsx` | 가이드로 돌아가는 기본 문구 변경 |
| `frontend/src/components/HelpLink.jsx` | 가이드 아이콘의 기본 안내 문구 변경 |
| `frontend/src/pages/HelpPages.jsx` | 가이드 첫 화면 제목, 메뉴 접근성 이름, PUBG 뒤로 가기 문구 변경 |
| `frontend/src/styles/layout.css` | 프로필 트리거/메뉴 스타일 및 모바일 대응, 이전 user-summary 스타일 정리 |
| `frontend/src/styles/announcements.css` | 이전 user-summary 모바일 규칙만 정리 |
| `frontend/test/userProfileMenu.test.js` | 프로필 메뉴와 헤더 회귀 테스트 12개 추가 |
| `HEADER_PROFILE_MENU.md` | 구현/검증/수동 확인 안내 |

## 메뉴와 라우트

1. 내 커뮤니티 → `/communities.html`
2. GuildUp 공지 → `/announcements`
3. GuildUp 가이드 → `/help`
4. 개발자 → `/developer` (`systemAdmin` 사용자에게만 기존 링크 유지)
5. 구분선 뒤 로그아웃 → 기존 `POST /api/auth/logout`, 성공 후 `/login.html`

개인 설정 페이지는 없고 커뮤니티 운영 설정만 있으므로 설정 항목을 노출하지 않는다.
공지 NEW 표시는 기존 알림 응답의 `recent[].newAnnouncement` 값이 있을 때 표시한다.
클라이언트 타이머나 임시 읽음 저장은 추가하지 않았다. 프로필 메뉴를 여는 것만으로 API를 호출하지 않는다.

기존 가이드 경로 `/help/general`, `/help/attendance`, `/help/ranking`, `/help/pubg`,
`/help/pubg/bingo`, `/help/pubg/kill-competition`과 기능별 설명 내용은 유지한다.
서비스 안내 메뉴, 첫 화면 제목, 기본 가이드 아이콘 안내 문구와 뒤로 가기 문구를
`GuildUp 가이드`로 통일하고, 기능별 제목은 기존대로 유지한다.

## 동작

- 프로필 이미지/이니셜, 닉네임, 화살표 전체가 하나의 버튼이다.
- 다시 클릭, 바깥 클릭, ESC, 메뉴 항목 선택, 경로 변경, 포커스 이탈 시 닫힌다.
- ESC로 닫으면 프로필 버튼으로 포커스가 돌아간다.
- Enter/Space 또는 위/아래 방향키로 열고, 방향키/Home/End로 항목을 선택한다.
- Tab/Shift+Tab은 메뉴를 닫고 페이지의 일반 포커스 순서로 이동한다.
- 모바일에서는 헤더 닉네임을 숨기고 메뉴 안에 전체 닉네임을 표시한다.
- 우측 정렬과 메뉴 최대 너비/높이, 긴 닉네임 줄바꿈으로 작은 화면에서도 메뉴가 잘리지 않게 한다.
- 비로그인/공용 헤더에는 기존 가이드 링크만 유지한다.
- 새 백엔드 권한 처리는 없으며 개발자 API의 기존 SYSTEM_ADMIN 정책을 사용한다.

## 검증 결과

- `cd frontend && npm test`: 184개 통과 (신규 12개 포함), 실패/건너뜀 없음.
- `cd frontend && npm run build`: 성공, 프로덕션 라우트 39개 검증.
- `./mvnw -q -Dtest=DiscordLoginControllerTests,SessionCsrfFlowTests,DeveloperAccessAndQueryTests test`:
  29개 통과, 실패/건너뜀 없음.
- 로컬 빌드 + 임시 Mock API에서 실제 브라우저로 메뉴 열기, 바깥 클릭/ESC, 커뮤니티/공지/가이드 이동,
  종과 프로필 메뉴 전환, 로그아웃과 비로그인 화면, 긴 닉네임의 320px/390px 메뉴 표시 확인.
- 320px 검증에서 메뉴 자체는 화면 안에 표시된다. 기존 공통 CSS의 `html/body min-width: 320px` 때문에
  스크롤바가 너비를 차지하는 데스크톱 브라우저의 320px 뷰포트에는 기존 가로 스크롤이 남는다.
  이번 변경에서는 공통 최소 너비 정책을 변경하지 않았다.
- 실제 Discord 계정 로그인과 운영 데이터는 브라우저 테스트에 사용하지 않았다.

## 실제 화면 확인 순서

1. 비로그인 화면에서 프로필/로그아웃이 없고 GuildUp 가이드로 이동할 수 있는지 확인한다.
2. Discord로 로그인하여 커뮤니티 선택 화면에서 종과 프로필 버튼이 보이는지 확인한다.
3. 프로필 이미지, 닉네임, 화살표를 각각 클릭하고, 다시 클릭/바깥 클릭/ESC로 닫아 본다.
4. Tab으로 프로필 버튼에 접근한 뒤 Enter/방향키/Home/End/ESC/Tab을 사용한다.
5. 내 커뮤니티 → GuildUp 공지 → GuildUp 가이드 순서로 이동하고 기존 설명 링크도 확인한다.
6. 커뮤니티 내부에서 같은 프로필 메뉴와 기존 종을 각각 열어 기존 알림 동작을 확인한다.
7. 일반 사용자에게 개발자가 없고 SYSTEM_ADMIN에게 개발자가 보이며 기존 개발자 기능이 열리는지 확인한다.
8. 작은 화면과 긴 닉네임에서 종/프로필 버튼과 드롭다운이 잘리는지 확인한다.
9. 마지막으로 로그아웃하고 로그인 화면으로 돌아오며 사용자 메뉴가 사라지는지 확인한다.

운영 서버/DB에 접속하지 않았다. 이번 헤더 작업에는 별도 SQL 적용이 필요 없다.

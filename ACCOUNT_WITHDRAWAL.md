# GuildUp 회원탈퇴 관계 조사와 정책

구현 전 소스 조사: Entity, Repository, Service, Controller, `src/main/resources/db/manual/*.sql`.
운영 DB를 조회하지 않았다. 실제 운영의 추가 FK/트리거는 이 조사에 포함되지 않는다.

| 대상 | 실제 관계 / 제약 | 탈퇴 처리 |
|---|---|---|
| users | nickname NOT NULL, UNIQUE 없음. system_role. 자식 삭제 cascade 없음 | id 유지, WITHDRAWN/withdrawn_at, 이름 익명화, 시스템 권한 제거 |
| user_credentials | user_id 1:1 UNIQUE, 정규화 email UNIQUE, users 삭제 시 DB CASCADE | 행 삭제. 이메일/hash/email_verified 제거. 인증메일/재설정 토큰은 아직 없음 |
| user_external_accounts | (user,provider), (provider,external_id) UNIQUE, users 삭제 시 DB CASCADE | 모든 로그인 연결 삭제, 재가입은 새 User |
| Discord OAuth 임시 결과 / state | 메모리 결과 TTL 5분, state TTL 10분, 세션 바인딩 | 커밋 후 연결된 Discord id의 프로필 결과를 모든 커뮤니티/생성 흐름에서 제거. 세션 인증 문맥은 invalidate로 폐기 |
| community_users | (community,user) UNIQUE. user/community 삭제 시 DB CASCADE. member 연결 nullable UNIQUE, member 삭제 시 SET NULL | 행 보존, ended_at 설정, member 연결 NULL. 목록/인원/권한은 종료 행 제외 |
| community_members | User FK 없음. community 삭제 시 CASCADE. ACTIVE/LEFT 클랜원 상태 | 삭제/LEFT 전환하지 않음. 연결된 기록의 nickname 익명화, anonymized 표시. 랭킹 대상과 점수 유지 |
| community_member_accounts | member/community FK CASCADE. PUBG는 platform 포함 UNIQUE, Discord는 별도 partial UNIQUE | 연결된 클랜원의 Discord 식별자 대체 및 프로필 제거. PUBG 계산 식별자는 보존, 표시 프로필 제거 |
| pubg_bingo_events / participants | 생성자/참가자는 community_users NOT NULL FK, 참가자에 선택적 member FK와 PUBG 스냅샷. (event,membership) UNIQUE | 멤버십/참가자/진행/완성/처리 소스 유지. 비식별 이름 표시. 종료 멤버십 자동 신규 참가 제외 |
| pubg_kill_competitions / participants / results | 생성자/참가자는 community_members FK. team/member/PUBG 스냅샷, 결과/합계 저장 | 행/팀/점수/순위 그대로. 이름과 공개 PUBG 별칭 익명화. 재정산 호출 없음 |
| community_attendances / scores / score_history | community_member FK, member 삭제 시 CASCADE | 행/점수/참조/날짜 유지. ACTIVE 조건 랭킹에서 누락시키지 않음 |
| Discord voice sessions | users/member FK 없음, (community,discord_user,open_marker) UNIQUE | 종료 시각은 확정된 행 그대로. Discord 식별자를 비식별 키로 바꾸고 같은 키의 member account로 조회 보존. 열린 구간은 탈퇴 시 닫음 |
| PUBG match facts / activity snapshots / match players | member/account/game 또는 PUBG account 식별자 참조. match->roster/player, activity->match/player의 ALL/orphanRemoval | 부모/자식 삭제하지 않음. 계산 식별자/원본 경기 사실은 보존, 연결된 클랜원의 공개 표시 마스킹 |
| community_posts / comments | author_community_user_id NOT NULL FK. post 삭제 시 comments CASCADE | 게시물/댓글 보존, 작성자 users.nickname 익명화 |
| notices / events / platform announcements | author_id 또는 created_by -> users NOT NULL | 내용/일정 보존, 작성자 이름 익명화 |
| platform_announcement_reads | (announcement,user) UNIQUE, users 삭제 시 CASCADE | 개인 읽음 상태 삭제 |
| 문의/건의 | FeedbackService가 SMTP 메일 발송, 별도 DB Entity 없음 | 이미 발송된 메일은 앱 DB로 회수 불가. 새 개인정보 복제 없음 |
| 운영/개발자 로그 | monitoring_events.user_id nullable scalar, FK 없음. metadata와 제한 메모리/외부 파일 로그 | 과거 운영 기록 보존, 새 USER_WITHDRAWN 이벤트는 id/시각만. 과거 외부 로그/메일/백업의 별도 보관 정책 필요 |
| 커뮤니티 생성 요청 | community.creation_request_key에 user id와 요청 키, hash | 커뮤니티 운영 기록으로 보존, 로그인 증거로 사용하지 않음 |

`users` 또는 `community_users` 삭제는 필수 과거 FK와 충돌하며 `community_members` 삭제는
출석/점수/외부계정 및 다른 기록의 cascade 위험이 있다. 따라서 세 행 모두 물리 삭제하지 않는다.
credential/external 계정은 자식이며 삭제 시 User/member/event로 올라가는 JPA cascade가 없다.

OWNER 커뮤니티를 모두 응답에 제공하고 최종 트랜잭션에서도 재검증한다. 현재 소유권 이전 API는
없으므로 기존 커뮤니티 삭제 기능으로 소유권 정리가 가능하며 탈퇴 API가 임의로 이전하지 않는다.

비밀번호가 있으면 비밀번호 재검증, 없으면 목적/사용자/세션/state에 바인딩한 Discord OAuth
재승인(`identify`, `prompt=consent`)과 연결된 Discord id 일치를 검증한다. 검증 증거는 세션에
5분간 저장하며 다른 로그인/세션으로 재사용할 수 없다. Discord 재승인은 Discord 비밀번호 입력을
강제하는 기능은 아니다. credential 보유자는 Discord 방식으로 비밀번호 검증을 우회할 수 없다.

핵심 DB 변경은 한 트랜잭션에서 User 행 잠금 아래 수행한다. 세션 invalidate/감사 이벤트는
커밋 후 수행한다. 같은 서버에서 등록된 다른 로그인 세션도 즉시 invalidate하며 만료 세션은
컨테이너 listener로 추적에서 제거한다. 다른 서버/배포 전 잔존 세션도 모든 API 진입 시 ACTIVE
검사로 차단/무효화한다. 개발자 로그 SSE는 세션 무효화를 감지해 중단한다. 기존 CSRF
인터셉터와 클라이언트를 사용하며 예외를 추가하지 않는다.

재가입은 이전 사용자/멤버십/점수 복구가 없다. 익명화된 클랜원 Discord 식별자는 실제 Discord id와
일치하지 않아 봇 동기화/가입에서도 과거 클랜원에 자동 연결하지 않는다. 향후 수집되는 Discord
활동은 독립적인 새 클랜원 자료가 될 수 있다. PUBG 원본/계산 식별자의 완전 삭제는 결과 보존과
별개 기능이며 이 작업에서 수행하지 않는다.

DB 방식은 개발 `ddl-auto=update` + PostgreSQL 수동 SQL이다. 배포 전에
`add_account_withdrawal.sql`을 실행해야 한다. 기존 users/member/이벤트 데이터를 삭제하지 않는다.
클랜원/외부계정/음성 세션의 `version` 열은 탈퇴와 동시에 진행된 동기화가 오래된 객체로
개인정보를 복원하는 것을 차단한다. 사용자 행 잠금은 OSIV 캐시까지 refresh해서 상태를 재검증한다.

## API와 화면

- `GET /api/account/withdrawal/check`: 본인 확인 방식/증거 유효 여부/모든 OWNER 커뮤니티 반환.
- `POST /api/account/withdrawal/verify`: 현재 비밀번호 검증. 증거는 세션에만 저장.
- `POST /api/auth/discord/withdrawal`: CSRF 검증 후 별도 WITHDRAWAL OAuth 시작.
- `GET /api/auth/discord/callback`: 일회용 state/현재 사용자/연결 Discord 계정 검증. 탈퇴를 자동 실행하지 않음.
- `DELETE /api/account`: 안내 확인 + 본인 확인 + OWNER 재검증 후 원자적 탈퇴.
- `account.html`: 위험 영역 → 안내 체크박스 → 본인 확인 → 최종 확인. 오류/OWNER 목록/만료 처리.

## 검증 범위

`AccountWithdrawalTests`는 실제 H2 FK/트랜잭션과 MockMvc 인증/CSRF를 사용한다.
MEMBER/ADMIN, 여러 OWNER와 소유권 정리/삭제, credential/외부 계정 삭제, 이메일/Discord 재가입,
미검증/오입력/만료/다른 세션과 사용자/CSRF 누락/잘못된 state/재사용 거부, 반복/동시 탈퇴,
활성 사용자 기능, DB 강제 실패의 전체 롤백, 모든 등록 세션 즉시 종료,
오래된 동기화 객체의 개인정보 복원 차단을 검증한다.

실제 완료된 빙고 진행/완성, 킬내기 참가/팀/최종 점수/경기 결과, 랭킹, 출석, 게시글/댓글,
Discord 음성 이력을 만든 뒤 탈퇴 전후 값과 API 응답을 비교한다.
`SessionCookieIntegrationTests`와 prod 프로필의 상속 테스트는 실제 Tomcat에서 이전 JSESSIONID,
다른 브라우저 세션, 반복 DELETE를 검사한다.
`AccountWithdrawalPostgresTests`는 같은 검증과 레거시 스키마의 수동 SQL 반복 실행을 실제
PostgreSQL에서 수행한다. 반드시 독립 테스트 DB가 필요하다.
프론트 테스트는 단계별 확인, 비밀번호 오류, OWNER 목록, Discord 인증 및 돌아온 후 자동 탈퇴
방지를 확인한다. 전체 프론트 테스트/배포 빌드와 브라우저 시각 검사도 수행한다.

## 배포 작업

기존 `add_user_credentials.sql` 적용 여부를 확인한 뒤, 배포 전에 PostgreSQL에서
`src/main/resources/db/manual/add_account_withdrawal.sql`을 실행한다. 수동 SQL은 트랜잭션으로
열/기본값/제약/인덱스만 추가하며 기존 행을 삭제하거나 초기화하지 않는다.
`psql -v ON_ERROR_STOP=1 -f src/main/resources/db/manual/add_account_withdrawal.sql` 형태로 기존
배포 절차의 DB 연결 설정을 사용한다. 이 작업에서는 운영 DB를 조회/수정하지 않았다.

이미 발송된 문의 메일, 외부 로그/백업, 사용자 작성 본문의 개인정보는 이 계정 탈퇴 코드로
자동 회수할 수 없다. PUBG 원본 경기 사실과 계산용 account id는 기록 보존을 위해 내부에 남는다.

## 2026-10-07 최종 결과

- 백엔드 전체 `./mvnw -q test`: 969건 중 776 통과, 193 환경 조건으로 건너뜀, 실패/오류 0.
  운영/외부 DB 환경변수를 제거하고 실행했다. 건너뛴 PostgreSQL/덤프 기반 검증까지 통과했다고
  주장하지 않는다.
- 새 회원탈퇴 PostgreSQL 검증: 별도 임시 로컬 클러스터에서 17건 통과, 실패/오류 0.
  기존 운영 DB나 개발 DB를 사용하지 않았다. 레거시 스키마 SQL 반복 실행도 포함한다.
- 프론트 `npm test`: 198건 통과, 실패 0.
- `npm run build`, `npm run verify:build`: 성공, 41 경로 검증.
- 테스트 데이터로 실제 브라우저의 계정 설정/위험 영역/안내/비활성 다음 버튼을 시각 확인했다.
- `git diff --check`: 성공.

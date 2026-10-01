# 커뮤니티 랭킹 기간 설정

## 분석 및 설계

- `community_attendances` / `CommunityAttendance`: community_id, community_member_id, 서울 출석 인정일 attendance_date, created_at. 멤버·날짜 UNIQUE로 하루 한 번만 기록한다.
- `community_score_history` / `CommunityScoreHistory`: 커뮤니티·클랜원, score_change, score_type, reference_type/id, created_at. created_at은 기존 코드에서 실제 점수 지급 Instant를 전달하는 발생 시각이다. (멤버, 점수 유형, 참조 유형, 참조 ID) UNIQUE가 중복 지급을 방지한다.
- `community_member_scores` / `CommunityMemberScore`: 기존 랭킹 조회용 누적 총점. 원장과 같은 트랜잭션에서 갱신된다.
- `community_users`: 로그인 사용자의 OWNER/ADMIN/MEMBER 권한. 점수 대상인 `community_members`와 구별한다. 모든 랭킹 조회·조인에 커뮤니티 조건을 적용한다.
- 출석 → 멤버 잠금 → 서울 날짜별 중복 검사 → 출석 저장 → +1점 원장 → 누적 총점. 날짜와 지급 시각에 단일 clock.instant()를 사용하도록 보완했다.
- 킬내기 → 최종 결과 확정 트랜잭션 → 승인 참가자 4명 이상일 때 적격 우승자 +3점 → 원장·누적 총점. 기준 시각은 기존 completedAt을 유지한다. 멤버 잠금, 대회별 중복 검사, 서울 날짜별 하루 지급 한도를 유지한다.
- 기존 랭킹은 누적 총점, ACTIVE 클랜원, 총점 내림차순·멤버 ID 오름차순이다. ALL_TIME은 이를 유지한다. 항목별 점수만 원장에서 추가 집계한다.
- MONTHLY/QUARTERLY는 출석 원장의 연결된 실제 attendance_date를 사용한다. 연결 출석이 없는 원장은 이미 기록된 created_at을 사용하며 날짜를 만들어내지 않는다. 킬내기·기타 활동은 created_at을 사용한다.
- 활동 원장은 DB에서 SUM/GROUP BY 한다. Java에는 최종 랭킹 행과 표시용 Discord 계정만 가져온다. 출석 기간 조회는 기존 (community_id, attendance_date), 시각 기간 조회는 신규 (community_id, created_at, community_member_id) 인덱스를 사용한다.
- 새 점수 이벤트/스냅샷/설정 이력 테이블은 추가하지 않는다. 설정은 현재 표시 정책이며 점수 리셋 스케줄러가 없다.

## DB 변경 및 운영 적용

프로젝트는 Flyway/Liquibase 대신 `src/main/resources/db/manual` 수동 SQL을 사용한다. 새 컬럼은 `communities.ranking_period_type VARCHAR(20) NOT NULL DEFAULT 'ALL_TIME'`이다. 기존·신규 커뮤니티 모두 전체 누적을 기본값으로 한다. 값 CHECK와 원장 집계 인덱스를 추가한다. 기존 점수·원장·출석 행은 수정/삭제하지 않는다.

**실제 운영 PostgreSQL에 백엔드 배포 전에 아래 SQL을 반드시 적용한다.** 엔티티에도 DB DEFAULT ALL_TIME을 명시해 로컬 ddl-auto=update가 기존 행이 있는 테이블을 안전하게 확장하도록 보완했지만, 운영 배포에는 명시적인 SQL 적용을 유지한다. DB 연결 정보는 실제 운영 주소/계정으로 설정한다.

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f src/main/resources/db/manual/add_community_ranking_periods.sql
```

`DATABASE_URL`은 psql 형식 `postgresql://...` 연결 문자열이다(JDBC URL 아님). 기존 점수 기능 SQL이 이미 적용된 DB를 전제로 한다. 파일은 트랜잭션으로 적용하고 재실행 가능하다. 대형 원장에서는 인덱스 생성 시간 동안 쓰기 잠금이 발생하므로 배포 작업 시간에 실행한다.

적용 확인:

```sql
SELECT ranking_period_type, count(*) FROM communities GROUP BY ranking_period_type;
SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_community_score_history_community_created';
```

## 기존 데이터 및 과거 조회 범위

원장·출석일·킬내기 지급 시각이 이미 있으므로 이벤트 데이터 백필은 필요하지 않다. 설정 컬럼 추가 시 기존 행은 ALL_TIME으로 보존된다. 실제 서비스 DB 내용을 임의로 읽거나 마이그레이션하지 않았다.

운영 DB에서 다음 읽기 전용 쿼리로 원장 없는 누적 차이를 확인할 수 있다. 차이가 있으면 ALL_TIME은 기존 누적 총점을 표시하고, 기간별 랭킹은 날짜가 기록된 원장 점수만 표시한다. 불일치 점수의 날짜나 활동 종류를 추정해서 생성하지 않는다.

```sql
SELECT s.community_id, s.community_member_id, s.total_score,
       coalesce(h.total, 0) AS history_score,
       s.total_score - coalesce(h.total, 0) AS difference
FROM community_member_scores s
LEFT JOIN (
    SELECT community_id, community_member_id, sum(score_change) AS total
    FROM community_score_history GROUP BY community_id, community_member_id
) h ON h.community_id = s.community_id AND h.community_member_id = s.community_member_id
WHERE s.total_score <> coalesce(h.total, 0);
```

출석 원장 중 원본 출석이 없는 경우 확인:

```sql
SELECT h.community_id, h.community_member_id, h.id, h.created_at
FROM community_score_history h
LEFT JOIN community_attendances a ON a.id = h.reference_id
    AND a.community_id = h.community_id AND a.community_member_id = h.community_member_id
    AND h.reference_type = 'ATTENDANCE'
WHERE h.score_type = 'ATTENDANCE' AND a.id IS NULL;
```

과거 점수는 보존된 원장에서 재계산한다. 기존 킬내기 삭제는 지급 점수 원장과 총점을 되돌리는 기능이며 이를 유지한다. 현재 ACTIVE 클랜원과 현재 닉네임을 표시하므로 탈퇴·닉네임 변경·대회 삭제 이후 당시 화면의 불변 결과를 복원하지는 않는다. 삭제된 원장이나 과거 멤버 상태 이력이 없는 경우 당시 결과의 완전한 복원은 불가능하다. 이는 월/분기 변경에 의한 초기화와 별개이며, 이번 SQL/정책 변경은 어떤 점수도 삭제하지 않는다.

## API 및 권한

- 기존 `GET /api/communities/{communityId}/rankings` 유지. 쿼리 없이 현재 커뮤니티 정책의 현재 기간을 반환한다. 프론트가 periodType을 선택하지 않는다.
- 월간 과거 조회: `?year=2026&month=9`
- 분기 과거 조회: `?year=2026&quarter=3`
- year와 해당 정책의 month/quarter를 함께 지정해야 한다. 부적절한 조합/값, 미래 기간, ALL_TIME에서 기간 지정은 400이다. 조회 권한은 기존 커뮤니티 멤버십을 사용한다.
- 기존 `myRanking` 및 `rankings[].rank/memberId/nickname/score/me` 유지. `rankings[].attendanceScore/killCompetitionScore`, 응답 `period` 추가.
- period: periodType, year, month, quarter, startDate, endDate, title, current. startDate/endDate는 서울 달력의 화면 표시용 포함 날짜다. ALL_TIME 날짜 필드는 null이다.
- `GET /api/communities/{communityId}/ranking-settings`: 커뮤니티 구성원 조회.
- `PUT /api/communities/{communityId}/ranking-settings`, JSON `{"periodType":"MONTHLY"}`: OWNER/ADMIN만 가능. MEMBER 및 외부인은 403. null/잘못된 enum은 400.
- 출석 API 응답의 currentScore는 기존대로 전체 누적 점수이며 기간 랭킹의 내 점수와 구별한다.

## 기간 계산

`Clock`에서 읽은 Instant를 `ZoneId.of("Asia/Seoul")`로 변환해 현재 월/분기를 결정한다. LocalDate 첫날 00:00 KST를 Instant로 변환하며 조회는 `[start, end)`이다. JVM 또는 DB 세션 기본 시간대에 의존하지 않는다.

- 2026년 9월: 2026-09-01 00:00 KST 이상, 2026-10-01 00:00 KST 미만. UTC 기준 2026-08-31T15:00Z ~ 2026-09-30T15:00Z 미만.
- 2026년 3분기: 2026-07-01 00:00 KST 이상, 2026-10-01 00:00 KST 미만. UTC 기준 2026-06-30T15:00Z ~ 2026-09-30T15:00Z 미만.
- 분기 시작월: `((month - 1) / 3) * 3 + 1`.
- UI 종료일은 exclusive endDate의 전날을 표시한다. 출석 인정일은 DATE 자체에 동일한 시작일 이상/다음 기간 첫날 미만 조건을 적용한다.

## 프론트 및 배포

React `CommunitySettingsPage`의 기존 설정 화면에 랭킹 설정 라디오/저장을 추가했다. `RankingsPage`에는 기간 제목/날짜, 이전·다음·현재 기간 버튼, 출석·킬내기 항목 점수를 표시한다. 미래 이동은 UI와 API 모두 차단한다. 내 점수는 선택 기간의 랭킹 점수, 오늘 출석 카드 점수는 누적 점수로 표시한다. 기존 디자인 변수/패널/버튼/CSS를 사용한다. Spring 레거시 `static/rankings.html`도 같은 조회 기능을 제공한다. React 도움말도 변경했다.

SQL 적용 후:

```bash
./mvnw clean package
cd frontend
npm ci
npm run build
npm run verify:build
```

백엔드 JAR와 `frontend/dist/` **전체**를 기존 방식으로 각각 배포한다. 백엔드만 배포하면 React 화면 변경은 반영되지 않는다. 기존 커뮤니티는 전체 누적으로 정상 표시되고 운영진이 설정을 변경할 수 있다. 추가 cron/리셋 작업/백필 명령은 없다.

## 테스트

H2 흐름 테스트와 별도 PostgreSQL 테스트 DB에서 월간/분기·경계·합산·커뮤니티 분리·과거 조회·권한·중복 지급·ALL_TIME 호환을 검증한다. PostgreSQL 테스트는 아래 환경변수가 있는 경우만 실행하며 **create-drop을 사용하는 전용 테스트 DB여야 한다**.

```bash
TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/guildup_ranking_test \
TEST_POSTGRES_USER=your_test_user \
./mvnw -Dtest=CommunityRankingPostgresIntegrationTests test
```

PostgreSQL 테스트에서는 설정 컬럼이 없는 기존 스키마로 되돌린 뒤 실제 운영 SQL을 재적용하고 기존 커뮤니티의 ALL_TIME 기본값, 누적 총점 보존, SQL 재실행을 검증한다. 프론트 테스트는 월/분기의 연도 경계를 검증한다.

## 이번 작업의 변경 파일

기존에 작업 중이던 활동 동기화/모니터링 파일은 이번 변경 목록에 포함하지 않는다.

백엔드 (`src/main/java/com/guildup/community/`):

- `domain/Community.java`
- `domain/CommunityScoreHistory.java`
- `domain/RankingPeriodType.java` (추가)
- `dto/CommunityRankingEntryResponse.java`
- `dto/CommunityRankingsResponse.java`
- `dto/RankingPeriodResponse.java` (추가)
- `dto/RankingSettingsRequest.java` (추가)
- `dto/RankingSettingsResponse.java` (추가)
- `repository/CommunityRankingRepository.java` (추가)
- `service/CommunityAttendanceService.java`
- `service/CommunityRankingService.java`
- `service/RankingPeriod.java` (추가)
- `controller/CommunityScoreController.java`

프론트/정적 화면:

- `frontend/src/pages/CommunitySettingsPage.jsx`
- `frontend/src/pages/RankingsPage.jsx`
- `frontend/src/pages/HelpPages.jsx`
- `frontend/src/styles/pages.css`
- `frontend/src/rankingView.js` (추가)
- `src/main/resources/static/rankings.html`

SQL/문서/테스트:

- `src/main/resources/db/manual/add_community_ranking_periods.sql` (추가)
- `RANKING_PERIODS.md` (추가)
- `src/test/java/com/guildup/community/CommunityScoreFlowTests.java`
- `src/test/java/com/guildup/community/CommunityRankingPostgresIntegrationTests.java` (추가)
- `src/test/java/com/guildup/community/domain/CommunityDomainTests.java`
- `frontend/test/rankingView.test.js` (추가)

검증 결과 (2026-09-30):

- 전체 백엔드: 총 451개, 434개 통과, 17개 환경 조건부 건너뜀, 실패/오류 0.
- 별도 PostgreSQL 전용 DB 실행: 랭킹/출석 흐름 및 운영 SQL 테스트 14개 통과. 일반 실행에서는 환경변수 조건으로 건너뛴다.
- 출석·랭킹·킬내기·빙고 관련 집중 실행: 52개 통과.
- 프론트 Node 테스트: 63개 통과.
- Vite 프로덕션 빌드 및 36개 라우트/asset 검증 통과. 기존 단일 JS 번들 크기 경고가 있으며 빌드 실패는 아니다.
- 테스트용 API 응답을 사용하는 로컬 빌드 화면에서 설정 저장, 월간/분기 과거 이동, 미래 버튼 비활성화, 누적/기간 점수 구분을 확인했다. 모바일 375px 지정 화면에서 가로 넘침 없이 닉네임 생략과 점수 표시를 확인했다. 실제 집계·권한 검증은 H2/PostgreSQL 및 MockMvc에서 수행했다.

## 2026-10-01 커뮤니티 목록 500 복구

기존 PostgreSQL 테이블에 행이 있는 상태에서 Hibernate ddl-auto=update가 DEFAULT 없이 NOT NULL ranking_period_type 컬럼을 추가하려다 실패했다. 서버는 계속 시작되었지만 Community를 조회하는 커뮤니티 목록과 Discord 조회가 없는 컬럼을 읽어 SQLState 42703 / HTTP 500이 발생했다.

- Community.rankingPeriodType에 `@ColumnDefault("'ALL_TIME'")` 추가. Java 필드 초기값과 DB DDL 기본값은 별개이므로 두 곳 모두 명시한다.
- 확인된 로컬 guildup DB에 기존 수동 SQL을 적용했다. 적용 전후 커뮤니티 수, 점수 원장 수, 누적 총점이 동일함을 확인했다. 운영/원격 DB에는 이 복구 작업을 적용하지 않았다.
- 실제 로그인된 로컬 React 커뮤니티 목록을 새로고침해 정상 표시를 확인했다.
- 신규 `src/test/java/com/guildup/community/CommunityRankingSchemaUpdatePostgresTests.java`는 별도 PostgreSQL 테스트 DB의 임시 스키마에 컬럼 없는 기존 테이블과 커뮤니티 행을 만든 뒤 실제 Hibernate hbm2ddl.auto=update를 실행한다. 기본값이 있는 NOT NULL 컬럼 추가, 기존 행 로딩, 신규 행 저장을 검증한다.
- 위 회귀 테스트 1개, PostgreSQL 랭킹 통합 테스트 14개, Community 도메인 테스트 3개: 총 18개 통과.

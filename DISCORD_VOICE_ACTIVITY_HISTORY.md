# Discord 음성 활동 과거 기간 조회 개선 보고

## 1. 기존 구조

작업 전 React 화면은 `DiscordVoiceActivityPage.jsx`에서 ALL/DAY/WEEK/MONTH/YEAR 탭을 렌더링하고, 목록·상세 모두 `period`만 전달했다. `voiceActivityView.js`는 표시·현재 접속 인원·진행 중 요청 공유를 담당했다. 기본값은 ALL이었다.

백엔드는 Controller → `CommunityDiscordVoiceActivityService` → `DiscordVoiceSessionRepository` 구조였다. `DiscordVoiceActivityPeriod.startAt(now)`가 서울 달력의 현재 기간 시작을 계산하고 Service가 조회 종료를 `Clock.instant()`로 정했다. 전체 조회는 시작 제한이 없었다.

저장 데이터는 `DiscordVoiceSession`의 `joinedAt`, `leftAt`이며 모두 `Instant`다. `leftAt = NULL`은 열린 세션이다. 기존 Repository 겹침 조건과 Service의 max/min 체류시간 함수가 이미 기간 경계와 열린 세션을 처리했다. 애플리케이션 Clock은 UTC, 달력 계산과 화면 날짜는 Asia/Seoul이었다.

기존의 ALL 기본값, 현재 기간 조회, 권한 검사, JSON 응답 형식, 세션 저장·재시작 복구, Repository 조건과 체류시간 함수를 유지한 채 기준 날짜 선택만 확장했다.

## 2. 변경한 파일

이번 개선에서 변경한 파일은 다음 17개다. 이전 작업의 미커밋 변경도 그대로 유지했다.

| 파일 | 변경 |
| --- | --- |
| `src/main/java/com/guildup/discord/domain/DiscordVoiceActivityPeriod.java` | 기준 날짜의 기간 시작·종료 달력 계산 |
| `src/main/java/com/guildup/community/service/DiscordVoiceActivityRange.java` | 과거/현재 범위와 미래 기간 검증 추가 |
| `src/main/java/com/guildup/community/controller/CommunityDiscordVoiceActivityController.java` | 두 API에 optional referenceDate 추가 |
| `src/main/java/com/guildup/community/service/CommunityDiscordVoiceActivityService.java` | 범위 계산 연결, 과거 조회의 접속 상태 처리 |
| `frontend/src/pages/DiscordVoiceActivityPage.jsx` | URL 상태·목록/상세 요청·선택 영역 연결 |
| `frontend/src/components/VoiceActivityPeriodPicker.jsx` | 날짜/주 달력, 월·연도 팝업, 화살표와 복귀 버튼 |
| `frontend/src/voiceActivityPeriod.js` | 서울 오늘, 달력 이동, 정규화, 제목, URL/API 파라미터 |
| `frontend/src/voiceActivityView.js` | 요청 공유 키에 referenceDate 포함 |
| `frontend/src/styles/pages.css` | PC·모바일 선택 영역과 팝업 스타일 |
| `src/test/java/com/guildup/community/service/DiscordVoiceActivityRangeTests.java` | 날짜 범위·미래 검증 테스트 18개 |
| `src/test/java/com/guildup/community/CommunityDiscordVoiceActivityFlowTests.java` | 과거 조회 API/DB 테스트 18개 추가 |
| `frontend/test/discordVoiceActivityPage.test.js` | 실제 React UI 테스트 9개 추가 및 기존 요청 검증 확장 |
| `frontend/test/voiceActivityPeriod.test.js` | 날짜 연산·타임존·URL 검증 7개 |
| `frontend/test/voiceActivityView.test.js` | 같은 period의 서로 다른 날짜 요청 분리 검증 |
| `README.md` | 현재·과거 선택 조회 설명 |
| `DISCORD_VOICE_ACTIVITY_PERIODS.md` | 최초 구현 보고서에서 후속 보고서 안내 |
| `DISCORD_VOICE_ACTIVITY_HISTORY.md` | 이번 개선 분석·구현·검증 기록 |

`DiscordVoiceSessionRepository`, DTO, 세션 저장/복구 코드는 이번 개선에서 바꾸지 않았다. 기존 `CommunityDiscordVoiceActivityPostgresTests`는 상속한 통합 테스트를 그대로 사용하므로 추가된 케이스도 PostgreSQL에서 실행된다.

## 3. 기간 선택 UI

- 기존 `전체 | 일 | 주 | 월 | 년` 탭을 유지했다.
- PC에서는 탭 옆에 작은 `‹ 선택 기간 ›` 탐색 영역을 배치한다. 모바일에서는 탭 아래로 배치한다.
- 일: 하루 전/후 이동, 날짜 클릭 시 월요일부터 시작하는 달력. 미래 날짜는 disabled.
- 주: 이전/다음 주 이동, 월요일~일요일 날짜 범위 표시. 달력에서 날짜를 클릭하면 그 날짜가 속한 주를 선택한다. 애매한 월별 주차 표시는 추가하지 않았다.
- 월: 이전/다음 달 이동, 3열×4행 월 선택 팝업, 이전/다음 연도 이동. 현재 선택 월을 강조하고 미래 월을 disabled 처리한다.
- 년: 이전/다음 연도 이동, 12개 연도의 선택 그리드와 12년 단위 탐색. 선택 연도를 강조하고 미래 연도를 disabled 처리한다.
- 과거를 볼 때만 오늘/이번 주/이번 달/올해 복귀 버튼을 표시한다. 현재 기간에서는 다음 화살표를 disabled 처리한다.
- 전체에서는 날짜 선택 영역을 표시하지 않는다.
- 기존 GuildUp의 색상 변수, border, radius, hover와 보라색 active 스타일을 사용한다. 선택 영역은 통계보다 작은 크기로 유지한다.
- 팝업은 Escape/바깥 클릭으로 닫을 수 있으며 선택·Escape 후 트리거로 포커스가 돌아간다. 키보드 Tab/Enter로 선택할 수 있다.
- 기존 React Router의 `useSearchParams`로 선택 상태를 유지한다. `communityId`와 다른 기존 쿼리를 보존하고 전체 페이지를 새로고침하지 않는다.

예시 URL:

```text
/discord-voice-activity.html?communityId=9&period=MONTH&referenceDate=2026-08-01
```

이 URL로 새로고침하거나 다시 진입해도 2026년 8월을 유지한다. 같은 탭 재클릭이나 데이터 응답이 현재 기간으로 선택을 초기화하지 않는다. 다른 탭으로 전환하면 해당 탭의 현재 기간을 기본 선택한다.

## 4. API 변경

기존 목록·상세 경로와 응답 JSON은 그대로다. 파라미터는 하나의 기준 날짜 형식으로 통일했다.

```text
GET /api/communities/{communityId}/discord/voice-activity?period=DAY&referenceDate=2026-10-05
GET /api/communities/{communityId}/discord/voice-activity?period=WEEK&referenceDate=2026-10-01
GET /api/communities/{communityId}/discord/voice-activity?period=MONTH&referenceDate=2026-09-01
GET /api/communities/{communityId}/discord/voice-activity?period=YEAR&referenceDate=2025-01-01
GET /api/communities/{communityId}/discord/voice-activity/members/{memberId}?period=MONTH&referenceDate=2026-09-01
```

`referenceDate`는 ISO LocalDate(YYYY-MM-DD)이며 그 날짜가 속한 기간을 조회한다. 프론트는 일은 해당 날짜, 주는 월요일, 월은 1일, 년은 1월 1일로 정규화하여 전달한다. year/month/date 같은 다른 파라미터 방식은 추가하지 않았다.

하위 호환:

- `period` 미전달: ALL.
- period만 전달: 기존처럼 현재 기간.
- ALL: 기존 전체 누적. referenceDate를 함께 지정하면 HTTP 400으로 잘못된 조합을 거절한다.
- 기존 Service 인자 방식도 유지하고 새로운 referenceDate 인자 방식에 위임한다.

잘못된 날짜, 지원 범위 밖 연도, 미래 기간은 서버에서도 HTTP 400으로 거절한다. 미래 날짜가 URL에 직접 들어온 경우 프론트는 현재 기간으로 보정해 미래 API 요청을 보내지 않는다.

## 5. 날짜 범위 계산 방식

`DiscordVoiceActivityPeriod`의 `startDate`/`endDate`는 기준 날짜를 달력 구간으로 정규화한다. `DiscordVoiceActivityRange.resolve`는 한 번 읽은 현재 `Instant`와 서울 오늘을 이용해 조회 범위를 정한다.

| period | 시작 | 달력 종료(미포함) |
| --- | --- | --- |
| DAY | 선택 날짜 00:00 KST | 다음 날 00:00 KST |
| WEEK | 선택 날짜가 속한 주 월요일 00:00 KST | 다음 월요일 00:00 KST |
| MONTH | 선택 날짜가 속한 달 1일 00:00 KST | 다음 달 1일 00:00 KST |
| YEAR | 선택 날짜가 속한 해 1월 1일 00:00 KST | 다음 해 1월 1일 00:00 KST |
| ALL | 시작 제한 없음 | 현재 시각 |

날짜 경계는 Asia/Seoul에서 Instant로 변환한다. 월/년 이동은 달력 연산을 사용하므로 월별 일수, 윤년, 연말의 주 경계를 처리한다. 프론트 날짜 이동도 UTC 달력 연산과 명시적인 서울 오늘을 사용해 사용자 브라우저 시간대의 영향을 피한다.

## 6. 과거/현재 기간 처리 방식

선택한 기간 시작과 현재 기간 시작을 비교한다.

- 과거: 종료는 달력상의 다음 기간 시작까지.
- 현재: 종료는 해당 API 호출의 현재 시각까지.
- 미래: 선택한 기간 시작이 현재 기간 시작보다 늦으면 거절.

현재가 2026-10-07 12:00 KST일 때:

| 선택 | 실제 조회 구간(KST) |
| --- | --- |
| DAY / 2026-10-05 | 10월 5일 00:00 ~ 10월 6일 00:00 |
| WEEK / 2026-10-01 | 9월 28일 00:00 ~ 10월 5일 00:00 |
| MONTH / 2026-09-15 | 9월 1일 00:00 ~ 10월 1일 00:00 |
| YEAR / 2025-06-15 | 2025년 1월 1일 00:00 ~ 2026년 1월 1일 00:00 |
| DAY / 2026-10-07 | 10월 7일 00:00 ~ 현재 |
| MONTH / 2026-10-01 | 10월 1일 00:00 ~ 현재 |

서버는 기준 날짜가 속한 **기간**을 검증한다. 예를 들어 같은 현재 주의 일요일을 기준 날짜로 보내도 현재 주 조회로 정규화된다. 프론트는 미래 날짜를 달력에서 비활성화하고 정규화된 기간 시작을 전달한다.

## 7. 음성 활동 집계 영향

기존 Repository 조건을 그대로 재사용한다.

```sql
joined_at < :periodEnd
AND (left_at IS NULL OR left_at > :periodStart)
AND (left_at IS NULL OR left_at > joined_at)
```

커뮤니티 및 Discord 사용자 범위도 기존처럼 DB 조건에 포함한다. 전체 기록을 Java에 가져와 과거 기간을 필터링하지 않는다.

기존 체류시간 함수도 그대로 재사용한다.

```text
start = max(joinedAt, periodStart)
end = min(leftAt 또는 periodEnd, periodEnd)
durationSeconds = end > start일 때 두 시각의 차이
```

2026-10-04 23:00 ~ 2026-10-05 02:00 KST 세션은 10월 5일 일간에서 2시간으로 계산한다. 과거 기간에 걸친 열린 세션은 해당 과거 기간 끝까지만 합산한다.

사용자별 시간, 상세 합계, 상세 세션별 시간과 최근 입장 시각은 같은 선택 범위로 계산한다. 현재 기간과 ALL의 접속 중 우선 정렬은 유지한다. 과거 조회에서는 `currentlyConnected`를 false로 제공하므로 현재 접속 상태가 과거 순서를 바꾸지 않고 기간별 시간순으로 정렬된다. 과거 화면은 현재 접속 인원 대신 기간 내 활동 기록 인원을 표시하며, 원본 leftAt이 없는 상세 세션에는 '퇴장 기록 없음'을 표시한다.

저장 시각과 상세 입장/퇴장 원본은 수정하지 않는다. 기존에 없던 별도 횟수·평균·전체 합계·숫자 순위 통계는 추가하지 않았다.

## 8. 추가한 테스트

- 날짜 범위 단위 테스트 18개: 과거 일/주/월/년의 정확한 전체 종료, 현재 종료, period만 전달, 미래 거절, ALL, 윤년, 연말 주, 서울 자정.
- API/실제 DB 통합 테스트 18개 추가: 과거 네 기간의 조회·양끝 보정·DB 제외, 현재 기준 날짜와 기존 period-only의 동일 응답, 열린 과거 세션, 과거 정렬, 사용자 예시의 2시간, 미래/잘못된 날짜/ALL 조합 검증.
- 기존 통합 22개와 합쳐 40개를 H2 및 PostgreSQL에서 각각 실행한다. 기존 ALL·기간 경계·권한 테스트도 유지한다.
- 프론트 신규 테스트 17개: 날짜 연산/서울 시간/URL/미래 보정 7개, 같은 period의 날짜별 요청 분리 1개, 실제 React UI 9개.
- 실제 React UI에서 각 화살표와 복귀, 달력·월·연도 선택, 미래 disabled, 상세 요청 범위, URL 재진입 유지, 과거 열린 세션 안내, Escape/바깥 클릭, 같은 월에서의 늦은 응답 무시를 확인한다.
- 브라우저 샘플 미리보기에서 PC 월 팝업, 모바일 360px와 320px, 가장 긴 주간 범위 표시를 확인했다. 320px에서 문서 scrollWidth가 viewport와 같은 320px임을 확인했다.

## 9. 전체 테스트 결과

| 검증 | 결과 |
| --- | --- |
| 기본 전체 Maven 실행 | 877개 중 720개 실행·통과, 실패/오류 0. 환경 조건으로 157개 건너뜀 |
| 별도 PostgreSQL 전용 전체 실행 | 230개 실행·통과, 실패/오류/건너뜀 0 |
| 최종 백엔드 보고서 합계 | 950개 실행·통과, 실패/오류 0. 운영 dump baseline 2개만 미실행 |
| 음성 활동 H2/ PostgreSQL 통합 | 각각 40개 통과 |
| 날짜 범위 신규 단위 테스트 | 18개 통과 |
| 프론트 전체 `npm test` | 157개 통과 |
| 뉴욕 시간대에서 음성 활동 프론트 테스트 | 32개 통과 |
| 프론트 배포 빌드 | 성공, 37개 프로덕션 경로 검증 통과 |
| `git diff --check` | 통과 |

실행 명령:

```sh
env -u TEST_POSTGRES_URL -u SPRING_DATASOURCE_URL ./mvnw test
env -u SPRING_DATASOURCE_URL TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55473/voice_tests TEST_POSTGRES_USER=guildup_test TEST_POSTGRES_PASSWORD= ./mvnw '-Dtest=*Postgres*Tests' test
cd frontend
npm test
TZ=America/New_York node --test test/voiceActivityPeriod.test.js test/voiceActivityView.test.js test/discordVoiceActivityPage.test.js
npm run build
```

PostgreSQL은 운영 DB와 분리된 임시 `/tmp` 클러스터를 생성해 테스트했다. 기본 실행에서 건너뛴 PostgreSQL 전용 테스트도 이 별도 실행에서 전부 검증했다.

## 10. 남아 있는 문제

- 운영 dump 복원본이 필요한 기존 `BingoProductionDumpBaselineTests` 2개는 실행하지 않았다. 그 외 최종 테스트 보고서의 실행 케이스는 모두 통과했다.
- Vite의 500 kB 초과 JS chunk 경고는 남아 있으며 빌드는 성공했다.
- ALL의 전체 기록 응답량과 Bot 중단 중 실제 퇴장 시각을 알 수 없는 기존 복구 정책은 유지했다. 이 작업에서 집계/저장 구조를 대규모로 변경하지 않았다.
- 현재 기간의 열린 세션 시간은 각각의 API 조회 시점 기준이며 자동 초당 갱신은 기존처럼 제공하지 않는다.
- 스키마 변경이나 배포는 하지 않았다. 운영 DB에 연결하거나 데이터를 수정/삭제하지 않았다. 첨부된 `add_activity_sync_observability.sql`도 수정하거나 직접 실행하지 않았다.

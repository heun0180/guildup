# Discord 음성 활동 기간별 조회 변경 보고

이 문서는 최초 기간 탭 추가 시점의 보고서다. 이후 추가된 과거 기간 선택 UI와 referenceDate API는 [과거 기간 조회 개선 보고서](DISCORD_VOICE_ACTIVITY_HISTORY.md)를 참고한다.

## 1. 기존 Discord 음성 활동 조회 구조

- `DiscordVoiceEventListener`가 주입된 `Clock`의 `Instant`로 JDA 음성 이벤트를 전달한다.
- `DiscordVoiceSessionService`는 `discord_voice_sessions`에 커뮤니티, Discord 서버/사용자/채널 ID, 채널명 snapshot, `joined_at`, `left_at`을 저장한다. 퇴장은 열린 행을 종료하고, 채널 이동은 기존 행 종료 후 새 행을 생성한다.
- `left_at = NULL`은 접속 중이다. `open_session_marker`와 커뮤니티·사용자별 UNIQUE 제약으로 열린 세션은 하나만 허용한다.
- 재시작 시 `DiscordVoiceRecoveryService`가 실제 Discord 상태와 열린 세션을 맞춘다.
- 목록 API: `GET /api/communities/{communityId}/discord/voice-activity`
- 상세 API: `GET /api/communities/{communityId}/discord/voice-activity/members/{communityMemberId}`
- Controller → `CommunityDiscordVoiceActivityService` → `DiscordVoiceSessionRepository` 구조다. 목록은 ACTIVE 클랜원과 Discord 외부 계정을 결합하며, 상세는 커뮤니티 소속을 확인한 뒤 LEFT 클랜원의 기록도 읽을 수 있다. 두 API 모두 운영진 권한을 요구한다.
- React 화면은 `frontend/src/pages/DiscordVoiceActivityPage.jsx`, 화면 계산/표시는 `voiceActivityView.js`에 있다.

**확인된 차이:** 작업 전 실제 소스는 전체 누적이 아니라 `DEFAULT_PERIOD_DAYS = 14`로 최근 14일만 조회했다. 목록·상세 API, DTO 주석, 프론트 설명, 기존 테스트가 모두 이 제한을 반영했다. 따라서 요청대로 전체 누적 `ALL`을 제공하기 위해 14일 제한을 제거했다. 기존 응답 형식, 권한, 계정 연결, 정렬 방식과 세션 저장 구조는 유지했다.

기존 체류시간 계산은 이미 기간 경계에 맞춰 입장·퇴장 시각을 보정했고, Repository에도 겹침 조회가 있었다. 이를 확장했으며 저장/복구 흐름은 변경하지 않았다. 시간은 `Instant`로 보관하고 애플리케이션 `Clock`은 UTC다. 기존 프론트 날짜 표시는 브라우저 기본 시간대를 사용했으나 이제 서울 시간대를 명시한다.

현재 제공되는 통계와 기간 적용은 다음과 같다.

| 제공 항목 | 기간 적용 |
| --- | --- |
| 사용자별 `totalSeconds` | 기간과 겹치는 세션의 보정된 시간 합계 |
| 목록 정렬 | 기존 접속 중 우선 → 선택 기간 합계 내림차순 → 닉네임 순 |
| `lastJoinedAt` | 기간과 겹치는 세션 중 가장 최근의 실제 입장 시각 |
| `currentlyConnected`, 화면의 현재 접속 인원 | 조회에 포함된 열린 세션 기준 |
| 상세 `totalSeconds` | 선택 기간의 상세 세션 시간 합계 |
| 상세 `sessions`, `durationSeconds` | 같은 기간의 유효한 세션과 각각의 보정된 시간 |

음성 활동 횟수, 전체 사용자 합계/평균, 숫자 순위는 기존 API/화면에 별도 통계 항목으로 존재하지 않는다. 기존 제공 항목에 기간을 적용했으며 새 통계 항목은 추가하지 않았다. 상세 세션 개수와 합계의 일치도 테스트에서 확인한다.

관련 기존 테스트는 음성 이벤트 저장/이동/중복 처리, 재시작 복구, Service의 계정 결합·시간 합계·커뮤니티 범위·LEFT 클랜원 상세, 프론트 표시와 중복 요청 공유를 검증하고 있었다.

## 2. 변경한 파일

| 파일 | 변경 |
| --- | --- |
| `src/main/java/com/guildup/discord/domain/DiscordVoiceActivityPeriod.java` | ALL/DAY/WEEK/MONTH/YEAR enum 및 서울 달력 경계 추가 |
| `src/main/java/com/guildup/community/controller/CommunityDiscordVoiceActivityController.java` | 목록·상세의 period 파라미터와 기본값 ALL |
| `src/main/java/com/guildup/community/service/CommunityDiscordVoiceActivityService.java` | 기간 계산과 ALL 조회 연결, 기존 인자 Service 호출도 ALL 위임 |
| `src/main/java/com/guildup/discord/repository/DiscordVoiceSessionRepository.java` | 전체 조회 추가, 엄격한 겹침·양의 세션 조건 |
| `src/main/java/com/guildup/discord/dto/DiscordVoiceActivitySummaryResponse.java` | 기간 설명 주석 갱신; 필드 유지 |
| `src/main/java/com/guildup/discord/dto/DiscordVoiceActivityDetailResponse.java` | 기간 설명 주석 갱신; 필드 유지 |
| `frontend/src/pages/DiscordVoiceActivityPage.jsx` | 기간 UI, 목록·상세 period 전달, 늦은 응답 무시 |
| `frontend/src/voiceActivityView.js` | 기간 상수/문구, 서울 날짜 표시, 진행 중 요청만 기간별 공유 |
| `frontend/src/styles/pages.css` | 기존 테마를 사용하는 선택 버튼과 상세 설명 스타일 |
| `frontend/src/pages/IntegrationsPage.jsx` | 음성 활동 기능 소개 갱신 |
| `src/test/java/com/guildup/community/service/CommunityDiscordVoiceActivityServiceTests.java` | 기존 5개 테스트를 ALL 기준으로 갱신 |
| `src/test/java/com/guildup/discord/domain/DiscordVoiceActivityPeriodTests.java` | 서울 기간 경계 테스트 11개 |
| `src/test/java/com/guildup/community/CommunityDiscordVoiceActivityFlowTests.java` | 실제 DB·Service·API 통합 테스트 22개 |
| `src/test/java/com/guildup/community/CommunityDiscordVoiceActivityPostgresTests.java` | 동일 통합 테스트를 PostgreSQL에서도 실행 |
| `frontend/test/voiceActivityView.test.js` | 서울 날짜 표시와 기간별 요청 공유·재조회·실패 후 재시도 |
| `frontend/test/discordVoiceActivityPage.test.js` | 실제 React 페이지 동작 테스트 5개 |
| `README.md` | 음성 활동 조회 범위 갱신 |
| `DISCORD_VOICE_ACTIVITY_PERIODS.md` | 분석·구현·검증 결과 기록 |

첨부된 `src/main/resources/db/manual/add_activity_sync_observability.sql`은 음성 세션 조회와 별개인 monitoring_events 변경 스크립트다. 수정하거나 직접 실행하지 않았다. DB 스키마 변경은 필요하지 않다.

## 3. 기간 계산 방식

각 API 호출에서 `clock.instant()`를 한 번 읽어 조회 종료 시각으로 사용한다. 종료 시각을 `Asia/Seoul`의 날짜로 변환한 뒤 아래 시작 경계를 다시 `Instant`로 변환한다. JVM/브라우저 기본 시간대와 무관하다.

| period | 시작 | 종료 |
| --- | --- | --- |
| ALL | 시작 제한 없음 | 현재 시각 |
| DAY | 오늘 00:00 KST | 현재 시각 |
| WEEK | 이번 주 월요일 00:00 KST | 현재 시각 |
| MONTH | 이번 달 1일 00:00 KST | 현재 시각 |
| YEAR | 올해 1월 1일 00:00 KST | 현재 시각 |

목록·상세 모두 `period` 생략 시 ALL이다. enum에 없는 값은 HTTP 400이다. 기존 응답 JSON 구조는 바꾸지 않았다.

## 4. DB 조회 조건

기간 조회는 다음 조건을 Repository의 JPQL에서 적용한다.

```sql
community_id = :communityId
AND discord_user_id IN (:discordUserIds)
AND joined_at < :periodEnd
AND (left_at IS NULL OR left_at > :periodStart)
AND (left_at IS NULL OR left_at > joined_at)
```

기간 전에 입장했더라도 시작 경계 이후 퇴장했거나 아직 접속 중이면 포함한다. 시작 시각에 정확히 퇴장하거나 종료 시각에 정확히 입장한 세션, 시간이 0인 종료 세션은 제외한다. 정렬은 `joined_at DESC`다.

ALL은 별도의 `findAllSessionsBefore` 쿼리에서 시작 경계 조건만 생략한다. 미래 세션과 시간이 0인 종료 세션은 동일하게 제외한다. 기간 조회를 위해 전체 기록을 Java에 가져와 필터링하지 않는다. 기존 커뮤니티·사용자·입장 시각 인덱스와 저장 구조를 유지한다.

## 5. 음성 체류시간 계산 방식

```text
start = ALL이면 joinedAt, 그 외에는 max(joinedAt, periodStart)
end = min(leftAt 또는 periodEnd, periodEnd)
durationSeconds = end > start 이면 Duration.between(start, end).getSeconds(), 그 외 0
```

예시인 2026-10-06 23:00 ~ 2026-10-07 02:00 KST 세션은 DAY에서 00:00 ~ 02:00, 즉 7,200초만 합산한다. 열린 세션은 조회 현재 시각까지만 계산한다. 기존 초 단위 정밀도를 유지한다.

상세의 `joinedAt`/`leftAt`은 원래 접속 시각을 유지한다. 상세 안내 문구에서 활동시간만 선택 기간과 겹치는 구간을 합산한다는 점을 설명한다.

## 6. 프론트 UI 변경 내용

- 화면 상단에 `전체 | 일 | 주 | 월 | 년` 버튼 그룹을 추가했다. 기본 선택은 전체이며 선택한 버튼을 기존 보라색 계열로 강조한다.
- 서울 시간 기준과 선택 기간의 시작을 안내한다. 날짜 표시에도 `Asia/Seoul`을 적용했다.
- 목록과 상세가 같은 period를 요청한다. 기간 전환 시 상세를 닫고 새 목록을 로드한다.
- 로딩/빈 기록/상세의 기존 최근 14일 문구를 선택 기간에 맞게 갱신했다.
- 요청 공유 키에 커뮤니티와 기간을 함께 넣고, 완료/실패한 요청은 캐시에서 제거한다. 기간을 다시 선택하면 최신 활동을 조회한다.
- 이전 목록이나 닫은 상세의 늦은 응답은 현재 화면을 덮어쓰지 않는다. React StrictMode의 중복 목록 요청 공유는 유지했다.
- 로컬 샘플 데이터를 사용한 브라우저 미리보기에서 기간 버튼과 일간 상세 표시를 확인했다.

## 7. 추가한 테스트

요청한 최소 케이스를 H2/MockMvc 통합 테스트와 동일한 PostgreSQL 통합 테스트로 검증한다.

1. 오늘 안에 입장·퇴장한 세션의 전체 체류시간.
2. 전날 23시 입장 → 오늘 2시 퇴장의 일간 2시간 계산.
3. 오늘 입장 → 접속 중인 세션의 현재까지 계산.
4. 지난주 세션이 이번 주 DB 결과와 API에서 제외됨.
5. 지난달 세션이 이번 달 DB 결과와 API에서 제외됨.
6. 지난해 세션이 올해 DB 결과와 API에서 제외됨.
7. ALL의 전체 시간 계산, 기존 기간 인자 없는 Service 호출과 명시적 ALL의 동일 결과. 기존 4,800초 합계 테스트는 유지하고 14일 이전 기록 포함을 추가 검증함.
8. 일·주·월·년 시작 전부터 조회 종료 이후까지 걸친 세션의 정확한 보정.
9. 목록·상세 period 미전달과 명시적 ALL의 동일 응답.

추가로 열린 장기 세션, 정확히 경계에 닿는 기록, 0초·미래 기록, 기간별 정렬, 커뮤니티/계정 격리, 권한/클랜원 범위, 잘못된 enum 값, 월요일·연말 경계를 검증한다. 프론트는 모든 선택의 요청 파라미터, 목록·상세 시간 일치, 재조회, 늦은 응답, 오류 초기화와 StrictMode를 검증한다.

## 8. 테스트 결과

| 검증 | 결과 |
| --- | --- |
| 기존 전체 Maven 테스트 실행 | 820개 중 684개 실행·통과, 실패 0, 오류 0. 환경 조건으로 PostgreSQL/운영 dump 테스트 136개 건너뜀 |
| 별도 임시 PostgreSQL 16.15에서 PostgreSQL 전용 전체 테스트 | 212개 실행·통과, 실패/오류/건너뜀 0. 새 음성 활동 통합 테스트 22개 포함 |
| 음성 활동 H2 통합 테스트 | 22개 통과 |
| 음성 활동 기간 경계 단위 테스트 | 11개 통과 |
| 프론트 전체 `npm test` | 140개 통과, 실패/건너뜀 0 |
| `TZ=America/New_York`에서 음성 활동 프론트 테스트 | 15개 통과 |
| `npm run build` | 성공, 프로덕션 37개 경로 검증 통과 |
| `git diff --check` | 통과 |

실행 명령:

```sh
env -u TEST_POSTGRES_URL -u SPRING_DATASOURCE_URL ./mvnw test
env -u SPRING_DATASOURCE_URL TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55473/voice_tests TEST_POSTGRES_USER=guildup_test TEST_POSTGRES_PASSWORD= ./mvnw '-Dtest=*Postgres*Tests' test
cd frontend
npm test
TZ=America/New_York node --test test/voiceActivityView.test.js test/discordVoiceActivityPage.test.js
npm run build
```

PostgreSQL 전용 테스트는 별도 `/tmp` 클러스터와 테스트 DB에서 실행했다. 운영 DB에는 연결하지 않았다. 기본 전체 실행에서 건너뛴 PostgreSQL 전용 테스트는 이 별도 실행으로 검증했다. 운영 dump 복원본을 요구하는 `BingoProductionDumpBaselineTests` 2개는 실행하지 않았다.

## 9. 주의할 점이나 남아 있는 문제

- 실제 기존 소스의 14일 제한과 요청의 전체 누적 설명이 달랐다. 요청한 ALL 기본값을 적용했으므로 14일 이전 기록이 있는 경우 period를 보내지 않던 클라이언트도 더 큰 누적 합계를 받는다. API 경로와 응답 형식은 유지한다.
- ALL은 과거 전체 세션을 읽으므로 기록이 매우 많은 커뮤니티의 응답량은 커질 수 있다. 기간 조회는 DB에서 겹치는 세션만 제한하며, 응답 형식을 바꾸는 페이지네이션이나 대규모 집계 리팩터링은 하지 않았다.
- 목록과 상세는 각각 조회 시점의 현재 시각을 사용한다. 열린 세션은 이후 상세를 열면 그동안 증가한 시간이 반영된다.
- Bot 중단 중 실제 퇴장 시각을 알 수 없어 재시작 시각으로 종료하는 기존 복구 정책은 유지했다. 중단 시간의 추정 오차를 이번 변경에서 수정하지 않았다.
- 운영 dump 전용 baseline 2개는 미실행이다. 그 외 실행한 테스트는 모두 통과했다.
- 프론트 빌드는 성공했으며 Vite의 500 kB 초과 JS chunk 경고가 남아 있다.
- 운영 DB 데이터 수정/삭제, 스키마 변경, 배포는 수행하지 않았다.

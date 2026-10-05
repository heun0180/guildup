# 킬내기 팀 등수 점수 수정

## 원인

기존 `KillCompetitionSettlementStore.mergeMatchResults()`는 참가자마다 PUBG 경기 등수 점수를 더해
`placement_points`, `total_points`에 저장했다. `applyTotals()`가 이 값을 개인 중간/최종 점수에 누적했고,
조회 DTO와 우승자 판정기는 개인 점수를 다시 팀별로 합산했다.
같은 경기에서 4명이 1등하면 +10이 4번 적용되어 `25 + 40 = 65`가 됐다.
기존 듀오 테스트도 이 중복 계산을 정상 결과로 기대했다.

수정 전 디버거 로그에서도 같은 경기의 두 참가자에게 각각 킬 5점과 등수 5점이 저장되고,
팀 점수가 20점으로 합산되는 것을 확인했다. 새 회귀 테스트는 수정 전 2/3/4인 팀 보너스가
각각 20/30/40점이 되는 문제와 인원수에 따른 잘못된 순위를 재현했다.

## 확인한 경로와 변경

| 경로 | 처리 |
| --- | --- |
| 개인별 킬 수 수집 | `KillCompetitionPubgAggregator`: 대회 시작/종료 및 개인 `eligibleFrom` 필터를 유지한다. |
| 개인별 점수 저장 | 공통 `KillCompetitionScoring.personalMatchScore()`: 팀전 개인 점수는 킬 수 × 킬 점수, 개인 등수 보너스는 0이다. SOLO 계산은 유지한다. |
| 팀별 점수 합산 | `KillCompetitionScoring.teamScore()`: 승인된 팀원의 개인 기본 점수 합계에 팀 보너스 합계를 한 번 더한다. |
| 등수 결정 | 등수 보너스의 기준은 기존처럼 PUBG 경기의 `winPlace`다. 대회 순위는 보너스를 반영한 팀 점수 내림차순이며 동점 순위 규칙은 유지한다. |
| 등수 보너스 적용 | `KillCompetitionScoring.teamMatchScores()`: `(팀 ID, 경기 ID)`로 묶어 경기당 팀별 보너스를 한 번 계산한다. 등수가 누락/불일치하면 유효한 가장 높은 등수를 사용한다. |
| 중간 정산 | `finishInterim()` → `applyTotals()` → 공통 계산기. 기존 저장 경기와 최신 응답을 합쳐 재계산한다. |
| 최종 발표 | `finishFinal()` → 동일한 `applyTotals()` 및 계산기. 발표 대기, claim, 원자적 확정 로직은 유지한다. |
| 결과 DB | 개인 경기/참가자 점수에는 팀 보너스를 저장하지 않는다. `pubg_kill_competition_teams.interim_placement_points`, `final_placement_points`에 팀 보너스를 저장한다. |
| 결과 API | 상세 조회, 중간 정산 응답, 최종 발표 응답 모두 같은 팀 계산기를 사용한다. `finalMatches[].teams[]`에 경기별 팀 점수 근거를 추가했다. |
| 프론트 표시 | 팀 카드와 순위는 서버 팀 점수를 표시한다. 최종 경기 근거는 팀 보너스와 팀 합계를 표시하고 팀원의 개인 킬 점수를 따로 표시한다. 도움말/점수 설정에 팀당 한 번 적용 정책을 설명한다. |
| 우승 보상/빙고 | `KillCompetitionWinnerResolver`도 같은 팀 점수를 사용하므로 새 대회의 순위와 활동 점수/빙고 우승자 판정이 일치한다. |

예를 들어 팀원 수가 1/2/3/4명이어도 팀원 킬 점수가 총 25점이고 해당 경기에서 1등 보너스가
10점이면 팀 점수는 모두 35점이다. 여러 경기는 기존 정책처럼 각각의 경기 점수를 누적한다.

## 기존 DB 전환

PostgreSQL 스키마 전환에 팀 보너스 컬럼 2개를 추가했다.
스키마 변경을 별도로 관리하는 환경용 SQL은
`src/main/resources/db/manual/add_kill_competition_team_placement_points.sql`이다.
기존 팀의 `interim_placement_points = NULL`을 전환 대상으로 구분하며 신규 팀은 0으로 생성한다.

`KillCompetitionTeamScoringMigration`은 애플리케이션 시작 시 대회를 잠근 트랜잭션 안에서 공통 계산기를
사용해 기존 개인/팀 점수 데이터를 한 번 전환한다. 원본 킬 수, 경기 등수, 참가 시각, 경기 수는 유지하고,
마지막 중간 정산 경기 시각을 사용해 최종 집계에서 추가된 경기가 과거 중간 결과에 섞이지 않도록 한다.
재시작해도 전환된 팀 보너스를 다시 추가하지 않는다.

점수 보정으로 과거 대회의 우승 팀이 달라질 수 있다. 이미 지급된 활동 점수 원장과 빙고 이력은
이 전환에서 자동 수정하지 않는다. 과거 보상까지 새 정책에 맞추려면 별도 이력 재정산이 필요하다.
이 작업에서는 운영 DB에 접근하거나 배포하지 않았다.

## 검증

- 1/2/3/4인 팀의 1등 +10: 인원수와 관계없이 팀 +10, 개인 보너스 0.
- 등수 점수 OFF: 개인/팀 점수 및 DB 경기 점수가 기존 킬 점수와 일치.
- 4인 팀과 1인 팀이 동일한 킬 점수/등수를 기록하면 공동 1등.
- 중간 정산 반복 및 최신 응답에서 누락된 과거 경기 유지, 최종 점수/순위와 일치.
- 여러 경기의 팀 보너스 누적, 기존 데이터 전환의 반복 실행과 중간/최종 스냅샷 구분.
- 중간 참가자는 승인 전 경기에서 제외하고, 승인 후 공유 경기의 팀 보너스는 한 번만 적용.
- 킬 점수 0 및 등수 누락 시 경기 근거에서도 0점을 유지.
- 수정된 우승자에게 활동 점수와 빙고 승리가 반영되는지 검증.
- 실제 React 화면에서 중간/최종 순위, 팀 카드, 경기 근거의 팀 보너스와 개인 점수 표시 검증.

실행 명령:

```sh
./mvnw -q test
TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55437/guildup_team_score_test TEST_POSTGRES_USER=postgres \
  ./mvnw -q -Dtest=KillCompetitionTeamScoringPostgresTests test
cd frontend
npm test
npm run build
```

PostgreSQL 검증은 운영 DB와 분리된 임시 DB를 사용한다.

검증 결과: 백엔드 전체 테스트 통과. 마지막 변경 후 킬내기 흐름 46개와 빙고 연동 8개 통과.
지정된 `BingoAggregationConsistencyFlowTests` 26개도 통과했다.
PostgreSQL 팀 점수 흐름 46개, 프론트 테스트 95개, 프론트 빌드 및 36개 라우트 검증이 통과했다.
검증용 PostgreSQL 서버는 종료했고, 작업 중 추가한 디버거 브레이크포인트는 제거했다.

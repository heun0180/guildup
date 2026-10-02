# PUBG Kakao / Steam 구현 결과

운영 DB에는 적용하지 않았다. 기존 GameType, HTTP API 경로, 빙고 미션 엔진과 킬내기 점수 계산을 유지했다. PostgreSQL migration은 확장·감사·제약 강화로 분리했다.

## 변경한 DB

| 테이블 | 추가 컬럼 / 제약 | 기존 데이터 처리 |
|---|---|---|
| community_member_accounts | platform varchar(20); PUBG 플랫폼 필수 CHECK; 플랫폼 포함 unique 2개; non-PUBG 부분 unique 2개 | 알려진 PUBG CommunityGame이 정확히 하나일 때만 보정. 모호하면 NULL 유지하고 최종 migration 중단 |
| pubg_matches | UNIQUE(shard,match_id) | Match ID, shard, 숫자 PK 변경 없음 |
| pubg_match_players | nullable time_survived double precision, road_kills integer | 기존 NULL 유지; 원본 추정 없음 |
| pubg_bingo_events / pubg_kill_competitions | community_game_id NOT NULL, 복합 소유권 FK | 유일한 PUBG 게임만 자동 연결. 모호하면 최종 migration 중단 |
| community_games | UNIQUE(id,community_id) | 새 FK의 참조키. 데이터 변경 없음 |

SQL은 [확장](src/main/resources/db/manual/add_pubg_platform_boundaries.sql) → [감사](src/main/resources/db/manual/audit_pubg_platform_boundaries.sql) → [강화](src/main/resources/db/manual/enforce_pubg_platform_boundaries.sql) 순서다. [Fact 초기 DDL](src/main/resources/db/manual/add_pubg_match_facts.sql)도 신규 필드와 unique를 반영했다. 신규 설치에도 강화 SQL을 적용해야 PostgreSQL 부분 index가 생성된다. Flyway/Liquibase 자동 실행은 도입하지 않았다.

외부 계정 유일성은 `(community_member_id,provider,platform)` 및 `(community_id,provider,platform,external_user_id)`다. Discord 등의 NULL 플랫폼 중복 방지는 `provider <> 'PUBG'` 부분 unique index 두 개로 별도 보장한다. PUBG 계정에는 KAKAO/STEAM이 필수이고 다른 provider에는 NULL이 필수다.

## Backend 변경

아래 경로는 `src/main/java/com/guildup/` 기준이다.

| 파일 | 변경 내용 / 플랫폼 분리 방식 |
|---|---|
| pubg/model/PubgPlatform.java, PubgMatchKey.java | 플랫폼·shard 계약과 플랫폼 + Match ID 값 객체 |
| pubg/support/PubgGameSupport.java | CommunityGame/GameType → PubgPlatform → shard. 기존 requireShard 유지 |
| community/domain/CommunityMemberAccount.java | 플랫폼 컬럼·복합 unique·CHECK. 플랫폼 없는 PUBG 생성 금지 |
| community/repository/CommunityMemberAccountRepository.java | 플랫폼 필수 PUBG 조회. 기존 provider-only 조회는 non-PUBG만 허용 |
| community/service/CommunityMemberPubgIdentityService.java | 선택 게임 플랫폼 계정만 조회. 해당 게임 닉네임 규칙 사용 |
| community/service/CommunityGameNicknameSyncService.java | 선택 플랫폼 계정만 삭제/교체. 반대 플랫폼 보존 |
| community/service/CommunityMemberActivitySyncWorker.java, CommunityMemberActivityService.java | 준비·계정 저장·현재 계정 및 snapshot 유효성 판정을 선택 플랫폼으로 제한 |
| community/service/CommunityTeamMakerService.java | 현재 플랫폼 계정만 Season Stats 입력으로 사용 |
| community/dto/PubgAccountResponse.java, CommunityMemberResponse.java | pubgAccounts 배열 추가. 기존 gameNickname 유지; 두 플랫폼이면 Kakao 우선 |
| community/service/CommunityMemberService.java | 멤버별 플랫폼 계정 그룹 응답. 목록 row 중복 방지 |
| community/config/CommunityGameBoundaryDataMigration.java | 기존 시작 시 이벤트 backfill도 알려진 PUBG 게임만 집계 |
| pubg/domain/PubgStoredMatch.java, PubgStoredMatchPlayer.java | shard + matchId unique, 생존시간·로드킬 저장 |
| pubg/repository/PubgStoredMatchRepository.java | 중복 확인·잠금·기간 조회·Telemetry 재시도/upgrade 모두 shard 포함 |
| pubg/service/PubgMatchFactWriter.java | 플랫폼별 저장/잠금. 원본 생존시간·로드킬 저장 |
| pubg/service/PubgMatchFactQueryService.java | 플랫폼·기간·대상 account 조건. 전체 팀 fetch 유지. 원본 필드 복원 |
| pubg/service/PubgMatchFactProvider.java, PubgMatchSyncService.java | 실제 Telemetry strict 수집 계약, in-flight 플랫폼 격리, 성공 후에만 loaded 저장 |
| bingo/mission/PubgBingoFactService.java | Fact 캐시에 플랫폼 추가. fallback 캐시와 실제 수집 성공 구분 |
| bingo/service/BingoParticipantEnrollmentService.java | 이벤트 게임의 플랫폼 계정만 참가자 연결 |
| bingo/service/BingoAggregationPreparationService.java | 전체/개인 참가 계정·커뮤니티 account 집합을 이벤트 플랫폼으로 제한 |
| bingo/service/BingoAggregationService.java | 필요한 경기만 조회/준비 상태 확인. 재시도 실패 시 기존 진행도 보존 |
| bingo/service/BingoAggregationCalculationService.java | 이벤트와 입력 Fact 플랫폼 일치 검증. 미션 계산식 유지 |
| bingo/service/TemporaryBingoRebuildService.java | 권한 검증한 플랫폼을 트랜잭션 밖 계산에 직접 전달. 적용 시 계정 재검증 |
| bingo/domain/BingoEvent.java, killcompetition/domain/KillCompetition.java | community_game_id NOT NULL 매핑 |
| killcompetition/service/KillCompetitionParticipationStore.java | Competition 플랫폼 계정 조회/생성. 참가 준비와 snapshot 플랫폼 검증 |
| developer/service/DeveloperQueryService.java, developer/dto/DeveloperResponses.java | PUBG JOIN 제거 후 page ID의 계정 별도 조회. 다중 계정 배열과 플랫폼 검색 표시. 기존 단일 필드 유지 |
| pubg/client/PubgApiClient.java | 동적 API 유지. Team Maker의 양 플랫폼 TPP squad 정책 주석만 추가 |

숫자 Match PK 및 자식 FK는 유지했다. `pubg_match_players`, `pubg_match_kills`에 shard를 중복 저장하지 않았다. 자식 Repository의 `findByMatchId(Long)`는 숫자 부모 PK 기준이므로 플랫폼 간 충돌하지 않는다.

## Frontend 변경

아래 경로는 `frontend/src/` 기준이다.

| 파일 | 변경 내용 |
|---|---|
| App.jsx, community/GameScopeBoundary.jsx | community/game/page key로 페이지 상태 새로 생성, 이전 요청 취소 |
| api/requestScope.js | AbortController + 세대 검증. 취소 후 늦은 성공 응답도 AbortError 처리 |
| community/usePubgGame.js, pubgPlatform.js | 선택 CommunityGame 해석, 플랫폼별 멤버 닉네임 선택 |
| components/PubgPlatformBar.jsx, styles/pages.css | PUBG · Kakao/Steam 표시. 두 플랫폼일 때 선택. 전환 시 competitionId 제거 |
| pages/BingoPage.jsx | 폼·선택·집계 상태와 요청을 공통 경계에 연결 |
| pages/KillCompetitionsPage.jsx | 참가/팀/폼/결과 및 요청 격리 |
| pages/TeamMakerPage.jsx | 참가자·결과·manualDamages·옵션 및 요청 격리 |
| pages/MembersPage.jsx | 선택 플랫폼 닉네임 사용. 동기화·편집 상태 및 요청 격리 |
| pages/CommunitySettingsPage.jsx, IntegrationsPage.jsx | 선택 게임의 설정 조회와 활동 링크 |
| pages/GameNicknameSettingsPage.jsx, ActivityRuleSettingsPage.jsx | 게임 변경 시 규칙 편집 상태와 요청 격리 |
| pages/MemberActivitiesPage.jsx, MemberActivityDetailPage.jsx | 활동 목록·상세·동기화 상태 및 요청 격리 |
| components/TemporaryBingoRebuildPanel.jsx | 미리보기/적용 요청 취소 연결 |
| pages/CommunitiesPage.jsx | 게임 PUBG + 플랫폼 Kakao/Steam 표시. 전송 GameType 유지 |
| developer/DeveloperPages.jsx | 한 멤버의 Kakao/Steam 계정 함께 표시 |

테스트용 jsdom/esbuild devDependency와 lock을 추가했다. Backend 단일 응답 필드는 기존 frontend 호환을 위해 유지했다. 새 frontend는 pubgAccounts가 있으면 선택 플랫폼 계정만 표시하고, 해당 계정이 없을 때 다른 플랫폼 닉네임으로 대체하지 않는다.

## 수정한 기존 버그

- Bingo Fact 범위: 플랫폼 + 기간 + 실제 대상 accountId로 조회한 뒤 경기 정책·참가 시각을 검증한다. 해당 경기만 Telemetry 상태를 검사한다. 다른 플랫폼 및 같은 플랫폼의 무관한 계정 Fact가 집계를 막지 않는다.
- Telemetry loaded: strict 경로에서 실제 배열 응답을 수집한 경우만 Fact 저장을 호출한다. URL 누락·수집 실패·파싱 실패·fallback이 loaded=true가 되지 않는다. 과거 플래그는 일괄 추정해 변경하지 않는다.
- timeSurvived / roadKills: API model → Fact 저장 → 재조회 → PubgMatch 복원 값 보존. 과거 NULL은 원본 미상이며 기존 숫자 모델 호환을 위해 0으로 읽는다.
- 기존 진행도 보존: 이미 집계한 경기의 Telemetry 재시도 실패 시 불완전한 Fact로 전체 진행도를 덮어쓰지 않는다.
- 개발자 목록: 다중 플랫폼 계정이 pagination/count를 부풀리지 않는다.
- 임시 재집계: 트랜잭션 밖 계산에서 detached CommunityGame을 읽는 문제를 플랫폼 직접 전달로 해결했다.
- 요청 취소 표시: 플랫폼 전환 및 개발 StrictMode의 정상적인 AbortError를 화면 오류/경고에서 제외했다. 실제 HTTP 오류와 timeout은 계속 표시한다.

## 캐시 및 background scope 감사

| 대상 | 식별 / 결과 |
|---|---|
| Player cache | 기존 shard + 조회 종류 + nickname/accountId 유지 |
| Match cache / in-flight | 기존 shard + matchId 유지 |
| Season cache | 기존 shard; 통계는 shard + accountId + seasonId 유지 |
| Telemetry in-flight | PubgMatchKey(platform,matchId)로 변경 |
| Bingo Fact cache | platform + matchId + 정렬한 대상 account 집합 |
| Bingo 작업/processed ledger | 전역 event PK 및 participant PK가 부모 CommunityGame을 식별 |
| Kill Competition 작업/경기 결과 | 전역 competition PK로 부모 CommunityGame 식별. snapshot 입력 플랫폼 검증 |
| Activity 작업/snapshot | 기존 communityGameId 기준 유지. 입력 계정만 플랫폼화 |
| 임시 Bingo 복구 | event PK lock, preview UUID + communityId/communityGameId 검증 유지 |
| Community frontend cache | communityId의 게임 설정 공통 데이터. 페이지/요청 scope는 communityGameId 포함 |

## 테스트 결과

| 검증 | 전체 | 성공 | 실패/오류 | 건너뜀 |
|---|---:|---:|---:|---:|
| Backend ./mvnw clean package (독립 PostgreSQL 포함) | 531 | 529 | 0 | 2 |
| Frontend npm test | 93 | 93 | 0 | 0 |
| Frontend build / verify:build | 36 경로 | 성공 | 0 | 0 |

건너뛴 테스트는 BingoProductionDumpBaselineTests의 두 운영 덤프 baseline이다. SPRING_DATASOURCE_URL을 지정하지 않았다. PostgreSQL migration 테스트 5개는 모두 실행했으며, 모호한 데이터의 실패/rollback, 예상하지 않은 기존 constraint/index 이름, Discord NULL unique, 동일 Match ID의 두 플랫폼 저장, 재실행, 신규 Fact DDL과 기존 migration 스키마 일치를 검증했다. JAR 패키징도 성공했다. Vite의 500kB 초과 bundle 경고는 남아 있다. 임시 PostgreSQL은 검증 후 종료했다.

계정 동시 저장과 실제 DB 양방향 닉네임 동기화 보존, Competition snapshot 선택, Steam Activity/Team Maker 입력, 동일 Match ID 독립 조회/Telemetry/in-flight/cache, 양방향 Bingo 격리, fallback loaded 판정, 원본 필드 round-trip을 검증했다. React DOM 테스트는 Bingo/Team Maker/Kill Competition 상태 초기화·늦은 응답 차단과 Members/Settings/Integrations의 플랫폼별 표시/링크를 확인했다.
StrictMode에서 실제 요청 취소를 발생시켜 Team Maker·Bingo·Kill Competition의 오류/경고가 표시되지 않는지 검증했고, 실제 503 오류는 계속 표시됨을 확인했다.

## Kakao 회귀 결과

아래 정상은 자동 테스트 범위의 결과다. 실제 Discord/PUBG 계정 및 운영 DB로 수행한 검증이 아니며 배포 후 확인은 별도로 필요하다.

| 핵심 기능 | 결과 |
|---|---|
| 커뮤니티 생성 | 정상 |
| 닉네임 동기화 | 정상 |
| 클랜원 조회 | 정상 |
| 활동 조회 | 정상 |
| 빙고 생성 | 정상 |
| 빙고 참가 | 정상 |
| 빙고 집계 | 정상 |
| 봇 제외 빙고 | 정상 |
| 킬내기 생성 | 정상 |
| 킬내기 참가 | 정상 |
| 킬내기 정산 | 정상 |
| 킬내기 승리 → Bingo 미션 | 정상 |
| 팀 만들기 | 정상 |
| 랭킹 점수 | 정상 |
| 개발자 페이지 조회 | 정상 |

## Steam 지원 결과

| 기능 | 현재 코드 지원 / 자동 검증 |
|---|---|
| 커뮤니티 생성 | 기존 Steam GameType 생성 및 shard 매핑 검증 |
| 닉네임 동기화 | Steam 계정만 교체, Kakao 보존. DB 및 unit 검증 |
| 활동 | Steam 계정 및 API shard 선택 검증. 공통 활동 계산 사용 |
| 빙고 | 공통 미션 엔진, Steam 참가·Fact 조회/집계·반대 플랫폼 미완료 Telemetry 격리 검증 |
| 킬내기 | Steam snapshot, 생성·참가·시작·정산 흐름 및 steam Aggregator 입력 검증 |
| 팀 만들기 | Steam 계정 시즌 딜량 조회. 기존 TPP squad 정책 유지 |
| 개발자 페이지 | 두 계정 동시 표현. 멤버 row와 total 유지 검증 |

실제 Steam 계정으로 외부 API를 호출하는 운영 점검은 추가로 필요하다. 모호한 계정/이벤트 출처를 해결하지 못하면 migration 및 배포를 보류해야 한다. Clan API, Leaderboard, FPP 선택, 랭킹 재설계, Discord 도메인 변경은 하지 않았다.

## 운영 서버 적용 작업

[배포 안내](PUBG_PLATFORM_DEPLOYMENT.md)의 명령과 순서를 따른다: Git 반영/build → 모든 writer 중지 → DB 백업 → 확장 SQL → 감사 및 증거 기반 수동 해결 → 강화 SQL → 새 JAR/전체 frontend dist 배포 → ddl-auto=validate 기동 → Kakao 우선 점검 → Steam 점검. 실제 서버/운영 DB 적용은 수행하지 않았다.

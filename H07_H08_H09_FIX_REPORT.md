# H07·H08·H09 수정 결과 — 2026-10-05

수정 범위는 빙고 재집계의 파생 상태 정합성, 최종 정산의 수집 완료 판단, 오래된 집계의 반영 방지다. 다른 High/Medium/Low 항목과 기존 커뮤니티 관련 작업 트리 변경은 수정하지 않았다. 운영 DB에는 연결하지 않았으며 배포·운영 데이터 보정도 실행하지 않았다.

1. **실제 호출 경로와 수정 전 증거**

   전체 집계는 `BingoController → BingoAggregationJobService → BingoAggregationService.aggregate → prepareAll → PubgMatchSyncService → PubgMatchFactQueryService → calculate`를 따른다. 개인 집계는 `aggregatePersonal → preparePersonal → calculateParticipant`를 사용한다. 두 계산 모두 `calculateLockedParticipant → BingoMissionEngine → BingoProgressCompletionService → BingoParticipant`를 통과한다.

   준비 단계가 상태를 갱신하여 종료된 이벤트를 SETTLING으로 만들고, 전체 계산 단계가 종료 범위의 exclusive 상한과 30분 유예를 확인하여 최종 완료한다. 개인 집계는 기존과 같이 이벤트 전체의 최종 완료를 확정하지 않는다. 이벤트 수정·취소, 참가 등록·계정 동기화, 킬내기 우승 실적의 추가/삭제 경로도 함께 추적했다. 우승 실적 추가는 `updateLines`, 삭제는 기존 `rebuildLines`를 사용한다.

   기존 코드에 회귀 테스트부터 추가했다. 첫 실행에서 20건 중 19건이 실패했고, 완료 취소를 직접 검증하는 추가 감소 테스트도 전체/개인 두 경우 모두 실패했다. 기대값을 낮추지 않고 완료 취소·수집 실패·stale 반영을 재현했다.

   IntelliJ `ij-debugger` 로그포인트의 수정 전 실행 값은 `completeRecalculation=true`, `changed=true`, `lineCount=8`, `targetAt=2026-09-22T12:00:00Z`, `cellAt=2026-09-22T11:50:00Z`였다. 셀 완료 시각과 목표 완료 시각이 다른 사실도 확인했다. 실행 값은 집계 서비스의 계산 경로에서 수집했고, 짧게 끝나는 테스트의 로그를 읽기 위해 테스트 단언 직전에 정지하여 호출 스택을 확인했다. 생성한 로그포인트·중단점과 디버그 세션은 정리했다.

2. **H07 원인·변경·동작 비교**

   전체 재계산의 `replaceSnapshot`은 완료 셀을 미완료로 되돌릴 수 있었다. 그러나 뒤이어 호출하는 `updateLines`는 새 줄만 추가했고, `BingoParticipant.updateLines`는 미달성 시 기존 목표·블랙빙고 완료 시각을 제거하지 않았다.

   [BingoAggregationCalculationService](src/main/java/com/guildup/bingo/service/BingoAggregationCalculationService.java)는 감소 가능한 전체 재계산에서 기존 `rebuildLines`를 호출한다. 셀 값이 달라지지 않은 경우에도 파생 상태를 다시 확인하여 이전 오류가 남지 않게 했다. 완료 시각 자체의 불일치도 셀 snapshot 변경으로 판단한다. 계정이 해제된 참가자도 완전 재계산에서는 기존 PUBG 진행도를 비우고 파생 상태를 다시 만든다.

   `rebuildLines`는 현재 완료 셀로 줄 목록을 만들고, 줄 완료 시각은 구성 셀 완료 시각의 최댓값으로 계산한다. 목표 시각은 목표 개수에 해당하는 줄 완료 시각, 블랙빙고 시각은 모든 셀 완료 시각의 최댓값이다. 목표/블랙빙고가 취소되면 시각은 null이 된다. 참가자의 완료 표시와 순위에 전달하는 시각도 같은 값이다. 이 기존 서비스와 참가자의 `replaceDerivedProgress`를 재사용했으며 별도의 순위·완료 정책을 추가하지 않았다. 안전한 증분 경로는 기존 `updateLines`를 유지한다.

   이전에는 8줄 완료에서 1줄/0줄로 줄어도 이전 줄과 완료 시각이 남았다. 변경 후 줄 목록·lineCount·목표·블랙빙고 시각이 현재 셀과 일치하고, 다시 조건을 충족하면 새로운 실적의 완료 시각으로 복원된다.

3. **H08 원인·변경·동작 비교**

   계산 단계는 수집 성공 여부를 받지 않고 상태와 시간만으로 `complete`를 호출했다. Player 누락은 로그뿐이었고, Match 저장 실패 정보도 완료 판단에 전달되지 않았다. Telemetry 실패는 완료 처리 후 응답에 경고로 붙었다. 빈/부분 fact도 저장 경계에서 충분히 검증하지 않았다.

   [PubgMatchSyncService](src/main/java/com/guildup/pubg/service/PubgMatchSyncService.java)의 수집 결과에 실패 단계와 데이터 ID를 추가했다. `PLAYER_LOOKUP`, `MATCH_LOOKUP`, `MATCH_SAVE`, `TELEMETRY_LOOKUP`, `TELEMETRY_SAVE`, `FACT_GENERATION`을 구분한다. 조회 예외와 결과 누락을 모두 기록하며, Telemetry 조회·파싱·저장 실패는 기존 단계 구분을 재사용한다. [PubgMatchFactWriter](src/main/java/com/guildup/pubg/service/PubgMatchFactWriter.java)는 필요한 플레이어 fact가 빠졌으면 loaded로 저장하지 않는다.

   [BingoAggregationService](src/main/java/com/guildup/bingo/service/BingoAggregationService.java)는 수집 실패, 정산 범위의 미완료 Telemetry, 기존 집계 원본의 확보 여부를 확인한다. 불완전한 경우 정상 실적은 안전한 증분 경로로 보존하고 완전 재구성 및 이벤트 최종 확정을 보류한다. `calculate`는 수집 완료와 완전 재계산이 모두 가능할 때만 기존 시간·상태 조건에 따라 COMPLETED로 전환한다.

   미수집 Match ID는 이벤트의 `pubg_bingo_pending_matches`에 보관한다. 다음 집계는 이 ID와 기존 집계 이력을 최신 Player 목록에 합쳐 재시도하므로, 실패한 경기나 기존 원본이 최근 목록에서 빠져도 잊지 않는다. 수집 성공 시 보류 ID를 제거한다. Telemetry 미완료 경기는 기존 저장 Match 조회 경로로 재시도한다. 보류 ID 반영도 H09 검증을 통과한 계산 트랜잭션에서만 수행한다.

   [BingoAggregationResponse](src/main/java/com/guildup/bingo/dto/BingoAggregationResponse.java)는 수집 실패를 전달하고, [BingoAggregationJobService](src/main/java/com/guildup/bingo/service/BingoAggregationJobService.java)는 Match만 실패한 경우도 사용자 메시지와 운영 이력에 남긴다. 사용자 메시지는 실패 단계와 ID, 최종 확정 보류 및 재시도를 설명한다. 기존 `COMPLETED_WITH_WARNINGS`는 수집 작업 종료 상태이고, 데이터가 부족한 이벤트의 DB 상태는 SETTLING을 유지한다.

   이전에는 일부 수집 실패 후 이벤트가 COMPLETED가 되어 다음 집계가 막혔다. 변경 후 실패 시 SETTLING을 유지하고 정상 progress를 보존한다. 다음 수집 성공 후 모든 필요한 fact로 재계산하여 완료하며, 완료 후 중복 요청은 기존 검증으로 거절되어 결과·완료 시각이 바뀌지 않는다. 기존 30분 집계 간격은 유지한다.

4. **H09 원인·변경·동작 비교**

   기존 row lock은 준비 트랜잭션과 계산 트랜잭션 각각만 보호했다. 외부 호출 사이의 변경을 막거나 감지하지 못했고, 계산 단계는 준비 입력과 최신 엔티티를 비교하지 않은 채 오래된 fact 목록을 적용했다.

   [BingoAggregationGuard](src/main/java/com/guildup/bingo/service/BingoAggregationGuard.java)를 추가하고 기존 `BingoEvent.@Version`을 작업 세대로 재사용한다. [준비 서비스](src/main/java/com/guildup/bingo/service/BingoAggregationPreparationService.java)는 전체·개인 모두 이벤트 잠금을 얻고 동기화를 flush한 뒤 `PESSIMISTIC_FORCE_INCREMENT`로 세대를 증가시킨다. 새로운 버전 컬럼이나 분산 락 시스템은 추가하지 않았다. 외부 호출 동안 DB 잠금을 유지하지 않는다.

   스냅샷은 이벤트 ID·버전·상태·게임/플랫폼·시작/종료, 봇/클랜 조건, 보드 크기·목표·블랙빙고·늦은 참가 설정, 참가자 목록·버전·적용 시작·계정·연결 멤버 상태, 플랫폼별 클랜 계정 목록, 셀 미션·집계 방식·비교 연산·임계값·옵션을 포함한다. 실제 DB 저장 정밀도로 스냅샷을 잡고 숫자 scale을 정규화하여 정상 작업의 오인을 방지한다.

   전체·개인 계산은 같은 이벤트 잠금 순서로 최신 스냅샷을 비교하고, 실제 수집 입력이 준비 스냅샷과 일치하는지도 확인한다. 준비 중 계정이 교체되어 스냅샷과 수집 계정이 어긋나는 경합도 거절한다. 검증은 progress·줄·완료 시각·집계 이력·보류 Match·마지막 집계 시각을 쓰기 전에 실행한다. 기간 조건도 최종 계산에서 다시 적용한다.

   이전에는 A 시작 → B 시작 → B 완료 → A 완료 시 A가 B의 진행도를 덮을 수 있었다. 변경 후 같은 이벤트의 새 전체/개인 준비가 이전 작업을 무효화하고, 오래된 A는 409로 거절된다. 실데이터 테스트에서 B가 반영한 값 12와 완료 상태가 보존되었고, 이후 정상 재집계도 가능했다. 공유 PUBG 원본 fact의 수집·저장은 유지되며 stale 작업의 빙고 결과 반영만 거절한다.

   같은 이벤트에서 서로 다른 참가자의 개인 집계가 겹치는 경우에도 이벤트 세대 기준을 적용한다. 새 준비가 시작되면 기존 작업은 재시도가 필요할 수 있다. 기존 Job key와 중복 클릭 방지는 유지한다.

5. **추가 회귀 테스트**

   [BingoAggregationConsistencyFlowTests](src/test/java/com/guildup/bingo/BingoAggregationConsistencyFlowTests.java), [BingoAggregationConcurrencyFlowTests](src/test/java/com/guildup/bingo/BingoAggregationConcurrencyFlowTests.java)에 40건, [Job 서비스 테스트](src/test/java/com/guildup/bingo/service/BingoAggregationJobServiceTests.java)에 1건을 추가했다. 매개변수 테스트 한 건에서 단계별 전이나 전체/개인 두 경로를 검증하는 경우도 있다.

   - H07: 미완료→완료, 완료→미완료, 8줄→1줄→0줄, 목표/블랙빙고 취소, 재달성, 완료·근거 시각 제거/복원, 반복 재계산의 시각 불변, 계정 해제. 전체·개인 경로를 모두 검증한다.
   - H08: 정상 최종 완료, Player 누락/조회 예외, Match 누락/조회 예외, Match 저장 실패, Telemetry 조회/저장 실패, 빈 fact 생성 실패, 각 실패 후 정상 재시도, 정상 progress 보존, 최근 Player 목록에서 빠진 실패 Match와 기존 원본 복구, 중복 완료 요청의 결과 불변, Telemetry 실패가 0이어도 Match 실패를 Job 메시지에 표시.
   - H09: 수집 중 취소, 기간 연장, 봇/클랜 옵션 변경, 계정 교체, 적용 시작 변경, 멤버 상태 변경, 참가자 추가, 준비 중 계정 교체, 전체/개인 역순 완료의 네 조합, 변경 없는 정상 처리, stale 거절 이후 재집계, 늦은 참가자의 DB 정밀도 차이. 래치와 별도 트랜잭션으로 실제 commit 순서를 강제하며 외부 호출에 DB 트랜잭션이 없는지도 검증한다.

   [PubgMatchPersistenceTests](src/test/java/com/guildup/pubg/PubgMatchPersistenceTests.java)의 플랫폼 분리 테스트는 빈 fact로 loaded 상태를 만들던 입력을 정상 플레이어 fact로 교체했다. 기존 플랫폼 분리 기대값은 유지했고, 빈 fact 거절은 새 H08 회귀 테스트가 검증한다.

6. **백엔드 검증**

   최종 실행 명령은 `./mvnw test`다. **631건 중 589건 실행, 실패 0, 오류 0, 조건부 건너뛰기 42건, BUILD SUCCESS**였다. 기본 실행에서 PostgreSQL 환경 조건과 생산 덤프 조건에 따른 테스트를 건너뛰었다. 새 PostgreSQL 테스트는 아래 별도 실행에서 검증했다. 일반/최종/개인 집계, 봇 제외·클랜 조건, 킬내기 우승 실적, Steam/Kakao 분리, 기존 완료 표시·시각 관련 테스트를 포함한다.

7. **PostgreSQL 검증**

   PostgreSQL 16.15를 `/tmp`의 새 데이터 디렉터리와 `127.0.0.1:55439`에 기동했다. 테스트 DB `guildup_bingo_test`에만 연결했고 실제 PUBG 호출은 mock으로 대체했다. [정합성 PostgreSQL 테스트](src/test/java/com/guildup/bingo/BingoAggregationConsistencyPostgresTests.java) 26건과 [동시성 PostgreSQL 테스트](src/test/java/com/guildup/bingo/BingoAggregationConcurrencyPostgresTests.java) 14건을 별도로 실행하여 **40건 모두 통과, 실패·오류·건너뛰기 0건, BUILD SUCCESS**였다. 테스트 종료 후 임시 PostgreSQL 서버도 종료했다.

   [미수집 Match 테이블 SQL](src/main/resources/db/manual/add_bingo_pending_matches.sql)은 격리 스키마에서 두 번 실행하여 반복 실행 가능성과 이벤트 삭제의 ON DELETE CASCADE를 확인했다. 테스트 스키마는 rollback했으며 운영 적용은 하지 않았다. 배포 시에는 기존 Hibernate schema update 또는 이 수동 SQL로 테이블을 준비해야 한다.

8. **프런트엔드·diff 검증**

   프런트엔드 코드·UI·라우트는 변경하지 않았다. 기존 Job 상태/필드를 유지하며 메시지로 수집 보류·재시도를 전달한다. `npm test`는 93건 통과, 실패 0건이고, `npm run build`는 성공했다. 프로덕션 빌드 검증은 36개 경로와 JS 번들 1개를 확인했다. 기존 완료 순위는 정정된 목표/블랙빙고 시각을 사용한다.

   `git diff --check`는 종료 코드 0으로 통과했다. 새 파일 7개도 `git diff --no-index --check`로 검사하여 공백 오류가 없음을 확인했다.

9. **이번에 수정하지 않은 항목과 메모**

   H07·H08·H09 외의 High/Medium/Low 항목은 수정하지 않았다. 작업 시작 전에 있던 커뮤니티 멤버십·닉네임 동기화·정리 테스트와 원래 종합 점검 보고서는 보존했다. `ExternalAccountProvider.java`도 변경하지 않았다. 관련 없는 구조 변경·UI 변경은 하지 않았다.

   별도 메모: DB가 집계 시각을 마이크로초로 반올림하면 나노초 Clock에서 정확히 30분을 더한 시점이 기존 cooldown보다 아주 조금 이를 수 있었다. 이번 범위의 수정에는 포함하지 않고 독립 트랜잭션 테스트의 재시도는 30분 1초 이후로 설정했다. 프런트엔드 빌드의 기존 500 kB 초과 번들 경고도 수정하지 않았다. 기존 보고서의 경기 수집 API 최신 목록 범위 제한 등 다른 항목은 그대로 남는다.

최종 검증 로그는 [전체 백엔드](/tmp/guildup-backend-full-tests.log), [격리 PostgreSQL](/tmp/guildup-bingo-postgres.log), [프런트엔드 테스트](/tmp/guildup-h07-h09-frontend.log), [프런트엔드 빌드](/tmp/guildup-h07-h09-frontend-build.log)에 남겼다. 수정 전 실패 증거는 [최초 회귀 테스트](/tmp/guildup-h07-h09-before.log), [완료 취소 회귀](/tmp/guildup-h07-before.log), [준비 단계 계정 경합](/tmp/guildup-prepare-race-before.log)에 남겼다.

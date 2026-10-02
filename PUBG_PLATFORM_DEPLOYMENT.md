# PUBG Kakao / Steam 플랫폼 분리 배포

이번 변경은 `BATTLEGROUNDS_KAKAO`, `BATTLEGROUNDS_STEAM`과 기존 HTTP API 경로를 유지한다. 빙고 미션 엔진과 킬내기 점수 계산을 복제하지 않는다. 운영 DB에는 아직 적용하지 않았다.

## 서버에서 적용할 순서

로컬 DB 변경은 서버 DB에 전달되지 않는다. 이번 변경 파일과 새 SQL 파일을 배포 브랜치에 commit/push한 뒤 서버에서 받아야 한다. 새 앱이 먼저 실행되면 로컬에서 봤던 migration 미완료/UNIQUE 오류가 다시 발생할 수 있으므로, 자동 배포가 있다면 새 앱 시작을 DB 전환 이후로 맞춘다.

1. 새 코드를 받고 백엔드/프론트엔드를 빌드한다. 아래 자세한 절차의 빌드 명령을 사용한다.
2. 현재 사용 중인 방식(systemd, Docker, 직접 실행)으로 **모든 백엔드 인스턴스**를 중지한다.
3. 기존 서버 DB 접속 설정을 확인하고 `pg_dump`로 **서버 DB 자체**를 백업한다. 로컬 백업을 서버 DB에 복원하지 않는다.
4. 저장소 루트에서 서버 DB에 다음 파일을 차례로 적용한다. `psql`은 서버 DB의 `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`와 기존 인증 설정을 사용해야 한다.

   ```bash
   psql -X -v ON_ERROR_STOP=1 -f src/main/resources/db/manual/add_pubg_platform_boundaries.sql
   psql -X -v ON_ERROR_STOP=1 -f src/main/resources/db/manual/audit_pubg_platform_boundaries.sql
   ```

   감사 SQL의 **첫 번째 결과(미해결 계정)**와 **두 번째 결과(잘못된 이벤트 게임 연결)**가 모두 0건인지 확인한다. 남아 있으면 여기서 중단하고 출처를 확인한다. 이후 결과의 과거 Telemetry/원본 통계/snapshot 점검 행은 별도 판단 대상이다.

   ```bash
   psql -X -v ON_ERROR_STOP=1 -f src/main/resources/db/manual/enforce_pubg_platform_boundaries.sql
   ```

5. 새 JAR과 `frontend/dist/` 전체를 배포하고, 기존 DB·Discord·PUBG 설정을 유지한 채 백엔드를 시작한다. 기동 시 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` 또는 `--spring.jpa.hibernate.ddl-auto=validate`를 적용한다.
6. Kakao 클랜원 목록과 닉네임 동기화를 먼저 확인하고 빙고·킬내기·활동·팀 만들기를 점검한다. 이후 Steam 기능을 확인한다.

`pubg_matches` 등 필수 테이블이 없는 서버의 초기화 순서, 백업 명령, 데이터가 모호할 때의 중단/복구 절차는 아래 자세한 절차를 따른다. migration 중 오류가 나면 다음 SQL이나 새 앱 시작으로 넘어가지 않는다.

## 로컬 적용 기록 (2026-10-02)

사용자가 로컬 개발 DB임을 확인한 `localhost:5432/guildup`에 백업 후 **1단계 → 감사 → 2단계**를 적용했다. 해당 커뮤니티에 Kakao CommunityGame이 정확히 하나인 기존 PUBG 계정 95개의 플랫폼을 KAKAO로 채웠다. 모호한 계정/이벤트는 없었다. 변경 전후 38개 테이블을 비교해 플랫폼 보정 외의 기존 데이터가 보존됐음을 확인했다. Discord 계정 98개, 빙고 3개, 킬내기 6개도 보존됐다.

백업과 migration/감사 로그는 `~/Desktop/guildup-backups/pubg-platform-20261002-160737/`에 있다. IntelliJ의 `GuildupBackendApplication` 실행 설정으로 다시 시작했고, 이번 실행에만 `--spring.jpa.hibernate.ddl-auto=validate`를 적용해 스키마 검증을 통과했다. 운영 DB 적용 기록은 아니다.

재시작 후 로컬 클랜원 화면에서 실제 인게임 닉네임 동기화를 실행해 활성 클랜원 86명 중 84명 동기화를 확인했다. 2명은 PUBG 계정을 확인하지 못한 것으로 안내됐으며, 기존 UNIQUE 충돌에 의한 500 오류는 발생하지 않았다. 실행 결과 화면은 위 백업 디렉터리의 `nickname-sync-result.png`에 저장했다.

## 스키마와 데이터 정책

| 테이블 | 최종 변경 |
|---|---|
| `community_member_accounts` | nullable `platform varchar(20)`; PUBG에는 KAKAO/STEAM 필수, 다른 provider에는 NULL 필수 |
| 위 테이블의 PUBG 유일성 | `(community_member_id, provider, platform)`, `(community_id, provider, platform, external_user_id)` |
| 위 테이블의 다른 provider 유일성 | `provider <> 'PUBG'` 부분 unique index 두 개로 기존 Discord 등의 중복 방지를 유지 |
| `pubg_matches` | 기존 `match_id` 단독 unique 제거, `(shard, match_id)` unique. 숫자 PK와 자식 FK 유지 |
| `pubg_match_players` | nullable `time_survived double precision`, `road_kills integer` |
| `pubg_bingo_events`, `pubg_kill_competitions` | 감사 후 `community_game_id NOT NULL`; `(community_game_id, community_id)` FK로 소속 커뮤니티까지 검증 |
| `community_games` | 위 복합 FK를 위한 `(id, community_id)` unique |

자동 보정은 커뮤니티에 **알려진 PUBG CommunityGame이 정확히 하나**인 경우만 한다. 다른 게임은 PUBG 게임 수에 포함하지 않는다. Kakao 하나면 KAKAO, Steam 하나면 STEAM이다. PUBG 게임이 없거나 두 플랫폼이 모두 존재하면 account platform과 기존 이벤트의 NULL game 연결을 그대로 남긴다. 기존 숫자 ID, 계정 ID, 닉네임, 참가자, 진행도, 점수, Match shard는 변경하지 않는다.

과거 Fact의 생존시간과 로드킬은 원본을 복구할 수 없으므로 DB에 NULL로 남긴다. 기존 숫자 모델 호환을 위해 재구성 시 0으로 읽지만, 이 값은 실제 0으로 확인된 기록이 아니다. 과거의 잘못된 `telemetry_loaded=true` 여부도 일괄 추정·수정하지 않는다. 감사 SQL의 의심 행과 과거 대회 참가 계정 snapshot은 증거를 보고 별도로 판단한다. snapshot 차이는 정상적인 계정 변경일 수도 있다.

## 기존 설치 적용 순서

저장소에는 운영 systemd/Docker/정적 호스트 배포 정의가 없다. 중지·재시작·정적 파일 전송에는 운영 중인 방식을 사용한다. 아래 명령은 저장소 루트에서 실행한다. PostgreSQL 접속은 운영의 `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSFILE` 또는 `PGSERVICE`를 설정하고 확인한 뒤 실행한다. JDBC URL을 `psql` URL로 그대로 넘기지 않는다.

1. 변경을 검토하고 배포할 Git 브랜치에 반영한 뒤 서버 checkout을 갱신한다. 빌드는 운영 DB에 연결하지 않는다.

   ```bash
   git pull --ff-only
   env -u SPRING_DATASOURCE_URL -u TEST_POSTGRES_URL ./mvnw clean package
   npm --prefix frontend ci
   npm --prefix frontend test
   npm --prefix frontend run build
   npm --prefix frontend run verify:build
   ```

   빌드 명령은 해당 실행에서만 DB 관련 테스트 환경변수를 제외한다. 서버에 설정된 운영 URL 때문에 덤프 baseline 또는 PostgreSQL create-drop 테스트가 실행되지 않도록 하기 위함이다. PostgreSQL 전용 검증은 별도 테스트 DB에서 이미 수행했으며, 아래 운영 기동의 접속 설정은 그대로 유지한다.

2. 점검 모드에서 **모든 기존 앱 인스턴스**를 중지한다. HTTP 쓰기뿐 아니라 JDA 동기화, 예약 집계, 백그라운드 작업도 중지되어야 한다. 기존/새 버전 동시 실행은 이 배포에서 지원하지 않는다.

3. 백업을 확보하고 접속 대상과 기존 필수 테이블을 확인한다.

   ```bash
   pg_dump --format=custom --file=guildup-before-pubg-platform.dump
   psql -X -v ON_ERROR_STOP=1 -c 'SELECT current_database(), current_schema();'
   psql -X -v ON_ERROR_STOP=1 -c "SELECT to_regclass('community_member_accounts'), to_regclass('community_games'), to_regclass('pubg_matches'), to_regclass('pubg_match_players'), to_regclass('pubg_bingo_events'), to_regclass('pubg_kill_competitions');"
   ```

   Fact 테이블이 아직 없는 설치에서만 기존 Fact 초기화 DDL을 먼저 적용한다. 다른 기능의 기본 테이블도 이미 존재해야 한다.

   ```bash
   psql -X -v ON_ERROR_STOP=1 -f src/main/resources/db/manual/add_pubg_match_facts.sql
   ```

4. 1단계 확장 및 안전 보정 SQL을 적용하고 읽기 전용 감사를 실행한다.

   ```bash
   psql -X -v ON_ERROR_STOP=1 -f src/main/resources/db/manual/add_pubg_platform_boundaries.sql
   psql -X -v ON_ERROR_STOP=1 -f src/main/resources/db/manual/audit_pubg_platform_boundaries.sql
   ```

   NULL/잘못된 플랫폼, NULL/다른 커뮤니티의 이벤트 게임 연결은 보정 증거가 없으면 배포를 진행하지 않는다. 해당 row의 ID와 출처를 확인한 운영 SQL로만 보정한다. 일괄 KAKAO 지정, 계정/이벤트 삭제, 진행도 초기화는 하지 않는다. 해결할 수 없으면 이전 앱을 유지하고, 2단계와 새 앱 기동을 보류한다. 1단계는 기존 unique를 유지하므로 기존 앱의 계정 저장 계약을 바꾸지 않는다. 1단계 이후 기존 앱을 다시 실행했다면 최종 전환 전에 다시 중지하고 1단계와 감사를 재실행한다.

5. 감사 결과를 해결한 뒤 2단계 제약 강화를 적용한다.

   ```bash
   psql -X -v ON_ERROR_STOP=1 -f src/main/resources/db/manual/enforce_pubg_platform_boundaries.sql
   ```

   이 SQL은 unresolved account/event를 발견하면 **기존 제약을 제거하기 전에 실패**한다. 중간 오류도 트랜잭션 전체를 rollback한다. 실제 기존 constraint/index 이름은 컬럼 구성을 통해 찾아 제거한다. 테이블에 ACCESS EXCLUSIVE 잠금을 사용하므로 앱이 중지된 점검 시간에 실행한다.

6. 새 JAR과 `frontend/dist/` **전체**를 기존 운영 배포 방식으로 교체하고 같은 설정/secret으로 새 백엔드를 시작한다. 권장 Hibernate 설정은 스키마 자동 변경이 아닌 검증이다.

   ```bash
   java -jar target/guildup-backend-0.0.1-SNAPSHOT.jar --spring.jpa.hibernate.ddl-auto=validate
   ```

   위 명령은 저장소에서 확인되는 직접 실행 예시다. 운영 프로세스 관리자에서는 동일 JAR/옵션을 기존 시작 설정에 반영한다. 새 앱은 2단계 완료 전 시작하지 않는다. `ddl-auto=update`가 migration을 대체하지 못하며 PostgreSQL 부분 index를 생성하지도 않는다.

7. Kakao 기존 계정·빙고·킬내기를 먼저 확인하고 Steam 계정 동기화·활동·팀 만들기·참가·집계를 확인한다. 같은 커뮤니티에서 두 플랫폼을 전환해 닉네임, 선택 항목, 폼, 결과가 섞이지 않는지 확인한다. 개발자 페이지의 멤버 수와 페이지 수가 이전과 같은지 확인한다.

2단계 이후 기존 바이너리로 단순 되돌리면 PUBG platform 없는 INSERT가 거부된다. 1단계까지의 보류와 2단계 이후의 rollback을 구분한다. 2단계 이후에는 검증한 백업 복구 또는 별도로 검토한 역 migration이 필요하며, 새로 저장된 Steam 데이터가 있는 상태에서 일괄 platform 제거를 하지 않는다.

## 닉네임 동기화에서 기존 계정 UNIQUE 오류가 나는 경우

`uk_community_member_account_community_provider_user` 또는 `uk_community_member_account_provider` 오류와 함께 기존 PUBG 계정의 `platform`이 NULL이라면, 새 코드에 필요한 수동 migration이 아직 완료되지 않은 상태다. `ddl-auto=update`로 새 컬럼/제약이 생겨도 기존 계정의 플랫폼이나 기존 unique 제약은 자동 전환되지 않는다. 동기화 코드는 플랫폼 미지정 계정이 있는 커뮤니티에서 PUBG API 호출/계정 삭제 전에 409와 migration 필요 안내를 반환한다.

해당 계정을 삭제하거나 모든 계정을 KAKAO로 지정하지 않는다. 위 기존 설치 절차대로 쓰기를 중지하고 백업한 뒤 **1단계 → 감사 → 2단계**를 완료하고 앱을 다시 시작한다. 1단계만 적용하면 Kakao/Steam 동시 계정을 막는 기존 unique가 남으므로 플랫폼 분리는 완료되지 않는다.

## 신규 설치와 스키마 일치

기존 manual 디렉터리는 전체 빈 DB 초기화 migration 집합이 아니다. 신규 설치는 현재 JPA 모델/기존 초기화 절차로 기본 스키마를 **사용자 쓰기 없이** 만든 다음, 현재 `add_pubg_match_facts.sql`, 1단계 SQL, 감사 SQL, 2단계 SQL을 위 순서로 적용한다. 두 단계 SQL은 재실행할 수 있다. 신규 설치에도 2단계가 필수이며, 이것이 PostgreSQL의 non-PUBG 부분 unique index와 이벤트 소유권 FK를 보장한다. Fact 신규 DDL과 기존 Fact + migration의 컬럼/제약 일치는 PostgreSQL 자동 테스트로 검증한다.

## 자동 검증

기본 테스트는 H2와 mock 외부 API를 사용한다. PostgreSQL 전용 테스트는 별도로 준비한 **폐기 가능한 테스트 DB**만 사용한다. 기존 PostgreSQL 통합 테스트는 `create-drop`이므로 운영 DB URL을 절대 지정하지 않는다.

```bash
TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55439/guildup_platform_test TEST_POSTGRES_USER=postgres ./mvnw test
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run verify:build
```

예시 포트/DB는 이번 검증에 사용한 임시 DB다. 다시 실행하려면 먼저 자신의 별도 테스트 DB를 만들고 URL을 바꾼다. `SPRING_DATASOURCE_URL`을 지정하지 않아 운영 덤프 기반 baseline 두 테스트를 제외했다. Kakao 닉네임 동기화는 위 로컬 기록대로 실제 API를 사용해 확인했다. Steam 실제 API와 서버 운영 데이터는 검증하지 않았으므로 배포 후 위 점검이 필요하다.

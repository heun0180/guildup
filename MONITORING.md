# 클랜원 활동 조회 오류 모니터링

활동 조회 실행이 실제로 실패하면 개발자 모니터링에 `SYSTEM` / `ERROR` /
`COMMUNITY_ACTIVITY_SYNC_FAILED`를 기록한다. Community ID, 요청한 User ID,
communityGameId, 처리 시간, 예외 클래스, HTTP 상태 및 PUBG 오류 코드와 upstream
상태를 확인할 수 있다. 예외 메시지 원문, SQL, 요청 본문과 인증 정보는 저장하지 않는다.

HTTP 응답이 5xx이면 별도의 `HTTP_5XX` 이벤트와 서버 로그 요약도 기록한다.
따라서 하나의 실패에 업무 이벤트와 HTTP 이벤트가 함께 보일 수 있다.
PUBG API 재시도 등은 기존 `PUBG_API` 이벤트로 확인한다.

권한 부족, 설정 누락, 이미 진행 중인 조회, 재조회 대기시간 같은 일반 4xx 요청
거절은 운영 장애 목록에 기록하지 않는다. 브라우저 자체 오류나 통신 연결 실패는
서버에 요청이 도달하지 않을 수 있으므로 이 서버 이벤트 목록으로 전부 확인할 수 없다.
기록 기능이 도입되기 전의 오류는 소급해서 추가되지 않는다.

## 배포

기존 monitoring_events 테이블이 있는 DB에 아래 SQL을 배포 전에 실행한다.
Hibernate가 자동 생성한 기존 event_code CHECK 제약에 새 코드를 허용하기 위한 SQL이다.

```sh
psql -d guildup -v ON_ERROR_STOP=1 \
  -f src/main/resources/db/manual/add_community_activity_monitoring_event_code.sql
```

새 backend JAR를 배포하고 서버를 재시작한다. 이번 변경은 환경변수와 프론트 빌드
변경이 필요하지 않다. 메모리 기반 로그인 세션은 재시작 후 다시 로그인해야 한다.
개발자 모니터링에서 Event Code `COMMUNITY_ACTIVITY_SYNC_FAILED`로 조회하면 된다.

## 실제 장애나 외부 API 호출 없이 검증

```sh
./mvnw -Dtest=CommunityMemberActivitySyncFlowTests,CommunityMemberActivityMonitoringTests test
```

H2와 모의 PUBG 응답을 사용하여 이벤트 저장, HTTP 503 기록, 429 재조회 거절 제외,
기존 활동 데이터 유지 및 모니터링 저장 실패 시 원래 예외/상태 처리를 검증한다.
실제 DB나 운영 모니터링 목록에는 테스트 이벤트를 남기지 않는다.

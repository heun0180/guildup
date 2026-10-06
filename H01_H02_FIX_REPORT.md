# H01 · H02 수정 결과

검증일: 2026-10-06. 수정 범위는 로그인 성공 시 세션 ID 교체와 세션 인증 API의 CSRF 방어다.
Discord OAuth, HttpSession, 기존 community/developer interceptor와 OWNER / ADMIN / MEMBER 정책을 유지했다.
운영 DB에 연결하지 않았으며 테스트는 H2와 로컬 임의 포트의 실제 Tomcat을 사용했다.

## 1. H01 실제 원인

`DiscordLoginController.authorize()`가 로그인 전 HttpSession에 OAuth state와 callback URI를 저장한다.
`callback()`은 state를 검증·소비하고 저장한 URI로 Discord code를 교환한 뒤 `DiscordLoginService.findOrCreateUser()`를 호출한다.
기존 코드는 같은 세션에 `LOGIN_USER_ID`만 저장했으며 ID를 교체하지 않았다.
OAuth state는 해당 로그인 시도를 검증하지만 인증 전후 세션 ID의 재사용을 막지는 않았다.

구현 전에 추가한 세션 ID 비교가 실제로 실패했다. IntelliJ의 로그인 성공 후 관찰에서도
`session.getId().equals(beforeLoginId)`가 `true`였다. 확인에 사용한 agent breakpoint는 제거했고 기존 user breakpoint는 보존했다.

## 2. H02 실제 원인

`CommunityAccessInterceptor`는 세션 사용자와 membership을 검사하고 `CommunityAccessService`는 기존 역할 정책을 적용한다.
developer interceptor도 동일한 세션을 이용한다. 컨트롤러 직접 인증 경로에는 community 발견/참여,
Discord bot 설치 확인, feedback, 로그아웃 등이 있다.
어느 경로에도 상태 변경 요청의 CSRF 토큰 검증이 없었다. 전역 CORS 허용이나 Spring Security 설정도 없었다.

구현 전 정상 OWNER 세션에 토큰 없이 보낸 실제 출석 POST, 설정 PUT, 역할 PATCH, 커뮤니티 DELETE가
각각 200 / 200 / 200 / 204였다. 세션 쿠키가 공격 요청에 동반되는 환경에서는 권한 검사만으로 요청의 의도를 구분할 수 없었다.

## 3. 수정한 파일과 이유

| 파일 | 변경 이유 |
|---|---|
| `src/main/java/com/guildup/user/auth/controller/DiscordLoginController.java` | 성공한 로그인 세션 ID/CSRF 토큰 교체, 인증된 토큰 조회 API |
| `src/main/java/com/guildup/user/auth/service/SessionCsrfTokens.java` | 세션에 난수 토큰 보관, 발급·교체·비교 |
| `src/main/java/com/guildup/user/auth/config/SessionCsrfInterceptor.java` | 인증된 상태 변경 요청의 헤더 검증, 구분 가능한 403 응답 |
| `src/main/java/com/guildup/user/auth/config/SessionCsrfWebConfig.java` | 기존 접근 interceptor 이후 `/api/**`에 CSRF 검사 등록 |
| `src/main/resources/application.properties` | HttpOnly / SameSite / Path / 쿠키 전용 세션 추적 명시, 로컬 HTTP 유지 |
| `src/main/resources/application-prod.properties` | HTTPS 운영 profile의 Secure 기본값 |
| `frontend/src/api/http.js` | React 공통 API 호출에서 현재 토큰 조회와 헤더 전달 |
| `src/main/resources/static/community-ui.js` | 기존 정적 페이지의 공통 API 호출에도 동일한 보호 적용 |
| `src/test/java/com/guildup/user/auth/controller/DiscordLoginControllerTests.java` | 기존 로그인 테스트에 회전·속성 유지 검증 추가, 로그인 실패 회귀 추가 |
| `src/test/java/com/guildup/user/auth/SessionCsrfFlowTests.java` | 실제 MVC 설정을 사용하는 CSRF·권한 회귀 테스트 |
| `src/test/java/com/guildup/user/auth/SessionCookieIntegrationTests.java` | 실제 Tomcat 쿠키로 이전 ID와 로그아웃 세션 접근 차단 검증 |
| `src/test/java/com/guildup/user/auth/SessionSecureCookieIntegrationTests.java` | prod profile의 실제 Secure 쿠키 확인 |
| `src/test/java/com/guildup/support/SessionCsrfTestClient.java` | 기존 업무 테스트에서 정상 클라이언트처럼 세션 토큰 전달 |
| `frontend/test/csrf.test.js` | 공통 API의 헤더·세션 변경·외부 출처·취소 회귀 테스트 |
| `frontend/test/http.test.js`, `frontend/test/pubgPlatformPages.test.js` | 기존 HTTP/화면 테스트의 서버 fixture에 토큰 응답 추가 |
| `README.md`, 이 보고서 | 호출 규약, 운영 profile 및 검증 결과 기록 |

다음 기존 업무 테스트에는 공통 토큰 전달 fixture annotation 한 줄만 추가했다.
기존 상태 코드·업무 데이터 기대값은 변경하지 않았다.

- `CommunityDeletionFlowTests`, `CommunityFlowTests`, `CommunityMemberActivitySyncFlowTests`
- `CommunityMemberDeletionFlowTests`, `CommunityMembershipCleanupFlowTests`, `CommunityNewsFlowTests`
- `CommunityPostFlowTests`, `CommunityScoreFlowTests`, `FeedbackFlowTests`

## 4. 로그인 전후 세션 ID 처리

Discord state, callback URI, 외부 사용자 조회와 사용자 저장이 성공한 뒤 `request.changeSessionId()`를 호출한다.
그 다음 동일한 세션 객체에 사용자 ID를 저장하고 CSRF 토큰도 새로 만든다.
세션 전체를 무효화하지 않으므로 다른 필수 속성이 유지된다. OAuth state와 URI는 기존처럼 검증 과정에서 소비된다.
로그인 거절·잘못된 state·Discord 조회 실패는 인증 정보 저장과 ID 회전 단계에 도달하지 않는다.
로그아웃은 기존 `session.invalidate()` 동작을 유지하며 인증된 요청에는 먼저 CSRF 검사를 적용한다.

| 상황 | 기존 | 수정 후 |
|---|---|---|
| 로그인 성공 | 로그인 전 ID 유지 | 새 ID로 인증 정보 유지 |
| 이전 ID로 `/api/auth/me` 호출 | 동일 인증 세션을 가리킬 수 있음 | 실제 컨테이너에서 401 |
| 인증된 상태 변경, 토큰 누락 | 기존 권한만 검사 | 403 `CSRF_TOKEN_MISSING` |
| 토큰 불일치 / 다른 세션 토큰 | 검사 없음 | 403 `CSRF_TOKEN_INVALID` |
| 정상 세션 + 정상 토큰 | 기존 업무 처리 | 기존 업무 처리와 역할 검사 유지 |
| 조회 / OAuth redirect | 기존 인증 및 state 정책 | 해당 정책 유지 |
| 로그아웃 후 인증 접근 | 세션 무효화 | 동일하게 401, 토큰도 세션과 함께 제거 |

## 5. CSRF 생성·전달·검증 흐름

1. `SecureRandom`으로 32바이트를 만들고 Base64 URL 형식으로 세션에 저장한다. 로그인 성공 시 교체한다.
2. `GET /api/auth/csrf`는 기존 인증된 세션에서만 토큰을 반환한다. 익명 요청은 401이며 새 세션을 생성하지 않는다.
3. 토큰 응답에 `Cache-Control: no-store`를 설정한다. 토큰은 URL·별도 쿠키·localStorage에 저장하지 않는다.
4. React/정적 페이지 공통 모듈은 동일 출처 `/api` 상태 변경 요청마다 현재 토큰을 조회하고 `X-CSRF-Token`을 추가한다.
   기존 본문·Content-Type·취소 signal을 유지한다. 다른 탭에서 로그인/로그아웃한 경우를 위해 토큰을 장기 캐시하지 않는다.
5. 서버는 세션의 저장값과 헤더를 `MessageDigest.isEqual()`로 비교한다. 누락/불일치는 고정된 오류 코드와 메시지로 403을 반환한다.
6. 요청당 토큰 조회 GET이 하나 추가된다. 토큰 조회 실패·취소 시 상태 변경 요청은 보내지 않으며, 거부된 상태 변경 요청을 자동 재시도하지 않는다.

실패 payload와 로그에 전달받은 토큰 값을 포함하지 않는다. 인증 실패 401, 기존 권한 실패 403,
CSRF 실패 403의 `CSRF_TOKEN_MISSING` / `CSRF_TOKEN_INVALID` 코드로 구분한다.
community/developer interceptor의 기존 순서를 유지하고 CSRF는 order 100에서 검사한다.
컨트롤러·서비스의 OWNER / ADMIN / MEMBER 검사는 그대로 수행한다.

## 6. CSRF 적용 endpoint 범위

`/api/**`의 컨트롤러 요청 중 인증 세션이 있는 POST / PUT / PATCH / DELETE를 보호한다.
GET / HEAD / OPTIONS / TRACE 이외의 다른 메서드도 같은 검사를 적용한다.

| 기능 | 보호되는 경로/작업 |
|---|---|
| 인증 | `POST /api/auth/logout` |
| 커뮤니티 | 생성, 삭제, 설정 변경, 사용자 역할 변경 |
| 참여 | `/api/community-discoveries/discord/{communityId}/join`, Discord guild-selection inspect/join |
| 출석 | `/api/communities/{communityId}/attendance` |
| 멤버 | 추가·삭제·Discord 동기화, 역할 설정 |
| 소식 | posts / comments / notices / events 생성·수정·삭제 |
| 빙고 | 생성·수정·삭제/취소, 전체/개인 aggregate, temporary-rebuild preview/apply |
| 킬내기 | 생성·참가·승인/거절·수정·시작·종료·취소·삭제·중간/최종 정산 |
| Discord 관리 | DM 전송, bot-install authorize/confirm, guild-selection |
| PUBG 연동 | nickname-rule preview/save/sync, activity-rule 변경, activities/sync |
| 기타 | team-maker generate/rebalance, feedback |

feedback은 기존 community interceptor 제외 경로지만 CSRF interceptor에서는 제외하지 않는다.
community 경로 밖의 설치 확인·발견/참여·로그아웃도 보호한다. 플랫폼별 게임 경로와 빙고/킬내기 서비스는 변경하지 않았다.
개발자 조회/SSE 경로의 기존 인증·인가도 유지했다. 현재 코드에 별도 HTTP webhook 상태 변경 endpoint는 없었다.

## 7. 제외 endpoint와 이유

| 제외 대상 | 이유와 기존 보호 |
|---|---|
| `GET /api/auth/discord/authorize`, `/api/auth/discord/callback` | 브라우저 OAuth redirect. 기존 로그인 state/세션 검증 사용 |
| `GET /api/communities/{communityId}/discord/oauth/authorize` | 관리 권한을 확인한 뒤 Discord redirect용 state 생성 |
| `GET /api/discord/oauth/callback` | 기존 로그인 세션·community state·관리 권한을 검증하고 일회용 OAuth state 소비 |
| 일반 GET / HEAD 조회 및 OPTIONS | CSRF 상태 변경 메서드 검사 제외. 기존 인증/역할 정책 유지 |
| 공개 정적 페이지/리소스 | API 상태 변경 컨트롤러가 아님 |
| 인증 세션 없는 요청 | 기존 인증 정책에 맡김. 보호 API는 기존처럼 401, 익명 로그아웃은 기존 204 유지 |

OAuth 전체 경로를 일괄 제외하지 않았다. Discord bot 설치의 POST API는 redirect 예외에 해당하지 않는다.
CSRF 제외 이유는 interceptor의 safe-method/anonymous/handler 검사 주석에도 기록했다.

Origin/Referer 검증은 검토했으나 이번 수정에는 추가하지 않았다. 토큰 검증은 출처 헤더 부재나 프록시의 host/scheme 변환에 의존하지 않는다.
현재 클라이언트는 상대 `/api` 주소를 쓰고 Vite proxy는 `changeOrigin=false`다. 실제 운영 도메인과 프록시 설정은 저장소만으로 확정할 수 없다.
출처를 하드코딩하거나 추가 CORS 허용을 도입하지 않았으며, 클라이언트는 외부 출처에 토큰을 자동 첨부하지 않는다.

## 8. 쿠키 설정 확인 결과

기존 `application.properties`에는 아래 세션 설정이 명시되어 있지 않았다. 저장소 밖 실제 응답 설정은 확인하지 않았다.

| 항목 | 수정 후 저장소 설정 | 검증 |
|---|---|---|
| HttpOnly | `true` | 실제 Tomcat Set-Cookie 확인 |
| Secure | 기본 profile `false`, prod profile `true`; `SESSION_COOKIE_SECURE` override 가능 | 로컬/prod 각각 실제 Set-Cookie 확인 |
| SameSite | `lax` | 실제 Set-Cookie 확인. Discord의 최상위 GET redirect 흐름 유지 |
| Path | `/` | 실제 Set-Cookie 확인 |
| URL session tracking | `tracking-modes=cookie` | 유효한 새 ID를 `;jsessionid=`로만 보낸 요청도 401 |
| Domain | 별도 property를 추가하지 않음 | 운영 proxy/property override는 별도 확인 필요 |

## 9. 운영 환경에서 별도로 확인할 설정

- HTTPS 운영에서 `SPRING_PROFILES_ACTIVE=prod` 또는 `SESSION_COOKIE_SECURE=true`가 실제로 적용되는지 확인한다.
  표준 `SERVER_SERVLET_SESSION_COOKIE_*` override와 Nginx cookie rewrite 등이 결과를 바꾸는지도 확인한다.
- 실제 브라우저의 JSESSIONID에 Secure / HttpOnly / SameSite=Lax / Path=/가 붙는지 확인한다.
- 프런트와 `/api`의 동일 출처 routing, OAuth redirect 등록값, TLS 종료와 전달 헤더를 확인한다.
  기존 `server.forward-headers-strategy=framework`는 유지했다. 전달 헤더의 신뢰/정제 정책은 저장소 밖 사항이다.
- proxy/CDN이 토큰 API의 `no-store`를 유지하고 Cookie 및 X-CSRF-Token 전체를 access/debug 로그에 저장하지 않는지 확인한다.
- backend와 변경된 공통 프런트 API 모듈을 함께 배포한다. 저장소 밖 systemd/environment/Nginx 값이 설정되어 있다고 단정하지 않았다.

테스트에서 Discord 서비스/JDA는 mock이었다. 실제 Discord 승인 화면과 운영 TLS/proxy의 브라우저 로그인은 검증하지 않았다.

## 10. 회귀 테스트

구현 전 테스트를 추가하여 H01의 동일 ID와 H02의 무토큰 실행을 재현한 뒤 구현했다.
프런트 CSRF 테스트 최초 실행은 8개 중 7개가 실패했다. 이후 외부 출처/취소 회귀 2개도 추가했다.
실제 쿠키 테스트는 로컬 OAuth 설정 fixture를 보완한 뒤, ID 회전만 제거했을 때 새 인증 쿠키가 발급되지 않아 실패하는 것도 확인했다.

| 테스트 | 검증 내용 |
|---|---|
| `DiscordLoginControllerTests` | 로그인 전후 ID 변경, 사용자 ID·다른 속성 유지, 기존 토큰 교체, state 정상 소비, 잘못된 state/Discord 거절/조회 실패 시 미승격, 로그아웃 |
| `SessionCookieIntegrationTests` | 실제 authorize→callback→me, 새 쿠키 인증, 이전 쿠키 401, URL tracking 차단, CSRF 로그아웃 후 쿠키 401, 로컬 HTTP 쿠키 속성 |
| `SessionSecureCookieIntegrationTests` | 같은 실제 컨테이너 흐름에서 prod Secure 속성 |
| `SessionCsrfFlowTests` | POST/PUT/PATCH/DELETE 정상·누락 각각 검증, 잘못된/다른 세션 토큰, logout 거절 시 세션 유지, 반복 발급과 no-store, GET/HEAD·공개·익명·callback, OWNER/ADMIN/MEMBER 권한, 기능별 상태 변경 경로 거절 |
| `frontend/test/csrf.test.js` | 모든 변경 메서드의 자동 헤더, caller 헤더/본문/signal 유지, GET 유지, 토큰 조회 401 시 요청 중단, CSRF 거부 시 무재시도, 세션 변경, legacy 공통 모듈, 외부 출처, 취소 |

CSRF 보안 회귀 클래스는 정상 클라이언트 fixture를 import하지 않는다. 기존 업무 테스트의 권한 거절/성공 기대값을 바꾸지 않았다.

## 11. 전체 백엔드 테스트 결과

`./mvnw test`: **BUILD SUCCESS**.

- 전체 701개, 실행 621개 모두 통과, failure 0 / error 0 / skipped 80.
- 조건부 PostgreSQL 테스트 78개와 외부 dump fixture 테스트 2개는 환경변수/fixture 미지정으로 생략됐다.
  이번 작업에서 PostgreSQL 통합 테스트를 실행하거나 운영 DB에 연결하지 않았다.
- 새 보안 검증: DiscordLoginControllerTests 10개, SessionCsrfFlowTests 14개, 실제 컨테이너 local/prod 각 1개 모두 통과.
- 기존 출석·설정·삭제·권한·게시글·이벤트·Discord·PUBG·빙고·킬내기 테스트도 포함해 실행했다.

## 12. 전체 프런트엔드 테스트 결과

- `npm test`: **105개 모두 통과**, failure 0 / skipped 0.
- `npm run build`: 성공. 기존 500 kB 초과 번들 크기 경고는 별도 변경하지 않았다.
- `npm run verify:build`: 성공, **36 routes / 1 JS bundle** 검증.
- 로그인 상태 확인·커뮤니티 입장 GET, 출석, 빙고, 킬내기, 커뮤니티 설정, 닉네임 동기화, 로그아웃의 실제 호출이
  React 공통 `api()`를 사용하는 것을 추적했다. 직접 fetch 호출은 두 공통 모듈에만 있다.
  기존 정적 페이지도 `community-ui.js`를 사용한다. 화면별 헤더 복붙이나 UI 변경은 하지 않았다.
- 실제 React 화면의 기존 플랫폼 전환·요청 취소 테스트는 유지했고 서버 fixture에 토큰 응답만 추가했다.

## 13. diff 검사

`git diff --check`: 통과. 새 파일에도 whitespace 오류 검사를 적용했다.

## 14. 발견했으나 이번 작업에서 수정하지 않은 사항

- 게시글 상세 GET은 `CommunityPostService.get(..., incrementView=true)`에서 조회수를 증가시킨다.
- 빙고 list/current/detail GET은 `refreshCommunityStatuses()`와 `ensureParticipation()`을 통해 상태 갱신 또는 참가자/진행 데이터를 만들 수 있다.
  Spring MVC의 HEAD도 GET handler를 사용하므로 조회가 완전히 부수효과 없는 것은 아니다.
  해당 조회 동작의 메서드/업무 구조 변경은 이번 패치에 포함하지 않았고, CSRF 검사도 이 GET/HEAD에는 추가하지 않았다.
- 실제 프록시 전달 헤더 정책, 배포 도메인, 운영 cookie/property override는 확인할 수 없었다.
- 프런트 번들 크기 경고를 해결하기 위한 분할/리팩터링은 하지 않았다.
- 다른 H/M/L 항목, Spring Security 전면 도입, JWT 전환, OAuth/권한 재설계, DB 스키마 및 관련 없는 서비스/UI 변경은 하지 않았다.
  이전 H07/H08/H09 집계 구현도 변경하지 않았다.

구현 기준 참고: [Jakarta Servlet changeSessionId](https://jakarta.ee/specifications/servlet/6.1/apidocs/jakarta.servlet/jakarta/servlet/http/httpservletrequest),
[OWASP CSRF Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html),
[Spring Boot session properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html).

# GuildUp 이용약관·개인정보 처리방침 검토

검토 및 문구 수정일: 2026-10-08. 시행일을 2026-10-08로 기재했으며 실제 공지·배포 일정에 맞춰 확정해야 한다.

## 반영한 변경

- `frontend/src/pages/TermsPage.jsx`: 현재 제공하는 이메일·Discord 로그인, 프로필·게시판·문의 기능을 반영했다. 제12조에 본인 확인 및 최종 확인, 소유 커뮤니티 정리, 탈퇴 후 계정·기록 처리, 재가입 및 외부 서비스와의 관계를 명시했다.
- `frontend/src/pages/PrivacyPage.jsx`: 문의 및 탈퇴 처리 항목, 삭제되는 정보와 남는 원본·활동 기록, 기본 로그 보관기간, 개인정보 요청 경로를 보완했다. Discord 로그인과 서버 연결 과정에서 조회하는 정보를 구분했다.
- `frontend/src/components/AccountWithdrawalPanel.jsx`: 제공하지 않는 소유권 이전 안내를 현재 가능한 커뮤니티 삭제 안내로 변경했다. 과거 자료가 모두 익명화된다고 오해할 수 있는 표현을 고치고 개인정보 처리방침 링크를 추가했다.

## 구현과 대조한 내용

| 항목 | 확인한 실제 처리 | 소스 근거 |
| --- | --- | --- |
| 탈퇴 본인 확인 | 이메일 로그인 보유자는 현재 비밀번호, 그 외에는 연결된 Discord 계정 인증. 확인 결과는 세션에서 5분간 유효 | `AccountWithdrawalService`, `WithdrawalVerification` |
| 소유 커뮤니티 | OWNER 커뮤니티가 하나라도 있으면 탈퇴 차단. 소유권 이전 API는 없으며 기존 커뮤니티 삭제 기능 사용 가능 | `AccountWithdrawalService`, `CommunityService` |
| 로그인·프로필 | 로그인 credential·외부 계정·공지 읽음 상태 삭제. 사용자 닉네임 변경, 생년월일 삭제, 계정 비활성화 | `AccountWithdrawalService`, `User.withdraw` |
| 멤버십·활동 | 멤버십 종료 및 클랜원 연결 해제. 클랜원 표시 정보 변경, Discord 식별자 대체. 출석·점수·이벤트·게시물 관계 유지 | `CommunityUser.endMembership`, `CommunityMember.anonymize`, `CommunityMemberAccount.anonymize` |
| PUBG 기록 | 계산용 account ID와 원본 경기·참가 스냅샷 유지, 연결된 클랜원의 화면 표시 마스킹 | `AccountWithdrawalService`, `CommunityMemberAccount.anonymize`, 빙고·킬내기·PUBG 모델 및 응답 코드 |
| 문의 | 접수 시 닉네임 등 스냅샷을 DB에 저장하고 운영자 메일 전송. 탈퇴 계정의 조회 이름만 변경하며 스냅샷·본문·발송된 메일은 탈퇴로 삭제하지 않음 | `Feedback`, `FeedbackService`, `FeedbackMailListener` |
| 로그 | DB 운영 이벤트는 기본 30일 후 정기 삭제. 파일 로그도 30일 보관 설정 및 용량 제한 적용 | `MonitoringRetentionScheduler`, `application.properties`, `logback-spring.xml` |
| 수동 SQL | 탈퇴 상태·시각, 멤버십 종료 및 표시 정보 변경 지원 컬럼 등을 추가. 이 SQL 자체는 기존 개인정보를 삭제하지 않음 | `src/main/resources/db/manual/add_account_withdrawal.sql` |

`ACCOUNT_WITHDRAWAL.md`의 문의 항목은 예전 SMTP 전용 구조를 설명한다. 현재 구현은 `SUPPORT.md` 및 `Feedback` 모델처럼 DB 접수 기록도 저장하므로 처리방침에는 현재 소스를 반영했다.

## 운영자가 확정해야 하는 항목

1. **공개 문의 연락처:** 개인정보 처리방침 제11항의 운영자 이메일은 미제공 상태이므로 기존 입력 필요 표시를 유지했다. `/support`는 활성 로그인 회원만 사용 가능하므로 탈퇴자·비회원의 권리 행사에는 별도 연락처가 필요하다. SMTP 인증 계정이나 내부 수신 메일을 공개용 연락처로 임의 사용하지 않았다.
2. **남는 기록의 보관 근거와 기간:** 탈퇴 계정의 내부 식별자와 멤버십, PUBG 원본·계산용 식별자, 문의 접수 당시 닉네임 등은 탈퇴로 완전히 삭제되지 않는다. 항목별 보관 근거, 기간 또는 명확한 종료 조건과 파기 절차를 확정하고 실제 처리에 반영해야 한다. 기본 로그 정리 기능이 이 자료까지 삭제하는 것은 아니다.
3. **문의 메일·외부 로그·백업:** 운영 메일함과 외부 저장소의 실제 보관기간·파기 주기를 확인하고 문서에 명시해야 한다. 애플리케이션의 회원탈퇴 및 로그 정리 코드로 과거 메일·외부 로그·백업까지 회수되지 않는다.
4. **인프라·위탁·국외 처리:** 기존 제7항은 AWS 사용 가능성만 기재한다. 실제 클라우드·메일 업체, 위탁 업무, 데이터 저장 위치와 해당되는 국외 처리 항목을 확인한 뒤 구체화해야 한다. 운영 환경을 조회하지 않았으므로 업체·지역·법적 근거를 임의로 추가하지 않았다.
5. **시행일과 공지:** 두 문서의 시행일 및 부칙을 실제 공개 일정과 맞추고 변경 내용·사유를 공지해야 한다. 기존 최초 시행일인 2026-09-16은 유지했다.

개인정보 보호법 제21조는 불필요해진 개인정보의 파기와 법정 보존 시 분리 관리를 규정하고, 제30조는 보유기간·파기·위탁·권리 행사·연락처 등의 처리방침 기재를 요구한다. 따라서 현재 기록 보존 동작을 설명하는 문구와 별개로 위 운영 항목의 확정 및 필요한 삭제 처리가 필요하다. 화면 표시 정보 변경만으로 남은 원본이 개인정보에 해당하지 않는다고 단정할 수 없다는 점을 반영했다.

- [개인정보 보호법 제21조 — 국가법령정보센터](https://www.law.go.kr/LSW/lsLawLinkInfo.do?chrClsCd=010202&lsJoLnkSeq=900078981)
- [개인정보 보호법 제30조 — 국가법령정보센터](https://www.law.go.kr/lsLinkCommonInfo.do?lsJoLnkSeq=1033214955)

## 검증

- 프론트 `npm test`: 213건 통과, 실패·건너뜀 0.
- `npm run build` 및 `npm run verify:build`: 성공, 43개 경로 검증.
- 로컬 프로덕션 미리보기의 `/terms`, `/privacy`: 로그인 없이 개정 문구, 시행일 및 문단 배치 확인. 이용약관의 개인정보 처리방침 링크 확인.
- `git diff --check`: 성공.

운영 DB 조회·SQL 실행·운영 배포는 수행하지 않았다.

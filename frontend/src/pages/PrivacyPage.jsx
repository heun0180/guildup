import PolicyLayout from "../components/PolicyLayout.jsx";

export default function PrivacyPage() {
  return (
    <PolicyLayout title="GuildUp 개인정보 처리방침" effectiveDate="2026년 9월 16일">
      <div className="policy-introduction">
        <p>GuildUp은 이용자의 개인정보를 중요하게 생각하며 관련 개인정보 보호 법령을 준수하기 위해 노력합니다.</p>
        <p>본 개인정보 처리방침은 GuildUp 서비스 이용 과정에서 어떤 정보가 수집되고 어떻게 이용되는지 설명합니다.</p>
      </div>

      <section><h2>1. 수집하는 개인정보</h2>
        <h3>Discord 로그인</h3><p>Discord 계정을 이용하여 로그인하는 경우 다음 정보가 수집될 수 있습니다.</p><ul>
          <li>Discord 사용자 ID</li><li>Discord 사용자명</li><li>Discord 표시 이름</li><li>Discord 프로필 이미지</li><li>사용자가 참여 중인 Discord 서버 관련 정보</li><li>Discord 서버 내 권한 정보</li>
        </ul><p>GuildUp은 Discord 비밀번호를 수집하거나 저장하지 않습니다.</p>

        <h3>Discord 서버 연동</h3><p>커뮤니티 운영자가 Discord 서버를 GuildUp과 연결한 경우 다음 정보가 처리될 수 있습니다.</p><ul>
          <li>Discord 서버 ID</li><li>서버 이름</li><li>서버 구성원의 Discord 사용자 ID</li><li>사용자명 및 표시 이름</li><li>서버 내 역할</li><li>서버 가입 여부</li><li>GuildUp 기능 제공에 필요한 서버 구성 정보</li>
        </ul><p>음성 활동 관련 기능을 사용하는 경우:</p><ul>
          <li>음성 채널 입장 시각</li><li>음성 채널 퇴장 시각</li><li>음성 채널 이용 시간</li><li>최근 활동 시각</li>
        </ul><p>GuildUp은 Discord 음성 통화의 실제 음성 내용은 수집하거나 저장하지 않습니다.</p>

        <h3>게임 정보</h3><p>게임 관련 기능 사용 시 다음 정보가 처리될 수 있습니다.</p><ul>
          <li>게임 플랫폼</li><li>게임 닉네임</li><li>게임 계정 식별자</li><li>시즌 기록</li><li>매치 기록</li><li>킬</li><li>데미지</li><li>생존 기록</li><li>팀 구성 정보</li><li>기타 게임사가 공식 API를 통해 제공하는 정보</li>
        </ul>

        <h3>커뮤니티 정보</h3><ul>
          <li>GuildUp 커뮤니티 가입 정보</li><li>커뮤니티 내 역할</li><li>가입 및 탈퇴 상태</li><li>출석 기록</li><li>포인트</li><li>랭킹</li><li>이벤트 참가 기록</li><li>빙고 진행 정보</li><li>킬내기 참가 및 결과 정보</li><li>기타 커뮤니티 기능 이용 기록</li>
        </ul>

        <h3>자동 생성 정보</h3><p>서비스 안정성 및 보안을 위해 다음 정보가 자동으로 생성될 수 있습니다.</p><ul>
          <li>접속 일시</li><li>IP 주소</li><li>요청 및 오류 로그</li><li>브라우저 및 기기 관련 기본 정보</li>
        </ul>
      </section>

      <section><h2>2. 개인정보의 이용 목적</h2><p>수집 정보는 다음 목적으로 사용됩니다.</p><ul>
        <li>이용자 식별 및 로그인</li><li>GuildUp 계정 관리</li><li>커뮤니티 가입 및 권한 관리</li><li>Discord 서버 연결 및 구성원 확인</li><li>게임 계정 확인 및 게임 기록 조회</li><li>출석, 랭킹, 빙고, 킬내기 등 기능 제공</li><li>서비스 장애 분석</li><li>부정 이용 및 보안 문제 방지</li><li>서비스 개선</li>
      </ul></section>

      <section><h2>3. 개인정보의 보유 및 이용 기간</h2><p>원칙적으로 개인정보는 처리 목적이 달성될 때까지 보유합니다.</p><p>예:</p><ul>
        <li>GuildUp 계정 정보: 회원 탈퇴 시까지</li><li>Discord 연동 정보: 연동 해제 또는 회원 탈퇴 시까지</li><li>커뮤니티 가입 정보: 커뮤니티 탈퇴 또는 커뮤니티 삭제 시까지</li><li>게임 계정 연동 정보: 게임 계정 연동 해제 또는 회원 탈퇴 시까지</li><li>게임 기록 및 커뮤니티 활동 기록: 서비스 제공에 필요한 기간</li><li>서버 접속 및 오류 로그: 서비스 안정성 및 보안 목적 달성에 필요한 기간</li>
      </ul><p>관련 법령에 따라 별도 보존이 필요한 경우 해당 기간 동안 보관할 수 있습니다.</p></section>

      <section><h2>4. 개인정보의 파기</h2><p>개인정보의 처리 목적이 달성되거나 보유 기간이 종료된 경우 개인정보를 지체 없이 파기합니다.</p><p>전자적 파일 및 데이터베이스 정보는 복구하기 어려운 방법으로 삭제합니다.</p></section>

      <section><h2>5. 개인정보의 제3자 제공</h2><p>GuildUp은 원칙적으로 이용자의 개인정보를 외부에 판매하거나 임의로 제공하지 않습니다.</p><p>다음의 경우 예외가 될 수 있습니다.</p><ul>
        <li>이용자가 사전에 동의한 경우</li><li>법령에 특별한 규정이 있는 경우</li><li>관계기관의 적법한 요청이 있는 경우</li>
      </ul></section>

      <section><h2>6. 외부 서비스 및 API</h2><h3>Discord</h3><p>사용 목적:</p><ul>
        <li>로그인</li><li>Discord 서버 연결</li><li>서버 구성원 및 역할 확인</li><li>Discord 관련 커뮤니티 관리 기능</li>
      </ul><h3>PUBG API</h3><p>사용 목적:</p><ul>
        <li>PUBG 플레이어 검색</li><li>게임 전적 조회</li><li>매치 기록 조회</li><li>시즌 기록 조회</li><li>GuildUp 내부 콘텐츠 및 통계 집계</li>
      </ul></section>

      <section><h2>7. 서비스 운영 인프라</h2><p>GuildUp은 서비스 제공을 위해 클라우드 서버 및 데이터베이스 등의 인프라를 사용할 수 있습니다.</p><p>현재 Amazon Web Services(AWS) 등의 클라우드 인프라가 서비스 운영에 사용될 수 있습니다.</p></section>

      <section><h2>8. 쿠키 및 브라우저 저장소</h2><p>GuildUp은 로그인 상태 유지, 인증 및 서비스 편의를 위해 쿠키 또는 브라우저 저장소를 사용할 수 있습니다.</p></section>

      <section><h2>9. 개인정보의 안전성 확보</h2><p>GuildUp은 개인정보 보호를 위해 다음과 같은 보호조치를 적용하도록 한다.</p><ul>
        <li>HTTPS 암호화 통신</li><li>데이터베이스 접근 권한 제한</li><li>서버 접근 권한 관리</li><li>인증 정보 보호</li><li>중요 비밀키 및 환경변수를 소스코드와 분리</li><li>서비스 로그 및 오류 모니터링</li>
      </ul></section>

      <section><h2>10. 이용자의 권리</h2><p>이용자는 자신의 개인정보에 대해 다음 권리를 행사할 수 있습니다.</p><ul>
        <li>개인정보 열람 요청</li><li>개인정보 수정 요청</li><li>개인정보 삭제 요청</li><li>개인정보 처리 정지 요청</li><li>서비스 탈퇴</li><li>외부 서비스 연동 해제</li>
      </ul></section>

      <section><h2>11. 개인정보 보호 업무 연락처</h2><address className="privacy-contact"><span>개인정보 보호 업무 담당: GuildUp 운영자</span><span>이메일: [운영자 이메일 입력 필요]</span></address></section>

      <section><h2>12. 개인정보 처리방침의 변경</h2><p>서비스 기능 추가 또는 관련 법령 변경 등에 따라 개인정보 처리방침이 변경될 수 있습니다.</p><p>중요한 변경 사항은 서비스 내 공지를 통해 안내합니다.</p></section>

      <section><h2>부칙</h2><p>본 개인정보 처리방침은 2026년 9월 16일부터 적용됩니다.</p></section>
    </PolicyLayout>
  );
}

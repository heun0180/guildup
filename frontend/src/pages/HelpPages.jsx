import AppLink from "../components/AppLink.jsx";
import HelpLayout, { HelpFaq, HelpNotice, HelpSection, HelpSteps } from "../components/HelpLayout.jsx";
import Icon from "../components/Icon.jsx";

const commonGuides = [
  { href: "/help/general", icon: "book", title: "기본 사용법", description: "로그인, 계정 관리·회원탈퇴와 커뮤니티 이용 방법" },
  { href: "/help/attendance", icon: "calendar", title: "출석", description: "매일 출석하고 활동 점수를 받는 방법" },
  { href: "/help/ranking", icon: "ranking", title: "랭킹", description: "GuildUp 활동 점수와 랭킹 산정 방식" },
];

const pubgGuides = [
  { href: "/help/pubg/kill-competition", icon: "target", title: "킬내기", description: "방 생성, 참가, PUBG 경기 집계와 결과 발표 방법" },
  { href: "/help/pubg/bingo", icon: "bingo", title: "빙고판", description: "PUBG 미션 진행도와 경기 집계 방법" },
];

function BulletList({ children }) {
  return <ul className="help-bullet-list">{children}</ul>;
}

function HelpCardList({ guides, even = false, label }) {
  return <nav className={`help-card-grid${even ? " is-even" : ""}`} aria-label={label}>
    {guides.map((guide) => <AppLink className="panel help-card" href={guide.href} key={guide.href}>
      <span className="help-card-icon"><Icon name={guide.icon} size={23} /></span>
      <span><strong>{guide.title}</strong><small>{guide.description}</small></span>
      <Icon name="arrow" size={18} className="help-card-arrow" />
    </AppLink>)}
  </nav>;
}

export function HelpIndexPage() {
  return <HelpLayout title="GuildUp 가이드" description="GuildUp의 기능별 사용 방법과 집계 기준을 확인해 보세요.">
    <section className="help-category" aria-labelledby="common-help-title">
      <div className="help-category-heading"><h2 id="common-help-title">GuildUp</h2><p>모든 커뮤니티에서 공통으로 사용하는 기능입니다.</p></div>
      <HelpCardList guides={commonGuides} label="GuildUp 공통 가이드" />
    </section>
    <section className="help-category" aria-labelledby="game-help-title">
      <div className="help-category-heading"><h2 id="game-help-title">게임별 기능</h2><p>게임 데이터와 API를 사용하는 전용 기능입니다.</p></div>
      <div className="panel help-game-group">
        <div className="help-game-heading">
          <div><span className="help-game-badge">PUBG</span><h3>PUBG: BATTLEGROUNDS</h3><p>GuildUp에서 사용할 수 있는 PUBG 전용 기능입니다.</p></div>
          <AppLink href="/help/pubg">PUBG 전체 보기 <Icon name="arrow" size={16} /></AppLink>
        </div>
        <HelpCardList guides={pubgGuides} even label="GuildUp 가이드 · PUBG" />
      </div>
    </section>
  </HelpLayout>;
}

export function PubgHelpPage() {
  return <HelpLayout detail title="PUBG: BATTLEGROUNDS" description="GuildUp에서 PUBG API와 경기 데이터를 이용하는 전용 기능입니다.">
    <section className="panel help-game-introduction">
      <span className="help-game-badge">PUBG</span>
      <div><h2>PUBG 계정 확인</h2><p>킬내기와 빙고 집계에는 PUBG 계정 정보가 필요합니다. 저장된 PUBG 계정이 있으면 그 정보를 사용하고, 없으면 커뮤니티에 설정된 닉네임 규칙으로 Discord 닉네임에서 인게임 닉네임을 확인합니다.</p></div>
    </section>
    <HelpCardList guides={pubgGuides} even label="GuildUp 가이드 · PUBG 기능" />
  </HelpLayout>;
}

export function GeneralHelpPage() {
  return <HelpLayout detail title="GuildUp 기본 사용법" description="로그인과 커뮤니티 이용, 계정 관리 및 회원탈퇴 방법을 안내합니다.">
    <HelpSection title="GuildUp 시작하기">
      <HelpSteps items={["이메일 또는 Discord 계정으로 로그인", "내 커뮤니티를 선택하거나 가입 가능한 커뮤니티에 가입", "커뮤니티 대시보드 입장", "출석과 랭킹 등 GuildUp 공통 기능 이용", "커뮤니티가 지원하는 게임별 기능 이용"]} />
    </HelpSection>
    <HelpSection title="로그인과 커뮤니티">
      <p><AppLink href="/signup.html">회원가입</AppLink>에서 이메일과 비밀번호로 계정을 만들거나, <AppLink href="/login.html">로그인</AppLink> 화면에서 Discord 계정으로 시작할 수 있습니다. 이미 Discord로 GuildUp을 이용하고 있다면 기존 계정으로 로그인한 뒤 이메일 로그인을 추가해 주세요.</p>
      <p>로그인 후 내 커뮤니티에 입장하거나 초대 코드로 참여할 수 있습니다. 연결된 Discord 서버에서 가입 가능한 커뮤니티를 찾아 참여할 수도 있습니다.</p>
      <p>직접 운영하는 커뮤니티는 기본 정보 입력, 선택적인 Discord 연결, 최종 확인을 거쳐 만들 수 있습니다. Discord 없이도 커뮤니티를 이용할 수 있으며, 커뮤니티 설정이나 연동 기능에서 나중에 연결할 수 있습니다.</p>
    </HelpSection>
    <HelpSection title="계정과 프로필 관리">
      <p>상단 프로필 메뉴에서 <strong>계정</strong>을 선택하거나 <AppLink href="/account.html">계정 화면</AppLink>으로 이동합니다.</p>
      <BulletList>
        <li><strong>프로필:</strong> GuildUp 닉네임과 선택 정보인 생년월일을 수정합니다. 생년월일을 입력하지 않아도 이용할 수 있으며, 입력값을 비워서 저장하면 삭제됩니다.</li>
        <li>GuildUp 닉네임은 Discord 이름 및 PUBG 닉네임과 별도로 관리합니다. 프로필 이미지는 연결된 Discord 계정의 이미지를 사용하며, 연결된 이미지가 없으면 기본 아바타를 표시합니다.</li>
        <li><strong>로그인 및 보안:</strong> Discord로 가입한 사용자는 <strong>이메일 로그인 추가</strong>로 현재 계정에 이메일과 비밀번호를 등록할 수 있습니다. 기존 커뮤니티와 기록을 계속 이용합니다.</li>
        <li><strong>연결된 계정:</strong> 이메일 가입 사용자도 <strong>Discord 연결</strong>로 같은 GuildUp 계정에 Discord 로그인을 추가할 수 있습니다.</li>
        <li>Discord 연결 해제는 이메일 로그인 수단이 등록되어 있을 때 가능합니다. 해제 후에는 이메일과 비밀번호로 로그인합니다. 개인 Discord 로그인 연결과 커뮤니티의 Discord 서버 연결은 별도로 관리합니다.</li>
      </BulletList>
      <HelpNotice>이메일 소유 인증, 비밀번호 변경 및 비밀번호 재설정은 현재 제공되지 않습니다.</HelpNotice>
    </HelpSection>
    <HelpSection title="문의 / 건의">
      <p>로그인한 뒤 상단 프로필 메뉴에서 <strong>문의 / 건의</strong>를 선택하거나 <AppLink href="/support">문의 / 건의 화면</AppLink>으로 이동합니다. 커뮤니티에 가입하지 않아도 문의할 수 있습니다.</p>
      <HelpSteps items={["문의 유형을 선택하고 제목과 내용을 입력", "보내기를 눌러 문의 접수", "같은 화면의 내 문의 내역에서 처리 상태와 답변 확인"]} />
      <p>제목은 100자, 내용은 3,000자까지 입력할 수 있습니다. 문의에는 로그인 사용자와 접수 시각, 이용하던 페이지 및 해당되는 경우 커뮤니티 정보가 함께 전달됩니다.</p>
    </HelpSection>
    <HelpSection title="회원탈퇴 전 확인">
      <p>회원탈퇴하면 모든 GuildUp 커뮤니티 멤버십과 이용 권한이 종료되고 기존 계정으로 로그인할 수 없습니다. 탈퇴 후 복구할 수 없으므로 아래의 데이터 처리 안내를 먼저 확인해 주세요.</p>
      <p>소유한 커뮤니티가 있으면 탈퇴가 차단되며 해당 커뮤니티 목록이 표시됩니다. 현재는 목록의 커뮤니티 이름을 눌러 설정으로 이동한 뒤 소유한 커뮤니티를 모두 삭제해야 탈퇴할 수 있습니다. 소유권 이전 기능은 제공되지 않습니다.</p>
      <HelpNotice>커뮤니티 삭제는 다른 구성원과 커뮤니티 기록에도 영향을 주는 별도 작업입니다. 회원탈퇴 버튼을 눌러도 소유한 커뮤니티가 자동으로 삭제되지는 않습니다.</HelpNotice>
    </HelpSection>
    <HelpSection title="회원탈퇴 순서">
      <HelpSteps items={[
        "프로필 메뉴에서 계정으로 이동한 뒤 계정 관리의 회원탈퇴 버튼 선택",
        "탈퇴 안내를 읽고 위 내용을 확인했습니다에 체크한 뒤 다음 선택",
        "현재 비밀번호 또는 연결된 Discord 계정으로 본인 확인",
        "회원탈퇴 최종 확인에서 회원탈퇴 버튼 선택",
        "로그인 화면에서 회원탈퇴 완료 안내 확인",
      ]} />
      <p>이메일 로그인이 등록되어 있으면 현재 비밀번호를 확인합니다. Discord도 연결되어 있더라도 비밀번호 확인을 사용합니다. 이메일 로그인이 없는 계정은 연결된 Discord 계정으로 다시 인증해야 합니다.</p>
      <p>본인 확인은 5분간 유효합니다. 확인 결과가 만료되면 다시 인증해 주세요. Discord 인증을 마치고 계정 화면으로 돌아와도 바로 탈퇴되지 않으며, 안내 확인과 최종 회원탈퇴 버튼 선택이 필요합니다. 최종 실행 전에는 <strong>취소</strong>로 중단할 수 있습니다.</p>
    </HelpSection>
    <HelpSection title="탈퇴 후 정보와 기록">
      <BulletList>
        <li><strong>삭제되는 정보:</strong> 이메일 로그인 정보, Discord 로그인 연결과 프로필 정보, 생년월일 및 개인 공지 읽음 상태를 삭제합니다. GuildUp 닉네임은 “탈퇴한 사용자”로 변경하고 로그인 세션을 무효화합니다.</li>
        <li><strong>남는 활동 기록:</strong> 출석·점수·랭킹, 빙고·킬내기 참가와 결과, 음성 활동 및 게시글·댓글·공지·이벤트의 과거 기록은 탈퇴만으로 삭제되거나 재정산되지 않습니다. 연결된 클랜원의 표시 이름과 외부 계정 표시 정보를 변경하거나 가립니다.</li>
        <li><strong>자동 삭제되지 않는 원본:</strong> PUBG 원본 경기 자료와 계산용 계정 식별자, 문의 접수 기록과 이미 발송된 메일, 작성 본문에 직접 기재한 개인정보 등이 남을 수 있습니다. 표시 이름 변경이 모든 원본의 완전한 익명화나 삭제를 의미하지는 않습니다.</li>
      </BulletList>
      <p>항목별 처리와 개인정보 삭제 요청 방법은 <AppLink href="/privacy">개인정보 처리방침</AppLink>에서, 회원탈퇴에 관한 조건은 <AppLink href="/terms">이용약관</AppLink>에서 확인할 수 있습니다. 추가 삭제가 필요한 내용은 탈퇴 전에 확인하고 별도로 요청해 주세요.</p>
      <HelpNotice>같은 이메일이나 Discord 계정으로 다시 가입해도 새 계정이 만들어집니다. 이전 계정의 멤버십·권한·활동 기록은 자동으로 복구되거나 새 계정에 연결되지 않습니다.</HelpNotice>
    </HelpSection>
    <HelpSection title="계정과 회원탈퇴 자주 묻는 질문"><HelpFaq items={[
      { question: "Discord 연결을 해제하면 GuildUp에서도 탈퇴되나요?", answer: "아니요. 개인 Discord 로그인 연결만 해제됩니다. 이메일 로그인 수단이 남아 있어야 연결을 해제할 수 있으며, GuildUp 회원탈퇴는 계정 관리에서 별도로 진행합니다." },
      { question: "Discord로 본인 확인했는데 탈퇴가 완료되지 않았어요.", answer: "Discord 인증은 본인 확인 단계입니다. 계정 화면으로 돌아온 뒤 안내를 확인하고 다음을 눌러 최종 회원탈퇴 버튼을 선택해야 완료됩니다." },
      { question: "현재 비밀번호를 잘못 입력했어요.", answer: "탈퇴는 실행되지 않습니다. 현재 로그인 세션은 유지되며 올바른 비밀번호로 다시 확인할 수 있습니다. 이메일 로그인이 등록된 계정은 Discord 인증으로 비밀번호 확인을 대신할 수 없습니다." },
      { question: "GuildUp에서 탈퇴하면 Discord 서버에서도 나가게 되나요?", answer: "아니요. GuildUp 회원탈퇴는 Discord 서버 탈퇴나 Discord·PUBG 계정 삭제를 대신하지 않습니다. 연동된 Discord 서버에 계속 참여하면 이후 구성원 정보와 활동이 별도의 클랜원 기록으로 다시 수집될 수 있습니다." },
    ]} /></HelpSection>
  </HelpLayout>;
}

export function KillCompetitionHelpPage() {
  return <HelpLayout detail backHref="/help/pubg" backLabel="GuildUp 가이드 · PUBG" title="킬내기 사용 방법" description="정해진 시간 동안 PUBG 경기를 플레이하고 설정된 점수로 경쟁합니다.">
    <HelpSection title="킬내기란?">
      <p>클랜원끼리 일정 시간 동안 PUBG 게임을 진행하며 킬 점수와 선택한 등수 점수를 합산해 개인 또는 팀 순위를 정하는 기능입니다.</p>
    </HelpSection>
    <HelpSection title="이용 순서"><HelpSteps items={["방 생성", "참가 신청", "참가 확정 및 팀 구성", "킬내기 시작", "PUBG 게임 플레이와 중간 정산", "종료 후 결과 발표 요청"]} /></HelpSection>
    <HelpSection title="방 생성과 점수 설정">
      <BulletList>
        <li><strong>제목, 게임 방식, 종료 시각</strong>을 설정합니다. 게임 방식은 SOLO, DUO, SQUAD 중에서 선택합니다.</li>
        <li><strong>킬 1회당 점수</strong>를 0~100점으로 설정할 수 있습니다.</li>
        <li><strong>등수 점수</strong> 사용 여부와 1~10등의 점수를 각각 0~100점으로 설정할 수 있습니다. 11등 이하는 등수 점수가 없습니다.</li>
        <li>생성자도 자동 참가하지 않으므로 참가하려면 직접 신청해야 합니다.</li>
        <li>시작 시각은 생성 시각이 아니라 관리자가 <strong>킬내기 시작</strong>을 누른 서버 시각입니다.</li>
      </BulletList>
    </HelpSection>
    <HelpSection title="참가와 중간 참가">
      <p>시작 전 승인된 참가자는 시작 시각부터 집계됩니다. 진행 중에도 참가 신청을 받을 수 있으며, 이때는 킬내기 관리자의 승인이 필요합니다. 승인된 중간 참가자는 <strong>승인 시각 이후 시작된 경기</strong>부터 집계됩니다.</p>
      <p>DUO와 SQUAD는 승인 참가자를 두 팀 이상으로 나누고 모든 참가자를 한 팀에 배정해야 시작할 수 있습니다. SOLO는 팀 구성 없이 시작합니다.</p>
    </HelpSection>
    <HelpSection title="집계 기준">
      <p>참가자별로 집계 대상 시간 안에 시작한 PUBG 경기의 킬 수를 불러옵니다. SOLO의 경기 점수는 <strong>킬 수 × 킬 점수 + 해당 경기의 등수 점수</strong>입니다. DUO와 SQUAD의 팀 점수는 <strong>팀원 킬 점수 합계 + 경기별 팀 등수 점수</strong>이며, 등수 점수는 팀원 수와 관계없이 같은 경기에서 팀당 한 번만 적용됩니다. 모든 경기 점수를 합산해 순위를 정합니다.</p>
      <p>진행 중에는 관리자가 중간 정산을 할 수 있으며, 한 번 정산한 뒤 5분이 지나야 다시 정산할 수 있습니다.</p>
      <HelpNotice>게임 종료 직후에는 최근 경기가 PUBG API에 바로 나타나지 않을 수 있습니다. 최근 전적이 보이지 않으면 잠시 후 다시 정산해 주세요.</HelpNotice>
    </HelpSection>
    <HelpSection title="결과 발표">
      <p>종료 시각이 지나거나 관리자가 킬내기를 종료하면 결과 발표를 요청할 수 있습니다. 요청 후 최근 경기 반영을 위해 약 30분을 기다린 다음 최종 집계가 진행됩니다. 최고 점수가 같으면 공동 우승으로 표시될 수 있으며, 실제 집계 경기가 있는 참가자만 우승자로 처리됩니다.</p>
      <p>승인 참가자가 4명 이상인 킬내기에서는 우승자에게 활동 점수 3점이 지급됩니다. 이 우승 점수는 한국 시간 기준 한 사람당 하루 한 번만 받을 수 있습니다.</p>
    </HelpSection>
    <HelpSection title="자주 묻는 질문"><HelpFaq items={[
      { question: "진행 중에 참가해도 이전 경기가 포함되나요?", answer: "포함되지 않습니다. 관리자가 참가를 승인한 시각 이후에 시작된 경기부터 반영됩니다." },
      { question: "결과 발표를 눌렀는데 바로 결과가 나오지 않아요.", answer: "PUBG 전적 반영 시간을 고려해 결과 발표 요청 후 약 30분의 대기 시간이 있습니다." },
      { question: "킬내기 우승 점수가 지급되지 않았어요.", answer: "승인 참가자가 4명 미만이거나, 같은 날 이미 다른 킬내기 우승 점수를 받았거나, 집계된 참가 경기가 없는 경우에는 지급되지 않습니다." },
    ]} /></HelpSection>
  </HelpLayout>;
}

export function BingoHelpPage() {
  return <HelpLayout detail backHref="/help/pubg" backLabel="GuildUp 가이드 · PUBG" title="빙고판 사용 방법" description="기간 안에 PUBG 미션을 완료하고 목표 빙고 줄을 달성합니다.">
    <HelpSection title="빙고란?">
      <p>정해진 기간 동안 PUBG 게임을 플레이하며 각 칸의 미션을 완료하고 가로, 세로 또는 대각선 빙고를 만드는 기능입니다.</p>
    </HelpSection>
    <HelpSection title="이용 순서"><HelpSteps items={["진행 중인 빙고 확인", "PUBG 게임 플레이", "내 빙고 업데이트", "미션 진행도 확인", "미션 완료", "목표 줄 수 또는 블랙빙고 달성"]} /></HelpSection>
    <HelpSection title="빙고 구성">
      <p>빙고판은 3×3, 4×4, 5×5 크기로 만들 수 있고 목표 줄 수를 정합니다. 블랙빙고를 사용하면 모든 칸을 완료한 시점도 별도로 표시됩니다. 중간 참가 허용, AI 봇 기록 제외, 클랜원과 같은 팀으로 플레이한 경기만 기록하는 옵션도 설정할 수 있습니다.</p>
    </HelpSection>
    <HelpSection title="미션 진행도">
      <p>진행 중인 칸에는 <strong>현재 값 / 목표 값</strong>이 표시됩니다. 예를 들어 누적 50킬 미션에서 32킬을 기록했다면 <strong>32 / 50</strong>으로 보입니다. 특정 조건을 여러 경기에서 달성하는 미션은 달성한 경기 수가 표시되며, 완료한 칸에는 체크 표시가 나타납니다.</p>
      <p>칸을 누르면 내 진행 상황, 달성 시각과 근거 경기를 확인할 수 있습니다.</p>
    </HelpSection>
    <HelpSection title="집계 대상 경기">
      <p><strong>일반전과 경쟁전</strong>으로 확인된 경기만 빙고에 반영됩니다. 캐주얼, 커스텀 매치, 아케이드·이벤트·훈련 경기와 종류를 확인할 수 없는 경기는 제외됩니다. 미션에 맵이나 SOLO·DUO·SQUAD 및 FPP 모드 조건이 설정되어 있으면 그 조건도 만족해야 합니다.</p>
      <p>중간 참가가 허용된 빙고에 뒤늦게 참가한 사용자는 참가 처리 시각 이후의 경기부터 집계됩니다.</p>
    </HelpSection>
    <HelpSection title="AI 봇 기록 제외">
      <p>AI 봇 기록 제외 옵션을 사용하면 봇을 대상으로 한 킬, 피해량, 기절, 헤드샷 킬과 무기·거리·투척 무기·벽 관통 등 킬 조건형 미션 기록이 제외됩니다. 이동 거리, 생존 시간처럼 봇 전투와 관계없는 기록에는 이 옵션이 적용되지 않습니다.</p>
    </HelpSection>
    <HelpSection title="전적 반영">
      <p><strong>내 빙고 업데이트</strong>를 누르면 최근 PUBG 경기와 필요한 Telemetry를 확인해 진행도를 갱신합니다. 마지막 업데이트 후 30분이 지나야 다시 업데이트할 수 있습니다.</p>
      <HelpNotice>PUBG API 전적과 Telemetry 반영이 늦으면 진행도가 즉시 오르지 않을 수 있습니다. 최근 경기가 보이지 않거나 일부 경기 재시도가 표시되면 잠시 후 다시 업데이트해 주세요.</HelpNotice>
    </HelpSection>
  </HelpLayout>;
}

export function AttendanceHelpPage() {
  return <HelpLayout detail title="출석 사용 방법" description="매일 커뮤니티에서 출석하고 활동 점수를 쌓습니다.">
    <HelpSection title="출석 기준">
      <p>커뮤니티 대시보드 또는 활동 랭킹의 출석 영역에서 <strong>출석 체크 +1점</strong>을 누르면 출석이 완료됩니다. 현재 GuildUp 계정과 커뮤니티의 활성 클랜원 정보가 연결되어 있어야 합니다. Discord 없이 가입한 사용자는 내부 클랜원 연결을 사용합니다.</p>
    </HelpSection>
    <HelpSection title="횟수와 기준 시간">
      <BulletList>
        <li>커뮤니티마다 <strong>한국 시간(Asia/Seoul) 기준 하루 한 번</strong> 출석할 수 있습니다.</li>
        <li>첫 출석에는 활동 점수 1점이 지급됩니다.</li>
        <li>같은 날 다시 요청해도 출석은 유지되지만 점수가 추가 지급되지는 않습니다.</li>
        <li>출석 점수는 해당 커뮤니티의 활동 랭킹 총점에 바로 반영됩니다.</li>
      </BulletList>
    </HelpSection>
  </HelpLayout>;
}

export function RankingHelpPage() {
  return <HelpLayout detail title="랭킹 사용 방법" description="커뮤니티 활동으로 얻은 점수 합계로 순위를 확인합니다.">
    <HelpSection title="랭킹 산정 방식">
      <p>현재 커뮤니티에 소속된 활성 클랜원을 활동 점수가 높은 순서로 표시합니다. 아직 점수가 없는 클랜원도 0점으로 목록에 포함됩니다.</p>
      <BulletList>
        <li><strong>일일 출석:</strong> 한국 시간 기준 하루 한 번 1점</li>
        <li><strong>킬내기 우승:</strong> 승인 참가자가 4명 이상인 킬내기에서 3점</li>
      </BulletList>
      <p>킬내기 우승 점수는 실제 집계 경기가 있는 우승자에게 지급되며, 한 사람이 한국 시간 기준 하루에 한 번만 받을 수 있습니다. 공동 우승 조건을 만족하면 여러 명이 점수를 받을 수 있습니다.</p>
    </HelpSection>
    <HelpSection title="집계 기간과 과거 랭킹">
      <p>커뮤니티 설정에서 OWNER와 ADMIN이 월간, 분기, 전체 누적 중 집계 주기를 선택합니다. 기본값은 전체 누적입니다.</p>
      <p>월간과 분기 랭킹은 이전·다음 버튼으로 과거 기간을 조회할 수 있습니다. 출석은 한국 시간의 출석 인정일, 킬내기는 최종 결과가 확정되어 점수를 지급한 시점에 포함됩니다. 기간이 바뀌어도 점수를 초기화하지 않습니다.</p>
      <p>내 점수와 목록의 점수는 선택한 기간 기준이며, 오늘 출석 카드의 누적 점수는 전체 기간 기준입니다.</p>
      <HelpNotice>과거 랭킹도 현재 활성 클랜원과 남아 있는 점수 이력으로 다시 계산됩니다. 킬내기 삭제에 따른 점수 취소나 클랜원 상태 변경은 과거 조회에도 반영됩니다.</HelpNotice>
    </HelpSection>
    <HelpSection title="순위 확인">
      <p>활동 랭킹 화면에서 내 순위와 점수, 전체 활성 클랜원의 순서를 확인할 수 있습니다. 같은 점수인 경우에도 목록에는 각 클랜원의 순번이 차례로 표시됩니다.</p>
      <HelpNotice>현재 랭킹 총점에는 출석 점수와 지급 조건을 만족한 킬내기 우승 점수만 반영됩니다.</HelpNotice>
    </HelpSection>
  </HelpLayout>;
}

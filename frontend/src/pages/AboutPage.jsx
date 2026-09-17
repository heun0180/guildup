import PolicyLayout from "../components/PolicyLayout.jsx";

export default function AboutPage() {
  return (
    <PolicyLayout title="서비스 소개">
      <section>
        <h2 className="about-name">GuildUp</h2>
        <p>GuildUp은 게임 클랜과 커뮤니티 운영을 더 편리하게 만들기 위한 커뮤니티 관리 서비스입니다.</p>
        <p>Discord 및 게임 데이터를 연동하여 클랜원 관리, 활동 확인, 게임 통계, 이벤트 및 다양한 커뮤니티 콘텐츠를 한 곳에서 관리할 수 있도록 지원합니다.</p>
        <p>현재는 PUBG 및 Discord를 중심으로 기능을 제공하고 있으며 향후 다양한 게임과 커뮤니티 환경을 지원하는 것을 목표로 합니다.</p>
        <p>GuildUp은 현재 베타 서비스로 운영되고 있으며 서비스 기능은 지속적으로 추가 및 변경될 수 있습니다.</p>
      </section>
    </PolicyLayout>
  );
}

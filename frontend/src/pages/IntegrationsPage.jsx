import DashboardLayout from "../components/DashboardLayout.jsx";
import IntegrationServiceCard from "../components/IntegrationServiceCard.jsx";
import { useCommunity } from "../community/CommunityContext.jsx";

export default function IntegrationsPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const { community } = useCommunity();

  const encodedCommunityId = encodeURIComponent(communityId || "");
  const canManage = community?.role === "OWNER" || community?.role === "ADMIN";
  const discordFeatures = [
    {
      id: "roles",
      title: "Discord 역할 관리",
      description: "Discord 역할을 GuildUp 클랜원 역할로 설정합니다.",
      icon: "users",
      href: `/community-settings.html?communityId=${encodedCommunityId}#role-settings-title`,
    },
    {
      id: "activity",
      title: "Discord 활동",
      description: "동기화된 클랜원의 활동 기록을 확인합니다.",
      icon: "activity",
      href: `/member-activities.html?communityId=${encodedCommunityId}`,
    },
    canManage && {
      id: "voice-activity",
      title: "음성 활동",
      description: "최근 14일 Discord 음성 채널 활동을 확인합니다.",
      icon: "activity",
      href: `/discord-voice-activity.html?communityId=${encodedCommunityId}`,
    },
    canManage && {
      id: "dm",
      title: "DM 보내기",
      description: "Discord 서버 멤버에게 운영 공지와 알림을 보냅니다.",
      icon: "discord",
      href: `/discord-dm.html?communityId=${encodedCommunityId}`,
    },
  ].filter(Boolean);

  return (
    <DashboardLayout active="integrations" communityId={communityId} community={community}
                     loadCommunity={false}>
      <div className="dashboard-content integrations-content">
        <div className="page-heading is-compact">
          <p className="eyebrow">Integrations</p>
          <h1>연동 기능</h1>
        </div>

        {community && (
          <section className="integration-service-list" aria-label="연동 서비스 목록">
            <IntegrationServiceCard
              icon="discord"
              name="Discord"
              connected={community.discordConnected}
              connectedLabel={community.discordGuildName || community.discordGuildId || "Discord 서버"}
              connectHref={`/discord-connect.html?communityId=${encodedCommunityId}`}
              features={discordFeatures}
            />
          </section>
        )}
      </div>
    </DashboardLayout>
  );
}

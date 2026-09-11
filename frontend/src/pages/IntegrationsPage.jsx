import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import IntegrationServiceCard from "../components/IntegrationServiceCard.jsx";

export default function IntegrationsPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [community, setCommunity] = useState(null);
  const [message, setMessage] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");

  useEffect(() => {
    if (!validId) return;

    let cancelled = false;
    api(`/api/communities/${encodeURIComponent(communityId)}`)
      .then((data) => {
        if (!cancelled) setCommunity(data);
      })
      .catch((error) => {
        if (!redirectToLogin(error) && !cancelled) {
          setMessage(error.status === 403
            ? "이 커뮤니티의 연동 기능에 접근할 권한이 없습니다."
            : error.message || "연동 정보를 불러오지 못했습니다.");
        }
      });

    return () => { cancelled = true; };
  }, [communityId, validId]);

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
                     loadCommunity={false} onError={setMessage}>
      <div className="dashboard-content integrations-content">
        <div className="page-heading">
          <p className="eyebrow">Integrations</p>
          <h1>연동 기능</h1>
          <p>외부 서비스를 연결하고 커뮤니티에서 사용할 기능을 관리합니다.</p>
        </div>

        {!community && !message && <p className="panel page-state" role="status">연동 정보를 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}

        {community && (
          <section className="integration-service-list" aria-label="연동 서비스 목록">
            <IntegrationServiceCard
              icon="discord"
              name="Discord"
              description="Discord 서버의 역할과 멤버 활동을 GuildUp에서 관리합니다."
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

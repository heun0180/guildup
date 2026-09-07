import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import Icon from "../components/Icon.jsx";

export default function CommunityDashboardPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [community, setCommunity] = useState(null);
  const [memberCount, setMemberCount] = useState(null);
  const [roleCount, setRoleCount] = useState(null);
  const [message, setMessage] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");

  useEffect(() => {
    if (!validId) return;
    let cancelled = false;

    async function loadDashboard() {
      try {
        const dashboard = await api(`/api/communities/${encodeURIComponent(communityId)}`);
        if (cancelled) return;
        setCommunity(dashboard);

        const requests = [api(`/api/communities/${encodeURIComponent(communityId)}/members`)];
        if (dashboard.discordConnected) {
          requests.push(api(`/api/communities/${encodeURIComponent(communityId)}/discord/roles`));
        }
        const [membersResult, rolesResult] = await Promise.allSettled(requests);
        if (cancelled) return;
        if (membersResult.status === "fulfilled") setMemberCount(membersResult.value.length);
        if (rolesResult?.status === "fulfilled") setRoleCount(rolesResult.value.length);
      } catch (error) {
        if (!redirectToLogin(error) && !cancelled) {
          setMessage(error.status === 403 ? "이 커뮤니티에 접근할 권한이 없습니다." : error.message);
        }
      }
    }

    loadDashboard();
    return () => { cancelled = true; };
  }, [communityId, validId]);

  const discordName = community?.discordGuildName || community?.discordGuildId;

  return (
    <DashboardLayout active="dashboard" communityId={communityId} community={community}
                     loadCommunity={false} onError={setMessage}>
      <div className="dashboard-content">
        <div className="page-heading">
          <p className="eyebrow">{community?.name || "Community"}</p>
          <h1>커뮤니티 대시보드</h1>
          <p>{community?.gameName
            ? `${community.gameName} · Discord 서버와 클랜원을 관리할 수 있습니다.`
            : "Discord 서버와 클랜원을 관리할 수 있습니다."}</p>
        </div>

        {!community && !message && <p role="status">커뮤니티를 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}

        {community && (
          <>
            <section className="summary-grid" aria-label="커뮤니티 요약">
              <article className="summary-card">
                <span className="summary-icon"><Icon name="users" size={21} /></span>
                <div><p>클랜원</p><strong>{memberCount === null ? "-" : `${memberCount}명`}</strong></div>
              </article>
              <article className="summary-card">
                <span className={`summary-icon${community.discordConnected ? " success" : ""}`}><Icon name="discord" size={21} /></span>
                <div><p>Discord</p><strong>{community.discordConnected ? "연결됨" : "연결 안 됨"}</strong></div>
              </article>
              <article className="summary-card">
                <span className="summary-icon"><Icon name="link" size={21} /></span>
                <div><p>Discord 역할</p><strong>{roleCount === null ? "-" : `${roleCount}개`}</strong></div>
              </article>
            </section>

            <div className="section-heading">
              <div><h2>관리</h2><p>자주 사용하는 커뮤니티 관리 기능입니다.</p></div>
            </div>
            <section className="management-grid">
              <article className="management-card">
                <span className="management-card-icon discord"><Icon name="discord" size={22} /></span>
                <div className="management-card-body">
                  <div className="management-title-row">
                    <h2>Discord 서버</h2>
                    <span className={`status-badge ${community.discordConnected ? "connected" : "disconnected"}`}>
                      <span aria-hidden="true" />{community.discordConnected ? "연결됨" : "연결 안 됨"}
                    </span>
                  </div>
                  <p>{community.discordConnected
                    ? `${discordName} 서버의 역할과 멤버를 확인할 수 있습니다.`
                    : "Discord 서버가 연결되어 있지 않습니다."}</p>
                  <a className="secondary-button" href={community.discordConnected
                    ? `/members.html?communityId=${encodeURIComponent(communityId)}&discordRoles=true`
                    : `/discord-connect.html?communityId=${encodeURIComponent(communityId)}`}>
                    {community.discordConnected ? "Discord 관리" : "Discord 연결하기"}<Icon name="arrow" size={16} />
                  </a>
                </div>
              </article>
              <article className="management-card">
                <span className="management-card-icon"><Icon name="users" size={22} /></span>
                <div className="management-card-body">
                  <div className="management-title-row"><h2>클랜원</h2></div>
                  <p>Discord 역할을 기준으로 동기화된 커뮤니티 멤버를 확인하고 관리합니다.</p>
                  <a className="secondary-button" href={`/members.html?communityId=${encodeURIComponent(communityId)}`}>
                    클랜원 보기 <Icon name="arrow" size={16} />
                  </a>
                </div>
              </article>
            </section>
          </>
        )}
      </div>
    </DashboardLayout>
  );
}

import Icon from "./Icon.jsx";
import { canAccessCommunityMenu, canManageCommunity } from "../communityAccess.js";
import AppLink from "./AppLink.jsx";

export default function Sidebar({ community, communityId, active, loading = false }) {
  const canManage = canManageCommunity(community?.role);
  const validId = /^\d+$/.test(communityId ?? "");
  const dashboardUrl = validId
    ? `/community-dashboard.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const membersUrl = validId
    ? `/members.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const settingsUrl = validId && canManage
    ? `/community-settings.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const activityUrl = validId && canManage
    ? `/member-activities.html?communityId=${encodeURIComponent(communityId)}`
    : null;
  const teamMakerUrl = validId && canManage
    ? `/team-maker.html?communityId=${encodeURIComponent(communityId)}`
    : null;
  const integrationsUrl = validId && canManage
    ? `/integrations.html?communityId=${encodeURIComponent(communityId)}`
    : null;

  const newsUrl = validId
    ? `/community-news.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const rankingsUrl = validId
    ? `/rankings.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const killCompetitionsUrl = validId
    ? `/kill-competitions.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const bingoUrl = validId
    ? `/bingos.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const feedbackUrl = validId
    ? `/feedback.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";

  const mainItems = [
    { id: "dashboard", label: "대시보드", icon: "dashboard", href: dashboardUrl },
    { id: "news", label: "공지 · 이벤트", icon: "calendar", href: newsUrl },
    { id: "members", label: "클랜원", icon: "users", href: membersUrl },
    { id: "rankings", label: "랭킹", icon: "ranking", href: rankingsUrl },
    { id: "kill-competitions", label: "킬내기", icon: "target", href: killCompetitionsUrl },
    { id: "bingos", label: "빙고", icon: "bingo", href: bingoUrl },
    { id: "feedback", label: "문의/건의", icon: "message", href: feedbackUrl },
  ];
  const managementItems = [
    { id: "activity", label: "인게임 활동", icon: "activity", href: activityUrl },
    { id: "team-maker", label: "팀 만들기", icon: "game", href: teamMakerUrl },
    { id: "integrations", label: "연동 기능", icon: "link", href: integrationsUrl },
  ].filter((item) => canAccessCommunityMenu(community?.role, item.id));

  function renderItem(item) {
    return item.href ? (
      <AppLink className={`sidebar-item${active === item.id ? " is-active" : ""}`} href={item.href} key={item.id}
         aria-current={active === item.id ? "page" : undefined}>
        <Icon name={item.icon} />
        <span>{item.label}</span>
      </AppLink>
    ) : (
      <span className={`sidebar-item is-disabled${active === item.id ? " is-active" : ""}`} key={item.id} aria-disabled="true">
        <Icon name={item.icon} />
        <span>{item.label}</span>
        <small>{item.note}</small>
      </span>
    );
  }

  return (
    <aside className="sidebar" aria-label="커뮤니티 관리 메뉴">
      <AppLink className="community-switcher" href="/communities.html" aria-label="다른 커뮤니티 선택">
        <span className={`community-mark${loading ? " is-loading" : ""}`} aria-hidden="true">
          {community?.name?.charAt(0) || ""}
        </span>
        <span className="community-switcher-copy">
          {community?.name
            ? <strong>{community.name}</strong>
            : <strong className="community-name-skeleton" aria-label="커뮤니티 이름을 불러오는 중" />}
          <span>Community</span>
        </span>
        <span className="community-chevron" aria-hidden="true">⌄</span>
      </AppLink>

      <nav className="sidebar-nav">
        {mainItems.map(renderItem)}
        {managementItems.length > 0 && <>
          <div className="sidebar-section-divider" role="separator">
            <span>관리자 전용</span>
          </div>
          {managementItems.map(renderItem)}
        </>}
      </nav>

      {canAccessCommunityMenu(community?.role, "settings") && <div className="sidebar-footer">
        <AppLink className={`sidebar-item${active === "settings" ? " is-active" : ""}`} href={settingsUrl}
           aria-current={active === "settings" ? "page" : undefined}>
          <Icon name="settings" />
          <span>설정</span>
        </AppLink>
      </div>}
    </aside>
  );
}

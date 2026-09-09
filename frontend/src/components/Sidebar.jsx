import Icon from "./Icon.jsx";

export default function Sidebar({ community, communityId, active }) {
  const validId = /^\d+$/.test(communityId ?? "");
  const dashboardUrl = validId
    ? `/community-dashboard.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const membersUrl = validId
    ? `/members.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const settingsUrl = validId
    ? `/community-settings.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";
  const activityUrl = validId
    ? `/member-activities.html?communityId=${encodeURIComponent(communityId)}`
    : null;
  const teamMakerUrl = validId && (community?.role === "OWNER" || community?.role === "ADMIN")
    ? `/team-maker.html?communityId=${encodeURIComponent(communityId)}`
    : null;
  const integrationsUrl = validId
    ? `/integrations.html?communityId=${encodeURIComponent(communityId)}`
    : "/communities.html";

  const mainItems = [
    { id: "dashboard", label: "대시보드", icon: "dashboard", href: dashboardUrl },
    { id: "members", label: "클랜원", icon: "users", href: membersUrl },
    { id: "activity", label: "활동", icon: "activity", href: activityUrl },
    { id: "team-maker", label: "팀 만들기", icon: "game", href: teamMakerUrl },
    { id: "integrations", label: "연동 기능", icon: "link", href: integrationsUrl },
  ];

  return (
    <aside className="sidebar" aria-label="커뮤니티 관리 메뉴">
      <a className="community-switcher" href="/communities.html" aria-label="다른 커뮤니티 선택">
        <span className="community-mark" aria-hidden="true">{community?.name?.charAt(0) || "G"}</span>
        <span className="community-switcher-copy">
          <strong>{community?.name || "커뮤니티"}</strong>
          <span>Community</span>
        </span>
        <span className="community-chevron" aria-hidden="true">⌄</span>
      </a>

      <nav className="sidebar-nav">
        {mainItems.map((item) => item.href ? (
          <a className={`sidebar-item${active === item.id ? " is-active" : ""}`} href={item.href} key={item.id}
             aria-current={active === item.id ? "page" : undefined}>
            <Icon name={item.icon} />
            <span>{item.label}</span>
          </a>
        ) : (
          <span className={`sidebar-item is-disabled${active === item.id ? " is-active" : ""}`} key={item.id} aria-disabled="true">
            <Icon name={item.icon} />
            <span>{item.label}</span>
            <small>{item.note}</small>
          </span>
        ))}
      </nav>

      <div className="sidebar-footer">
        <a className={`sidebar-item${active === "settings" ? " is-active" : ""}`} href={settingsUrl}
           aria-current={active === "settings" ? "page" : undefined}>
          <Icon name="settings" />
          <span>설정</span>
        </a>
      </div>
    </aside>
  );
}

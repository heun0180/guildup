import { createContext, useContext, useEffect, useState } from "react";
import { Outlet, useLocation } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";
import AppLink from "../components/AppLink.jsx";
import Icon from "../components/Icon.jsx";
import SiteFooter from "../components/SiteFooter.jsx";

const DeveloperContext = createContext(null);

export function useDeveloper() {
  return useContext(DeveloperContext);
}

function AccessState({ title, message, link = "/communities.html" }) {
  return <div className="public-page">
    <AppHeader actions />
    <main className="public-main login-main">
      <section className="login-card">
        <h1>{title}</h1>
        <p className="login-description">{message}</p>
        <AppLink href={link}>내 커뮤니티로 돌아가기</AppLink>
      </section>
    </main>
  </div>;
}

export default function DeveloperLayout() {
  const location = useLocation();
  const [user, setUser] = useState(null);
  const [state, setState] = useState("loading");
  const [message, setMessage] = useState("");

  useEffect(() => {
    let cancelled = false;
    api("/api/auth/me").then((current) => {
      if (cancelled) return;
      setUser(current);
      setState(current.systemAdmin ? "allowed" : "forbidden");
    }).catch((error) => {
      if (!cancelled && !redirectToLogin(error)) {
        setMessage(error.message);
        setState("error");
      }
    });
    return () => { cancelled = true; };
  }, []);

  if (state === "loading") return <AccessState title="권한을 확인하고 있습니다." message="SYSTEM_ADMIN 권한을 확인하는 중입니다." />;
  if (state === "forbidden") return <AccessState title="접근 권한이 없습니다." message="GuildUp SYSTEM_ADMIN만 개발자 페이지를 열 수 있습니다." />;
  if (state === "error") return <AccessState title="페이지를 열 수 없습니다." message={message || "권한을 확인하지 못했습니다."} />;

  const active = location.pathname === "/developer" ? "dashboard"
    : location.pathname.startsWith("/developer/announcements") ? "announcements"
    : location.pathname.startsWith("/developer/monitoring") ? "monitoring"
    : location.pathname.startsWith("/developer/communities") ? "communities" : "";
  return <DeveloperContext.Provider value={{ user }}>
    <div className="app-shell developer-shell">
      <AppHeader actions />
      <div className="dashboard-shell">
        <aside className="sidebar developer-sidebar" aria-label="개발자 메뉴">
          <div className="developer-identity">
            <span className="community-mark">D</span>
            <span><strong>개발자 도구</strong><small>GuildUp 관리</small></span>
          </div>
          <nav className="sidebar-nav">
            <AppLink className={`sidebar-item${active === "announcements" ? " is-active" : ""}`} href="/developer/announcements">
              <Icon name="bell" /><span>GuildUp 공지 관리</span>
            </AppLink>
            <AppLink className={`sidebar-item${active === "dashboard" ? " is-active" : ""}`} href="/developer">
              <Icon name="dashboard" /><span>서비스 현황</span>
            </AppLink>
            <AppLink className={`sidebar-item${active === "communities" ? " is-active" : ""}`} href="/developer/communities">
              <Icon name="users" /><span>커뮤니티</span>
            </AppLink>
            <AppLink className={`sidebar-item${active === "monitoring" ? " is-active" : ""}`} href="/developer/monitoring">
              <Icon name="activity" /><span>모니터링</span>
            </AppLink>
          </nav>
          <div className="sidebar-footer">
            <AppLink className="sidebar-item" href="/communities.html"><Icon name="arrow" /><span>일반 화면</span></AppLink>
          </div>
        </aside>
        <div className="dashboard-body">
          <main className="dashboard-main"><Outlet /></main>
          <SiteFooter />
        </div>
      </div>
    </div>
  </DeveloperContext.Provider>;
}

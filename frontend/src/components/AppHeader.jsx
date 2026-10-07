import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppLink from "./AppLink.jsx";
import Icon from "./Icon.jsx";
import AnnouncementBell from "./AnnouncementBell.jsx";
import UserProfileMenu from "./UserProfileMenu.jsx";
import { useAnnouncements } from "../announcement/AnnouncementContext.jsx";

export default function AppHeader({ actions = false, communityId, onError }) {
  const [user, setUser] = useState(null);
  const [error, setError] = useState("");
  const announcements = useAnnouncements();
  const hasNewAnnouncements = announcements?.notifications?.recent?.some((item) => item?.newAnnouncement);

  function showError(message) {
    setError(message || "사용자 요청을 처리하지 못했습니다. 다시 시도해 주세요.");
    onError?.(message);
  }

  useEffect(() => {
    if (!actions) return;
    api("/api/auth/me")
      .then(setUser)
      .catch((error) => {
        if (!redirectToLogin(error)) showError(error.message);
      });
  }, [actions, onError]);

  async function logout() {
    setError("");
    try {
      await api("/api/auth/logout", { method: "POST" });
      window.location.replace("/login.html");
    } catch (error) {
      if (!redirectToLogin(error)) showError(error.message);
    }
  }

  return (
    <header className="site-header">
      <div className="header-inner">
        <AppLink className="brand" href={actions && /^\d+$/.test(communityId ?? "")
          ? `/community-dashboard.html?communityId=${encodeURIComponent(communityId)}`
          : actions ? "/communities.html" : "/login.html"}>
          <span className="brand-mark" aria-hidden="true">G</span>
          <span>GuildUp</span>
        </AppLink>
        <nav className="header-actions" aria-label={actions ? "사용자 메뉴" : "공용 메뉴"}>
          {(!actions || !user) && <AppLink className="header-link header-help-link" href="/help" aria-label="GuildUp 가이드">
            <Icon name="help" size={17} />
            <span>GuildUp 가이드</span>
          </AppLink>}
          {actions && user && <>
            <AnnouncementBell user={user} />
            <UserProfileMenu user={user} onLogout={logout} hasNewAnnouncements={hasNewAnnouncements} />
          </>}
        </nav>
      </div>
      {error && <div className="header-error message" role="alert">{error}</div>}
    </header>
  );
}

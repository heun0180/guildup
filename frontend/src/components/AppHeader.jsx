import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppLink from "./AppLink.jsx";

export default function AppHeader({ actions = false, communityId, onError }) {
  const [user, setUser] = useState(null);

  useEffect(() => {
    if (!actions) return;
    api("/api/auth/me")
      .then(setUser)
      .catch((error) => {
        if (!redirectToLogin(error)) onError?.(error.message);
      });
  }, [actions, onError]);

  async function logout() {
    try {
      await api("/api/auth/logout", { method: "POST" });
      window.location.replace("/login.html");
    } catch (error) {
      onError?.(error.message);
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
        {actions && (
          <nav className="header-actions" aria-label="사용자 메뉴">
            {user && (
              <span className="user-summary">
                <span className="header-avatar" aria-hidden="true">{user.nickname?.charAt(0) || "U"}</span>
                <span>{user.nickname}</span>
              </span>
            )}
            <AppLink className="header-link" href="/communities.html">내 커뮤니티</AppLink>
            <button className="text-button" type="button" onClick={logout}>로그아웃</button>
          </nav>
        )}
      </div>
    </header>
  );
}

import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";

export default function AppHeader({ actions = false, onError }) {
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
        <a className="brand" href={actions ? "/communities.html" : "/login.html"}>
          <span className="brand-mark" aria-hidden="true">G</span>
          <span>GuildUp</span>
        </a>
        {actions && (
          <nav className="header-actions" aria-label="사용자 메뉴">
            {user && (
              <span className="user-summary">
                <span className="header-avatar" aria-hidden="true">{user.nickname?.charAt(0) || "U"}</span>
                <span>{user.nickname}</span>
              </span>
            )}
            <a className="header-link" href="/communities.html">내 커뮤니티</a>
            <button className="text-button" type="button" onClick={logout}>로그아웃</button>
          </nav>
        )}
      </div>
    </header>
  );
}

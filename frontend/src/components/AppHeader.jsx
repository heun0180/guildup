import { api } from "../api/http.js";

export default function AppHeader({ actions = false, onError }) {
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
        <a className="brand" href={actions ? "/communities.html" : "/login.html"}>GuildUp</a>
        {actions && (
          <nav>
            <a href="/communities.html">내 커뮤니티</a>
            <button type="button" onClick={logout}>로그아웃</button>
          </nav>
        )}
      </div>
    </header>
  );
}

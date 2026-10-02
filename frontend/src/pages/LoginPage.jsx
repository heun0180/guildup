import { useEffect, useState } from "react";
import AppHeader from "../components/AppHeader.jsx";
import Icon from "../components/Icon.jsx";
import SiteFooter from "../components/SiteFooter.jsx";
import { api } from "../api/http.js";

export default function LoginPage() {
  const [message, setMessage] = useState(() => {
    const oauthError = new URLSearchParams(window.location.search).get("oauthError");
    if (oauthError === "session") return "로그인 인증 정보가 만료되었습니다. Discord 로그인을 다시 시작해 주세요.";
    if (oauthError === "discord") return "Discord 로그인이 취소되었거나 완료되지 않았습니다. 다시 시도해 주세요.";
    return "";
  });

  useEffect(() => {
    let cancelled = false;
    api("/api/auth/me")
      .then(() => { if (!cancelled) window.location.replace("/communities.html"); })
      .catch((error) => { if (!cancelled && error.status !== 401) setMessage(error.message); });
    return () => { cancelled = true; };
  }, []);

  return (
    <div className="public-page login-page">
      <AppHeader />
      <main className="public-main login-main">
        <section className="login-card" aria-labelledby="login-title">
          <span className="login-brand-mark" aria-hidden="true">G</span>
          <p className="eyebrow">GuildUp</p>
          <h1 id="login-title">클랜 운영을 더 간편하게</h1>
          <p className="login-description">Discord와 연결하여 커뮤니티와 클랜원을 한곳에서 관리하세요.</p>
          <a className="button-link login-button" href="/api/auth/discord/authorize">
            <Icon name="discord" size={20} />
            Discord로 로그인
          </a>
          {message && <p className="message" role="alert">{message}</p>}
        </section>
      </main>
      <SiteFooter showAbout={false} />
    </div>
  );
}

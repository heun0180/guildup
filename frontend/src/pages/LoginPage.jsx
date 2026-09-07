import { useEffect, useState } from "react";
import AppHeader from "../components/AppHeader.jsx";
import Icon from "../components/Icon.jsx";

export default function LoginPage() {
  const [message, setMessage] = useState("");

  useEffect(() => {
    fetch("/api/auth/me", { credentials: "same-origin" })
      .then((response) => {
        if (response.ok) window.location.replace("/communities.html");
        else if (response.status !== 401) throw new Error("로그인 상태를 확인하지 못했습니다.");
      })
      .catch((error) => setMessage(error.message));
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
    </div>
  );
}

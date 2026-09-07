import { useEffect, useState } from "react";
import AppHeader from "../components/AppHeader.jsx";

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
    <>
      <AppHeader />
      <main className="page-main compact-main">
        <section className="card">
          <h1>GuildUp 로그인</h1>
          <p>Discord 계정으로 로그인하고 내 커뮤니티를 선택하세요.</p>
          <a className="button-link" href="/api/auth/discord/authorize">Discord로 로그인</a>
          {message && <p className="message" role="alert">{message}</p>}
        </section>
      </main>
    </>
  );
}

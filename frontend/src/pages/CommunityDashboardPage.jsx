import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";

export default function CommunityDashboardPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [community, setCommunity] = useState(null);
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (!validId) {
      setMessage("올바른 커뮤니티를 선택해 주세요.");
      return;
    }
    api(`/api/communities/${encodeURIComponent(communityId)}`)
      .then(setCommunity)
      .catch((error) => {
        if (!redirectToLogin(error)) {
          setMessage(error.status === 403 ? "이 커뮤니티에 접근할 권한이 없습니다." : error.message);
        }
      });
  }, [communityId, validId]);

  return (
    <>
      <AppHeader actions onError={setMessage} />
      <main className="page-main wide-main">
        <h1>{community?.name ?? "커뮤니티 대시보드"}</h1>
        {!community && !message && <p role="status">커뮤니티를 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}
        {community && (
          <div className="community-grid">
            <section className="card">
              <h2>Discord</h2>
              <p>{community.discordConnected
                ? `Discord 연결됨 · 서버 이름: ${community.discordGuildName || community.discordGuildId}`
                : "Discord 서버가 연결되어 있지 않습니다."}</p>
              <a className="button-link" href={community.discordConnected
                ? `/members.html?communityId=${encodeURIComponent(communityId)}&discordRoles=true`
                : `/discord-connect.html?communityId=${encodeURIComponent(communityId)}`}>
                {community.discordConnected ? "역할 관리 보기" : "Discord 연결하기"}
              </a>
            </section>
            <section className="card">
              <h2>클랜원 관리</h2>
              <p>커뮤니티의 클랜원을 직접 등록하고 조회합니다.</p>
              <a className="button-link" href={`/members.html?communityId=${encodeURIComponent(communityId)}`}>
                클랜원 관리
              </a>
            </section>
            <section className="card">
              <h2>게임 관리 · 설정</h2>
              <p>준비 중입니다.</p>
              <button type="button" disabled>준비 중</button>
            </section>
          </div>
        )}
      </main>
    </>
  );
}

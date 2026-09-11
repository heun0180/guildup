import { useCallback, useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";
import Icon from "../components/Icon.jsx";

export default function CommunitiesPage() {
  const [communities, setCommunities] = useState([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [gameType, setGameType] = useState("BATTLEGROUNDS_KAKAO");
  const [message, setMessage] = useState("");

  const loadCommunities = useCallback(async () => {
    try {
      setCommunities(await api("/api/auth/me/communities"));
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadCommunities(); }, [loadCommunities]);

  async function createCommunity(event) {
    event.preventDefault();
    const normalized = name.trim();
    if (!normalized) return setMessage("커뮤니티 이름을 입력해 주세요.");
    setCreating(true);
    setMessage("");
    try {
      const created = await api("/api/communities", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: normalized, gameType }),
      });
      setName("");
      window.location.assign(`/discord-connect.html?communityId=${encodeURIComponent(created.id)}`);
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message);
    } finally {
      setCreating(false);
    }
  }

  return (
    <div className="public-page">
      <AppHeader actions onError={setMessage} />
      <main className="public-main communities-main">
        <div className="public-heading">
          <p className="eyebrow">Communities</p>
          <h1>내 커뮤니티</h1>
          <p>관리할 커뮤니티를 선택하세요.</p>
        </div>
        {loading && <p className="panel page-state" role="status">커뮤니티를 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}
        {!loading && communities.length === 0 && (
          <p className="panel page-state">아직 참여한 커뮤니티가 없습니다. 새 커뮤니티를 만들어 시작하세요.</p>
        )}
        <div className="community-grid">
          {communities.map((community) => (
            <article className="community-card" key={community.id}>
              <div className="community-card-top">
                <span className="community-card-mark" aria-hidden="true">{community.name?.charAt(0) || "G"}</span>
                <span className="role-badge">{community.role}</span>
              </div>
              <h2>{community.name}</h2>
              <p>{community.gameName || "게임 미설정"}</p>
              <a className="community-card-link" href={`/community-dashboard.html?communityId=${encodeURIComponent(community.id)}`}>
                대시보드 열기 <Icon name="arrow" size={17} />
              </a>
            </article>
          ))}
        </div>
        <section className="create-community-card" aria-labelledby="create-community-title">
          <div className="create-community-copy">
            <span className="add-mark"><Icon name="plus" size={20} /></span>
            <div><h2 id="create-community-title">새 커뮤니티</h2><p>새로운 클랜 관리 공간을 만듭니다.</p></div>
          </div>
          <form className="create-community-form" onSubmit={createCommunity}>
            <label htmlFor="community-name">
              <span>커뮤니티 이름</span>
              <input id="community-name" maxLength="255" required placeholder="예: 치즈 클랜"
                     value={name} onChange={(event) => setName(event.target.value)} />
            </label>
            <label htmlFor="community-game">
              <span>게임</span>
              <select id="community-game" required value={gameType}
                      onChange={(event) => setGameType(event.target.value)}>
                <option value="BATTLEGROUNDS_KAKAO">배틀그라운드 카카오</option>
                <option value="BATTLEGROUNDS_STEAM">배틀그라운드 스팀</option>
              </select>
            </label>
            <button type="submit" disabled={creating}>{creating ? "만드는 중..." : "커뮤니티 만들기"}</button>
          </form>
        </section>
      </main>
    </div>
  );
}

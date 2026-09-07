import { useCallback, useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";

export default function CommunitiesPage() {
  const [communities, setCommunities] = useState([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
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
      await api("/api/communities", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: normalized }),
      });
      setName("");
      await loadCommunities();
    } catch (error) {
      if (!redirectToLogin(error)) setMessage(error.message);
    } finally {
      setCreating(false);
    }
  }

  return (
    <>
      <AppHeader actions onError={setMessage} />
      <main className="page-main wide-main">
        <h1>내 커뮤니티</h1>
        <p>사용할 커뮤니티를 선택하세요.</p>
        {loading && <p role="status">커뮤니티를 불러오는 중입니다.</p>}
        {message && <p className="message" role="alert">{message}</p>}
        {!loading && communities.length === 0 && (
          <p>아직 참여한 커뮤니티가 없습니다. 새 커뮤니티를 만들어 시작하세요.</p>
        )}
        <div className="community-grid">
          {communities.map((community) => (
            <article className="card" key={community.id}>
              <h2>{community.name}</h2>
              <p>{community.role}</p>
              <a className="button-link" href={`/community-dashboard.html?communityId=${encodeURIComponent(community.id)}`}>
                들어가기
              </a>
            </article>
          ))}
        </div>
        <section className="card">
          <h2>새 커뮤니티 만들기</h2>
          <form className="inline-form" onSubmit={createCommunity}>
            <label htmlFor="community-name">커뮤니티 이름</label>
            <input id="community-name" maxLength="255" required placeholder="치즈 클랜"
                   value={name} onChange={(event) => setName(event.target.value)} />
            <button type="submit" disabled={creating}>{creating ? "만드는 중..." : "만들기"}</button>
          </form>
        </section>
      </main>
    </>
  );
}

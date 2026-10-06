import { useEffect, useState } from "react";
import { usePubgGame } from "../community/usePubgGame.js";
import { useScopedApi } from "../community/GameScopeBoundary.jsx";
import { isRequestCancelled, redirectToLogin } from "../api/http.js";

/** Discord 없는 커뮤니티의 멤버가 기존 플랫폼별 PUBG 계정을 직접 등록한다. */
export default function CommunityGameAccountForm({ communityId }) {
  const api = useScopedApi();
  const game = usePubgGame();
  const [nickname, setNickname] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const endpoint = game ? `/api/communities/${encodeURIComponent(communityId)}/games/${game.communityGameId}/pubg-account/me` : null;

  useEffect(() => {
    if (!endpoint) return;
    let cancelled = false;
    api(endpoint).then((account) => { if (!cancelled) setNickname(account.nickname || ""); })
      .catch((error) => { if (!cancelled && !isRequestCancelled(error) && !redirectToLogin(error)) setMessage(error.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [api, endpoint]);

  async function save(event) {
    event.preventDefault();
    if (saving) return;
    setSaving(true); setMessage("");
    try {
      const account = await api(endpoint, { method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ nickname: nickname.trim() }) });
      setNickname(account.nickname);
      setMessage("게임 계정을 연결했습니다. 빙고와 킬내기 등에 사용할 수 있습니다.");
    } catch (error) {
      if (!isRequestCancelled(error) && !redirectToLogin(error)) setMessage(error.message);
    } finally { setSaving(false); }
  }

  if (!game) return null;
  return <form className="panel creation-form" onSubmit={save}>
    <h2>내 게임 계정</h2>
    <p>Discord 없이도 게임 닉네임으로 {game.gameName} 계정을 등록할 수 있습니다.</p>
    <label htmlFor="my-game-nickname">게임 닉네임</label>
    <input id="my-game-nickname" required maxLength={64} value={nickname} disabled={loading || saving}
      placeholder="게임에서 사용하는 닉네임" onChange={(event) => setNickname(event.target.value)} />
    <button disabled={loading || saving}>{saving ? "확인 중..." : "게임 계정 연결하기"}</button>
    {message && <p role="status">{message}</p>}
  </form>;
}

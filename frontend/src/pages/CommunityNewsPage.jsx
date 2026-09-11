import { useCallback, useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import CommunityNewsForm from "../components/CommunityNewsForm.jsx";
import CommunityNewsMeta from "../components/CommunityNewsMeta.jsx";
import { canManageNews, formatNewsDate, newsError, newsUrl } from "../communityNews.js";

export default function CommunityNewsPage() {
  const query = new URLSearchParams(window.location.search);
  const communityId = query.get("communityId");
  const tab = query.get("tab") === "events" ? "events" : "notices";
  const id = query.get("id");
  const valid = /^\d+$/.test(communityId ?? "") && (id === null || /^\d+$/.test(id));
  const isNotice = tab === "notices";
  const [community, setCommunity] = useState(null);
  const [items, setItems] = useState([]);
  const [item, setItem] = useState(null);
  const [loading, setLoading] = useState(valid);
  const [error, setError] = useState(valid ? "" : "올바른 커뮤니티와 게시물을 선택해 주세요.");
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(0);
  const canManage = canManageNews(community);
  const handleLayoutError = useCallback(() => setError("요청을 처리하지 못했습니다. 다시 시도해 주세요."), []);
  const base = `/api/communities/${encodeURIComponent(communityId)}/${tab}`;

  useEffect(() => {
    if (!valid) return;
    let cancelled = false;
    setLoading(true);
    setError("");
    Promise.all([api(`/api/communities/${encodeURIComponent(communityId)}`), api(id ? `${base}/${id}` : base)])
      .then(([dashboard, result]) => {
        if (cancelled) return;
        setCommunity(dashboard);
        if (id) setItem(result); else setItems(result);
      }).catch((failure) => {
        if (!cancelled && !redirectToLogin(failure)) setError(newsError(failure));
      }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [communityId, base, id, valid, retry]);

  async function save(payload) {
    setBusy(true);
    setError("");
    try {
      const result = await api(item ? `${base}/${item.id}` : base, {
        method: item ? "PUT" : "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
      });
      window.location.assign(newsUrl(communityId, tab, result.id));
    } catch (failure) {
      if (!redirectToLogin(failure)) setError(newsError(failure));
      setBusy(false);
    }
  }

  async function remove() {
    if (busy || !window.confirm(`이 ${isNotice ? "공지" : "이벤트"}를 삭제하시겠습니까? 삭제한 내용은 복구할 수 없습니다.`)) return;
    setBusy(true);
    setError("");
    try {
      await api(`${base}/${item.id}`, { method: "DELETE" });
      window.location.assign(newsUrl(communityId, tab));
    } catch (failure) {
      if (!redirectToLogin(failure)) setError(newsError(failure));
      setBusy(false);
    }
  }

  return (
    <DashboardLayout active="news" communityId={communityId} community={community} loadCommunity={false}
                     onError={handleLayoutError}>
      <div className="dashboard-content">
        <div className="page-heading"><p className="eyebrow">{community?.name || "Community"}</p>
          <h1>공지 · 이벤트</h1><p>커뮤니티 소식과 함께할 일정을 확인하세요.</p>
        </div>
        <div className="role-tabs" role="tablist" aria-label="커뮤니티 소식">
          {[["notices", "공지"], ["events", "이벤트"]].map(([value, label]) => (
            <button key={value} id={`tab-${value}`} className="role-tab" role="tab" aria-selected={tab === value}
                    aria-controls="news-content" tabIndex={tab === value ? 0 : -1} disabled={busy}
                    onKeyDown={(event) => {
                      if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) {
                        event.preventDefault();
                        window.location.assign(newsUrl(communityId,
                          event.key === "Home" ? "notices" : event.key === "End" ? "events" : isNotice ? "events" : "notices"));
                      }
                    }}
                    onClick={() => window.location.assign(newsUrl(communityId, value))}>{label}</button>
          ))}
        </div>
        {error && <div className="message" role="alert">{error}{" "}
          {!editing && !busy && valid && <button className="secondary-button" onClick={() => setRetry((value) => value + 1)}>다시 시도</button>}
        </div>}
        {loading && <p className="panel page-state" role="status">소식을 불러오는 중입니다.</p>}
        {!loading && community && <section id="news-content" style={{ paddingTop: "var(--space-card)" }} role="tabpanel" aria-labelledby={`tab-${tab}`}>
          {editing && canManage ? <CommunityNewsForm isNotice={isNotice} item={item} busy={busy} onSave={save}
            onCancel={() => { setEditing(false); setError(""); }} /> : item ? <article className="panel role-settings-panel">
            <CommunityNewsMeta item={item} isNotice={isNotice} />
            <h2>{item.title}</h2>
            <p>작성자 {item.authorName} · 작성 {formatNewsDate(item.createdAt)}</p>
            <p>수정 {formatNewsDate(item.updatedAt)}</p>
            <div className="dm-message-field" style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
              {item.content || "등록된 설명이 없습니다."}
            </div>
            <div className="settings-actions">
              <a className="secondary-button" href={newsUrl(communityId, tab)}>목록으로</a>
              {canManage && <div className="team-selection-actions">
                <button className="secondary-button" disabled={busy} onClick={() => { setError(""); setEditing(true); }}>수정</button>
                <button className="secondary-button" disabled={busy} onClick={remove}>{busy ? "삭제 중…" : "삭제"}</button>
              </div>}
            </div>
          </article> : <>
            <div className="section-heading"><h2>{isNotice ? "커뮤니티 공지" : "커뮤니티 이벤트"}</h2>
              {canManage && <button onClick={() => { setError(""); setEditing(true); }}>{isNotice ? "공지 작성" : "이벤트 만들기"}</button>}
            </div>
            {items.length === 0 ? <div className="panel empty-state">
              <p>{isNotice ? "등록된 공지가 없습니다." : "등록된 이벤트가 없습니다."}</p>
              {canManage && <p>{isNotice ? "첫 공지를 작성해보세요." : "첫 이벤트를 만들어보세요."}</p>}
            </div> : <div className="management-grid">
              {items.map((entry) => <article key={entry.id} className="panel role-settings-panel"
                style={isNotice && entry.pinned ? { background: "var(--color-primary-soft)", borderColor: "var(--color-primary)" } : undefined}>
                <CommunityNewsMeta item={entry} isNotice={isNotice} />
                <h2><a href={newsUrl(communityId, tab, entry.id)}>{entry.title}</a></h2>
                {isNotice && <p>{entry.authorName} · {formatNewsDate(entry.createdAt)}</p>}
              </article>)}
            </div>}
          </>}
        </section>}
      </div>
    </DashboardLayout>
  );
}

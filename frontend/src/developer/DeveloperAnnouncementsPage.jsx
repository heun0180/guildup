import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import { useAnnouncements } from "../announcement/AnnouncementContext.jsx";
import { formatNewsDate } from "../communityNews.js";
import AnnouncementForm from "../components/AnnouncementForm.jsx";
import AnnouncementMeta from "../components/AnnouncementMeta.jsx";

export default function DeveloperAnnouncementsPage() {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState(undefined);
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(0);
  const refresh = useAnnouncements()?.refresh;
  const base = "/api/developer/announcements";

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setError("");
    api(`${base}?page=${page}&size=20`, { cache: "no-store" }).then((response) => {
      if (!cancelled) setResult(response);
    }).catch((failure) => {
      if (!cancelled && !redirectToLogin(failure)) setError(failure.message);
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [page, retry]);

  async function edit(id) {
    setBusy(true); setError("");
    try { setEditing(await api(`${base}/${id}`, { cache: "no-store" })); }
    catch (failure) { if (!redirectToLogin(failure)) setError(failure.message); }
    finally { setBusy(false); }
  }
  async function save(payload) {
    setBusy(true); setError("");
    try {
      await api(editing ? `${base}/${editing.announcement.id}` : base, {
        method: editing ? "PUT" : "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
      });
      setEditing(undefined); setPage(0); setRetry((value) => value + 1); refresh?.();
    } catch (failure) { if (!redirectToLogin(failure)) setError(failure.message); }
    finally { setBusy(false); }
  }
  async function remove(item) {
    if (busy || !window.confirm(`“${item.title}” 공지를 삭제하시겠습니까?`)) return;
    setBusy(true); setError("");
    try {
      await api(`${base}/${item.id}`, { method: "DELETE" });
      if (result.content.length === 1 && page > 0) setPage(page - 1);
      else setRetry((value) => value + 1);
      refresh?.();
    } catch (failure) { if (!redirectToLogin(failure)) setError(failure.message); }
    finally { setBusy(false); }
  }

  return <div className="dashboard-content developer-content">
    <div className="page-heading feature-page-heading"><div><p className="eyebrow">GuildUp</p><h1>GuildUp 공지 관리</h1>
      <p>서비스 전체 공지의 공개 상태와 게시 기간을 관리합니다.</p></div>
      {editing === undefined && <button disabled={busy} onClick={() => { setError(""); setEditing(null); }}>공지 작성</button>}
    </div>
    {error && <div className="message" role="alert">{error}{" "}
      {editing === undefined && <button className="secondary-button" disabled={busy} onClick={() => setRetry((value) => value + 1)}>다시 시도</button>}
    </div>}
    {editing !== undefined ? <AnnouncementForm key={editing?.announcement.id ?? "new"} detail={editing} busy={busy} onSave={save}
      onCancel={() => { setEditing(undefined); setError(""); }} /> : loading ? <p className="panel page-state" role="status">공지를 불러오는 중입니다.</p>
      : result && <>
        {result.content.length === 0 && <div className="panel empty-state">등록된 GuildUp 공지가 없습니다.</div>}
        <div className="announcement-list">{result.content.map((item) => <article key={item.id} className="panel announcement-admin-card">
          <AnnouncementMeta item={item} admin /><h2>{item.title}</h2>
          <p>작성 {formatNewsDate(item.createdAt)} · 수정 {formatNewsDate(item.updatedAt)}</p>
          <p>게시 기간: {item.publishStartAt ? formatNewsDate(item.publishStartAt) : "즉시"} ~ {item.publishEndAt ? formatNewsDate(item.publishEndAt) : "제한 없음"}</p>
          <div className="settings-actions"><button className="secondary-button" disabled={busy} onClick={() => edit(item.id)}>수정</button>
            <button className="secondary-button" disabled={busy} onClick={() => remove(item)}>삭제</button></div>
        </article>)}</div>
        {result.totalPages > 1 && <nav className="announcement-pagination" aria-label="관리 공지 페이지">
          <button className="secondary-button" disabled={page === 0 || busy} onClick={() => setPage(page - 1)}>이전</button>
          <span>{page + 1} / {result.totalPages}</span>
          <button className="secondary-button" disabled={page + 1 >= result.totalPages || busy} onClick={() => setPage(page + 1)}>다음</button>
        </nav>}
      </>}
  </div>;
}

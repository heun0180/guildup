import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";
import { useAnnouncements } from "../announcement/AnnouncementContext.jsx";
import { announcementUrl } from "../announcements.js";
import { formatNewsDate } from "../communityNews.js";
import AppHeader from "../components/AppHeader.jsx";
import AppLink from "../components/AppLink.jsx";
import SiteFooter from "../components/SiteFooter.jsx";
import AnnouncementMeta from "../components/AnnouncementMeta.jsx";

export default function AnnouncementsPage() {
  const [query, setQuery] = useSearchParams();
  const id = query.get("id");
  const page = Number(query.get("page") ?? 0);
  const valid = (id === null || /^[1-9]\d*$/.test(id)) && Number.isSafeInteger(page) && page >= 0;
  const requestKey = `${id ?? "list"}:${page}`;
  const [responseState, setResponseState] = useState(null);
  const result = responseState?.key === requestKey ? responseState.value : null;
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const announcements = useAnnouncements();
  const refresh = announcements?.refresh;
  const recordRead = announcements?.recordRead;

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setError(""); setResponseState(null);
    if (!valid) { setError("올바른 공지 주소를 확인해 주세요."); setLoading(false); return; }
    async function load() {
      try {
        const response = await api(id ? `/api/announcements/${id}` : `/api/announcements?page=${page}&size=20`, { cache: "no-store" });
        if (cancelled) return;
        setResponseState({ key: requestKey, value: response }); setLoading(false);
        if (id && !response.announcement.read) {
          try {
            const read = await api(`/api/announcements/${id}/read`, { method: "POST" });
            if (!cancelled) { setResponseState({ key: requestKey, value: read }); recordRead?.(read.announcement.id); refresh?.(); }
          } catch (failure) {
            if (!cancelled && !redirectToLogin(failure)) setError("공지는 열었지만 읽음 기록을 저장하지 못했습니다. 다시 시도해 주세요.");
          }
        }
      } catch (failure) {
        if (!cancelled && !redirectToLogin(failure)) {
          setError(failure.status === 404 ? "공지를 찾을 수 없거나 게시 기간이 종료되었습니다." : failure.message);
        }
      } finally { if (!cancelled) setLoading(false); }
    }
    load();
    return () => { cancelled = true; };
  }, [id, page, valid, retry, refresh, requestKey, recordRead]);

  return <div className="public-page">
    <AppHeader actions />
    <main className="public-main announcements-main">
      <header className="page-heading"><p className="eyebrow">GuildUp</p><h1>GuildUp 공지</h1>
        <p>서비스 소식과 업데이트, 점검 안내를 확인하세요.</p>
      </header>
      {error && <div className="message" role="alert">{error}{" "}
        {valid && <button className="secondary-button" onClick={() => setRetry((value) => value + 1)}>다시 시도</button>}
      </div>}
      {loading && <p className="panel page-state" role="status">공지를 불러오는 중입니다.</p>}
      {!loading && result && (id ? <article className="panel announcement-detail">
        <AnnouncementMeta item={result.announcement} />
        <h2>{result.announcement.title}</h2>
        <p>작성일 <time dateTime={result.announcement.createdAt}>{formatNewsDate(result.announcement.createdAt)}</time></p>
        <div className="announcement-body">{result.content}</div>
        <AppLink className="secondary-button" href={announcementUrl()}>목록으로</AppLink>
      </article> : <>
        {result.content.length === 0 ? <div className="panel empty-state">게시중인 GuildUp 공지가 없습니다.</div>
          : <div className="announcement-list">{result.content.map((item) => <AppLink key={item.id}
            className={`panel announcement-card${item.pinned || item.important ? " is-highlighted" : ""}`} href={announcementUrl(item.id)}>
            <div><AnnouncementMeta item={{ ...item, read: item.read || announcements?.readIds.has(item.id) }} /><h2>{item.title}</h2></div>
            <time dateTime={item.createdAt}>{formatNewsDate(item.createdAt)}</time>
          </AppLink>)}</div>}
        {result.totalPages > 1 && <nav className="announcement-pagination" aria-label="공지 페이지">
          <button className="secondary-button" disabled={page === 0} onClick={() => setQuery({ page: String(page - 1) })}>이전</button>
          <span>{page + 1} / {result.totalPages}</span>
          <button className="secondary-button" disabled={page + 1 >= result.totalPages} onClick={() => setQuery({ page: String(page + 1) })}>다음</button>
        </nav>}
      </>)}
      <AppLink className="announcement-back" href="/communities.html">내 커뮤니티로</AppLink>
    </main>
    <SiteFooter />
  </div>;
}

import { useCallback, useEffect, useMemo, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";

const CATEGORIES = ["SYSTEM", "HTTP", "PUBG_API", "BINGO", "KILL_COMPETITION", "DISCORD", "DATABASE"];

function bytes(value) {
  if (!Number.isFinite(value) || value <= 0) return "-";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let number = value;
  let index = 0;
  while (number >= 1024 && index < units.length - 1) { number /= 1024; index += 1; }
  return `${number.toFixed(index >= 3 ? 1 : 0)}${units[index]}`;
}

function uptime(seconds) {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return [days && `${days}일`, hours && `${hours}시간`, `${minutes}분`].filter(Boolean).join(" ");
}

function dateTime(value) {
  return value ? new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "medium" }).format(new Date(value)) : "-";
}

function StatusBadge({ value }) {
  return <span className={`monitoring-badge monitoring-${String(value).toLowerCase()}`}>{value}</span>;
}

function EventDetail({ event, onClose }) {
  if (!event) return null;
  return <div className="monitoring-modal-backdrop" role="presentation" onMouseDown={onClose}>
    <section className="panel monitoring-modal" role="dialog" aria-modal="true" aria-labelledby="monitoring-detail-title"
             onMouseDown={(e) => e.stopPropagation()}>
      <header><div><p className="eyebrow">Monitoring Event</p><h2 id="monitoring-detail-title">{event.eventCode}</h2></div>
        <button type="button" className="secondary-button" onClick={onClose}>닫기</button></header>
      <dl className="monitoring-detail-grid">
        <div><dt>Severity</dt><dd><StatusBadge value={event.severity} /></dd></div>
        <div><dt>Category</dt><dd>{event.category}</dd></div>
        <div><dt>발생 시간</dt><dd>{dateTime(event.occurredAt)}</dd></div>
        <div><dt>Community</dt><dd>{event.communityName || "-"}{event.communityId ? ` (#${event.communityId})` : ""}</dd></div>
        <div><dt>User ID</dt><dd>{event.userId ?? "-"}</dd></div>
        <div><dt>Reference</dt><dd>{event.referenceId || "-"}</dd></div>
        <div className="monitoring-detail-wide"><dt>Message</dt><dd>{event.message}</dd></div>
      </dl>
      <div className="monitoring-metadata"><h3>Metadata</h3>
        {Object.keys(event.metadata || {}).length === 0 ? <p>추가 context가 없습니다.</p>
          : <dl>{Object.entries(event.metadata).map(([key, value]) => <div key={key}><dt>{key}</dt>
            <dd>{typeof value === "object" ? JSON.stringify(value) : String(value)}</dd></div>)}</dl>}
      </div>
    </section>
  </div>;
}

export default function MonitoringPage() {
  const [summary, setSummary] = useState(null);
  const [events, setEvents] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState({ severity: "", category: "", eventCode: "", communityId: "", from: "", to: "" });
  const [applied, setApplied] = useState(filters);
  const [detail, setDetail] = useState(null);

  const query = useMemo(() => {
    const params = new URLSearchParams({ page: String(page), size: "20" });
    Object.entries(applied).forEach(([key, value]) => {
      if (!value) return;
      if (key === "from") params.set(key, new Date(`${value}T00:00:00`).toISOString());
      else if (key === "to") params.set(key, new Date(`${value}T23:59:59.999`).toISOString());
      else params.set(key, value.trim ? value.trim() : value);
    });
    return params.toString();
  }, [applied, page]);

  const refresh = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    try {
      const [nextSummary, nextEvents] = await Promise.all([
        api("/api/developer/monitoring/summary"),
        api(`/api/developer/monitoring/events?${query}`),
      ]);
      setSummary(nextSummary);
      setEvents(nextEvents);
      setError("");
    } catch (failure) {
      if (!redirectToLogin(failure)) setError(failure.message);
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    let active = true;
    refresh();
    const timer = window.setInterval(() => { if (active && !document.hidden) refresh(true); }, 30000);
    return () => { active = false; window.clearInterval(timer); };
  }, [refresh]);

  async function openDetail(id) {
    try { setDetail(await api(`/api/developer/monitoring/events/${id}`)); }
    catch (failure) { if (!redirectToLogin(failure)) setError(failure.message); }
  }

  const serverCards = summary ? [
    ["서버", summary.server.status], ["DB", summary.database.status], ["Uptime", uptime(summary.server.uptimeSeconds)],
    ["CPU", summary.server.cpuUsage == null ? "-" : `${Math.round(summary.server.cpuUsage * 100)}%`],
    ["Memory", `${bytes(summary.server.memoryUsed)} / ${bytes(summary.server.memoryMax)}`],
    ["Disk", `${bytes(summary.server.diskUsed)} / ${bytes(summary.server.diskTotal)}`],
  ] : [];
  const countCards = summary ? [
    ["ERROR", summary.last24Hours.errors], ["HTTP 5xx", summary.last24Hours.http5xx],
    ["PUBG 429", summary.last24Hours.pubg429], ["PUBG Timeout", summary.last24Hours.pubgTimeouts],
    ["PUBG 실패", summary.last24Hours.pubgFailures], ["Bingo 실패", summary.last24Hours.bingoFailures],
    ["Kill Competition 실패", summary.last24Hours.killCompetitionFailures], ["Discord 실패", summary.last24Hours.discordFailures],
  ] : [];

  return <div className="dashboard-content developer-content monitoring-content">
    <div className="page-heading developer-heading"><p className="eyebrow">Developer</p><h1>모니터링</h1>
      <p>현재 서버 상태와 운영상 확인이 필요한 최근 이벤트입니다. 30초마다 자동으로 갱신됩니다.</p></div>
    {error && <p className="message" role="alert">{error}</p>}
    <section><h2 className="monitoring-section-title">서버 상태</h2>
      <div className="monitoring-status-grid">{serverCards.map(([label, value]) => <article className="panel monitoring-status-card" key={label}>
        <span>{label}</span><strong>{value}</strong></article>)}</div></section>
    <section><h2 className="monitoring-section-title">최근 24시간</h2>
      <div className="monitoring-count-grid">{countCards.map(([label, value]) => <article className="panel monitoring-count-card" key={label}>
        <span>{label}</span><strong>{Number(value).toLocaleString()}</strong></article>)}</div></section>
    <section><h2 className="monitoring-section-title">최근 오류 및 경고</h2>
      <form className="panel monitoring-filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(filters); }}>
        <label><span>Severity</span><select value={filters.severity} onChange={(e) => setFilters({ ...filters, severity: e.target.value })}>
          <option value="">전체</option><option>ERROR</option><option>WARN</option><option>INFO</option></select></label>
        <label><span>Category</span><select value={filters.category} onChange={(e) => setFilters({ ...filters, category: e.target.value })}>
          <option value="">전체</option>{CATEGORIES.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label><span>Event Code</span><input value={filters.eventCode} placeholder="예: HTTP_5XX"
          onChange={(e) => setFilters({ ...filters, eventCode: e.target.value.toUpperCase() })} /></label>
        <label><span>Community ID</span><input type="number" min="1" value={filters.communityId}
          onChange={(e) => setFilters({ ...filters, communityId: e.target.value })} /></label>
        <label><span>시작일</span><input type="date" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })} /></label>
        <label><span>종료일</span><input type="date" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })} /></label>
        <button type="submit">조회</button>
      </form>
      <div className="panel developer-table-wrap monitoring-table-wrap">
        <table className="developer-table monitoring-table"><thead><tr><th>시간</th><th>Severity</th><th>Category</th>
          <th>Event Code</th><th>Community</th><th>메시지</th></tr></thead><tbody>
          {events?.content.map((event) => <tr key={event.id} tabIndex="0" onClick={() => openDetail(event.id)}
            onKeyDown={(e) => { if (e.key === "Enter") openDetail(event.id); }}>
            <td>{dateTime(event.occurredAt)}</td><td><StatusBadge value={event.severity} /></td><td>{event.category}</td>
            <td><code>{event.eventCode}</code></td><td>{event.communityName || "-"}{event.communityId ? <small>#{event.communityId}</small> : null}</td>
            <td>{event.message}</td></tr>)}
          {!loading && events?.content.length === 0 && <tr><td colSpan="6" className="monitoring-empty">조건에 맞는 이벤트가 없습니다.</td></tr>}
          {loading && <tr><td colSpan="6" className="monitoring-empty">모니터링 정보를 불러오는 중입니다.</td></tr>}
        </tbody></table>
      </div>
      {events && events.totalPages > 1 && <nav className="developer-pager" aria-label="이벤트 페이지 이동">
        <button className="secondary-button" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page + 1} / {events.totalPages}</span>
        <button className="secondary-button" disabled={page + 1 >= events.totalPages} onClick={() => setPage(page + 1)}>다음</button>
      </nav>}
    </section>
    <EventDetail event={detail} onClose={() => setDetail(null)} />
  </div>;
}

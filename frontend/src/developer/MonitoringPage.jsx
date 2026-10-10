import { useCallback, useEffect, useMemo, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import LiveLogsPanel from "./LiveLogsPanel.jsx";
import { LOG_GROUPS, EMPTY_FILTERS, SLOW_SYNC_MS, monitoringQuery, duration, syncIdOf, isSlow, syncSummary } from "./monitoringView.js";

const CATEGORIES = ["SYSTEM", "HTTP", "PUBG_API", "BINGO", "KILL_COMPETITION", "DISCORD", "DATABASE", "SECURITY"];

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

function EventDetail({ event, onClose, onOpen }) {
  const syncId = syncIdOf(event);
  const [timeline, setTimeline] = useState(null);
  const [latest, setLatest] = useState(null);
  const [timelinePage, setTimelinePage] = useState(0);
  const [timelineError, setTimelineError] = useState("");
  const [revision, setRevision] = useState(0);
  useEffect(() => { setTimelinePage(0); setTimeline(null); setLatest(null); }, [syncId]);
  useEffect(() => {
    if (!syncId) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ syncId, order: "ASC", size: "100", page: String(timelinePage) });
    setTimelineError("");
    Promise.all([api(`/api/developer/monitoring/events?${params}`, { signal: controller.signal }),
      api(`/api/developer/monitoring/events?${new URLSearchParams({ group: "ACTIVITY", syncId, order: "DESC", size: "1" })}`, { signal: controller.signal }),
    ]).then(([flow, last]) => { if (!controller.signal.aborted) { setTimeline(flow); setLatest(last.content[0]); } })
      .catch((failure) => { if (!controller.signal.aborted && !redirectToLogin(failure)) setTimelineError(failure.message); });
    return () => controller.abort();
  }, [syncId, timelinePage, revision]);
  useEffect(() => {
    const close = (e) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, [onClose]);
  if (!event) return null;
  const operation = { ...event.metadata, ...latest?.metadata };
  return <div className="monitoring-modal-backdrop" role="presentation" onMouseDown={onClose}>
    <section className="panel monitoring-modal" role="dialog" aria-modal="true" aria-labelledby="monitoring-detail-title"
             onMouseDown={(e) => e.stopPropagation()}>
      <header><div><p className="eyebrow">Monitoring Event</p><h2 id="monitoring-detail-title">{event.eventCode}</h2></div>
        <button type="button" className="secondary-button" onClick={onClose}>닫기</button></header>
      {syncId && <div className="monitoring-sync-summary" aria-label="활동 조회 작업 요약">
        <span><small>상태</small><strong>{operation.syncStatus || "-"}</strong></span>
        <span><small>Duration</small><strong>{duration(operation.durationMs)}</strong></span>
        {[['Players', operation.playersFound], ['Matches', operation.uniqueMatches], ['Snapshots', operation.snapshotCount]]
          .filter(([, value]) => value != null).map(([label, value]) => <span key={label}><small>{label}</small><strong>{value}</strong></span>)}
      </div>}
      <dl className="monitoring-detail-grid">
        <div><dt>Severity</dt><dd><StatusBadge value={event.severity} /></dd></div>
        <div><dt>Category</dt><dd>{event.category}</dd></div>
        <div><dt>발생 시간</dt><dd>{dateTime(event.occurredAt)}</dd></div>
        <div><dt>Community</dt><dd>{event.communityName || "-"}{event.communityId ? ` (#${event.communityId})` : ""}</dd></div>
        <div><dt>User ID</dt><dd>{event.userId ?? "-"}</dd></div>
        <div><dt>Reference</dt><dd>{event.referenceId || "-"}</dd></div>
        {event.category === "SECURITY" && <>
          <div><dt>위험 수준</dt><dd>{event.metadata?.riskLevel || event.severity}</dd></div>
          <div><dt>요청 횟수</dt><dd>{event.metadata?.requestCount ?? "-"}</dd></div>
          <div><dt>제한 적용</dt><dd>{event.metadata?.rateLimited ? "적용" : "미적용"}</dd></div>
          <div><dt>요청 식별자</dt><dd>{event.metadata?.requestId || "-"}</dd></div>
        </>}
        <div className="monitoring-detail-wide"><dt>Message</dt><dd>{event.message}</dd></div>
      </dl>
      {syncId && <div className="monitoring-metadata"><h3>인게임 활동 조회 작업</h3>
        <dl>{syncSummary(event, latest).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{String(value)}</dd></div>)}</dl>
        <div className="monitoring-timeline-heading"><h3>같은 syncId의 전체 흐름</h3>
          <button type="button" className="secondary-button" onClick={() => setRevision((value) => value + 1)}>새로고침</button></div>
        <p className="monitoring-retention-note">기존 보관 기간 내 이벤트입니다. 실행 중이거나 로그 저장 지연·장애가 있으면 일부 단계가 아직 표시되지 않을 수 있습니다.</p>
        {timelineError && <p className="message" role="alert">{timelineError}</p>}
        {!timeline && !timelineError && <p>작업 흐름을 불러오는 중입니다.</p>}
        {timeline && <ol className="monitoring-timeline">{timeline.content.map((item) => <li key={item.id}>
          <time>{dateTime(item.occurredAt)}</time><StatusBadge value={item.severity} />
          <button type="button" className="monitoring-link" onClick={() => onOpen(item.id)}>{item.eventCode}</button>
          <span>{item.message}</span></li>)}</ol>}
        {timeline?.totalPages > 1 && <nav className="developer-pager" aria-label="작업 흐름 페이지 이동">
          <button type="button" disabled={timelinePage === 0} onClick={() => setTimelinePage((value) => value - 1)}>이전</button>
          <span>{timelinePage + 1} / {timeline.totalPages}</span>
          <button type="button" disabled={timelinePage + 1 >= timeline.totalPages} onClick={() => setTimelinePage((value) => value + 1)}>다음</button>
        </nav>}
      </div>}
      <div className="monitoring-metadata"><h3>선택한 이벤트 Metadata</h3>
        {Object.keys(event.metadata || {}).length === 0 ? <p>추가 context가 없습니다.</p>
          : <dl>{Object.entries(event.metadata).filter(([key]) => key !== "stackTrace").map(([key, value]) => <div key={key}><dt>{key}</dt>
            <dd>{typeof value === "object" ? JSON.stringify(value) : String(value)}</dd></div>)}</dl>}
      </div>
      {event.metadata?.stackTrace && <details className="live-log-stack monitoring-metadata"><summary>Stack Trace (길이 제한 · 전체는 서버 로그)</summary>
        <pre>{event.metadata.stackTrace}</pre></details>}
    </section>
  </div>;
}

export default function MonitoringPage() {
  const [summary, setSummary] = useState(null);
  const [events, setEvents] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState({ ...EMPTY_FILTERS });
  const [applied, setApplied] = useState(filters);
  const [detail, setDetail] = useState(null);

  const query = useMemo(() => monitoringQuery(applied, page), [applied, page]);

  function selectGroup(group) {
    const next = { ...filters, group, severity: "", category: "", eventCode: "", syncStatus: "", minDurationMs: "" };
    if (group === "SECURITY") Object.assign(next, { gameType: "", syncId: "", communityId: "" });
    if ((group === "ACTIVITY" || group === "PUBG_API") && !next.from && !next.syncId) {
      const yesterday = new Date(); yesterday.setDate(yesterday.getDate() - 1);
      next.from = `${yesterday.getFullYear()}-${String(yesterday.getMonth() + 1).padStart(2, "0")}-${String(yesterday.getDate()).padStart(2, "0")}`;
    }
    setFilters(next); setApplied(next); setPage(0);
  }

  const refresh = useCallback(async (quiet = false, signal) => {
    if (!quiet) setLoading(true);
    try {
      const [nextSummary, nextEvents] = await Promise.all([
        api("/api/developer/monitoring/summary", { signal }),
        api(`/api/developer/monitoring/events?${query}`, { signal }),
      ]);
      if (signal?.aborted) return;
      setSummary(nextSummary);
      setEvents(nextEvents);
      setError("");
    } catch (failure) {
      if (failure.name !== "AbortError" && !signal?.aborted && !redirectToLogin(failure)) setError(failure.message);
    } finally {
      if (!quiet && !signal?.aborted) setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    let active = true;
    let refreshing = false;
    const controller = new AbortController();
    async function run(quiet = false) {
      if (!active || refreshing) return;
      refreshing = true;
      try { await refresh(quiet, controller.signal); }
      finally { refreshing = false; }
    }
    run();
    const timer = window.setInterval(() => { if (!document.hidden) run(true); }, 30000);
    return () => { active = false; controller.abort(); window.clearInterval(timer); };
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
    <section><h2 className="monitoring-section-title">운영 이벤트 로그</h2>
      <div className="live-log-levels monitoring-log-groups" role="group" aria-label="운영 로그 종류">
        {LOG_GROUPS.map(([value, label]) => <button key={value} type="button" aria-pressed={applied.group === value}
          className={applied.group === value ? "is-active" : ""} onClick={() => selectGroup(value)}>{label}</button>)}
      </div>
      <datalist id="verification-event-codes">{["EMAIL_VERIFICATION_MAIL_FAILED", "EMAIL_VERIFICATION_INVALID",
        "EMAIL_VERIFICATION_EXPIRED", "EMAIL_VERIFICATION_RATE_LIMITED", "EMAIL_VERIFICATION_ABUSE",
        "EMAIL_VERIFICATION_COMPLETED", "PASSWORD_RESET_MAIL_FAILED", "PASSWORD_RESET_RATE_LIMITED",
        "PASSWORD_RESET_INVALID", "PASSWORD_RESET_EXPIRED", "PASSWORD_RESET_REUSED", "PASSWORD_RESET_ABUSE",
        "PASSWORD_RESET_COMPLETED", "PASSWORD_RESET_STORAGE_FAILED"].map((code) => <option key={code} value={code} />)}</datalist>
      <form className="panel monitoring-filters" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(filters); }}>
        <label><span>Severity</span><select value={filters.severity} onChange={(e) => setFilters({ ...filters, severity: e.target.value })}>
          <option value="">전체</option><option>ERROR</option><option>WARN</option><option>INFO</option></select></label>
        <label><span>Category</span><select value={filters.category} onChange={(e) => setFilters({ ...filters, category: e.target.value })}>
          <option value="">전체</option>{CATEGORIES.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label><span>Event Code</span><input value={filters.eventCode} placeholder="예: HTTP_5XX" list="verification-event-codes"
          maxLength={64} onChange={(e) => setFilters({ ...filters, eventCode: e.target.value.toUpperCase() })} /></label>
        <label><span>Community ID</span><input type="number" min="1" value={filters.communityId}
          onChange={(e) => setFilters({ ...filters, communityId: e.target.value })} /></label>
        <label><span>시작일</span><input type="date" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })} /></label>
        <label><span>종료일</span><input type="date" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })} /></label>
        <label><span>Game</span><select value={filters.gameType} onChange={(e) => setFilters({ ...filters, gameType: e.target.value })}>
          <option value="">전체</option><option>BATTLEGROUNDS_KAKAO</option><option>BATTLEGROUNDS_STEAM</option></select></label>
        <label><span>최종 상태</span><select value={filters.syncStatus} onChange={(e) => setFilters({ ...filters, syncStatus: e.target.value })}>
          <option value="">전체 단계</option><option value="SUCCESS">성공</option><option value="FAILED">실패</option></select></label>
        <label><span>syncId</span><input value={filters.syncId} maxLength={36} placeholder="작업 추적 ID"
          onChange={(e) => setFilters({ ...filters, syncId: e.target.value })} /></label>
        <label><span>총 처리시간</span><select value={filters.minDurationMs} onChange={(e) => setFilters({ ...filters, minDurationMs: e.target.value })}>
          <option value="">전체</option><option value={String(SLOW_SYNC_MS)}>60초 이상 (SLOW)</option><option value="180000">3분 이상</option></select></label>
        <button type="submit">조회</button>
      </form>
      <div className="panel developer-table-wrap monitoring-table-wrap">
        <table className="developer-table monitoring-table"><thead><tr><th>시간</th><th>Severity</th><th>Category</th>
          <th>Event Code</th><th>Community</th><th>Game</th><th>syncId</th><th>결과 / 처리시간</th><th>메시지</th></tr></thead><tbody>
          {events?.content.map((event) => <tr key={event.id} tabIndex="0" onClick={() => openDetail(event.id)}
            onKeyDown={(e) => { if (e.key === "Enter") openDetail(event.id); }}>
            <td>{dateTime(event.occurredAt)}</td><td><StatusBadge value={event.severity} /></td><td>{event.category}</td>
            <td><code>{event.eventCode}</code></td><td>{event.communityName || "-"}{event.communityId ? <small>#{event.communityId}</small> : null}</td>
            <td>{event.metadata?.gameType || "-"}</td>
            <td>{syncIdOf(event) ? <button type="button" className="monitoring-link monitoring-sync-id" title={syncIdOf(event)}
              onClick={(e) => { e.stopPropagation(); openDetail(event.id); }}>{syncIdOf(event)}</button> : "-"}</td>
            <td>{event.metadata?.syncStatus && <StatusBadge value={event.metadata.syncStatus} />} {duration(event.metadata?.durationMs)}
              {isSlow(event) && <span className="monitoring-badge monitoring-warn">SLOW</span>}</td>
            <td>{event.message}</td></tr>)}
          {!loading && events?.content.length === 0 && <tr><td colSpan="9" className="monitoring-empty">조건에 맞는 이벤트가 없습니다.</td></tr>}
          {loading && <tr><td colSpan="9" className="monitoring-empty">모니터링 정보를 불러오는 중입니다.</td></tr>}
        </tbody></table>
      </div>
      {events && events.totalPages > 1 && <nav className="developer-pager" aria-label="이벤트 페이지 이동">
        <button className="secondary-button" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page + 1} / {events.totalPages}</span>
        <button className="secondary-button" disabled={page + 1 >= events.totalPages} onClick={() => setPage(page + 1)}>다음</button>
      </nav>}
    </section>
    <LiveLogsPanel />
    {detail && <EventDetail event={detail} onClose={() => setDetail(null)} onOpen={openDetail} />}
  </div>;
}

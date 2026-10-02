import { memo, useEffect, useMemo, useRef, useState } from "react";
import { connectLiveLogs, createLogBuffer, filterLogs, LOG_CAPACITY, LOG_STREAM_URL } from "./liveLogs.js";
import { reportClientFailure } from "../api/diagnostics.js";

const CONNECTION_LABELS = {
  connecting: "연결 중", connected: "연결됨", reconnecting: "재연결 중", closed: "연결 종료됨",
};
const timeFormat = new Intl.DateTimeFormat("ko-KR", { hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });

const LogRow = memo(function LogRow({ entry }) {
  const context = Object.entries(entry.context);
  return <article className={`live-log-row live-log-${entry.level.toLowerCase()}`}>
    <div className="live-log-meta"><time dateTime={entry.timestamp} title={entry.timestamp}>{timeFormat.format(new Date(entry.timestamp))}</time>
      <span className={`monitoring-badge monitoring-${entry.level.toLowerCase()}`}>{entry.level}</span>
      <span className="live-log-category" title={entry.category}>{entry.category}</span></div>
    <pre className="live-log-message">{entry.message}</pre>
    {context.length > 0 && <div className="live-log-context">{context.map(([key, value]) =>
      <span key={key}>{key}=<strong>{value}</strong></span>)}</div>}
    {entry.stackTrace && <details className="live-log-stack"><summary>Stack trace</summary><pre>{entry.stackTrace}</pre></details>}
  </article>;
});

export default function LiveLogsPanel() {
  const buffer = useRef(null);
  if (!buffer.current) buffer.current = createLogBuffer();
  const pausedRef = useRef(false);
  const renderedVersion = useRef(-1);
  const viewport = useRef(null);
  const [entries, setEntries] = useState([]);
  const [paused, setPaused] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const [level, setLevel] = useState("ALL");
  const [search, setSearch] = useState("");
  const [connection, setConnection] = useState("connecting");
  const [notice, setNotice] = useState("");
  const [connectionAttempt, setConnectionAttempt] = useState(0);
  const [displayLimit, setDisplayLimit] = useState(200);

  useEffect(() => {
    let active = true;
    let disconnect;
    function parseFailure(error) {
      if (!active) return;
      setNotice("일부 로그 데이터를 표시하지 못했습니다. 다음 로그 수신은 계속됩니다.");
      reportClientFailure("LOG_STREAM_INVALID_RESPONSE", error, { endpoint: LOG_STREAM_URL, method: "GET" });
    }
    try {
      disconnect = connectLiveLogs({
        onEntry: (entry) => buffer.current.append(entry),
        onConnection: setConnection,
        onInvalid: parseFailure,
        onGap: () => setNotice("연결이 끊긴 동안 일부 로그가 버퍼에서 제거되었습니다. 최근 로그부터 이어서 표시합니다."),
      });
    } catch (failure) {
      setConnection("closed");
      setNotice("실시간 로그에 연결하지 못했습니다. 브라우저와 개발자 권한을 확인해 주세요.");
      reportClientFailure("LOG_STREAM_CONNECTION_ERROR", failure, { endpoint: LOG_STREAM_URL, method: "GET" });
    }
    // Consume on every event, render at most four times/second. Pausing freezes
    // the visible snapshot while the bounded ring keeps accepting live logs.
    const timer = window.setInterval(() => {
      if (!active || pausedRef.current || renderedVersion.current === buffer.current.version) return;
      renderedVersion.current = buffer.current.version;
      setEntries(buffer.current.snapshot());
    }, 250);
    return () => { active = false; window.clearInterval(timer); disconnect?.(); };
  }, [connectionAttempt]);

  const filtered = useMemo(() => filterLogs(entries, level, search), [entries, level, search]);
  const visible = filtered.slice(-displayLimit);
  useEffect(() => {
    if (autoScroll && !paused && viewport.current) viewport.current.scrollTop = viewport.current.scrollHeight;
  }, [entries, autoScroll, paused, level, search, displayLimit]);

  function togglePause() {
    const next = !pausedRef.current;
    pausedRef.current = next;
    setPaused(next);
    if (!next) {
      renderedVersion.current = buffer.current.version;
      setEntries(buffer.current.snapshot());
    }
  }

  function clearDisplay() {
    buffer.current.clear();
    renderedVersion.current = buffer.current.version;
    setEntries([]);
    setNotice("");
  }

  return <section className="live-logs-section" aria-labelledby="live-logs-title">
    <div className="live-logs-heading"><h2 id="live-logs-title">실시간 서버 로그</h2>
      <span className={`live-log-connection is-${connection}`} role="status"><span aria-hidden="true">●</span>{CONNECTION_LABELS[connection]}</span></div>
    <div className="panel live-logs-panel">
      <div className="live-logs-toolbar">
        <div className="live-log-levels" role="group" aria-label="로그 수준 필터">
          {["ALL", "INFO", "WARN", "ERROR"].map((value) => <button key={value} type="button" aria-pressed={level === value}
            className={level === value ? "is-active" : ""} onClick={() => setLevel(value)}>{value}</button>)}
        </div>
        <label className="live-log-search"><span className="sr-only">Category 또는 로그 검색</span>
          <input type="search" value={search} maxLength={200} onChange={(event) => setSearch(event.target.value)}
            placeholder="Category, 문자열, 요청 ID 검색" /></label>
        <label className="live-log-autoscroll"><input type="checkbox" checked={autoScroll}
          onChange={(event) => setAutoScroll(event.target.checked)} />자동 스크롤</label>
        <button type="button" className="secondary-button" onClick={togglePause}>{paused ? "다시 시작" : "일시정지"}</button>
        <button type="button" className="secondary-button" onClick={clearDisplay}>화면 로그 지우기</button>
      </div>
      <div className="live-log-caption"><p>최근 {LOG_CAPACITY.toLocaleString()}개 보관 · 일시정지는 화면 업데이트만 멈춥니다.</p>
        <span>{paused ? "화면 일시정지 · " : ""}{visible.length.toLocaleString()} / {filtered.length.toLocaleString()}개 표시</span></div>
      {notice && <p className="live-log-notice" role="status">{notice}</p>}
      {connection === "closed" && <div className="live-log-notice" role="alert">연결이 종료되었습니다. 로그인 상태와 개발자 권한을 확인해 주세요.{" "}
        <button type="button" className="secondary-button" onClick={() => { setConnection("connecting"); setConnectionAttempt((value) => value + 1); }}>다시 연결</button></div>}
      <div className="live-log-viewport" ref={viewport} tabIndex="0" aria-label="실시간 서버 로그 목록">
        {filtered.length > visible.length && <button type="button" className="live-log-more" onClick={() => {
          setAutoScroll(false); setDisplayLimit((value) => Math.min(LOG_CAPACITY, value + 200));
        }}>이전 로그 더 보기</button>}
        {visible.map((entry) => <LogRow entry={entry} key={entry.id} />)}
        {visible.length === 0 && <p className="live-log-empty">{entries.length ? "조건에 맞는 로그가 없습니다." : "새 서버 로그를 기다리고 있습니다."}</p>}
      </div>
    </div>
  </section>;
}

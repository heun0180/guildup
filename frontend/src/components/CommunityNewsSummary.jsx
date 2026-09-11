import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import { formatNewsDate, newsUrl } from "../communityNews.js";

export default function CommunityNewsSummary({ communityId }) {
  const [summary, setSummary] = useState(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setError("");
    api(`/api/communities/${encodeURIComponent(communityId)}/news-summary`)
      .then((result) => { if (!cancelled) setSummary(result); })
      .catch((failure) => {
        if (!cancelled && !redirectToLogin(failure)) setError("최근 소식을 불러오지 못했습니다.");
      });
    return () => { cancelled = true; };
  }, [communityId, retry]);

  return <section aria-label="커뮤니티 소식" style={{ marginBottom: "var(--space-card)" }}>
    {error && <p className="message" role="alert">{error}{" "}
      <button className="secondary-button" onClick={() => setRetry((value) => value + 1)}>다시 시도</button>
    </p>}
    <div className="management-grid">
      {[["notices", "최근 공지"], ["events", "다가오는 이벤트"]].map(([tab, title]) => (
        <article className="panel role-settings-panel" key={tab}>
          <div className="section-heading"><h2>{title}</h2><a href={newsUrl(communityId, tab)} aria-label={`${title} 더보기`}>더보기</a></div>
          {!summary ? <p role="status">{error ? "잠시 후 다시 시도해 주세요." : "소식을 불러오는 중입니다."}</p>
            : summary[tab].length === 0 ? <p>{tab === "notices" ? "등록된 공지가 없습니다." : "다가오는 이벤트가 없습니다."}</p>
            : summary[tab].map((item) => <div className="settings-actions" key={item.id}>
              <div><h3><a href={newsUrl(communityId, tab, item.id)}>
                {tab === "notices" ? item.pinned ? "📌 " : "" : "🎮 "}{item.title}
              </a></h3><p>{formatNewsDate(tab === "notices" ? item.createdAt : item.startAt)}</p></div>
            </div>)}
        </article>
      ))}
    </div>
  </section>;
}

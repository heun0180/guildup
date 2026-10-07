import { useEffect, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import { FEEDBACK_TYPES, FEEDBACK_STATUSES } from "../feedbackForm.js";
import { formatNewsDate } from "../communityNews.js";

export default function FeedbackHistory({ admin = false, revision = 0 }) {
  const base = admin ? "/api/developer/feedback" : "/api/feedback";
  const [page, setPage] = useState(0);
  const [result, setResult] = useState(null);
  const [detail, setDetail] = useState(null);
  const [form, setForm] = useState({ status: "RECEIVED", answer: "" });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);

  useEffect(() => { setPage(0); setDetail(null); }, [revision]);
  useEffect(() => {
    let cancelled = false;
    setLoading(true); setError(""); setResult(null);
    api(`${base}?page=${page}&size=20`, { cache: "no-store" }).then((response) => {
      if (!cancelled) setResult(response);
    }).catch((failure) => {
      if (!cancelled && !redirectToLogin(failure)) setError(failure.message);
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [base, page, retry, revision]);

  async function open(id) {
    if (busy) return;
    setBusy(true); setError("");
    try {
      const response = await api(`${base}/${id}`, { cache: "no-store" });
      setDetail(response);
      setForm({ status: response.feedback.status, answer: response.answer ?? "" });
    } catch (failure) { if (!redirectToLogin(failure)) setError(failure.message); }
    finally { setBusy(false); }
  }
  async function save(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true); setError("");
    try {
      const response = await api(`${base}/${detail.feedback.id}`, {
        method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...form, version: detail.version }),
      });
      setDetail(response); setRetry((value) => value + 1);
    } catch (failure) { if (!redirectToLogin(failure)) setError(failure.message); }
    finally { setBusy(false); }
  }

  const item = detail?.feedback;
  return <div className="feedback-history">
    {error && <div className="message" role="alert">{error}{" "}
      <button type="button" className="secondary-button" disabled={busy} onClick={() => item ? open(item.id) : setRetry((value) => value + 1)}>다시 불러오기</button>
    </div>}
    {detail ? <article className="panel support-detail">
      <header className="feedback-form-heading"><h2>{item.title}</h2>
        <p>{FEEDBACK_TYPES[item.type]} · {FEEDBACK_STATUSES[item.status]}</p></header>
      <dl className="developer-definition-grid support-context">
        {[["작성자", `${item.authorNickname} (User ID: ${item.userId})`],
          ["접수 시각", formatNewsDate(item.createdAt)], ["등록 당시 페이지", detail.pageRoute || "기록 없음"],
          ["관련 커뮤니티", item.communityId ? `${item.communityName || "이름 기록 없음"} (ID: ${item.communityId})` : "없음"]]
          .map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
      </dl>
      <div className="support-body"><h3>문의 내용</h3><p>{detail.content}</p>
        <h3>답변</h3><p>{detail.answer || "답변을 기다리고 있습니다."}</p>
        {detail.answeredAt && <small>답변 시각 {formatNewsDate(detail.answeredAt)}</small>}
      </div>
      {admin && <form onSubmit={save} aria-label="문의 답변 및 상태 관리">
        <label className="feedback-field" htmlFor="support-status"><span>상태</span>
          <select id="support-status" value={form.status} disabled={busy} onChange={(e) => setForm({ ...form, status: e.target.value })}>
            {Object.entries(FEEDBACK_STATUSES).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <label className="feedback-field" htmlFor="support-answer"><span>답변</span>
          <textarea id="support-answer" maxLength={3000} value={form.answer} disabled={busy}
            required={form.status === "ANSWERED"} onChange={(e) => setForm({ ...form, answer: e.target.value })} />
        </label>
        <div className="feedback-actions"><button disabled={busy}>{busy ? "저장 중…" : "답변 / 상태 저장"}</button></div>
      </form>}
      <div className="support-body"><button type="button" className="secondary-button" disabled={busy} onClick={() => { setDetail(null); setError(""); }}>목록으로</button></div>
    </article> : loading ? <p className="panel page-state" role="status">문의를 불러오는 중입니다.</p> : result && <>
      {result.content.length === 0 ? <p className="panel page-state">등록된 문의가 없습니다.</p> :
        <div className="panel developer-table-wrap"><table className="developer-table support-table">
          <thead><tr>{["유형", "제목", ...(admin ? ["작성자"] : []), "접수 시각", "상태", "관련 커뮤니티"].map((label) => <th key={label} scope="col">{label}</th>)}</tr></thead>
          <tbody>{result.content.map((f) => <tr key={f.id}>
            <td>{FEEDBACK_TYPES[f.type]}</td><td><button type="button" className="text-button support-title" disabled={busy} onClick={() => open(f.id)}>{f.title}</button></td>
            {admin && <td>{f.authorNickname}<small>User ID: {f.userId}</small></td>}
            <td>{formatNewsDate(f.createdAt)}</td><td>{FEEDBACK_STATUSES[f.status]}</td><td>{f.communityName || "없음"}{f.communityId && <small>ID: {f.communityId}</small>}</td>
          </tr>)}</tbody>
        </table></div>}
      {result.totalPages > 1 && <nav className="announcement-pagination" aria-label="문의 페이지">
        <button className="secondary-button" disabled={page === 0 || busy} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page + 1} / {result.totalPages}</span>
        <button className="secondary-button" disabled={page + 1 >= result.totalPages || busy} onClick={() => setPage(page + 1)}>다음</button>
      </nav>}
    </>}
  </div>;
}

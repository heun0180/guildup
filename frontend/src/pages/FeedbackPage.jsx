import { useState } from "react";
import { useLocation } from "react-router-dom";
import { api, redirectToLogin } from "../api/http.js";
import AppHeader from "../components/AppHeader.jsx";
import SiteFooter from "../components/SiteFooter.jsx";
import FeedbackHistory from "../components/FeedbackHistory.jsx";
import { FEEDBACK_TYPES, feedbackPayload, supportContext, safeSupportRoute } from "../feedbackForm.js";

const EMPTY_FORM = { type: "SERVICE", title: "", content: "" };

export default function FeedbackPage() {
  const location = useLocation();
  const context = location.state?.supportContext ?? supportContext(location);
  const [historyRevision, setHistoryRevision] = useState(0);
  const [form, setForm] = useState(EMPTY_FORM);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const set = (key, value) => {
    setForm((current) => ({ ...current, [key]: value }));
    setError("");
    setSuccess("");
  };

  async function submit(event) {
    event.preventDefault();
    if (sending) return;
    let payload;
    try {
      payload = feedbackPayload(form);
    } catch (validationError) {
      setError(validationError.message);
      return;
    }

    setSending(true);
    setError("");
    setSuccess("");
    try {
      const response = await api("/api/feedback", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...payload,
          communityId: Number.isSafeInteger(context.communityId) && context.communityId > 0 ? context.communityId : null,
          pageRoute: safeSupportRoute(context.pageRoute),
        }),
      });
      setForm((current) => ({ ...current, title: "", content: "" }));
      setSuccess(response.message);
      setHistoryRevision((value) => value + 1);
    } catch (failure) {
      if (!redirectToLogin(failure)) {
        setError(failure.message || "문의 접수에 실패했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="public-page">
      <AppHeader actions />
      <main className="public-main feedback-content">
        <div className="page-heading is-compact">
          <p className="eyebrow">Feedback</p>
          <h1>문의 / 건의</h1>
          <p>GuildUp 서비스 이용 문의, 오류 신고와 기능 건의를 보내주세요.</p>
        </div>

        {error && <p className="message feedback-notice" role="alert">{error}</p>}
        {success && <p className="success-message page-success feedback-notice" role="status">{success}</p>}

        <form className="panel feedback-form" onSubmit={submit} aria-label="문의 및 건의 작성">
          <div className="feedback-form-heading">
            <h2>문의 내용</h2>
            <p>로그인 사용자와 접수 시각, 이용하던 페이지가 함께 전달됩니다. 커뮤니티 가입 없이도 문의할 수 있습니다.</p>
          </div>

          <label className="feedback-field" htmlFor="feedback-type">
            <span>문의 유형</span>
            <select id="feedback-type" value={form.type} disabled={sending}
                    onChange={(event) => set("type", event.target.value)}>
              {Object.entries(FEEDBACK_TYPES).map(([value, label]) => (
                <option value={value} key={value}>{label}</option>
              ))}
            </select>
          </label>

          <label className="feedback-field" htmlFor="feedback-title">
            <span>제목</span>
            <input id="feedback-title" required maxLength={100} value={form.title} disabled={sending}
                   placeholder="문의 제목을 입력해 주세요"
                   onChange={(event) => set("title", event.target.value)} />
            <small>{form.title.length} / 100</small>
          </label>

          <label className="feedback-field" htmlFor="feedback-content">
            <span>내용</span>
            <textarea id="feedback-content" required maxLength={3000} value={form.content} disabled={sending}
                      placeholder="건의 사항이나 발생한 문제를 자세히 알려주세요"
                      onChange={(event) => set("content", event.target.value)} />
            <small>{form.content.length.toLocaleString()} / 3,000</small>
          </label>

          <div className="feedback-actions">
            <button type="submit" disabled={sending}>{sending ? "보내는 중…" : "보내기"}</button>
          </div>
        </form>
        <section className="support-history"><h2>내 문의 내역</h2>
          <FeedbackHistory revision={historyRevision} />
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}

import { useCallback, useState } from "react";
import { api, redirectToLogin } from "../api/http.js";
import DashboardLayout from "../components/DashboardLayout.jsx";
import { FEEDBACK_TYPES, feedbackPayload } from "../feedbackForm.js";

const EMPTY_FORM = { type: "FEATURE", title: "", content: "" };

export default function FeedbackPage() {
  const communityId = new URLSearchParams(window.location.search).get("communityId");
  const validId = /^\d+$/.test(communityId ?? "");
  const [form, setForm] = useState(EMPTY_FORM);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState(validId ? "" : "올바른 커뮤니티를 선택해 주세요.");
  const [success, setSuccess] = useState("");
  const handleLayoutError = useCallback((message) => setError(message || "커뮤니티 정보를 불러오지 못했습니다."), []);
  const set = (key, value) => {
    setForm((current) => ({ ...current, [key]: value }));
    setError("");
    setSuccess("");
  };

  async function submit(event) {
    event.preventDefault();
    if (sending || !validId) return;
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
      await api(`/api/communities/${encodeURIComponent(communityId)}/feedback`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      setForm((current) => ({ ...current, title: "", content: "" }));
      setSuccess("소중한 의견 감사합니다.\n개발자에게 전달되었습니다.");
    } catch (failure) {
      if (!redirectToLogin(failure)) {
        setError("문의 전송에 실패했습니다.\n잠시 후 다시 시도해주세요.");
      }
    } finally {
      setSending(false);
    }
  }

  return (
    <DashboardLayout active="feedback" communityId={communityId} onError={handleLayoutError}>
      <div className="dashboard-content narrow-content feedback-content">
        <div className="page-heading is-compact">
          <p className="eyebrow">Feedback</p>
          <h1>문의/건의</h1>
        </div>

        {error && <p className="message feedback-notice" role="alert">{error}</p>}
        {success && <p className="success-message page-success feedback-notice" role="status">{success}</p>}

        <form className="panel feedback-form" onSubmit={submit} aria-label="문의 및 건의 작성">
          <div className="feedback-form-heading">
            <h2>문의 내용</h2>
            <p>로그인 사용자와 현재 커뮤니티 정보는 자동으로 함께 전달됩니다.</p>
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
            <button type="submit" disabled={sending || !validId}>{sending ? "보내는 중…" : "보내기"}</button>
          </div>
        </form>
      </div>
    </DashboardLayout>
  );
}

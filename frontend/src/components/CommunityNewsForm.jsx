import { useState } from "react";
import { EVENT_TYPES, newsPayload, toLocalDateTime } from "../communityNews.js";
import Icon from "./Icon.jsx";

export default function CommunityNewsForm({ isNotice, item, busy, onSave, onCancel }) {
  const [form, setForm] = useState({
    title: item?.title ?? "", content: item?.content ?? "",
    important: item?.important ?? false, pinned: item?.pinned ?? false,
    type: item?.type ?? "GENERAL", startAt: toLocalDateTime(item?.startAt), endAt: toLocalDateTime(item?.endAt),
  });
  const [error, setError] = useState("");
  const set = (key, value) => setForm((previous) => ({ ...previous, [key]: value }));

  function submit(event) {
    event.preventDefault();
    if (busy) return;
    let payload;
    try { payload = newsPayload(form, isNotice); }
    catch (validationError) { setError(validationError.message); return; }
    setError("");
    onSave(payload);
  }

  return (
    <form className="panel role-settings-panel role-check-list" onSubmit={submit} aria-label={isNotice ? "공지 작성" : "이벤트 작성"}>
      <h2>{item ? "수정" : isNotice ? "공지 작성" : "이벤트 만들기"}</h2>
      <label className="activity-rule-field">
        <span>{isNotice ? "제목" : "이벤트 제목"}</span>
        <input autoFocus required maxLength={200} value={form.title} disabled={busy}
               onChange={(event) => set("title", event.target.value)} />
      </label>
      {!isNotice && <label className="activity-rule-field">
        <span>이벤트 종류</span>
        <select value={form.type} disabled={busy} onChange={(event) => set("type", event.target.value)}>
          {Object.entries(EVENT_TYPES).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
        </select>
      </label>}
      <label className="dm-message-field">
        <span>{isNotice ? "내용" : "설명"}</span>
        <textarea required={isNotice} maxLength={20000} value={form.content} disabled={busy}
                  onChange={(event) => set("content", event.target.value)} />
        <small>{form.content.length.toLocaleString()} / 20,000</small>
      </label>
      {isNotice ? <div className="activity-rule-fields">
        {[["important", "중요 공지"], ["pinned", "상단 고정"]].map(([key, label]) => (
          <label className={`role-check-item${form[key] ? " is-selected" : ""}`} key={key}>
            <input type="checkbox" checked={form[key]} disabled={busy} onChange={(event) => set(key, event.target.checked)} />
            <span className="custom-checkbox" aria-hidden="true"><Icon name="check" size={15} /></span>
            <span>{label}</span>
          </label>
        ))}
      </div> : <>
        <div className="activity-rule-fields">
          <label className="activity-rule-field"><span>시작 날짜/시간</span>
            <input type="datetime-local" step="1" required value={form.startAt} disabled={busy}
                   onChange={(event) => set("startAt", event.target.value)} />
          </label>
          <label className="activity-rule-field"><span>종료 날짜/시간</span>
            <input type="datetime-local" step="1" required min={form.startAt || undefined} value={form.endAt} disabled={busy}
                   onChange={(event) => set("endAt", event.target.value)} />
          </label>
        </div>
        <p className="field-help">날짜와 시간은 현재 기기의 시간대 기준입니다.</p>
      </>}
      {error && <p className="message" role="alert">{error}</p>}
      <div className="settings-actions">
        <button type="button" className="secondary-button" disabled={busy} onClick={onCancel}>취소</button>
        <button type="submit" disabled={busy}>{busy ? "저장 중…" : "저장"}</button>
      </div>
    </form>
  );
}

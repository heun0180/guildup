import { useState } from "react";
import { ANNOUNCEMENT_TYPES, announcementPayload } from "../announcements.js";
import { toLocalDateTime } from "../communityNews.js";
import Icon from "./Icon.jsx";

export default function AnnouncementForm({ detail, busy, onSave, onCancel }) {
  const item = detail?.announcement;
  const [form, setForm] = useState({ title: item?.title ?? "", content: detail?.content ?? "",
    type: item?.type ?? "NOTICE", important: item?.important ?? false, pinned: item?.pinned ?? false,
    popup: item?.popup ?? false, published: item?.published ?? false,
    publishStartAt: toLocalDateTime(item?.publishStartAt), publishEndAt: toLocalDateTime(item?.publishEndAt) });
  const [error, setError] = useState("");
  function set(key, value) {
    setForm((previous) => ({ ...previous, [key]: value, ...(key === "important" && !value ? { popup: false } : {}) }));
  }
  function submit(event) {
    event.preventDefault(); if (busy) return;
    let payload;
    try { payload = announcementPayload(form); }
    catch (failure) { setError(failure.message); return; }
    setError(""); onSave(payload);
  }

  return <form className="panel role-settings-panel role-check-list announcement-form" onSubmit={submit} aria-label="GuildUp 공지 작성">
    <h2>{item ? "GuildUp 공지 수정" : "GuildUp 공지 작성"}</h2>
    <label className="activity-rule-field"><span>제목</span><input autoFocus required maxLength={200} disabled={busy}
      value={form.title} onChange={(event) => set("title", event.target.value)} /></label>
    <label className="activity-rule-field"><span>공지 유형</span>
      <select value={form.type} disabled={busy} onChange={(event) => set("type", event.target.value)}>
        {Object.entries(ANNOUNCEMENT_TYPES).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select>
    </label>
    <label className="dm-message-field"><span>내용</span><textarea required maxLength={20000} disabled={busy}
      value={form.content} onChange={(event) => set("content", event.target.value)} />
      <small>{form.content.length.toLocaleString()} / 20,000</small>
    </label>
    <div className="activity-rule-fields">
      {[["published", "공개"], ["important", "중요 공지"], ["pinned", "상단 고정"], ["popup", "팝업 노출"]].map(([key, label]) => (
        <label className={`role-check-item${form[key] ? " is-selected" : ""}`} key={key}>
          <input type="checkbox" checked={form[key]} disabled={busy || (key === "popup" && !form.important)}
            onChange={(event) => set(key, event.target.checked)} />
          <span className="custom-checkbox" aria-hidden="true"><Icon name="check" size={15} /></span><span>{label}</span>
        </label>
      ))}
    </div>
    <p className="field-help">팝업은 중요 공지에서 선택할 수 있습니다. 공지 대상은 전체 사용자입니다.</p>
    <div className="activity-rule-fields">
      <label className="activity-rule-field"><span>게시 시작 일시</span><input type="datetime-local" step="1" disabled={busy}
        value={form.publishStartAt} onChange={(event) => set("publishStartAt", event.target.value)} /></label>
      <label className="activity-rule-field"><span>게시 종료 일시</span><input type="datetime-local" step="1" disabled={busy}
        min={form.publishStartAt || undefined} value={form.publishEndAt} onChange={(event) => set("publishEndAt", event.target.value)} /></label>
    </div>
    <p className="field-help">현재 기기의 시간대 기준입니다. 시작 생략 시 즉시 게시, 종료 생략 시 계속 게시됩니다.</p>
    {error && <p className="message" role="alert">{error}</p>}
    <div className="settings-actions"><button className="secondary-button" type="button" disabled={busy} onClick={onCancel}>취소</button>
      <button disabled={busy}>{busy ? "저장 중…" : "저장"}</button></div>
  </form>;
}

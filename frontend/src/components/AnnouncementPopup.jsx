import { useEffect, useRef, useState } from "react";
import AnnouncementMeta from "./AnnouncementMeta.jsx";
import { formatNewsDate } from "../communityNews.js";

export default function AnnouncementPopup({ detail, onConfirm }) {
  const { announcement: item, content } = detail;
  const modal = useRef(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // Native dialog의 focus trap/inert/초점 복원을 재사용한다.
  useEffect(() => {
    const dialog = modal.current;
    const previous = document.activeElement;
    dialog.showModal();
    return () => { dialog.close(); if (previous?.isConnected) previous.focus(); };
  }, []);

  async function confirm() {
    if (busy) return;
    setBusy(true); setError("");
    try { await onConfirm(item.id); }
    catch { setError("확인을 저장하지 못했습니다. 다시 시도해 주세요."); setBusy(false); }
  }

  return <dialog ref={modal} className="panel announcement-modal" aria-labelledby="announcement-popup-title"
    onCancel={(event) => { event.preventDefault(); confirm(); }}>
    <AnnouncementMeta item={item} readStatus={false} />
    <h2 id="announcement-popup-title">{item.title}</h2>
    <p><time dateTime={item.createdAt}>{formatNewsDate(item.createdAt)}</time></p>
    <div className="announcement-body">{content}</div>
    {error && <p className="message" role="alert">{error}</p>}
    <div className="settings-actions"><p>확인한 공지는 다시 팝업으로 표시하지 않습니다.</p>
      <button autoFocus disabled={busy} onClick={confirm}>{busy ? "저장 중…" : "확인"}</button>
    </div>
  </dialog>;
}

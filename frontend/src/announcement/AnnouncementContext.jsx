import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { api, isRequestCancelled } from "../api/http.js";
import AnnouncementPopup from "../components/AnnouncementPopup.jsx";

const AnnouncementContext = createContext(null);
export const useAnnouncements = () => useContext(AnnouncementContext);

/** App에서 유지하여 커뮤니티/화면 전환으로 알림이나 확인한 팝업 상태가 초기화되지 않게 한다. */
export function AnnouncementProvider({ children }) {
  const [userId, setUserId] = useState(null);
  const [notifications, setNotifications] = useState(null);
  const [popup, setPopup] = useState(null);
  const [error, setError] = useState("");
  const [readIds, setReadIds] = useState(() => new Set());
  const sequence = useRef(0);
  const recordRead = useCallback((id) => {
    setReadIds((previous) => previous.has(id) ? previous : new Set([...previous, id]));
  }, []);

  const refresh = useCallback(async (signal) => {
    if (!userId) return;
    const current = ++sequence.current;
    const [feed, modal] = await Promise.allSettled([
      api("/api/announcements/notifications", { cache: "no-store", signal }),
      api("/api/announcements/popup", { cache: "no-store", signal }),
    ]);
    if (signal?.aborted || current !== sequence.current) return;
    if (feed.status === "fulfilled" && Array.isArray(feed.value?.recent) && Number.isFinite(feed.value?.unreadCount)) {
      setNotifications(feed.value); setError("");
    } else if (feed.status === "fulfilled") setError("GuildUp 알림 응답을 확인하지 못했습니다.");
    else if (!isRequestCancelled(feed.reason)) setError("GuildUp 알림을 불러오지 못했습니다.");
    if (modal.status === "fulfilled") setPopup(modal.value?.announcement?.id ? modal.value : null);
    if ((feed.status === "rejected" && feed.reason.status === 401)
      || (modal.status === "rejected" && modal.reason.status === 401)) {
      setNotifications(null); setPopup(null); setUserId(null);
    }
  }, [userId]);

  useEffect(() => {
    setNotifications(null); setPopup(null); setError(""); setReadIds(new Set());
    if (!userId) return;
    const controller = new AbortController();
    refresh(controller.signal);
    const update = () => { if (document.visibilityState !== "hidden") refresh(controller.signal); };
    const timer = window.setInterval(update, 60000);
    window.addEventListener("focus", update);
    document.addEventListener("visibilitychange", update);
    return () => {
      controller.abort(); ++sequence.current;
      window.clearInterval(timer);
      window.removeEventListener("focus", update);
      document.removeEventListener("visibilitychange", update);
    };
  }, [userId, refresh]);

  async function confirmPopup(id) {
    await api(`/api/announcements/${id}/popup-confirmation`, { method: "POST" });
    recordRead(id);
    setPopup(null);
    await refresh();
  }

  return <AnnouncementContext.Provider value={{ connectUser: setUserId, notifications, error, refresh, readIds, recordRead }}>
    {children}
    {popup && <AnnouncementPopup key={popup.announcement.id} detail={popup} onConfirm={confirmPopup} />}
  </AnnouncementContext.Provider>;
}

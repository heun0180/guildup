import { useEffect, useId, useRef, useState } from "react";
import { useAnnouncements } from "../announcement/AnnouncementContext.jsx";
import { announcementUrl } from "../announcements.js";
import { formatNewsDate } from "../communityNews.js";
import AnnouncementMeta from "./AnnouncementMeta.jsx";
import AppLink from "./AppLink.jsx";
import Icon from "./Icon.jsx";

export default function AnnouncementBell({ user }) {
  const context = useAnnouncements();
  const [open, setOpen] = useState(false);
  const container = useRef(null);
  const button = useRef(null);
  const id = useId();
  const connectUser = context?.connectUser;
  useEffect(() => { if (user?.id) connectUser?.(user.id); }, [user?.id, connectUser]);

  useEffect(() => {
    if (!open) return;
    const outside = (event) => { if (!container.current?.contains(event.target)) setOpen(false); };
    const escape = (event) => { if (event.key === "Escape") { setOpen(false); button.current?.focus(); } };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", escape);
    return () => { document.removeEventListener("pointerdown", outside); document.removeEventListener("keydown", escape); };
  }, [open]);

  if (!context) return <AppLink className="header-link" href="/announcements">GuildUp 공지</AppLink>;
  const { notifications, error, refresh } = context;
  const unread = notifications?.unreadCount ?? 0;
  return <div className="announcement-bell" ref={container} onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
  }}>
    <button ref={button} type="button" className="text-button announcement-bell-button"
      aria-label={`GuildUp 알림${unread ? `, 읽지 않은 공지 ${unread}개` : ""}`}
      aria-expanded={open} aria-controls={id} onClick={() => {
        setOpen(!open); if (!open) refresh();
      }}><Icon name="bell" size={22} />
      {unread > 0 && <span className="announcement-count" aria-hidden="true">{unread > 99 ? "99+" : unread}</span>}
    </button>
    {open && <section id={id} className="panel announcement-dropdown" aria-label="최근 GuildUp 공지">
      <h2>GuildUp 공지</h2>
      {error && <div className="message" role="alert">{error} <button className="text-button" onClick={() => refresh()}>다시 시도</button></div>}
      {!notifications && !error && <p role="status">알림을 불러오는 중입니다.</p>}
      {notifications?.recent.length === 0 && <p className="empty-state">게시중인 공지가 없습니다.</p>}
      {notifications?.recent.map((item) => <AppLink key={item.id} href={announcementUrl(item.id)}
        className={`announcement-notification${item.read ? "" : " is-unread"}`} onClick={() => setOpen(false)}>
        <AnnouncementMeta item={item} /><strong>{item.title}</strong>
        <time dateTime={item.createdAt}>{formatNewsDate(item.createdAt)}</time>
      </AppLink>)}
      <AppLink className="secondary-button announcement-all" href="/announcements" onClick={() => setOpen(false)}>전체 공지 보기</AppLink>
    </section>}
  </div>;
}

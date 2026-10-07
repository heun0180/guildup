import { ANNOUNCEMENT_STATUSES, ANNOUNCEMENT_TYPES } from "../announcements.js";

export default function AnnouncementMeta({ item, admin = false, readStatus = true }) {
  return <div className="announcement-meta">
    <span className={`role-badge announcement-type type-${item.type?.toLowerCase()}`}>{ANNOUNCEMENT_TYPES[item.type]}</span>
    {item.important && <span className="status-badge announcement-important">중요</span>}
    {item.pinned && <span className="role-badge">📌 고정</span>}
    {item.newAnnouncement && <span className="status-badge announcement-new">NEW</span>}
    {admin ? <>
      <span className={`status-badge ${item.status === "PUBLISHED" ? "connected" : "disconnected"}`}>{ANNOUNCEMENT_STATUSES[item.status]}</span>
      {item.popup && <span className="role-badge">팝업</span>}
    </> : readStatus && <span className="announcement-read">{item.read ? "읽음" : "읽지 않음"}</span>}
  </div>;
}

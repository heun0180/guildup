import { EVENT_TYPES, EVENT_STATUSES, formatNewsDate } from "../communityNews.js";

export default function CommunityNewsMeta({ item, isNotice }) {
  return isNotice ? <>
    {item.pinned && <span className="role-badge">📌 고정</span>}{" "}
    {item.important && <span className="status-badge connected">중요</span>}
  </> : <>
    <span className="role-badge">{EVENT_TYPES[item.type]}</span>{" "}
    <span className={`status-badge ${item.status === "ENDED" ? "disconnected" : "connected"}`}>
      {EVENT_STATUSES[item.status]}
    </span>
    <p>{formatNewsDate(item.startAt)} ~ {formatNewsDate(item.endAt)}</p>
  </>;
}

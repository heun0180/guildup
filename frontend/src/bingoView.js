import { canManageCommunity } from "./communityAccess.js";

export function isBingoManagementView(role, search = "") {
  return canManageCommunity(role) && new URLSearchParams(search).get("view") === "manage";
}

export function groupManagedBingos(items) {
  return {
    active: items.filter(item => item.status === "ACTIVE" || item.status === "SETTLING"),
    scheduled: items.filter(item => item.status === "SCHEDULED"),
    draft: items.filter(item => item.status === "DRAFT"),
    completed: items.filter(item => item.status === "COMPLETED"),
    cancelled: items.filter(item => item.status === "CANCELLED"),
  };
}

export function currentBingoScreen(type) {
  if (type === "ACTIVE") return "BOARD";
  if (type === "SCHEDULED") return "SCHEDULED";
  return "EMPTY";
}

export function formatBingoLocalDateTime(value) {
  if (!value) return "";
  const [date, time = ""] = value.split("T");
  const [year, month, day] = date.split("-");
  const [hour = "", minute = ""] = time.split(":");
  if (!year || !month || !day || !hour || !minute) return value;
  const meaning = hour === "00" && minute === "00"
    ? " (자정, 하루 시작)"
    : hour === "12" && minute === "00" ? " (정오)" : "";
  return `${year}. ${month}. ${day}. ${hour}:${minute}${meaning}`;
}

export function formatBingoKstDateTime(value) {
  return value ? new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date(value)) : "-";
}

export function bingoParticipantNames(participant) {
  const discordNickname = participant?.nickname?.trim() || "알 수 없는 참가자";
  const pubgNickname = participant?.pubgNickname?.trim();
  return {
    primary: pubgNickname || discordNickname,
    secondary: pubgNickname && pubgNickname !== discordNickname ? discordNickname : null,
  };
}

export function bingoAggregationCooldown(lastAggregatedAt, now = Date.now()) {
  if (!lastAggregatedAt) return { disabled: false, remainingMs: 0, label: "빙고 집계" };
  const remainingMs = Math.max(0, new Date(lastAggregatedAt).getTime() + 30 * 60 * 1000 - now);
  if (remainingMs === 0) return { disabled: false, remainingMs: 0, label: "빙고 집계" };
  const totalSeconds = Math.ceil(remainingMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return {
    disabled: true,
    remainingMs,
    label: `빙고 집계 (${minutes}:${String(seconds).padStart(2, "0")})`,
  };
}

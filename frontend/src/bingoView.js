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

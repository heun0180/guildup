import { canManageCommunity } from "./communityAccess.js";

export const STATUS_COPY = {
  RECRUITING: ["모집 중", "현재 참가자를 모집하고 있습니다."],
  READY: ["시작 준비", "참가 모집이 완료되었습니다. 팀 구성을 완료한 뒤 킬내기를 시작해주세요."],
  IN_PROGRESS: ["진행 중", "킬내기가 진행 중입니다."],
  ENDED: ["종료", "킬내기가 종료되었습니다. 결과를 발표해주세요."],
  RESULT_PENDING: ["결과 발표 준비 중", "PUBG API 반영 지연을 고려해 30분 후 최종 결과를 집계합니다."],
  COMPLETED: ["결과 확정", "최종 결과가 발표되었습니다."],
  CANCELLED: ["취소", "관리자에 의해 취소된 킬내기입니다."],
};

export function statusLabel(status) { return STATUS_COPY[status]?.[0] ?? status; }
export function statusMessage(status) { return STATUS_COPY[status]?.[1] ?? ""; }
export function formatDateTime(value) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("ko-KR", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" })
    .format(new Date(value));
}
export function remainingLabel(endsAt, nowMs) {
  const remaining = new Date(endsAt).getTime() - nowMs;
  if (remaining <= 0) return "종료됨";
  const hours = Math.floor(remaining / 3_600_000);
  const minutes = Math.floor((remaining % 3_600_000) / 60_000);
  const seconds = Math.floor((remaining % 60_000) / 1000);
  return `${hours ? `${hours}시간 ` : ""}${minutes}분 ${seconds}초`;
}

export function canManageKillGame(detail, communityRole) {
  if (canManageCommunity(communityRole)) return true;
  return Boolean(detail?.canManageKillGame
    ?? (detail?.creatorView || detail?.administratorView));
}

export function canEndKillGame(detail, nowMs, communityRole) {
  return canManageKillGame(detail, communityRole)
    && detail?.status === "IN_PROGRESS"
    && nowMs < new Date(detail.endsAt).getTime();
}

export function canCancelKillGame(detail, communityRole) {
  return canManageKillGame(detail, communityRole)
    && ["RECRUITING", "READY"].includes(detail?.status);
}

export function toDateTimeLocal(value) {
  if (!value) return "";
  const date = new Date(value);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

import { VoiceActivityPeriod } from "./voiceActivityView.js";

// 날짜 선택은 UTC 달력 연산을 사용하고, '오늘'만 명시적인 서울 시간에서 얻는다.
export function seoulToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en", { timeZone: "Asia/Seoul", year: "numeric",
    month: "2-digit", day: "2-digit" }).formatToParts(now);
  const part = (type) => parts.find((item) => item.type === type).value;
  return `${part("year").padStart(4, "0")}-${part("month")}-${part("day")}`;
}

export function voiceDate(year, month = 1, day = 1) {
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(0, 0, 0, 0);
  return date;
}

export function voiceDateString(date) {
  return `${String(date.getUTCFullYear()).padStart(4, "0")}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`;
}

export function parseVoiceDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value ?? "")) return null;
  const [year, month, day] = value.split("-").map(Number);
  if (year < 1) return null;
  const date = voiceDate(year, month, day);
  return voiceDateString(date) === value ? date : null;
}

export function addVoiceDays(value, days) {
  const date = parseVoiceDate(value);
  date.setUTCDate(date.getUTCDate() + days);
  return voiceDateString(date);
}

export function voicePeriodStart(period, referenceDate) {
  if (period === VoiceActivityPeriod.ALL) return null;
  const date = parseVoiceDate(referenceDate);
  if (!date) return null;
  if (period === VoiceActivityPeriod.WEEK) date.setUTCDate(date.getUTCDate() - (date.getUTCDay() + 6) % 7);
  if (period === VoiceActivityPeriod.MONTH) date.setUTCDate(1);
  if (period === VoiceActivityPeriod.YEAR) date.setUTCMonth(0, 1);
  return voiceDateString(date);
}

export function shiftVoicePeriod(period, referenceDate, direction) {
  const date = parseVoiceDate(voicePeriodStart(period, referenceDate));
  if (!date) return null;
  if (period === VoiceActivityPeriod.DAY) date.setUTCDate(date.getUTCDate() + direction);
  if (period === VoiceActivityPeriod.WEEK) date.setUTCDate(date.getUTCDate() + 7 * direction);
  if (period === VoiceActivityPeriod.MONTH) date.setUTCMonth(date.getUTCMonth() + direction);
  if (period === VoiceActivityPeriod.YEAR) date.setUTCFullYear(date.getUTCFullYear() + direction);
  return date.getUTCFullYear() >= 1 && date.getUTCFullYear() <= 9999 ? voiceDateString(date) : null;
}

export function isCurrentVoicePeriod(period, referenceDate, today) {
  return period === VoiceActivityPeriod.ALL || voicePeriodStart(period, referenceDate) === voicePeriodStart(period, today);
}

export function voicePeriodTitle(period, referenceDate) {
  if (period === VoiceActivityPeriod.ALL) return "전체 누적 음성 활동";
  const start = voicePeriodStart(period, referenceDate);
  const date = parseVoiceDate(start);
  if (period === VoiceActivityPeriod.WEEK) return `${start.replaceAll("-", ".")} ~ ${addVoiceDays(start, 6).replaceAll("-", ".")}`;
  if (period === VoiceActivityPeriod.YEAR) return `${date.getUTCFullYear()}년`;
  const month = `${date.getUTCFullYear()}년 ${date.getUTCMonth() + 1}월`;
  return period === VoiceActivityPeriod.MONTH ? month : `${month} ${date.getUTCDate()}일`;
}

export function readVoicePeriodSelection(params, today) {
  const requested = params.get("period");
  const period = Object.values(VoiceActivityPeriod).includes(requested) ? requested : VoiceActivityPeriod.ALL;
  if (period === VoiceActivityPeriod.ALL) return { period, referenceDate: null };
  const selected = parseVoiceDate(params.get("referenceDate")) ? params.get("referenceDate") : today;
  const start = voicePeriodStart(period, selected);
  const currentStart = voicePeriodStart(period, today);
  return { period, referenceDate: start > currentStart ? currentStart : start };
}

export function voiceActivityQuery(period, referenceDate) {
  const params = new URLSearchParams({ period });
  if (period !== VoiceActivityPeriod.ALL) params.set("referenceDate", voicePeriodStart(period, referenceDate));
  return params.toString();
}

export function voiceCalendarDays(referenceDate) {
  const monthStart = voicePeriodStart(VoiceActivityPeriod.MONTH, referenceDate);
  const first = voicePeriodStart(VoiceActivityPeriod.WEEK, monthStart);
  return Array.from({ length: 42 }, (_, index) => addVoiceDays(first, index));
}

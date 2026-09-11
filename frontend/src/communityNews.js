export const EVENT_TYPES = {
  GENERAL: "일반", CLAN_MATCH: "내전", ACTIVITY: "활동 이벤트", LOTTERY: "추첨", ETC: "기타",
};
export const EVENT_STATUSES = { UPCOMING: "예정", ONGOING: "진행 중", ENDED: "종료" };

export function canManageNews(community) {
  return community?.role === "OWNER" || community?.role === "ADMIN";
}

export function newsUrl(communityId, tab = "notices", id) {
  const query = new URLSearchParams({ communityId, tab });
  if (id != null) query.set("id", id);
  return `/community-news.html?${query}`;
}

export function formatNewsDate(value) {
  return value ? new Date(value).toLocaleString("ko-KR", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false,
  }) : "-";
}

// datetime-local은 사용자의 현지 시각으로 편집하고 API에는 UTC ISO 시각을 보낸다.
export function toLocalDateTime(value) {
  if (!value) return "";
  const date = new Date(value);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 19);
}

export function newsPayload(form, isNotice) {
  const title = form.title.trim();
  const content = form.content.trim();
  if (!title || form.title.length > 200) throw new Error("제목은 1~200자로 입력해 주세요.");
  if ((isNotice && !content) || form.content.length > 20000) {
    throw new Error(isNotice ? "내용은 1~20,000자로 입력해 주세요." : "설명은 20,000자 이하로 입력해 주세요.");
  }
  if (isNotice) return { title, content, important: form.important, pinned: form.pinned };
  const startAt = new Date(form.startAt);
  const endAt = new Date(form.endAt);
  if (!form.startAt || !form.endAt || !Number.isFinite(startAt.getTime()) || !Number.isFinite(endAt.getTime())) {
    throw new Error("시작 날짜/시간과 종료 날짜/시간을 입력해 주세요.");
  }
  if (endAt < startAt) throw new Error("종료일은 시작일보다 이전일 수 없습니다.");
  if (!EVENT_TYPES[form.type]) throw new Error("이벤트 종류를 선택해 주세요.");
  return { title, content, type: form.type, startAt: startAt.toISOString(), endAt: endAt.toISOString() };
}

export function newsError(error) {
  if (error.status === 403) return "이 작업을 수행할 커뮤니티 권한이 없습니다.";
  if (error.status === 404) return "게시물을 찾을 수 없습니다. 목록에서 다시 확인해 주세요.";
  if (error.status === 400) return "입력 내용을 확인해 주세요. 제목·내용 길이와 이벤트 날짜를 확인해 주세요.";
  return "소식을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}

export const FEEDBACK_TYPES = {
  SERVICE: "서비스 문의",
  FEATURE: "기능 건의",
  BUG: "오류 신고",
  ETC: "기타",
};

export const FEEDBACK_STATUSES = {
  RECEIVED: "접수", IN_PROGRESS: "확인 중", ANSWERED: "답변 완료", CLOSED: "종료",
};

export function supportContext(location) {
  const rawId = new URLSearchParams(location.search).get("communityId");
  const id = Number(rawId);
  return {
    pageRoute: safeSupportRoute(location.pathname),
    communityId: /^[1-9]\d*$/.test(rawId ?? "") && Number.isSafeInteger(id) ? id : null,
  };
}

export function safeSupportRoute(value) {
  if (typeof value !== "string" || value.length > 2000) return null;
  try {
    const route = new URL(value, "https://guildup.invalid").pathname;
    return route.length <= 300 && /^(?:\/|\/[a-z-]+(?:\.html)?|\/help(?:\/[a-z-]+)*|\/developer(?:\/[a-z-]+(?:\/[0-9]+)?)*)$/.test(route)
      ? route : null;
  } catch { return null; }
}

export function feedbackPayload(form) {
  if (!Object.hasOwn(FEEDBACK_TYPES, form.type)) throw new Error("문의 유형을 선택해 주세요.");
  const title = form.title.trim();
  const content = form.content.trim();
  if (!title) throw new Error("제목을 입력해 주세요.");
  if (title.length > 100) throw new Error("제목은 100자 이하로 입력해 주세요.");
  if (/\r|\n/.test(title)) throw new Error("제목에는 줄바꿈을 사용할 수 없습니다.");
  if (!content) throw new Error("내용을 입력해 주세요.");
  if (content.length > 3000) throw new Error("내용은 3000자 이하로 입력해 주세요.");
  return { type: form.type, title, content };
}

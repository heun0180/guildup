export const FEEDBACK_TYPES = {
  FEATURE: "기능 건의",
  BUG: "버그 제보",
  ETC: "기타 문의",
};

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

export const ANNOUNCEMENT_TYPES = {
  NOTICE: "공지", UPDATE: "업데이트", MAINTENANCE: "점검", INCIDENT: "장애", EVENT: "이벤트",
};
export const ANNOUNCEMENT_STATUSES = {
  PRIVATE: "비공개", SCHEDULED: "예약", PUBLISHED: "게시중", ENDED: "종료",
};

export function announcementUrl(id) {
  return id == null ? "/announcements" : `/announcements?id=${encodeURIComponent(id)}`;
}

export function announcementPayload(form) {
  const title = form.title.trim();
  const content = form.content.trim();
  if (!title || form.title.length > 200) throw new Error("제목은 1~200자로 입력해 주세요.");
  if (!content || form.content.length > 20000) throw new Error("내용은 1~20,000자로 입력해 주세요.");
  if (!ANNOUNCEMENT_TYPES[form.type]) throw new Error("공지 유형을 선택해 주세요.");
  const date = (value) => {
    if (!value) return null;
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.getTime())) throw new Error("게시 날짜와 시간을 확인해 주세요.");
    return parsed.toISOString();
  };
  const publishStartAt = date(form.publishStartAt);
  const publishEndAt = date(form.publishEndAt);
  if (publishStartAt && publishEndAt && new Date(publishEndAt) <= new Date(publishStartAt)) {
    throw new Error("게시 종료는 시작보다 이후여야 합니다.");
  }
  if (form.popup && !form.important) throw new Error("중요 공지만 팝업으로 노출할 수 있습니다.");
  return { title, content, type: form.type, important: form.important, pinned: form.pinned,
    popup: form.popup, published: form.published, publishStartAt, publishEndAt };
}

export const BOARD_CATEGORIES = [
  ["ALL", "전체"],
  ["FREE", "자유"],
  ["QUESTION", "질문/정보"],
  ["PARTY", "같이하기"],
  ["REVIEW", "후기/자랑"],
];

export const CATEGORY_LABELS = Object.fromEntries(BOARD_CATEGORIES);
export const ROLE_LABELS = { OWNER: "운영진", ADMIN: "운영진", MEMBER: "클랜원" };

export function boardUrl(communityId, options = {}) {
  const query = new URLSearchParams({ communityId: String(communityId) });
  if (options.category && options.category !== "ALL") query.set("category", options.category);
  if (Number.isInteger(options.page) && options.page > 0) query.set("page", String(options.page));
  if (options.postId) query.set("postId", String(options.postId));
  if (options.mode) query.set("mode", options.mode);
  return `/community-board.html?${query}`;
}

export function formatBoardDate(value, includeTime = false) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("ko-KR", {
    year: "numeric", month: "2-digit", day: "2-digit",
    ...(includeTime ? { hour: "2-digit", minute: "2-digit" } : {}),
  }).format(new Date(value));
}

export function boardError(error) {
  if (error?.status === 403) return "이 커뮤니티 게시판에 접근할 권한이 없습니다.";
  if (error?.status === 404) return "게시글 또는 댓글을 찾을 수 없습니다.";
  return error?.message || "게시판 요청을 처리하지 못했습니다.";
}

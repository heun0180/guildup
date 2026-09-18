import test from "node:test";
import assert from "node:assert/strict";
import { BOARD_CATEGORIES, boardError, boardUrl } from "../src/communityBoard.js";

test("boardUrl keeps category and pagination filters", () => {
  assert.equal(boardUrl(7, { category: "QUESTION", page: 2 }),
    "/community-board.html?communityId=7&category=QUESTION&page=2");
  assert.equal(boardUrl(7, { category: "ALL", page: 0 }), "/community-board.html?communityId=7");
  assert.equal(boardUrl(7, { postId: 13, mode: "edit" }),
    "/community-board.html?communityId=7&postId=13&mode=edit");
});

test("board categories expose ALL only as a client filter", () => {
  assert.deepEqual(BOARD_CATEGORIES.map(([value]) => value), ["ALL", "FREE", "QUESTION", "PARTY", "REVIEW"]);
});

test("board errors explain access and missing resources", () => {
  assert.equal(boardError({ status: 403 }), "이 커뮤니티 게시판에 접근할 권한이 없습니다.");
  assert.equal(boardError({ status: 404 }), "게시글 또는 댓글을 찾을 수 없습니다.");
});

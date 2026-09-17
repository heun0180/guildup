import test from "node:test";
import assert from "node:assert/strict";
import { sortCommunityMembers } from "../src/memberView.js";

const members = [
  {
    id: 1,
    discordDisplayName: "나무2",
    discordUsername: "tree2",
    gameNickname: "Game20",
    discordJoinedAt: "2026-09-02T00:00:00Z",
    status: "ACTIVE",
  },
  {
    id: 2,
    discordDisplayName: "가람",
    discordUsername: "river",
    gameNickname: "Game3",
    discordJoinedAt: "2026-09-01T00:00:00Z",
    status: "PENDING",
  },
  {
    id: 3,
    nickname: "수동 등록",
    discordUsername: null,
    gameNickname: null,
    discordJoinedAt: null,
    status: "ACTIVE",
  },
];

test("클랜원 목록의 닉네임과 계정을 오름차순 및 내림차순으로 정렬한다", () => {
  assert.deepEqual(
    sortCommunityMembers(members, "discordNickname", "asc").map((member) => member.id),
    [2, 1, 3],
  );
  assert.deepEqual(
    sortCommunityMembers(members, "gameNickname", "desc").map((member) => member.id),
    [1, 2, 3],
  );
  assert.deepEqual(
    sortCommunityMembers(members, "discordAccount", "asc").map((member) => member.id),
    [2, 1, 3],
  );
});

test("가입일과 상태를 정렬하고 빈 값은 항상 마지막에 둔다", () => {
  assert.deepEqual(
    sortCommunityMembers(members, "discordJoinedAt", "desc").map((member) => member.id),
    [1, 2, 3],
  );
  assert.deepEqual(
    sortCommunityMembers(members, "status", "asc").map((member) => member.id),
    [1, 3, 2],
  );
});

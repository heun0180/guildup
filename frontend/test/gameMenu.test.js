import test from "node:test";
import assert from "node:assert/strict";
import { gameManagementMenuItems, gameMenuItems } from "../src/gameMenu.js";

const community = (role, capabilities) => ({ role, games: [{
  communityGameId: 10, gameType: "BATTLEGROUNDS_KAKAO", gameName: "배틀그라운드 카카오", capabilities,
}] });

test("capability가 없는 게임은 PUBG 기능 메뉴를 노출하지 않는다", () => {
  assert.deepEqual(gameMenuItems(community("OWNER", []), "1"), []);
});

test("각 capability만 대응 메뉴를 만들고 communityGameId를 링크에 포함한다", () => {
  for (const [capability, id] of [["KILL_COMPETITION", "kill-competitions"], ["BINGO", "bingos"]]) {
    const groups = gameMenuItems(community("OWNER", [capability]), "1");
    assert.equal(groups[0].gameName, "배틀그라운드 카카오");
    assert.equal(groups[0].items.length, 1);
    assert.equal(groups[0].items[0].id, id);
    assert.match(groups[0].items[0].href, /communityGameId=10/);
  }
});

test("일반 게임 메뉴에는 관리자 기능을 섞지 않는다", () => {
  const groups = gameMenuItems(community("MEMBER", ["ACTIVITY", "TEAM_MAKER", "BINGO"]), "1");
  assert.deepEqual(groups[0].items.map((item) => item.id), ["bingos"]);
});

test("인게임 활동과 팀 만들기를 OWNER와 ADMIN의 관리자 전용 메뉴로 복구한다", () => {
  for (const role of ["OWNER", "ADMIN"]) {
    const items = gameManagementMenuItems(community(role,
      ["ACTIVITY", "TEAM_MAKER", "KILL_COMPETITION", "BINGO"]), "1");
    assert.deepEqual(items.map((item) => item.id), ["activity", "team-maker"]);
    assert.deepEqual(items.map((item) => item.label), ["인게임 활동", "팀 만들기"]);
    assert.ok(items.every((item) => /communityGameId=10/.test(item.href)));
  }
  assert.deepEqual(gameManagementMenuItems(community("MEMBER", ["ACTIVITY", "TEAM_MAKER"]), "1"), []);
});

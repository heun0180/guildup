import test from "node:test";
import assert from "node:assert/strict";
import { gameMenuItems } from "../src/gameMenu.js";

const community = (role, capabilities) => ({ role, games: [{
  communityGameId: 10, gameType: "BATTLEGROUNDS_KAKAO", gameName: "배틀그라운드 카카오", capabilities,
}] });

test("capability가 없는 게임은 PUBG 기능 메뉴를 노출하지 않는다", () => {
  assert.deepEqual(gameMenuItems(community("OWNER", []), "1"), []);
});

test("각 capability만 대응 메뉴를 만들고 communityGameId를 링크에 포함한다", () => {
  for (const [capability, id] of [["ACTIVITY", "activity"], ["TEAM_MAKER", "team-maker"],
    ["KILL_COMPETITION", "kill-competitions"], ["BINGO", "bingos"]]) {
    const groups = gameMenuItems(community("OWNER", [capability]), "1");
    assert.equal(groups[0].items.length, 1);
    assert.equal(groups[0].items[0].id, id);
    assert.match(groups[0].items[0].href, /communityGameId=10/);
  }
});

test("MEMBER에게 관리자 전용 PUBG 기능을 노출하지 않는다", () => {
  const groups = gameMenuItems(community("MEMBER", ["ACTIVITY", "TEAM_MAKER", "BINGO"]), "1");
  assert.deepEqual(groups[0].items.map((item) => item.id), ["bingos"]);
});

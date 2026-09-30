import test from "node:test";
import assert from "node:assert/strict";
import { canDeleteCommunity, matchesCommunityName } from "../src/communityDeletion.js";

test("only an OWNER can see community deletion controls", () => {
  assert.equal(canDeleteCommunity("OWNER"), true);
  assert.equal(canDeleteCommunity("ADMIN"), false);
  assert.equal(canDeleteCommunity("MEMBER"), false);
});

test("community name confirmation is exact except for surrounding whitespace", () => {
  assert.equal(matchesCommunityName("  치즈 클랜  ", "치즈 클랜"), true);
  assert.equal(matchesCommunityName("치즈클랜", "치즈 클랜"), false);
  assert.equal(matchesCommunityName("치즈 클랜 ", "치즈 클랜 "), false);
});

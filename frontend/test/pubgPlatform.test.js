import test from "node:test";
import assert from "node:assert/strict";
import { pubgPlatform, selectedPubgGame, memberForPubgGame, gamePageScopeKey } from "../src/pubgPlatform.js";
import { createRequestScope, isRequestCancelled } from "../src/api/requestScope.js";

const kakao = { communityGameId: 1, gameType: "BATTLEGROUNDS_KAKAO" };
const steam = { communityGameId: 2, gameType: "BATTLEGROUNDS_STEAM" };

test("only expected AbortError cancellations are ignored, not timeouts or real failures", () => {
  assert.equal(isRequestCancelled(new DOMException("signal is aborted without reason", "AbortError")), true);
  assert.equal(isRequestCancelled(new DOMException("timed out", "TimeoutError")), false);
  assert.equal(isRequestCancelled(new Error("signal is aborted without reason")), false);
  assert.equal(isRequestCancelled(null), false);
});

test("platform selection and member nickname never fall back to another connected platform", () => {
  const community = { games: [kakao, steam] };
  assert.equal(pubgPlatform(selectedPubgGame(community, "2")).value, "STEAM");
  assert.equal(selectedPubgGame(community, "99"), null);
  assert.equal(selectedPubgGame({ games: [steam] }, null), steam);
  const member = { gameNickname: "LegacyKakao", pubgAccounts: [{ platform: "KAKAO", nickname: "KakaoNick" }] };
  assert.equal(memberForPubgGame(member, kakao).gameNickname, "KakaoNick");
  assert.equal(memberForPubgGame(member, steam).gameNickname, null);
  assert.equal(memberForPubgGame({ gameNickname: "OldServer" }, kakao).gameNickname, "OldServer");
});

test("page scope changes on platform and community transitions", () => {
  const key = (id, game) => gamePageScopeKey({ pathname: "/bingos.html", search: `?communityId=${id}&communityGameId=${game}` });
  assert.notEqual(key(1, 1), key(1, 2));
  assert.notEqual(key(1, 1), key(2, 1));
});

test("cancel rejects a late old-platform response even if transport ignores its AbortSignal", async () => {
  let resolve;
  let signal;
  const scope = createRequestScope((_url, options) => {
    signal = options.signal;
    return new Promise((done) => { resolve = done; });
  });
  const pending = scope.request("/kakao");
  const rejection = assert.rejects(pending, { name: "AbortError" });
  scope.cancel();
  assert.equal(signal.aborted, true);
  resolve({ oldPlatform: true });
  await rejection;
});

test("a replayed effect can request after cancellation without accepting its earlier response", async () => {
  const scope = createRequestScope(async (url) => url);
  scope.cancel();
  assert.equal(await scope.request("/steam"), "/steam");
});

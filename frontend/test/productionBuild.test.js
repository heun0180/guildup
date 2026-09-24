import test from "node:test";
import assert from "node:assert/strict";
import { htmlRoutes, scriptSources } from "../scripts/production-build.mjs";

test("React의 모든 html 경로를 프로덕션 진입 파일 대상으로 찾는다", () => {
  const source = `
    <Route path="/community-dashboard.html" element={<Dashboard />} />
    <Route path="/community-settings.html" element={<Settings />} />
    <Route path="/bingos.html" element={<Bingo />} />
    <Route path="/privacy" element={<Privacy />} />`;

  assert.deepEqual(htmlRoutes(source), [
    "/community-dashboard.html",
    "/community-settings.html",
    "/bingos.html",
  ]);
});

test("프로덕션 HTML이 참조하는 해시 JS asset을 찾는다", () => {
  const html = `<script type="module" crossorigin src="/assets/main-current.js"></script>`;
  assert.deepEqual(scriptSources(html), ["/assets/main-current.js"]);
});

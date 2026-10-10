import test from "node:test";
import assert from "node:assert/strict";
import { extensionlessRoutes, htmlRoutes, normalizePublicPermissions, scriptSources, verifyProductionBuild } from "../scripts/production-build.mjs";
import { chmod, mkdir, mkdtemp, rm, stat, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

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

test("정적 호스트에서 직접 여는 개발자 루트의 진입 파일을 만든다", () => {
  const source = `
    <Route path="/developer" element={<Developer />} />
    <Route path="/developer/communities" element={<Communities />} />
    <Route path="/developer/communities/:communityId" element={<Detail />} />`;

  assert.deepEqual(extensionlessRoutes(source), ["/developer", "/developer/communities"]);
});

test("프로덕션 HTML이 참조하는 해시 JS asset을 찾는다", () => {
  const html = `<script type="module" crossorigin src="/assets/main-current.js"></script>`;
  assert.deepEqual(scriptSources(html), ["/assets/main-current.js"]);
});

test("0600 파일과 0700 자산 디렉터리를 배포 검증에서 차단하고 공개 자산 권한을 복구한다", { skip: process.platform === "win32" }, async (t) => {
  const frontend = await mkdtemp(path.join(os.tmpdir(), "guildup-static-access-"));
  t.after(() => rm(frontend, { recursive: true, force: true }));
  const dist = path.join(frontend, "dist");
  await mkdir(path.join(frontend, "src"));
  await mkdir(path.join(dist, "assets"), { recursive: true });
  await writeFile(path.join(frontend, "src", "App.jsx"), '<Route path="/login.html" />');
  await writeFile(path.join(dist, "login.html"), '<script src="/assets/main.js"></script>');
  await writeFile(path.join(dist, "assets", "main.js"), "console.log('public build');");
  await normalizePublicPermissions(dist);
  await chmod(path.join(dist, "login.html"), 0o600);
  await assert.rejects(verifyProductionBuild(frontend), /not accessible to the web server: login.html/);
  await chmod(path.join(dist, "login.html"), 0o644);
  await chmod(path.join(dist, "assets"), 0o700);
  await assert.rejects(verifyProductionBuild(frontend), /not accessible to the web server: assets/);
  await normalizePublicPermissions(dist);
  assert.equal((await stat(path.join(dist, "login.html"))).mode & 0o777, 0o644);
  assert.equal((await stat(path.join(dist, "assets"))).mode & 0o777, 0o755);
  assert.deepEqual((await verifyProductionBuild(frontend)).routes, ["/login.html"]);
});

test("공개 산출물 권한 처리에서 외부 파일 심볼릭 링크를 거부한다", { skip: process.platform === "win32" }, async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "guildup-static-link-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const dist = path.join(root, "dist"), privateFile = path.join(root, "private.txt");
  await mkdir(dist);
  await writeFile(privateFile, "private");
  await chmod(privateFile, 0o600);
  await symlink(privateFile, path.join(dist, "linked.txt"));
  await assert.rejects(normalizePublicPermissions(dist), /must not contain symlinks/);
  assert.equal((await stat(privateFile)).mode & 0o777, 0o600);
});

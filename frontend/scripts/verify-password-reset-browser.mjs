// Isolated browser + static build + local mock API. No operational backend/SMTP/OAuth calls.
// Run after npm run build: node scripts/verify-password-reset-browser.mjs
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { mkdtemp, readFile, writeFile, rm, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const temporary = await mkdtemp(path.join(tmpdir(), "guildup-reset-browser-"));
const artifacts = process.env.RESET_BROWSER_ARTIFACTS || path.join(temporary, "screenshots");
await mkdir(artifacts, { recursive: true });
const requests = [];
const server = createServer(async (request, response) => {
  const url = new URL(request.url, "http://localhost");
  response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer");
  if (url.pathname.startsWith("/api/")) {
    let body = ""; for await (const chunk of request) body += chunk;
    const data = body ? JSON.parse(body) : {};
    requests.push({ path: url.pathname, method: request.method }); // No secrets retained in diagnostics.
    response.setHeader("Content-Type", "application/json");
    if (url.pathname === "/api/auth/csrf") return response.end(JSON.stringify({ token: "mock-csrf" }));
    if (url.pathname === "/api/auth/password-reset/request") {
      response.statusCode = 202; return response.end(JSON.stringify({ message: "accepted" }));
    }
    if (url.pathname === "/api/auth/password-reset/validate") {
      if (data.token?.startsWith("E")) {
        response.statusCode = 400;
        return response.end(JSON.stringify({ code: "PASSWORD_RESET_EXPIRED", message: "재설정 링크가 만료되었습니다. 비밀번호 찾기에서 새 링크를 요청해 주세요." }));
      }
      response.statusCode = 204; return response.end();
    }
    if (url.pathname === "/api/auth/password-reset/confirm") { response.statusCode = 204; return response.end(); }
    response.statusCode = 401; return response.end(JSON.stringify({ message: "로그인이 필요합니다." }));
  }
  const file = path.join(frontend, "dist", url.pathname === "/" ? "index.html" : url.pathname);
  if (!file.startsWith(path.join(frontend, "dist") + path.sep)) { response.statusCode = 404; return response.end(); }
  try {
    const content = await readFile(file);
    response.setHeader("Content-Type", file.endsWith(".html") ? "text/html; charset=utf-8" : file.endsWith(".js") ? "application/javascript" : "text/css");
    response.end(content);
  } catch { response.statusCode = 404; response.end(); }
});
await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const chromePath = process.env.CHROME_PATH || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const chrome = spawn(chromePath, ["--headless=new", "--disable-gpu", "--disable-background-networking", "--disable-component-update",
  "--no-first-run", `--user-data-dir=${temporary}/profile`, "--remote-debugging-port=0", "about:blank"], { stdio: "ignore" });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
let socket;
try {
  let port;
  for (let i = 0; i < 200; i++) {
    const value = await readFile(path.join(temporary, "profile/DevToolsActivePort"), "utf8").catch(() => "");
    if (value) { port = value.split("\n")[0]; break; }
    if (chrome.exitCode !== null) throw new Error("Isolated Chrome did not start");
    await pause(50);
  }
  assert.ok(port, "DevTools port unavailable");
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(targets.find(target => target.type === "page").webSocketDebuggerUrl);
  await new Promise(resolve => socket.addEventListener("open", resolve, { once: true }));
  let sequence = 0; const pending = new Map();
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data); const action = pending.get(message.id);
    if (action) { pending.delete(message.id); message.error ? action.reject(new Error(message.error.message)) : action.resolve(message.result); }
  });
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => {
    const result = await cdp("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error("Browser evaluation failed"); return result.result.value;
  };
  const until = async expression => {
    for (let i = 0; i < 200; i++) { if (await evaluate(expression)) return; await pause(25); }
    throw new Error("Browser view did not reach expected state");
  };
  const open = async (route, selector) => {
    await cdp("Page.navigate", { url: origin + route }); await until(`Boolean(document.querySelector(${JSON.stringify(selector)}))`);
  };
  const screenshot = async name => {
    const result = await cdp("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    await writeFile(path.join(artifacts, name + ".png"), Buffer.from(result.data, "base64"));
  };
  const layout = async () => {
    const result = await evaluate(`({ width: innerWidth, scroll: document.documentElement.scrollWidth,
      controls: [...document.querySelectorAll('.reset-card input,.reset-card button,.reset-card .button-link')].map(e => ({width:e.getBoundingClientRect().width,height:e.getBoundingClientRect().height})) })`);
    assert.ok(result.scroll <= result.width, "Horizontal overflow");
    for (const control of result.controls) assert.ok(control.height >= 44 && control.width <= result.width, "Mobile control dimensions");
  };
  const fill = async (name, value) => evaluate(`(() => {
    const input=document.querySelector('[name=${name}]');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,${JSON.stringify(value)});
    input.dispatchEvent(new Event('input',{bubbles:true}));
  })()`);
  await cdp("Page.enable"); await cdp("Runtime.enable");
  for (const width of [360, 390, 1280]) {
    await cdp("Emulation.setDeviceMetricsOverride", { width, height: width < 500 ? 844 : 900, deviceScaleFactor: 1, mobile: width < 500 });
    await open("/login.html", ".login-button");
    assert.equal(await evaluate("document.querySelector('.login-button').textContent.trim()"), "디스코드 로그인");
    assert.equal(await evaluate("document.querySelector('.login-button').getAttribute('href')"), "/api/auth/discord/authorize");
    assert.ok(await evaluate("Boolean(document.querySelector('.login-button svg[aria-hidden=true]'))"));
    await open("/forgot-password.html", "[name=email]"); await layout(); await screenshot(`forgot-${width}`);
    await fill("email", "missing@example.com"); await evaluate("document.querySelector('form').requestSubmit()");
    await until("document.querySelector('.auth-success')?.textContent.includes('보낼 수 있는 경우')");
    await layout(); await screenshot(`accepted-${width}`);
    await open(`/password-reset.html#token=${"R".repeat(43)}`, "[name=password]");
    assert.equal(await evaluate("location.hash"), "");
    assert.equal(await evaluate("document.querySelector('meta[name=referrer]').content"), "no-referrer");
    await layout(); await screenshot(`reset-${width}`);
    await fill("password", "NewPass123!"); await fill("passwordConfirmation", "NewPass123!");
    await evaluate("document.querySelector('form').requestSubmit()");
    await until("document.querySelector('.auth-success')?.textContent.includes('비밀번호가 변경되었습니다')");
    await layout(); await screenshot(`success-${width}`);
    await open(`/password-reset.html#token=${"E".repeat(43)}`, ".reset-card [role=alert]");
    assert.ok(await evaluate("document.querySelector('[role=alert]').textContent.includes('만료')"));
    assert.equal(await evaluate("document.querySelectorAll('.reset-card input').length"), 0);
    await layout(); await screenshot(`expired-${width}`);
  }
  assert.equal(requests.some(request => /token=/.test(request.path)), false);
  assert.equal(requests.some(request => request.path === "/api/auth/login"), false);
  console.log(`Browser checks passed at 360px, 390px and 1280px. Screenshots: ${artifacts}`);
} finally {
  socket?.close();
  if (chrome.exitCode === null) {
    const stopped = new Promise(resolve => chrome.once("exit", resolve));
    chrome.kill(); await stopped;
  }
  await new Promise(resolve => server.close(resolve));
  if (process.env.RESET_BROWSER_ARTIFACTS) await rm(temporary, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}

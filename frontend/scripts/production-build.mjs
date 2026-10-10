import { chmod, lstat, readFile, readdir, stat } from "node:fs/promises";
import path from "node:path";

const ROUTE_PATTERN = /<Route\s+path="([^"]+)"/g;
const SCRIPT_PATTERN = /<script\b[^>]*\bsrc="([^"]+)"/g;

export function htmlRoutes(appSource) {
  return [...appSource.matchAll(ROUTE_PATTERN)]
    .map((match) => match[1])
    .filter((route) => route.endsWith(".html"));
}

export function extensionlessRoutes(appSource) {
  return [...appSource.matchAll(ROUTE_PATTERN)]
    .map((match) => match[1])
    .filter((route) => route !== "/" && !route.includes(":") && !path.extname(route));
}

export function scriptSources(html) {
  return [...html.matchAll(SCRIPT_PATTERN)].map((match) => match[1]);
}

export async function productionBuildContext(frontendDirectory) {
  const source = await readFile(path.join(frontendDirectory, "src", "App.jsx"), "utf8");
  const routes = [...new Set([
    ...htmlRoutes(source),
    ...extensionlessRoutes(source).map((route) => `${route}/index.html`),
  ])].sort();
  return { distDirectory: path.join(frontendDirectory, "dist"), routes };
}

async function visitPublicBuild(directory, visitor) {
  const info = await lstat(directory);
  if (info.isSymbolicLink()) throw new Error(`Public build must not contain symlinks: ${directory}`);
  if (!info.isDirectory() && !info.isFile()) throw new Error(`Unsupported public build entry: ${directory}`);
  await visitor(directory, info);
  if (info.isDirectory()) {
    for (const name of await readdir(directory)) await visitPublicBuild(path.join(directory, name), visitor);
  }
}

// These are publicly served assets only. A restrictive build umask must not make
// them unreadable by Nginx after a transfer that preserves source permissions.
export async function normalizePublicPermissions(distDirectory) {
  if (process.platform === "win32") return;
  await visitPublicBuild(distDirectory, (entry, info) => chmod(entry, info.isDirectory() ? 0o755 : 0o644));
}

async function verifyPublicPermissions(distDirectory) {
  if (process.platform === "win32") return;
  await visitPublicBuild(distDirectory, async (entry, info) => {
    const required = info.isDirectory() ? 0o005 : 0o004;
    if ((info.mode & required) !== required) {
      throw new Error(`Public build entry is not accessible to the web server: ${path.relative(distDirectory, entry) || "."}`);
    }
  });
}

export async function verifyProductionBuild(frontendDirectory) {
  const { distDirectory, routes } = await productionBuildContext(frontendDirectory);
  await verifyPublicPermissions(distDirectory);
  const missingRoutes = [];
  const referencedAssets = new Set();

  for (const route of routes) {
    const output = path.join(distDirectory, route.slice(1));
    let html;
    try {
      html = await readFile(output, "utf8");
    } catch {
      missingRoutes.push(route);
      continue;
    }
    for (const source of scriptSources(html)) {
      if (source.startsWith("/assets/")) referencedAssets.add(source.slice(1));
    }
  }

  if (missingRoutes.length > 0) {
    throw new Error(`Production build is missing route entry files: ${missingRoutes.join(", ")}`);
  }
  if (referencedAssets.size === 0) {
    throw new Error("Production HTML does not reference a built JavaScript asset.");
  }
  for (const asset of referencedAssets) {
    const info = await stat(path.join(distDirectory, asset)).catch(() => null);
    if (!info?.isFile()) throw new Error(`Production HTML references a missing asset: /${asset}`);
  }

  const emittedHtml = (await readdir(distDirectory)).filter((name) => name.endsWith(".html"));
  return { routes, emittedHtml, referencedAssets: [...referencedAssets].sort() };
}

import { readFile, readdir, stat } from "node:fs/promises";
import path from "node:path";

const ROUTE_PATTERN = /<Route\s+path="([^"]+)"/g;
const SCRIPT_PATTERN = /<script\b[^>]*\bsrc="([^"]+)"/g;

export function htmlRoutes(appSource) {
  return [...appSource.matchAll(ROUTE_PATTERN)]
    .map((match) => match[1])
    .filter((route) => route.endsWith(".html"));
}

export function scriptSources(html) {
  return [...html.matchAll(SCRIPT_PATTERN)].map((match) => match[1]);
}

export async function productionBuildContext(frontendDirectory) {
  const source = await readFile(path.join(frontendDirectory, "src", "App.jsx"), "utf8");
  const routes = [...new Set(htmlRoutes(source))].sort();
  return { distDirectory: path.join(frontendDirectory, "dist"), routes };
}

export async function verifyProductionBuild(frontendDirectory) {
  const { distDirectory, routes } = await productionBuildContext(frontendDirectory);
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

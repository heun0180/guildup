import { copyFile, readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { productionBuildContext, verifyProductionBuild } from "./production-build.mjs";

const frontendDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const { distDirectory, routes } = await productionBuildContext(frontendDirectory);
const fallback = path.join(distDirectory, "index.html");

// Vite emits dedicated HTML entry points for a few public URLs. Every remaining
// React Router .html URL needs the same current bundle so a direct request or
// browser refresh cannot fall back to the removed legacy UI.
await readFile(fallback);
for (const route of routes) {
  const output = path.join(distDirectory, route.slice(1));
  if (output === fallback) continue;
  const existing = await stat(output).catch(() => null);
  if (existing?.isFile()) continue;
  await copyFile(fallback, output);
}

const result = await verifyProductionBuild(frontendDirectory);
console.log(`Production build verified: ${result.routes.length} routes, ${result.referencedAssets.length} JS bundle(s).`);

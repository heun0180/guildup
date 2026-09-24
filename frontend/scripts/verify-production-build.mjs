import path from "node:path";
import { fileURLToPath } from "node:url";
import { verifyProductionBuild } from "./production-build.mjs";

const frontendDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const result = await verifyProductionBuild(frontendDirectory);
console.log(`Production build verified: ${result.routes.length} routes, ${result.referencedAssets.length} JS bundle(s).`);

import { copyFile, mkdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const dist = resolve(root, "apps/app/dist");
const sourceManifestPath = resolve(root, "apps/app/manifest.json");
const distManifestPath = resolve(dist, "manifest.json");
// esbuild emits main.js/styles.css but does not copy the source manifest.
// Always synchronize it before preparing the root release bundle so CI cannot
// accidentally publish a stale version from a previous local build.
await copyFile(sourceManifestPath, distManifestPath);
const manifest = JSON.parse(await readFile(sourceManifestPath, "utf8"));

if (manifest.isDesktopOnly !== false) {
  throw new Error("Mobile release manifest must set isDesktopOnly to false");
}

await mkdir(root, { recursive: true });
await Promise.all([
  copyFile(resolve(dist, "main.js"), resolve(root, "main.js")),
  copyFile(resolve(dist, "styles.css"), resolve(root, "styles.css")),
  copyFile(resolve(dist, "manifest.json"), resolve(root, "manifest.json")),
]);

console.log(`Prepared Media Extended Mobile ${manifest.version}`);

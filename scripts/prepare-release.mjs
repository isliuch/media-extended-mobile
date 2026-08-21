import { copyFile, mkdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const dist = resolve(root, "apps/app/dist");
const manifest = JSON.parse(await readFile(resolve(dist, "manifest.json"), "utf8"));

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

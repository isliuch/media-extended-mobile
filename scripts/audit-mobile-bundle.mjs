import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const manifest = JSON.parse(await readFile(resolve(root, "manifest.json"), "utf8"));
const bundle = await readFile(resolve(root, "main.js"), "utf8");

const failures = [];
if (manifest.isDesktopOnly !== false) failures.push("manifest isDesktopOnly must be false");
if (!manifest.version.includes("-mobile.")) failures.push("manifest version must use -mobile.* suffix");
if (!bundle.includes("https://player.bilibili.com/player.html")) {
  failures.push("Bilibili mobile embed is missing from main.js");
}
if (!bundle.includes("Platform.isMobile")) {
  failures.push("mobile runtime branch is missing from main.js");
}
if (!bundle.includes("https://www.youtube.com/iframe_api")) {
  failures.push("YouTube playback-time API is missing from main.js");
}
if (!bundle.toLowerCase().includes("x-media-time")) {
  failures.push("Android screenshot-time bridge is missing from main.js");
}
if (bundle.includes('require("url")')) {
  failures.push("main.js still contains a top-level-compatible Node url dependency");
}

if (failures.length) throw new Error(failures.join("\n"));
console.log(`Mobile bundle audit passed for ${manifest.version}`);

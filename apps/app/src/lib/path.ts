import { Platform } from "obsidian";

type PathApi = Pick<
  typeof import("node:path"),
  "basename" | "dirname" | "extname" | "isAbsolute" | "join" | "relative" | "sep"
>;

const mobilePath: PathApi = {
  sep: "/",
  join: (...parts) => normalize(parts.join("/")),
  dirname: (value) => {
    const normalized = normalize(value);
    const index = normalized.lastIndexOf("/");
    return index <= 0 ? (normalized.startsWith("/") ? "/" : ".") : normalized.slice(0, index);
  },
  basename: (value, suffix) => {
    const basename = normalize(value).split("/").pop() ?? "";
    return suffix && basename.endsWith(suffix)
      ? basename.slice(0, -suffix.length)
      : basename;
  },
  extname: (value) => {
    const basename = mobilePath.basename(value);
    const index = basename.lastIndexOf(".");
    return index <= 0 ? "" : basename.slice(index);
  },
  isAbsolute: (value) => value.startsWith("/"),
  relative: (from, to) => {
    const fromParts = normalize(from).split("/").filter(Boolean);
    const toParts = normalize(to).split("/").filter(Boolean);
    while (fromParts[0] === toParts[0]) {
      fromParts.shift();
      toParts.shift();
    }
    return [...fromParts.map(() => ".."), ...toParts].join("/") || ".";
  },
};

function normalize(value: string): string {
  const absolute = value.startsWith("/");
  const parts: string[] = [];
  for (const part of value.split("/")) {
    if (!part || part === ".") continue;
    if (part === "..") parts.pop();
    else parts.push(part);
  }
  return `${absolute ? "/" : ""}${parts.join("/")}` || (absolute ? "/" : ".");
}

// Node.js is unavailable in Obsidian Mobile. Keep native path handling on
// desktop and use vault-style POSIX paths on mobile.
// eslint-disable-next-line @typescript-eslint/no-var-requires
const path: PathApi = Platform.isDesktopApp
  ? Platform.isWin
    ? require("path/win32")
    : require("path/posix")
  : mobilePath;
export default path;

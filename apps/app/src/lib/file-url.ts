import { Platform } from "obsidian";

export function fileURLToPath(url: string | URL): string {
  const value = url instanceof URL ? url : new URL(url);
  if (value.protocol !== "file:") throw new TypeError("URL must use file: protocol");
  let pathname = decodeURIComponent(value.pathname);
  if (Platform.isWin && /^\/[A-Za-z]:/.test(pathname)) pathname = pathname.slice(1);
  return Platform.isWin ? pathname.replaceAll("/", "\\") : pathname;
}

export function pathToFileURL(filePath: string): URL {
  const normalized = filePath.replaceAll("\\", "/");
  const pathname = normalized.startsWith("/") ? normalized : `/${normalized}`;
  return new URL(`file://${encodeURI(pathname).replaceAll("#", "%23").replaceAll("?", "%3F")}`);
}

export interface BilibiliEmbedSource {
  cleaned: URL;
  tempFrag: { start: number } | null;
}

export function buildBilibiliEmbedUrl(media: BilibiliEmbedSource): string | null {
  const id = media.cleaned.pathname.split("/").filter(Boolean).pop();
  if (!id) return null;

  const url = new URL("https://player.bilibili.com/player.html");
  if (/^BV[\dA-Za-z]+$/.test(id)) url.searchParams.set("bvid", id);
  else if (/^av\d+$/i.test(id)) url.searchParams.set("aid", id.slice(2));
  else if (/^ep\d+$/i.test(id)) url.searchParams.set("ep_id", id.slice(2));
  else if (/^ss\d+$/i.test(id)) url.searchParams.set("season_id", id.slice(2));
  else return null;

  const page = media.cleaned.searchParams.get("p");
  if (page) url.searchParams.set("page", page);
  const start = media.tempFrag?.start;
  if (start && start > 0) url.searchParams.set("t", String(Math.floor(start)));

  url.searchParams.set("autoplay", "0");
  url.searchParams.set("high_quality", "1");
  url.searchParams.set("danmaku", "0");
  url.searchParams.set("as_wide", "1");
  return url.href;
}

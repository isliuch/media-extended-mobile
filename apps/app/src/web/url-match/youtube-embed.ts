export interface YouTubeEmbedSource {
  cleaned: URL;
  id?: string;
  tempFrag: { start: number; end: number } | null;
}

export function buildYouTubeEmbedUrl(media: YouTubeEmbedSource): string | null {
  const id = media.id;
  if (!id || !/^[\w-]{6,}$/.test(id)) return null;

  const url = new URL(`https://www.youtube-nocookie.com/embed/${id}`);
  const list = media.cleaned.searchParams.get("list");
  if (list) url.searchParams.set("list", list);

  const start = media.tempFrag?.start;
  const end = media.tempFrag?.end;
  if (start && start > 0) url.searchParams.set("start", String(Math.floor(start)));
  if (end && Number.isFinite(end) && end > 0) {
    url.searchParams.set("end", String(Math.floor(end)));
  }

  url.searchParams.set("autoplay", "0");
  url.searchParams.set("enablejsapi", "1");
  url.searchParams.set("playsinline", "1");
  url.searchParams.set("rel", "0");
  return url.href;
}

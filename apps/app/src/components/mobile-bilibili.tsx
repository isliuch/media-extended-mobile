import type { MediaURL } from "@/info/media-url";
import { registerMobilePlaybackTimeReader } from "@/media-view/mobile-playback-time";
import { buildBilibiliEmbedUrl } from "@/web/url-match/bilibili-embed";
import { buildYouTubeEmbedUrl } from "@/web/url-match/youtube-embed";
import { useEffect, useRef } from "react";

interface YouTubePlayer {
  destroy(): void;
  getCurrentTime(): number;
}

interface YouTubeApi {
  Player: new (
    frame: HTMLIFrameElement,
    options: { events: { onReady: () => void } },
  ) => YouTubePlayer;
}

type YouTubeWindow = Window & {
  YT?: YouTubeApi;
  onYouTubeIframeAPIReady?: () => void;
};

const youtubeApiLoads = new WeakMap<Window, Promise<YouTubeApi>>();

function loadYouTubeApi(doc: Document): Promise<YouTubeApi> {
  const targetWindow = doc.defaultView as YouTubeWindow | null;
  if (!targetWindow) return Promise.reject(new Error("播放器窗口不可用"));
  if (targetWindow.YT?.Player) return Promise.resolve(targetWindow.YT);

  const existing = youtubeApiLoads.get(targetWindow);
  if (existing) return existing;

  const loading = new Promise<YouTubeApi>((resolve, reject) => {
    const previousReady = targetWindow.onYouTubeIframeAPIReady;
    const timeout = targetWindow.setTimeout(
      () => reject(new Error("YouTube 播放器 API 加载超时")),
      15_000,
    );
    targetWindow.onYouTubeIframeAPIReady = () => {
      previousReady?.();
      targetWindow.clearTimeout(timeout);
      if (targetWindow.YT?.Player) resolve(targetWindow.YT);
      else reject(new Error("YouTube 播放器 API 不可用"));
    };

    const script = doc.createElement("script");
    script.src = "https://www.youtube.com/iframe_api";
    script.onerror = () => {
      targetWindow.clearTimeout(timeout);
      reject(new Error("无法加载 YouTube 播放器 API"));
    };
    doc.head.appendChild(script);
  });
  youtubeApiLoads.set(targetWindow, loading);
  void loading.catch(() => youtubeApiLoads.delete(targetWindow));
  return loading;
}

function isAndroid() {
  return /Android/i.test(navigator.userAgent);
}

function MobileEmbedPlayer({
  src,
  title,
  originalUrl,
  frameRef,
  children,
}: {
  src: string;
  title: string;
  originalUrl: string;
  frameRef?: React.RefObject<HTMLIFrameElement>;
  children: React.ReactNode;
}) {
  return (
    <div className="mx-mobile-embed">
      <div className="mx-mobile-embed-frame">
        <iframe
          ref={frameRef}
          src={src}
          title={title}
          allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
          allowFullScreen
          referrerPolicy="no-referrer-when-downgrade"
        />
      </div>
      <p>
        {children} <a href={originalUrl}>在浏览器或视频 App 中打开</a>
      </p>
    </div>
  );
}

export function MobileBilibiliPlayer({ media }: { media: MediaURL }) {
  // player.bilibili.com can render its chrome in Android WebView while never
  // resolving the media (00:00 / 00:00). Bilibili's mobile HTML5 endpoint is
  // also compatible with older WebViews, so use it for every Android version.
  const src = buildBilibiliEmbedUrl(media, isAndroid());

  if (!src) {
    return (
      <div className="mx-mobile-bilibili-error">
        无法从这个哔哩哔哩链接提取视频 ID，请使用 BV、av、ep 或 ss 完整链接。
      </div>
    );
  }

  return (
    <MobileEmbedPlayer
      src={src}
      title="哔哩哔哩播放器"
      originalUrl={media.cleaned.href}
    >
      移动端使用哔哩哔哩官方嵌入播放器；可通过系统截图导入或 Android 截图助手保存画面。
    </MobileEmbedPlayer>
  );
}

export function MobileYouTubePlayer({ media }: { media: MediaURL }) {
  const src = buildYouTubeEmbedUrl(media);
  const frameRef = useRef<HTMLIFrameElement>(null);

  useEffect(() => {
    const frame = frameRef.current;
    if (!frame || !src) return;
    let disposed = false;
    let ready = false;
    let player: YouTubePlayer | null = null;
    let unregister: (() => void) | null = null;

    void loadYouTubeApi(frame.ownerDocument)
      .then((api) => {
        if (disposed) return;
        player = new api.Player(frame, {
          events: { onReady: () => (ready = true) },
        });
        unregister = registerMobilePlaybackTimeReader(frame, () =>
          ready && player ? player.getCurrentTime() : null,
        );
      })
      .catch((error) =>
        console.warn("Unable to read YouTube mobile playback time", error),
      );

    return () => {
      disposed = true;
      unregister?.();
      player?.destroy();
    };
  }, [src]);

  if (!src) {
    return <div className="mx-mobile-embed-error">无法从这个 YouTube 链接提取视频 ID。</div>;
  }

  return (
    <MobileEmbedPlayer
      src={src}
      title="YouTube 播放器"
      originalUrl={media.cleaned.href}
      frameRef={frameRef}
    >
      移动端使用 YouTube 官方嵌入播放器；需要设备网络能够访问 YouTube，可通过系统截图导入或 Android 截图助手保存画面。
    </MobileEmbedPlayer>
  );
}

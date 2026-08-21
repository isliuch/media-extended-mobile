import type { MediaURL } from "@/info/media-url";
import { buildBilibiliEmbedUrl } from "@/web/url-match/bilibili-embed";
import { buildYouTubeEmbedUrl } from "@/web/url-match/youtube-embed";

function isLegacyAndroid() {
  const match = navigator.userAgent.match(/Android\s+(\d+)/i);
  return !!match && Number(match[1]) <= 8;
}

function MobileEmbedPlayer({
  src,
  title,
  originalUrl,
  children,
}: {
  src: string;
  title: string;
  originalUrl: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mx-mobile-embed">
      <div className="mx-mobile-embed-frame">
        <iframe
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
  const src = buildBilibiliEmbedUrl(media, isLegacyAndroid());

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
      移动端使用哔哩哔哩官方嵌入播放器；插件内时间戳控制和截图暂不可用。
    </MobileEmbedPlayer>
  );
}

export function MobileYouTubePlayer({ media }: { media: MediaURL }) {
  const src = buildYouTubeEmbedUrl(media);
  if (!src) {
    return <div className="mx-mobile-embed-error">无法从这个 YouTube 链接提取视频 ID。</div>;
  }

  return (
    <MobileEmbedPlayer
      src={src}
      title="YouTube 播放器"
      originalUrl={media.cleaned.href}
    >
      移动端使用 YouTube 官方嵌入播放器；需要设备网络能够访问 YouTube，插件内截图暂不可用。
    </MobileEmbedPlayer>
  );
}

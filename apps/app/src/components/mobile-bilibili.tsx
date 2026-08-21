import type { MediaURL } from "@/info/media-url";
import { buildBilibiliEmbedUrl } from "@/web/url-match/bilibili-embed";

export function MobileBilibiliPlayer({ media }: { media: MediaURL }) {
  const src = buildBilibiliEmbedUrl(media);

  if (!src) {
    return (
      <div className="mx-mobile-bilibili-error">
        无法从这个哔哩哔哩链接提取视频 ID，请使用 BV、av、ep 或 ss 完整链接。
      </div>
    );
  }

  return (
    <div className="mx-mobile-bilibili">
      <iframe
        src={src}
        title="哔哩哔哩播放器"
        allow="autoplay; fullscreen; picture-in-picture"
        allowFullScreen
        referrerPolicy="no-referrer-when-downgrade"
      />
      <p>移动端使用哔哩哔哩官方嵌入播放器；插件内时间戳控制和截图暂不可用。</p>
    </div>
  );
}

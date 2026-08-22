import assert from "node:assert/strict";
import {
  calculateScreenshotCrop,
  parseScreenshotTime,
} from "../apps/app/src/lib/imported-screenshot.ts";
import { buildBilibiliEmbedUrl } from "../apps/app/src/web/url-match/bilibili-embed.ts";
import { buildYouTubeEmbedUrl } from "../apps/app/src/web/url-match/youtube-embed.ts";

function embed(path, search = "", start = null) {
  const href = buildBilibiliEmbedUrl({
    cleaned: new URL(`https://www.bilibili.com${path}${search}`),
    tempFrag: start === null ? null : { start },
  });
  return href ? new URL(href) : null;
}

assert.equal(embed("/video/BV1xx411c7mD")?.searchParams.get("bvid"), "BV1xx411c7mD");
assert.equal(embed("/video/av170001")?.searchParams.get("aid"), "170001");
assert.equal(embed("/bangumi/play/ep1")?.searchParams.get("ep_id"), "1");
assert.equal(embed("/bangumi/play/ss2")?.searchParams.get("season_id"), "2");
assert.equal(embed("/video/BV1xx411c7mD", "?p=3")?.searchParams.get("page"), "3");
assert.equal(embed("/video/BV1xx411c7mD", "?p=3")?.searchParams.get("p"), "3");
assert.equal(embed("/video/BV1xx411c7mD", "", 12.9)?.searchParams.get("t"), "12");
assert.equal(embed("/not-a-video"), null);

assert.equal(
  new URL(
    buildBilibiliEmbedUrl(
      {
        cleaned: new URL("https://www.bilibili.com/video/BV1xx411c7mD"),
        tempFrag: null,
      },
      true,
    ),
  ).pathname,
  "/blackboard/html5mobileplayer.html",
);

const youtube = new URL(
  buildYouTubeEmbedUrl({
    cleaned: new URL("https://www.youtube.com/watch?v=0zM3nApSvMg&list=PL123"),
    id: "0zM3nApSvMg",
    tempFrag: { start: 12.9, end: 45.8 },
  }),
);
assert.equal(youtube.hostname, "www.youtube-nocookie.com");
assert.equal(youtube.pathname, "/embed/0zM3nApSvMg");
assert.equal(youtube.searchParams.get("list"), "PL123");
assert.equal(youtube.searchParams.get("start"), "12");
assert.equal(youtube.searchParams.get("end"), "45");
assert.equal(youtube.searchParams.get("playsinline"), "1");
assert.equal(youtube.searchParams.get("enablejsapi"), "1");

assert.equal(parseScreenshotTime(""), 0);
assert.equal(parseScreenshotTime("83"), 83);
assert.equal(parseScreenshotTime("1:23"), 83);
assert.equal(parseScreenshotTime("1:02:03"), 3723);
assert.equal(parseScreenshotTime("1:70"), null);
assert.equal(parseScreenshotTime("invalid"), null);

assert.deepEqual(
  calculateScreenshotCrop(
    {
      left: 10,
      top: 20,
      width: 100,
      height: 50,
      viewportWidth: 200,
      viewportHeight: 400,
    },
    1000,
    2000,
  ),
  { x: 50, y: 100, width: 500, height: 250 },
);

console.log("Mobile embed URL and imported screenshot tests passed");

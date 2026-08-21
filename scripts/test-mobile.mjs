import assert from "node:assert/strict";
import { buildBilibiliEmbedUrl } from "../apps/app/src/web/url-match/bilibili-embed.ts";

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
assert.equal(embed("/video/BV1xx411c7mD", "", 12.9)?.searchParams.get("t"), "12");
assert.equal(embed("/not-a-video"), null);

console.log("Mobile Bilibili URL tests passed");

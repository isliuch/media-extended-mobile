import { Notice, Platform, requestUrl, type ItemView } from "obsidian";
import { isFileMediaInfo } from "@/info/media-info";
import { getMostRecentEditorLeaf } from "@/media-note/active-editor";
import type { PlayerComponent } from "./base";
import { getMobilePlaybackTime } from "./mobile-playback-time";
import {
  cropMobileScreenshot,
  getMobileScreenshotCropTarget,
  promptMobileScreenshotTime,
  saveMobileScreenshot,
} from "./mobile-screenshot";

const HELPER_ORIGIN = "http://127.0.0.1:47831";
const HELPER_SCHEME = "mxextendedcapture://authorize";
const HELPER_ACCESSIBILITY_SCHEME = "mxextendedcapture://accessibility";
const sessionToken = createSessionToken();

function createSessionToken() {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}

function helperUrl(path: string, params?: Record<string, string>) {
  const query = new URLSearchParams({ token: sessionToken, ...params });
  return `${HELPER_ORIGIN}${path}?${query}`;
}

async function helperReady() {
  try {
    const response = await requestUrl({
      url: helperUrl("/status"),
      method: "GET",
      throw: false,
    });
    return response.status === 200 && response.json?.ready === true;
  } catch {
    return false;
  }
}

function openHelperLink(scheme: string) {
  const link = document.body.createEl("a", {
    attr: {
      href: `${scheme}?token=${sessionToken}`,
      rel: "noopener",
    },
  });
  link.style.display = "none";
  link.click();
  window.setTimeout(() => link.remove(), 1_000);
}

function openHelperAuthorization() {
  openHelperLink(HELPER_SCHEME);
}

function openHelperAccessibilitySettings() {
  openHelperLink(HELPER_ACCESSIBILITY_SCHEME);
}

async function waitForHelper(timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (document.visibilityState === "visible" && (await helperReady())) {
      return true;
    }
    await new Promise((resolve) => window.setTimeout(resolve, 500));
  }
  return false;
}

async function ensureHelper() {
  if (await helperReady()) return;
  new Notice("请允许截图助手运行；授权会持续到通知栏中停止会话");
  openHelperAuthorization();
  if (!(await waitForHelper())) {
    throw new Error("未检测到截图助手，请先安装辅助 APK 并允许录屏");
  }
  // Let Obsidian and the video surface finish drawing after returning.
  await new Promise((resolve) => window.setTimeout(resolve, 700));
}

async function requestScreenPng(
  target: ReturnType<typeof getMobileScreenshotCropTarget>,
  recognizeTime: boolean,
) {
  if (!target) throw new Error("没有找到可裁剪的移动播放器");
  const response = await requestUrl({
    url: helperUrl("/capture", {
      recognizeTime: recognizeTime ? "1" : "0",
      left: String(target.left),
      top: String(target.top),
      width: String(target.width),
      height: String(target.height),
      viewportWidth: String(target.viewportWidth),
      viewportHeight: String(target.viewportHeight),
    }),
    method: "GET",
    throw: false,
  });
  if (response.status !== 200) {
    throw new Error(`截图助手返回 HTTP ${response.status}`);
  }
  const contentType = response.headers["content-type"] ?? "image/png";
  const detected = Object.entries(response.headers).find(
    ([name]) => name.toLowerCase() === "x-media-time",
  )?.[1];
  const automation = Object.entries(response.headers).find(
    ([name]) => name.toLowerCase() === "x-media-automation",
  )?.[1];
  const detectedTime = detected === undefined ? null : Number(detected);
  return {
    image: new Blob([response.arrayBuffer], { type: contentType }),
    detectedTime:
      detectedTime !== null &&
      Number.isFinite(detectedTime) &&
      detectedTime >= 0
        ? detectedTime
        : null,
    automation,
  };
}

async function requestSourceFrame(pageUrl: string, time: number) {
  const response = await requestUrl({
    url: helperUrl("/source-frame", {
      url: pageUrl,
      time: String(time),
    }),
    method: "GET",
    throw: false,
  });
  if (response.status !== 200) {
    const detail = response.json?.message;
    throw new Error(
      typeof detail === "string"
        ? detail
        : `截图助手返回 HTTP ${response.status}`,
    );
  }
  const contentType = response.headers["content-type"] ?? "image/png";
  return new Blob([response.arrayBuffer], { type: contentType });
}

function needsAccessibility(
  capture: Awaited<ReturnType<typeof requestScreenPng>>,
) {
  if (capture.automation !== "accessibility-required") return false;
  new Notice(
    "请启用“Media Extended 自动显示视频进度”无障碍服务；返回 Obsidian 后再次截图即可全自动完成",
    10_000,
  );
  openHelperAccessibilitySettings();
  return true;
}

export async function captureWithAndroidHelper(
  view: PlayerComponent & ItemView,
) {
  if (!Platform.isAndroidApp) return;
  const media = view.getMediaInfo();
  if (!media) {
    new Notice("当前没有打开媒体");
    return;
  }
  const target = getMobileScreenshotCropTarget(view.containerEl);
  if (!target) {
    new Notice("没有找到可裁剪的移动播放器");
    return;
  }
  const targetNote = getMostRecentEditorLeaf(view.app);
  if (!targetNote) {
    new Notice("请先打开并聚焦一个可编辑的笔记标签页");
    return;
  }

  try {
    await ensureHelper();

    const settings = view.plugin.settings.getState();
    const playerTime = getMobilePlaybackTime(view.containerEl);
    const capture = await requestScreenPng(target, playerTime === null);
    if (needsAccessibility(capture)) return;
    const time =
      playerTime ??
      capture.detectedTime ??
      (await promptMobileScreenshotTime(view.app));
    if (time === null) return;
    const screenshot = await cropMobileScreenshot(
      capture.image,
      target,
      time,
      settings.screenshotFormat,
      settings.screenshotQuality,
    );
    await saveMobileScreenshot(view, targetNote, screenshot);
  } catch (error) {
    console.error("Failed to capture through Android helper", error);
    new Notice(
      "辅助截图失败：" +
        (error instanceof Error ? error.message : String(error)),
    );
  }
}

export async function captureSourceFrameWithAndroidHelper(
  view: PlayerComponent & ItemView,
) {
  if (!Platform.isAndroidApp) return;
  const media = view.getMediaInfo();
  if (!media || isFileMediaInfo(media)) {
    new Notice("高清源视频帧目前仅支持 YouTube 和哔哩哔哩链接");
    return;
  }
  const target = getMobileScreenshotCropTarget(view.containerEl);
  if (!target) {
    new Notice("没有找到可裁剪的移动播放器");
    return;
  }
  const targetNote = getMostRecentEditorLeaf(view.app);
  if (!targetNote) {
    new Notice("请先打开并聚焦一个可编辑的笔记标签页");
    return;
  }

  let fallbackCapture: Awaited<ReturnType<typeof requestScreenPng>> | null =
    null;
  try {
    await ensureHelper();
    const playerTime = getMobilePlaybackTime(view.containerEl);
    if (playerTime === null) {
      fallbackCapture = await requestScreenPng(target, true);
      if (needsAccessibility(fallbackCapture)) return;
    }
    const time =
      playerTime ??
      fallbackCapture?.detectedTime ??
      (await promptMobileScreenshotTime(view.app));
    if (time === null) return;

    new Notice("正在解析视频源并提取高清帧，首次使用可能需要更长时间…", 8_000);
    try {
      const frame = await requestSourceFrame(media.jsonState.source, time);
      await saveMobileScreenshot(view, targetNote, {
        time,
        blob: { arrayBuffer: await frame.arrayBuffer(), type: frame.type },
      });
      return;
    } catch (sourceError) {
      console.error("Failed to extract source video frame", sourceError);
      const detail =
        sourceError instanceof Error
          ? sourceError.message
          : String(sourceError);
      const settings = view.plugin.settings.getState();
      if (!settings.sourceFrameFallbackToScreenCapture) {
        new Notice(
          `高清源帧提取失败：${detail}。可自行点击“辅助 APK 一键截图”`,
          10_000,
        );
        return;
      }
      new Notice(
        "高清源帧提取失败，将按设置自动使用屏幕截图：" + detail,
        10_000,
      );
    }

    const capture = fallbackCapture ?? (await requestScreenPng(target, false));
    const settings = view.plugin.settings.getState();
    const screenshot = await cropMobileScreenshot(
      capture.image,
      target,
      time,
      settings.screenshotFormat,
      settings.screenshotQuality,
    );
    await saveMobileScreenshot(view, targetNote, screenshot);
  } catch (error) {
    console.error("Failed to capture source video frame", error);
    new Notice(
      "高清源帧截图失败：" +
        (error instanceof Error ? error.message : String(error)),
      10_000,
    );
  }
}

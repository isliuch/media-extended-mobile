import { Notice, Platform, requestUrl, type ItemView } from "obsidian";
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

function openHelperAuthorization() {
  const link = document.body.createEl("a", {
    attr: {
      href: `${HELPER_SCHEME}?token=${sessionToken}`,
      rel: "noopener",
    },
  });
  link.style.display = "none";
  link.click();
  window.setTimeout(() => link.remove(), 1_000);
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
  const detectedTime = detected === undefined ? null : Number(detected);
  return {
    image: new Blob([response.arrayBuffer], { type: contentType }),
    detectedTime:
      detectedTime !== null &&
      Number.isFinite(detectedTime) &&
      detectedTime >= 0
        ? detectedTime
        : null,
  };
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
    if (!(await helperReady())) {
      new Notice("请允许截图助手录制屏幕；授权会持续到通知栏中停止会话");
      openHelperAuthorization();
      if (!(await waitForHelper())) {
        throw new Error("未检测到截图助手，请先安装辅助 APK 并允许录屏");
      }
      // Let Obsidian and the video surface finish drawing after returning.
      await new Promise((resolve) => window.setTimeout(resolve, 700));
    }

    const settings = view.plugin.settings.getState();
    const playerTime = getMobilePlaybackTime(view.containerEl);
    const capture = await requestScreenPng(target, playerTime === null);
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

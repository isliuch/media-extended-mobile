import { Modal, Notice, Platform, type App, type ItemView } from "obsidian";
import { isFileMediaInfo } from "@/info/media-info";
import {
  calculateScreenshotCrop,
  parseScreenshotTime,
  type ScreenshotCropTarget,
} from "@/lib/imported-screenshot";
import type { ScreenshotInfo } from "@/lib/screenshot";
import { getMostRecentEditorLeaf } from "@/media-note/active-editor";
import { saveScreenshotInfo } from "@/media-note/timestamp/screenshot";
import type { PlayerComponent } from "./base";

interface ScreenshotSelection {
  file: File;
  time: number;
}

class MobileScreenshotImportModal extends Modal {
  static run(app: App, defaultTime = ""): Promise<ScreenshotSelection | null> {
    return new Promise((resolve) => {
      const modal = new MobileScreenshotImportModal(app, defaultTime, resolve);
      modal.open();
    });
  }

  private resolved = false;

  constructor(
    app: App,
    private defaultTime: string,
    private resolve: (selection: ScreenshotSelection | null) => void,
  ) {
    super(app);
  }

  onOpen() {
    this.titleEl.setText(
      Platform.isIosApp ? "导入 iPad 系统截图" : "导入 Android 系统截图",
    );
    this.contentEl.createEl("p", {
      text: "请先暂停视频并完成系统截图，再选择刚刚保存的截图。插件会自动裁剪播放器区域并插入媒体笔记。",
    });

    const form = this.contentEl.createEl("form");
    const timeLabel = form.createEl("label", { text: "当前视频时间（可选）" });
    const timeInput = timeLabel.createEl("input", {
      type: "text",
      placeholder: "例如 1:23 或 01:02:03",
      value: this.defaultTime,
      attr: { name: "screenshot-time", inputmode: "decimal" },
    });
    timeInput.style.display = "block";
    timeInput.style.width = "100%";

    const fileLabel = form.createEl("label", { text: "系统截图" });
    const fileInput = fileLabel.createEl("input", {
      type: "file",
      attr: {
        name: "screenshot-file",
        accept: "image/*",
        required: true,
      },
    });
    fileInput.style.display = "block";
    fileInput.style.width = "100%";

    const autoImportLabel = form.createEl("label");
    autoImportLabel.style.display = "block";
    const autoImportInput = autoImportLabel.createEl("input", {
      type: "checkbox",
      attr: { name: "auto-import" },
    });
    autoImportInput.checked = true;
    autoImportLabel.appendText(" 选择图片后自动导入");

    const submitButton = form.createEl("button", {
      text: "导入并裁剪",
      attr: { type: "submit" },
    });

    const submitSelection = () => {
      const file = fileInput.files?.[0];
      if (!file) {
        new Notice("请选择刚刚生成的系统截图");
        return;
      }
      const time = parseScreenshotTime(timeInput.value);
      if (time === null) {
        new Notice("视频时间格式无效，请使用秒数、mm:ss 或 hh:mm:ss");
        return;
      }
      this.resolved = true;
      this.resolve({ file, time });
      this.close();
    };

    fileInput.onchange = () => {
      if (autoImportInput.checked) submitSelection();
    };
    autoImportInput.onchange = () => {
      submitButton.style.display = autoImportInput.checked ? "none" : "";
    };
    submitButton.style.display = "none";
    form.onsubmit = (event) => {
      event.preventDefault();
      submitSelection();
    };
  }

  onClose() {
    this.contentEl.empty();
    if (!this.resolved) this.resolve(null);
  }
}

function getCropTarget(containerEl: HTMLElement): ScreenshotCropTarget | null {
  const frame = Array.from(
    containerEl.querySelectorAll<HTMLIFrameElement>(
      ".mx-mobile-embed-frame iframe, iframe",
    ),
  ).find((candidate) => {
    const rect = candidate.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  });
  if (!frame) return null;

  const rect = frame.getBoundingClientRect();
  return {
    left: rect.left,
    top: rect.top,
    width: rect.width,
    height: rect.height,
    viewportWidth: window.innerWidth,
    viewportHeight: window.innerHeight,
  };
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("无法读取所选截图"));
    };
    image.src = url;
  });
}

async function cropScreenshot(
  file: File,
  target: ScreenshotCropTarget,
  time: number,
  requestedType: "image/jpeg" | "image/webp" | "image/png",
  quality: number | undefined,
): Promise<ScreenshotInfo> {
  const image = await loadImage(file);
  const crop = calculateScreenshotCrop(
    target,
    image.naturalWidth,
    image.naturalHeight,
  );
  if (!crop) throw new Error("无法计算播放器在截图中的位置");

  const canvas = document.createElement("canvas");
  canvas.width = crop.width;
  canvas.height = crop.height;
  const context = canvas.getContext("2d", { alpha: false });
  if (!context) throw new Error("无法创建截图画布");
  context.drawImage(
    image,
    crop.x,
    crop.y,
    crop.width,
    crop.height,
    0,
    0,
    crop.width,
    crop.height,
  );

  const type =
    Platform.isSafari && requestedType === "image/webp"
      ? "image/jpeg"
      : requestedType;
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (result) =>
        result ? resolve(result) : reject(new Error("无法生成裁剪后的截图")),
      type,
      quality,
    );
  });

  return {
    time,
    blob: { arrayBuffer: await blob.arrayBuffer(), type: blob.type },
  };
}

export async function importMobileSystemScreenshot(
  view: PlayerComponent & ItemView,
) {
  const media = view.getMediaInfo();
  if (!media) {
    new Notice("当前没有打开媒体");
    return;
  }
  const target = getCropTarget(view.containerEl);
  if (!target) {
    new Notice("没有找到可裁剪的移动播放器");
    return;
  }
  const targetNote = getMostRecentEditorLeaf(view.app);
  if (!targetNote) {
    new Notice("请先打开并聚焦一个可编辑的笔记标签页");
    return;
  }

  const initialTime = isFileMediaInfo(media) ? undefined : media.tempFrag?.start;
  const selection = await MobileScreenshotImportModal.run(
    view.app,
    initialTime && initialTime > 0 ? String(Math.floor(initialTime)) : "",
  );
  if (!selection) return;

  try {
    const settings = view.plugin.settings.getState();
    const screenshot = await cropScreenshot(
      selection.file,
      target,
      selection.time,
      settings.screenshotFormat,
      settings.screenshotQuality,
    );
    const previousActiveDocument = window.activeDocument;
    window.activeDocument = targetNote.containerEl.doc;
    try {
      await saveScreenshotInfo(
        view,
        { file: targetNote.view.file, editor: targetNote.view.editor },
        screenshot,
      );
      new Notice(`截图已插入“${targetNote.view.file.basename}”`);
    } finally {
      window.activeDocument = previousActiveDocument;
    }
  } catch (error) {
    console.error("Failed to import mobile system screenshot", error);
    new Notice(
      "导入系统截图失败：" +
        (error instanceof Error ? error.message : String(error)),
    );
  }
}

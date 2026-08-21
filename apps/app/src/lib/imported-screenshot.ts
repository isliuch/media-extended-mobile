export interface ScreenshotCropTarget {
  left: number;
  top: number;
  width: number;
  height: number;
  viewportWidth: number;
  viewportHeight: number;
}

export interface ScreenshotCropRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export function parseScreenshotTime(input: string): number | null {
  const value = input.trim();
  if (!value) return 0;

  const parts = value.split(":");
  if (parts.length > 3 || parts.some((part) => !/^\d+(?:\.\d+)?$/.test(part))) {
    return null;
  }

  const numbers = parts.map(Number);
  if (numbers.some((part) => !Number.isFinite(part))) return null;
  if (numbers.length > 1 && numbers.slice(1).some((part) => part >= 60)) {
    return null;
  }

  return numbers.reduce((total, part) => total * 60 + part, 0);
}

export function calculateScreenshotCrop(
  target: ScreenshotCropTarget,
  imageWidth: number,
  imageHeight: number,
): ScreenshotCropRect | null {
  if (
    imageWidth <= 0 ||
    imageHeight <= 0 ||
    target.viewportWidth <= 0 ||
    target.viewportHeight <= 0 ||
    target.width <= 0 ||
    target.height <= 0
  ) {
    return null;
  }

  const scaleX = imageWidth / target.viewportWidth;
  const scaleY = imageHeight / target.viewportHeight;
  const left = Math.round(target.left * scaleX);
  const top = Math.round(target.top * scaleY);
  const right = Math.round((target.left + target.width) * scaleX);
  const bottom = Math.round((target.top + target.height) * scaleY);

  const x = Math.max(0, Math.min(imageWidth, left));
  const y = Math.max(0, Math.min(imageHeight, top));
  const width = Math.max(0, Math.min(imageWidth, right) - x);
  const height = Math.max(0, Math.min(imageHeight, bottom) - y);
  if (!width || !height) return null;

  return { x, y, width, height };
}

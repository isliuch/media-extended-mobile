type TimeReader = () => number | null;

const readers = new WeakMap<HTMLIFrameElement, TimeReader>();

export function registerMobilePlaybackTimeReader(
  frame: HTMLIFrameElement,
  reader: TimeReader,
) {
  readers.set(frame, reader);
  return () => readers.delete(frame);
}

export function getMobilePlaybackTime(containerEl: HTMLElement) {
  const frame = Array.from(
    containerEl.querySelectorAll<HTMLIFrameElement>(
      ".mx-mobile-embed-frame iframe, iframe",
    ),
  ).find((candidate) => {
    const rect = candidate.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  });
  if (!frame) return null;

  const time = readers.get(frame)?.();
  return typeof time === "number" && Number.isFinite(time) && time >= 0
    ? time
    : null;
}

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [3.3.0-mobile.12] - 2026-08-25

### Added

- Added an Android setting controlling whether a failed or timed-out high-quality source-frame capture automatically falls back to a screen capture. It is disabled by default so users can choose the separate screen-capture action themselves.

### Fixed

- Fixed the Android “高清源视频帧” action not appearing because toolbar actions were registered before the remote media URL had loaded. The action is now registered independently of source loading and validates YouTube/Bilibili support when clicked.
- Fixed repeated source-frame timeouts by replacing the permanently shared extraction queue with an isolated task per request and cleaning up orphaned yt-dlp/QuickJS processes after cancellation.
- Replaced remote `MediaMetadataRetriever` seeking with a bounded FFmpeg process that performs fast input seeking and writes the requested source frame directly as PNG. Source resolution and frame extraction now have separate timeout messages.

### Changed

- The companion checks for a stable yt-dlp update at most once per day before resolving a source, while retaining the bundled copy when the update service is unavailable.
- Increased the companion APK version to 1.0.5 (`versionCode` 6) and bundled FFmpeg 0.18.1. The universal APK is now about 215 MB because it includes FFmpeg for all supported Android ABIs.

## [3.3.0-mobile.11] - 2026-08-25

### Added

- Added a separate Android “高清源视频帧” action for YouTube and Bilibili. The companion resolves an H.264/MP4 source stream with yt-dlp and decodes the requested frame as a lossless PNG instead of saving the displayed screen pixels.
- YouTube uses its live IFrame playback time; Bilibili reuses the existing on-device control-bar OCR before requesting the source frame.
- Source extraction is restricted to YouTube/Bilibili hosts and has a 75-second hard timeout.

### Changed

- Increased the companion APK version to 1.0.4 (`versionCode` 5).
- The companion APK now bundles Python 3.12, yt-dlp and QuickJS and is therefore substantially larger (about 80 MB as a universal debug APK).

## [3.3.0-mobile.10] - 2026-08-23

### Added

- Added an opt-in Android accessibility service that automatically taps the visible Obsidian video area when the first capture contains no readable playback time.
- The capture helper now takes a second screenshot after revealing the player controls and uses that image and its recognized current time for insertion.
- When the accessibility service is not enabled, the plugin opens the Android accessibility settings with setup guidance; the service only accepts gestures while Obsidian is the foreground app and after an authenticated loopback capture request.

### Changed

- Increased the companion APK version to 1.0.3 (`versionCode` 4).

## [3.3.0-mobile.9] - 2026-08-23

### Added

- YouTube mobile screenshots now use the official IFrame Player API to record the current playback time.
- Android capture-helper screenshots can read the visible Bilibili/YouTube control-bar time with an on-device bundled text recognizer; no Google Play services or network request is required for recognition.
- When neither player API nor screenshot recognition can provide a reliable time, the plugin asks for `mm:ss`/`hh:mm:ss` input or allows importing at `00:00`.

### Changed

- System-screenshot importing pre-fills the live YouTube playback time when available instead of reusing the media link's original start fragment.
- Increased the companion APK version to 1.0.2 (`versionCode` 3).

## [3.3.0-mobile.8] - 2026-08-22

### Fixed

- Fixed the Android capture companion's loopback server on Android 8.1 by replacing an API 33-only URL decoder overload with the API 1-compatible overload.
- Increased the companion APK version to 1.0.1 (`versionCode` 2) so existing installations can be upgraded normally.

## [3.3.0-mobile.7] - 2026-08-22

### Fixed

- Android BV/av videos now use Bilibili's official mobile HTML5 player on every supported Android version, avoiding cases where the desktop embed rendered its controls but stayed at `00:00 / 00:00`.
- Added both current `p` and legacy `page` parameters for multi-part Bilibili videos.

## [3.3.0-mobile.6] - 2026-08-22

### Added

- Added an optional Android 8.0+ companion APK using the official MediaProjection API for one-tap screen capture.
- Added a separate “辅助 APK 一键截图” action without replacing the existing screenshot and system-image import actions.
- The companion keeps one authorized foreground capture session for repeated screenshots, returns each fresh full-screen PNG over a token-protected loopback-only endpoint, and lets the plugin crop and insert it into the previously focused editable note.

### Security

- The capture endpoint binds only to `127.0.0.1` and requires a new random session token after every plugin load.
- Full-screen captures remain in memory and are not written by the companion APK.

## [3.3.0-mobile.5] - 2026-08-22

### Fixed

- Each imported system screenshot now uses a new filename instead of overwriting the first `00:00` image, preventing Obsidian's image cache from showing the first import again.
- Replaced the browser-default file-input label with a clear image-selection button and Android guidance for systems whose WebView opens Documents instead of the system photo picker.

## [3.3.0-mobile.4] - 2026-08-22

### Added

- Extended system-screenshot importing and automatic player-area cropping to Android.
- Added an enabled-by-default “import automatically after selecting an image” option. With it enabled, selecting the screenshot immediately starts cropping, saving, and inserting it without another button press.

### Changed

- Imported screenshots are inserted into the most recently focused editable Markdown tab. The import action no longer creates or opens a media note.

## [3.3.0-mobile.3] - 2026-08-22

### Added

- Added a separate iOS action for importing a system screenshot, automatically cropping it to the visible YouTube or Bilibili player, and inserting it into the media note. The existing player screenshot action remains unchanged.

## [3.3.0-mobile.2] - 2026-08-22

### Added

- Added a dedicated YouTube iframe player for Obsidian Mobile.

### Fixed

- Kept mobile iframe players at an inline 16:9 size instead of filling the entire media pane.
- Added an Android 8 and older fallback to Bilibili's legacy official mobile player for regular BV/av videos.
- Added `playsinline` parameters to reduce forced fullscreen playback on mobile WebViews.

## [3.3.0-mobile.1] - 2026-08-21

### Added

- Rebased the maintainable mobile fork on the upstream MIT-licensed v3.2.6 source.
- Added basic Bilibili playback on Obsidian Mobile through the official iframe player, including BV/av/ep/ss links, multi-part page selection, and link start time.
- Added a reproducible BRAT release workflow and generated release assets.

### Changed

- Renamed the plugin ID to `media-extended-mobile` and the display name to Media Extended Mobile so it can coexist with the original plugin.
- Replaced top-level Node `path` and `url` dependencies with mobile-safe implementations.
- Disabled the Electron-only login command on mobile.
- Marked the plugin as mobile-compatible in every release manifest.

### Fixed

- Build the vendored `@codemirror/language` package before type-checking or bundling, so releases succeed in a clean GitHub Actions environment.

### Known limitations

- Cross-origin YouTube and Bilibili iframe frames cannot be captured by an Obsidian Mobile plugin without a supported native screenshot bridge.
- The mobile Bilibili iframe does not yet expose playback state to Media Extended timestamp commands.

## [4.2.7] - 2026-06-07

### Fixed

- Restored compatibility with Obsidian v1.13.
- Embedded videos from browser-only hosts (e.g. Bilibili) now show a placeholder with an "Open in web viewer" or "Configure" button instead of an empty player with no media loaded.
- Broken-image icon no longer appears next to media embeds written with markdown image syntax (`![](url)`) in Live Preview on newer Obsidian versions, including inside popout windows.
- Embedded Bilibili videos now enter Bilibili's web fullscreen mode once the player opens, instead of leaving the video confined to its small default frame.
- After plugin updates, the main daemon setup popup now clearly identifies Media Extended and explains that the daemon module is being upgraded instead of presenting it as a fresh install.

See [changelog](https://mx.aidenlx.site/changelog/v4.2.6).

## [4.2.5] - 2026-05-19

### Fixed

- Restored compatibility with other plugins that patch `openLinkText` asynchronously (e.g. Recipe Grabber) — their async work was being dropped when Media Extended's link handler fell through to the default handler.

See [changelog](https://mx.aidenlx.site/changelog/v4.2.5).

## [4.2.4] - 2026-05-13

### Added

- Copy timestamp commands — copy the current playback time to your clipboard as plain text, URL, Obsidian URL, library URL, rich text link, or markdown link, each in multiple URL flavors. Available from the player menu ("Copy timestamp as" submenu) and as standalone command palette entries. Works with web sources, vault files, and local files outside the vault (desktop only for `file://` paths).
- "Open media note" item in the player embed context menu to jump to or create the media's note directly from the player.
- Extended `obsidian://open` with `?t=` and `?hash=` parameters — external links to media files in your vault now open at a specific timestamp. Falls through to Obsidian's default handler for non-media files.
- Extended `obsidian://mx-open` with `?id=`, `?vault=`, `?paneType=`, `?t=`, and `?hash=` parameters — resolve media by library ID, target a specific vault, open in a new pane, and seek to a timestamp.
- Media notes are now named after their media title (from YouTube metadata, ID3 tags, or filename) instead of random IDs. Falls back to `url-{id}` when no title is available; appends a unique suffix on name collision.
- Frame-by-frame video navigation to step backward or forward one frame at a time at best effort. Available on both native videos and supported web players (YouTube, Bilibili, Vimeo, Coursera, Baidu, Google Drive). Support both one-shot commands and hold hotkey to keep stepping commands.

### Fixed

- Bilibili multi-part videos now show the correct page-specific title instead of the raw video title.

See [changelog](https://mx.aidenlx.site/changelog/v4.2.4).

## [4.2.3] - 2026-05-09

See [changelog](https://mx.aidenlx.site/changelog/v4.2.3).

## [4.2.2] - 2026-05-07

See [changelog](https://mx.aidenlx.site/changelog/v4.2.2).

## [4.2.1] - 2026-05-06

See [changelog](https://mx.aidenlx.site/changelog/v4.2.1).

## [4.2.0] - 2026-04-25

See [changelog](https://mx.aidenlx.site/changelog/v4.2.0).

## [4.1.5] - 2025-12-02

See [changelog](https://mx.aidenlx.site/changelog/v4.1.5).

## [4.1.4] - 2025-11-15

See [changelog](https://mx.aidenlx.site/changelog/v4.1.4).

## [4.1.2] - 2025-10-10

See [changelog](https://mx.aidenlx.site/changelog/v4.1.2).

## [4.1.1] - 2025-10-08

See [changelog](https://mx.aidenlx.site/changelog/v4.1.1).

## [4.1.0] - 2025-10-03

See [changelog](https://mx.aidenlx.site/changelog/v4.1.0).

## [4.0.1] - 2025-09-23

See [changelog](https://mx.aidenlx.site/changelog/v4.0.1).

## [4.0.0] - 2025-09-17

See [changelog](https://mx.aidenlx.site/changelog/v4.0.0).

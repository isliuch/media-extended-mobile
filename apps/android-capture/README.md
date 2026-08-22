# Media Extended 截图助手（Android）

这是 `media-extended-mobile` 的可选 Android 辅助 APK。它通过 Android 官方 `MediaProjection` API 获取当前屏幕，由 Obsidian 插件裁剪播放器区域并插入此前聚焦的笔记。

## 使用

1. 从对应 GitHub Release 下载并安装 `media-extended-capture.apk`。支持 Android 8.0（API 26）及以上。
2. 在 Obsidian 中先打开并聚焦要插图的编辑模式笔记标签页，再切回 YouTube 或哔哩哔哩媒体页。
3. 点击“辅助 APK 一键截图”。首次使用或录屏会话结束后，Android 会要求确认录制屏幕；确认后会自动回到 Obsidian 并完成截图、裁剪和插入。
4. 如果哔哩哔哩控制条常常隐藏，按插件提示在 Android 无障碍设置中启用“Media Extended 自动显示视频进度”。启用一次后，首张截图无时间时会自动点击视频并使用第二张带控制条的截图。
4. 同一录屏会话中的后续截图无需再次授权。可从系统通知或系统的活动应用界面停止会话。

原来的“导入并裁剪系统截图”按钮仍然保留，可在未安装 APK、设备不兼容或视频禁止录屏时作为备用。

## 隐私与限制

- 服务只监听设备回环地址 `127.0.0.1:47831`，每次插件加载都会生成新的随机令牌；局域网中的其他设备无法访问该接口。
- Android 会持续显示录屏会话状态。辅助程序不保存整屏图片，PNG 只在内存中传给 Obsidian。
- 1.0.2 起使用随 APK 打包的本地文字识别模型读取播放器控制条时间，不依赖 Google Play 服务，不会上传截图。YouTube 优先使用官方播放器 API；识别失败时由插件请求手动输入时间。
- 1.0.3 的无障碍服务只监听 Obsidian，仅在带有当前随机会话令牌的本机截图请首次识别失败时，在播放器上方安全区执行一次点击；不会读取或操作其他应用。
- 受 DRM 或 `FLAG_SECURE` 保护的画面可能是黑色，这是 Android 的系统限制。
- Android 14 及以上在每个新 MediaProjection 会话开始时都会显示系统授权；一次授权会话内可以连续截图。

## 本地构建

需要 JDK 17 和 Android SDK 36：

```bash
cd apps/android-capture
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

发布版必须使用固定密钥签名。GitHub Actions 使用以下仓库 Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

未配置这些 Secrets 时，插件 Release 仍可生成，但不会包含辅助 APK。

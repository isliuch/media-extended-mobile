package com.mediaextended.mobile.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Resolves a source stream with yt-dlp and extracts one lossless frame with FFmpeg. */
final class SourceFrameExtractor {
    private static final String TAG = "SourceFrameExtractor";
    private static final int RESOLVE_TIMEOUT_SECONDS = 50;
    private static final int FRAME_TIMEOUT_SECONDS = 30;
    private static final long UPDATE_INTERVAL_MS = TimeUnit.DAYS.toMillis(1);
    private static final String PREFS_NAME = "source-frame-extractor";
    private static final String PREF_LAST_UPDATE_ATTEMPT = "last-ytdlp-update-attempt";
    private static final String PREF_YTDLP_TAG = "installed-ytdlp-tag";
    private static final String YTDLP_RELEASE_API =
        "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest";
    private static final String FORMAT =
        "bestvideo[vcodec^=avc1][ext=mp4]/"
            + "bestvideo[vcodec*=avc][ext=mp4]/"
            + "bestvideo[ext=mp4]/bestvideo/best[ext=mp4]/best";

    private final Context context;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private boolean initialized;
    private boolean closed;

    SourceFrameExtractor(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized byte[] capture(String pageUrl, double timeSeconds) throws Exception {
        validateRequest(pageUrl, timeSeconds);
        if (closed) throw new IllegalStateException("高清源帧服务已停止");

        YoutubeDLRequest request = new YoutubeDLRequest(pageUrl)
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", 12)
            .addOption("--retries", 1)
            .addOption("--extractor-retries", 1)
            .addOption("--format", FORMAT)
            .addOption("--dump-json");
        String processId = "source-frame-" + UUID.randomUUID();
        long startedAt = System.currentTimeMillis();
        VideoInfo info = resolve(request, processId);
        Log.i(TAG, "Resolved source in " + elapsed(startedAt) + " ms");

        startedAt = System.currentTimeMillis();
        byte[] frame = extractFrame(info, timeSeconds);
        Log.i(TAG, "Extracted source frame in " + elapsed(startedAt) + " ms");
        return frame;
    }

    private VideoInfo resolve(YoutubeDLRequest request, String processId) throws Exception {
        ExecutorService task = Executors.newSingleThreadExecutor();
        Future<VideoInfo> future = task.submit(() -> resolveBlocking(request, processId));
        try {
            return future.get(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            cancelYoutubeDl(processId);
            future.cancel(true);
            throw new IllegalStateException("解析视频源超时，请检查网络后重试", error);
        } catch (ExecutionException error) {
            cancelYoutubeDl(processId);
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("解析视频源失败", cause);
        } catch (InterruptedException error) {
            cancelYoutubeDl(processId);
            Thread.currentThread().interrupt();
            throw error;
        } finally {
            task.shutdownNow();
        }
    }

    private VideoInfo resolveBlocking(YoutubeDLRequest request, String processId) throws Exception {
        ensureInitialized();
        YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, processId, null);
        VideoInfo info = objectMapper.readValue(response.getOut(), VideoInfo.class);
        String streamUrl = info.getUrl();
        if (streamUrl == null || streamUrl.isEmpty()) {
            throw new IllegalStateException("没有取得可解码的视频流");
        }
        return info;
    }

    private synchronized void ensureInitialized() throws Exception {
        if (initialized) return;
        long startedAt = System.currentTimeMillis();
        YoutubeDL.getInstance().init(context);
        FFmpeg.getInstance().init(context);
        initialized = true;
        Log.i(TAG, "Initialized source-frame runtime in " + elapsed(startedAt) + " ms");
        maybeUpdateYoutubeDl();
    }

    private void maybeUpdateYoutubeDl() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastAttempt = prefs.getLong(PREF_LAST_UPDATE_ATTEMPT, 0L);
        if (now - lastAttempt < UPDATE_INTERVAL_MS) return;
        // Record before the network request so a failed update cannot delay every capture.
        prefs.edit().putLong(PREF_LAST_UPDATE_ATTEMPT, now).apply();
        try {
            long startedAt = System.currentTimeMillis();
            String installedTag = updateYoutubeDl(prefs);
            Log.i(
                TAG,
                "Checked yt-dlp update in " + elapsed(startedAt) + " ms; version="
                    + installedTag
            );
        } catch (Exception error) {
            // The bundled copy remains usable when GitHub is unavailable.
            Log.w(TAG, "Unable to update yt-dlp; using bundled copy", error);
        }
    }

    private String updateYoutubeDl(SharedPreferences prefs) throws Exception {
        byte[] releaseJson = download(YTDLP_RELEASE_API, 2 * 1024 * 1024, 7_000, 8_000);
        JsonNode release = objectMapper.readTree(releaseJson);
        String tag = release.path("tag_name").asText("");
        if (tag.isEmpty()) throw new IOException("yt-dlp 版本信息无效");
        if (tag.equals(prefs.getString(PREF_YTDLP_TAG, ""))) return tag;

        String downloadUrl = "";
        for (JsonNode asset : release.path("assets")) {
            if ("yt-dlp".equals(asset.path("name").asText())) {
                downloadUrl = asset.path("browser_download_url").asText("");
                break;
            }
        }
        if (!downloadUrl.startsWith("https://")) {
            throw new IOException("yt-dlp 下载地址无效");
        }
        byte[] binary = download(downloadUrl, 25 * 1024 * 1024, 7_000, 12_000);
        if (binary.length < 100_000) throw new IOException("yt-dlp 下载内容不完整");

        File directory = new File(context.getNoBackupFilesDir(), "youtubedl-android/yt-dlp");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建 yt-dlp 目录");
        }
        File target = new File(directory, "yt-dlp");
        File temporary = File.createTempFile("yt-dlp-", ".tmp", directory);
        File backup = new File(directory, "yt-dlp.backup");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(binary);
                output.getFD().sync();
            }
            if (backup.exists() && !backup.delete()) {
                throw new IOException("无法清理 yt-dlp 备份");
            }
            if (target.exists() && !target.renameTo(backup)) {
                throw new IOException("无法备份当前 yt-dlp");
            }
            if (!temporary.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target);
                throw new IOException("无法安装新版 yt-dlp");
            }
            if (backup.exists() && !backup.delete()) backup.deleteOnExit();
            prefs.edit().putString(PREF_YTDLP_TAG, tag).apply();
            return tag;
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    private static byte[] download(
        String url,
        int limit,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Media-Extended-Mobile-Capture");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("下载失败，HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return readBytes(input, limit);
            }
        } finally {
            connection.disconnect();
        }
    }

    private byte[] extractFrame(VideoInfo info, double timeSeconds) throws Exception {
        File output = File.createTempFile("source-frame-", ".png", context.getCacheDir());
        File log = File.createTempFile("source-frame-", ".log", context.getCacheDir());
        java.lang.Process ffmpeg = null;
        try {
            List<String> command = buildFfmpegCommand(
                ffmpegExecutable().getAbsolutePath(),
                info.getUrl(),
                info.getHttpHeaders(),
                timeSeconds,
                output.getAbsolutePath()
            );
            ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log);
            configureFfmpegEnvironment(builder.environment());
            ffmpeg = builder.start();
            if (!ffmpeg.waitFor(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                destroyForcibly(ffmpeg);
                throw new IllegalStateException("提取视频帧超时，请稍后重试");
            }
            if (ffmpeg.exitValue() != 0 || output.length() < 8) {
                String detail = sanitizeErrorDetail(readText(log, 4096));
                if (detail.isEmpty()) detail = "FFmpeg 未生成视频帧";
                throw new IllegalStateException("提取视频帧失败：" + detail);
            }
            byte[] png = readBytes(output, 40 * 1024 * 1024);
            if (!isPng(png)) throw new IllegalStateException("提取的视频帧不是有效 PNG");
            return png;
        } catch (InterruptedException error) {
            if (ffmpeg != null) destroyForcibly(ffmpeg);
            Thread.currentThread().interrupt();
            throw error;
        } finally {
            if (ffmpeg != null && ffmpeg.isAlive()) destroyForcibly(ffmpeg);
            if (!output.delete()) output.deleteOnExit();
            if (!log.delete()) log.deleteOnExit();
        }
    }

    static List<String> buildFfmpegCommand(
        String executable,
        String streamUrl,
        Map<String, String> headers,
        double timeSeconds,
        String outputPath
    ) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-ss");
        command.add(String.format(Locale.ROOT, "%.3f", Math.max(0d, timeSeconds)));
        String headerArgument = buildHeaderArgument(headers);
        if (!headerArgument.isEmpty()) {
            command.add("-headers");
            command.add(headerArgument);
        }
        command.add("-i");
        command.add(streamUrl);
        command.add("-map");
        command.add("0:v:0");
        command.add("-frames:v");
        command.add("1");
        command.add("-an");
        command.add("-sn");
        command.add("-c:v");
        command.add("png");
        command.add("-y");
        command.add(outputPath);
        return command;
    }

    static String buildHeaderArgument(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = sanitizeHeader(entry.getKey());
            String value = sanitizeHeader(entry.getValue());
            if (name.isEmpty() || value.isEmpty()) continue;
            result.append(name).append(": ").append(value).append("\r\n");
        }
        return result.toString();
    }

    private File ffmpegExecutable() {
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
        if (!executable.isFile()) throw new IllegalStateException("未找到 FFmpeg 运行时");
        return executable;
    }

    private void configureFfmpegEnvironment(Map<String, String> environment) {
        File packages = new File(context.getNoBackupFilesDir(), "youtubedl-android/packages");
        String libraryPath = new File(packages, "python/usr/lib").getAbsolutePath()
            + ":" + new File(packages, "ffmpeg/usr/lib").getAbsolutePath();
        environment.put("LD_LIBRARY_PATH", libraryPath);
        environment.put(
            "SSL_CERT_FILE",
            new File(packages, "python/usr/etc/tls/cert.pem").getAbsolutePath()
        );
        environment.put("TMPDIR", context.getCacheDir().getAbsolutePath());
    }

    private void cancelYoutubeDl(String processId) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to stop yt-dlp parent process", error);
        }
        // Version 0.18.1 passes its logical ID to pstree, so QuickJS may survive.
        // Kill only extractor processes owned by this app UID.
        killOwnedExtractorProcesses();
    }

    private void killOwnedExtractorProcesses() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return;
        int ownUid = Process.myUid();
        int ownPid = Process.myPid();
        for (File entry : entries) {
            int pid;
            try {
                pid = Integer.parseInt(entry.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (pid == ownPid || processUid(entry) != ownUid) continue;
            String command = readText(new File(entry, "cmdline"), 8192);
            if (!isExtractorProcess(command)) continue;
            Log.w(TAG, "Stopping orphaned extractor process pid=" + pid);
            Process.killProcess(pid);
        }
    }

    static boolean isExtractorProcess(String command) {
        return command != null
            && (command.contains("libpython.so") || command.contains("libqjs.so"));
    }

    private static int processUid(File procEntry) {
        String status = readText(new File(procEntry, "status"), 8192);
        for (String line : status.split("\n")) {
            if (!line.startsWith("Uid:")) continue;
            String[] fields = line.substring(4).trim().split("\\s+");
            try {
                return fields.length == 0 ? -1 : Integer.parseInt(fields[0]);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static void destroyForcibly(java.lang.Process process) {
        process.destroy();
        try {
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String sanitizeHeader(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    static String sanitizeErrorDetail(String value) {
        if (value == null) return "";
        // Signed media URLs can contain short-lived credentials; never surface
        // those query strings in Obsidian notices or Android logs.
        return value
            .replaceAll("https?://\\S+", "[video URL]")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim();
    }

    private static boolean isPng(byte[] value) {
        return value.length >= 8
            && (value[0] & 0xff) == 0x89
            && value[1] == 'P'
            && value[2] == 'N'
            && value[3] == 'G'
            && value[4] == 0x0d
            && value[5] == 0x0a
            && value[6] == 0x1a
            && value[7] == 0x0a;
    }

    private static byte[] readBytes(File file, int limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return readBytes(input, limit);
        }
    }

    private static byte[] readBytes(InputStream input, int limit) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IOException("视频帧文件过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String readText(File file, int limit) {
        try {
            byte[] bytes = readBytes(file, limit);
            return new String(bytes, StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    synchronized void close() {
        closed = true;
        killOwnedExtractorProcesses();
    }

    static void validateRequest(String pageUrl, double timeSeconds) {
        if (pageUrl == null || pageUrl.length() > 4096) {
            throw new IllegalArgumentException("视频地址无效");
        }
        URI uri;
        try {
            uri = URI.create(pageUrl);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("视频地址无效", error);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("https".equals(scheme) || "http".equals(scheme)) || host == null) {
            throw new IllegalArgumentException("仅支持 HTTP/HTTPS 视频地址");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!(isDomain(normalizedHost, "youtube.com")
            || isDomain(normalizedHost, "youtube-nocookie.com")
            || "youtu.be".equals(normalizedHost)
            || isDomain(normalizedHost, "bilibili.com")
            || "b23.tv".equals(normalizedHost))) {
            throw new IllegalArgumentException("高清源帧目前仅支持 YouTube 和哔哩哔哩");
        }
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0 || timeSeconds > 86_400) {
            throw new IllegalArgumentException("视频时间无效");
        }
    }

    private static boolean isDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}

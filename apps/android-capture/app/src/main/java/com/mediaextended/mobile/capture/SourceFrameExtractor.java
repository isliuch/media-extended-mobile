package com.mediaextended.mobile.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Resolves a source stream with yt-dlp and decodes one lossless video frame. */
final class SourceFrameExtractor {
    private static final String FORMAT =
        "bestvideo[vcodec^=avc1][ext=mp4]/"
            + "bestvideo[vcodec*=avc][ext=mp4]/"
            + "bestvideo[ext=mp4]/bestvideo/best[ext=mp4]/best";

    private final Context context;
    private final ExecutorService extractionTasks = Executors.newSingleThreadExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private boolean initialized;

    SourceFrameExtractor(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized byte[] capture(String pageUrl, double timeSeconds) throws Exception {
        validateRequest(pageUrl, timeSeconds);
        YoutubeDLRequest request = new YoutubeDLRequest(pageUrl)
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", 15)
            .addOption("--retries", 2)
            .addOption("--extractor-retries", 2)
            .addOption("--format", FORMAT);
        request.addOption("--dump-json");
        String processId = "source-frame-" + UUID.randomUUID();
        Future<byte[]> future = extractionTasks.submit(
            () -> captureBlocking(request, processId, timeSeconds)
        );
        try {
            // Covers yt-dlp resolution, network access and Android frame decoding.
            return future.get(75, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            YoutubeDL.getInstance().destroyProcessById(processId);
            future.cancel(true);
            throw new IllegalStateException("提取高清源帧超时，请稍后重试", error);
        } catch (ExecutionException error) {
            YoutubeDL.getInstance().destroyProcessById(processId);
            future.cancel(true);
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("提取高清源帧失败", cause);
        } catch (Exception error) {
            YoutubeDL.getInstance().destroyProcessById(processId);
            future.cancel(true);
            throw error;
        }
    }

    private byte[] captureBlocking(
        YoutubeDLRequest request,
        String processId,
        double timeSeconds
    ) throws Exception {
        if (!initialized) {
            YoutubeDL.getInstance().init(context);
            initialized = true;
        }
        YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, processId, null);
        VideoInfo info = objectMapper.readValue(response.getOut(), VideoInfo.class);
        String streamUrl = info.getUrl();
        if (streamUrl == null || streamUrl.isEmpty()) {
            throw new IllegalStateException("没有取得可解码的视频流");
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            Map<String, String> headers = info.getHttpHeaders();
            retriever.setDataSource(
                streamUrl,
                headers == null ? Collections.emptyMap() : headers
            );
            long timeUs = Math.max(0L, Math.round(timeSeconds * 1_000_000d));
            frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) throw new IllegalStateException("系统解码器未返回视频帧");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!frame.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("无法编码视频帧");
            }
            return output.toByteArray();
        } finally {
            if (frame != null) frame.recycle();
            retriever.release();
        }
    }

    void close() {
        extractionTasks.shutdownNow();
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

package com.mediaextended.mobile.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SourceFrameExtractorTest {
    @Test
    public void acceptsSupportedVideoHosts() {
        SourceFrameExtractor.validateRequest(
            "https://www.youtube.com/watch?v=M7lc1UVf-VE",
            12.5
        );
        SourceFrameExtractor.validateRequest(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            0
        );
        SourceFrameExtractor.validateRequest("https://b23.tv/example", 60);
    }

    @Test
    public void rejectsUntrustedHostsAndInvalidTimes() {
        assertThrows(
            IllegalArgumentException.class,
            () -> SourceFrameExtractor.validateRequest("http://127.0.0.1/private", 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SourceFrameExtractor.validateRequest("https://youtube.com.evil.test/v", 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SourceFrameExtractor.validateRequest("https://youtu.be/example", -1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SourceFrameExtractor.validateRequest("not a URL", 1)
        );
    }

    @Test
    public void buildsFastSeekFfmpegCommandWithSanitizedHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Mobile\r\nInjected: no");
        headers.put("Referer", "https://www.bilibili.com/");
        List<String> command = SourceFrameExtractor.buildFfmpegCommand(
            "/native/libffmpeg.so",
            "https://example.test/video.m4s?token=one&part=two",
            headers,
            12.3456,
            "/cache/frame.png"
        );

        assertEquals("/native/libffmpeg.so", command.get(0));
        assertTrue(command.indexOf("-ss") < command.indexOf("-i"));
        assertEquals("12.346", command.get(command.indexOf("-ss") + 1));
        assertEquals(
            "User-Agent: MobileInjected: no\r\nReferer: https://www.bilibili.com/\r\n",
            command.get(command.indexOf("-headers") + 1)
        );
        assertEquals(
            "https://example.test/video.m4s?token=one&part=two",
            command.get(command.indexOf("-i") + 1)
        );
    }

    @Test
    public void identifiesOnlyBundledExtractorProcesses() {
        assertTrue(SourceFrameExtractor.isExtractorProcess("/native/libpython.so yt-dlp"));
        assertTrue(SourceFrameExtractor.isExtractorProcess("/native/libqjs.so challenge.js"));
        assertFalse(SourceFrameExtractor.isExtractorProcess("com.mediaextended.mobile.capture"));
        assertFalse(SourceFrameExtractor.isExtractorProcess(null));
    }

    @Test
    public void removesSignedUrlsFromFfmpegErrors() {
        assertEquals(
            "Failed to open [video URL] code=403",
            SourceFrameExtractor.sanitizeErrorDetail(
                "Failed to open https://cdn.example/video.m4s?token=secret code=403\n"
            )
        );
    }
}

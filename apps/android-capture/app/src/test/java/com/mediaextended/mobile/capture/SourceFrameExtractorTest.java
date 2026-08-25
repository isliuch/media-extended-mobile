package com.mediaextended.mobile.capture;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

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
}

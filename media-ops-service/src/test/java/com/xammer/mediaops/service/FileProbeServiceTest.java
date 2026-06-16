package com.xammer.mediaops.service;

import com.xammer.mediaops.dto.FileProbeResult;
import com.xammer.mediaops.dto.SizeEstimation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileProbeServiceTest {

    @Mock
    private FFmpegService ffmpegService;

    private FileProbeService fileProbeService;

    @BeforeEach
    void setUp() {
        fileProbeService = new FileProbeService(ffmpegService);
    }

    // ========== probe ==========

    @Test
    void probe_delegatesToFfmpegService() {
        FileProbeResult expected = new FileProbeResult();
        expected.setVideoCodec("h264");
        expected.setAudioCodec("aac");
        expected.setResolution("1920x1080");
        expected.setBitrate(5000000L);
        expected.setDurationSeconds(120);

        when(ffmpegService.probeFile("s3://bucket/video.mp4")).thenReturn(expected);

        FileProbeResult result = fileProbeService.probe("s3://bucket/video.mp4");

        assertSame(expected, result);
        verify(ffmpegService).probeFile("s3://bucket/video.mp4");
    }

    @Test
    void probe_propagatesExceptionFromFfmpegService() {
        when(ffmpegService.probeFile("invalid-url"))
                .thenThrow(new RuntimeException("probe-failed: FFprobe timed out after 30 seconds"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> fileProbeService.probe("invalid-url"));
        assertTrue(ex.getMessage().contains("probe-failed"));
    }

    // ========== estimateOutputSize ==========

    @Test
    void estimateOutputSize_calculatesCorrectEstimate() {
        // video: 5_000_000 bps, audio: 128_000 bps, duration: 60 seconds
        // expected = (5_000_000 + 128_000) * 60 / 8 = 38_460_000 bytes
        SizeEstimation result = fileProbeService.estimateOutputSize(5_000_000L, 128_000L, 60);

        assertEquals(38_460_000L, result.getEstimatedBytes());
    }

    @Test
    void estimateOutputSize_lowerBoundIs80Percent() {
        SizeEstimation result = fileProbeService.estimateOutputSize(5_000_000L, 128_000L, 60);

        long expected = (long) (38_460_000L * 0.8);
        assertEquals(expected, result.getLowerBoundBytes());
    }

    @Test
    void estimateOutputSize_upperBoundIs120Percent() {
        SizeEstimation result = fileProbeService.estimateOutputSize(5_000_000L, 128_000L, 60);

        long expected = (long) (38_460_000L * 1.2);
        assertEquals(expected, result.getUpperBoundBytes());
    }

    @Test
    void estimateOutputSize_zeroVideoBitrate_usesAudioOnly() {
        // audio only: 256_000 bps, duration: 300 seconds
        // expected = 256_000 * 300 / 8 = 9_600_000 bytes
        SizeEstimation result = fileProbeService.estimateOutputSize(0L, 256_000L, 300);

        assertEquals(9_600_000L, result.getEstimatedBytes());
        assertEquals((long) (9_600_000L * 0.8), result.getLowerBoundBytes());
        assertEquals((long) (9_600_000L * 1.2), result.getUpperBoundBytes());
    }

    @Test
    void estimateOutputSize_zeroDuration_returnsZero() {
        SizeEstimation result = fileProbeService.estimateOutputSize(5_000_000L, 128_000L, 0);

        assertEquals(0L, result.getEstimatedBytes());
        assertEquals(0L, result.getLowerBoundBytes());
        assertEquals(0L, result.getUpperBoundBytes());
    }

    @Test
    void estimateOutputSize_largeBitrate_noOverflow() {
        // Large but reasonable values: 50 Mbps video + 320 kbps audio, 2 hour file
        long videoBitrate = 50_000_000L;
        long audioBitrate = 320_000L;
        int duration = 7200; // 2 hours

        SizeEstimation result = fileProbeService.estimateOutputSize(videoBitrate, audioBitrate, duration);

        long expectedEstimate = (videoBitrate + audioBitrate) * duration / 8;
        assertEquals(expectedEstimate, result.getEstimatedBytes());
        assertTrue(result.getLowerBoundBytes() < result.getEstimatedBytes());
        assertTrue(result.getUpperBoundBytes() > result.getEstimatedBytes());
    }
}

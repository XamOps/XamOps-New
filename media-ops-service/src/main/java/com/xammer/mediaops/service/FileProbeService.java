package com.xammer.mediaops.service;

import com.xammer.mediaops.dto.FileProbeResult;
import com.xammer.mediaops.dto.SizeEstimation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Service for probing input media files and estimating output sizes.
 * Delegates file analysis to FFmpegService and provides size estimation
 * based on target bitrate and duration.
 */
@Service
@RequiredArgsConstructor
public class FileProbeService {

    private final FFmpegService ffmpegService;

    /**
     * Probes a media file to extract metadata including codec, resolution,
     * bitrate, frame rate, duration, and file size.
     *
     * @param sourceUrl path or URL of the media file to probe
     * @return FileProbeResult containing structured metadata
     * @throws RuntimeException with "probe-failed" message if FFprobe fails
     */
    public FileProbeResult probe(String sourceUrl) {
        return ffmpegService.probeFile(sourceUrl);
    }

    /**
     * Estimates the output file size based on target bitrates and duration.
     * Uses the formula: estimated_bytes = (video_bitrate + audio_bitrate) * duration / 8
     * Returns a range with 80% lower bound and 120% upper bound.
     *
     * @param videoBitrateBps video bitrate in bits per second
     * @param audioBitrateBps audio bitrate in bits per second
     * @param durationSeconds duration of the media in seconds
     * @return SizeEstimation with lower bound (80%), estimate (100%), and upper bound (120%)
     */
    public SizeEstimation estimateOutputSize(long videoBitrateBps, long audioBitrateBps, int durationSeconds) {
        long estimatedBytes = (videoBitrateBps + audioBitrateBps) * durationSeconds / 8;
        return new SizeEstimation(
                (long) (estimatedBytes * 0.8),
                estimatedBytes,
                (long) (estimatedBytes * 1.2)
        );
    }
}

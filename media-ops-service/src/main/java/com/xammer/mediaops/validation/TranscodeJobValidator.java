package com.xammer.mediaops.validation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates TranscodeJobRequest fields before job creation.
 * Checks shell injection, subtitle format, time range, codec-format compatibility,
 * watermark opacity, encryption format, and watermark file size.
 */
@Component
public class TranscodeJobValidator {

    private static final Pattern SHELL_INJECTION_PATTERN =
            Pattern.compile("[;|`$&(){}\\[\\]<>!]");

    private static final Set<String> VALID_SUBTITLE_EXTENSIONS = Set.of("srt", "vtt");

    private static final long MAX_WATERMARK_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB

    // Codec-format compatibility matrix
    private static final Map<String, Set<String>> CODEC_FORMAT_COMPAT = Map.of(
            "H264", Set.of("MP4", "MKV", "MOV", "HLS", "DASH"),
            "H265", Set.of("MP4", "MKV", "MOV", "HLS", "DASH"),
            "AV1", Set.of("MP4", "MKV", "WEBM"),
            "VP9", Set.of("WEBM", "MKV")
    );

    /**
     * Validates all aspects of a transcode job request.
     * Throws ValidationException on the first validation failure encountered.
     */
    public void validate(String customFfmpegParams, String subtitleFilename,
                         String startTime, String endTime, String codec,
                         String outputFormat, Double watermarkOpacity,
                         boolean encryptionEnabled, Long watermarkFileSizeBytes) {
        validateCustomParams(customFfmpegParams);
        validateSubtitleFormat(subtitleFilename);
        validateTimeRange(startTime, endTime);
        validateCodecFormatCompat(codec, outputFormat);
        validateWatermarkOpacity(watermarkOpacity);
        validateEncryptionFormat(encryptionEnabled, outputFormat);
        validateWatermarkFileSize(watermarkFileSizeBytes);
    }

    /**
     * Validates that custom FFmpeg parameters do not contain shell injection characters.
     * Prohibited characters: ; | ` $ & ( ) { } [ ] < > !
     *
     * @param params the custom FFmpeg parameter string
     * @throws ValidationException if prohibited characters are found
     */
    public void validateCustomParams(String params) {
        if (params == null) return;
        if (SHELL_INJECTION_PATTERN.matcher(params).find()) {
            throw new ValidationException("parameter-validation-failed",
                    "Custom parameters contain prohibited characters");
        }
    }

    /**
     * Validates that the subtitle file has an SRT or VTT extension.
     *
     * @param filename the subtitle filename
     * @throws ValidationException if the format is not SRT or VTT
     */
    public void validateSubtitleFormat(String filename) {
        if (filename == null) return;
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) {
            throw new ValidationException("invalid-subtitle-format",
                    "Subtitle format must be SRT or VTT");
        }
        String ext = filename.substring(dotIndex + 1).toLowerCase();
        if (!VALID_SUBTITLE_EXTENSIONS.contains(ext)) {
            throw new ValidationException("invalid-subtitle-format",
                    "Subtitle format must be SRT or VTT");
        }
    }

    /**
     * Validates that start time is less than end time when both are provided.
     *
     * @param startTime the start time (HH:MM:SS or seconds)
     * @param endTime   the end time (HH:MM:SS or seconds)
     * @throws ValidationException if start >= end
     */
    public void validateTimeRange(String startTime, String endTime) {
        if (startTime != null && endTime != null) {
            long startSeconds = parseTimeToSeconds(startTime);
            long endSeconds = parseTimeToSeconds(endTime);
            if (startSeconds >= endSeconds) {
                throw new ValidationException("invalid-time-range",
                        "Start time must be less than end time");
            }
        }
    }

    /**
     * Validates that the codec is compatible with the output format
     * according to the compatibility matrix.
     *
     * @param codec  the video codec (H264, H265, AV1, VP9)
     * @param format the output format (MP4, MKV, MOV, HLS, DASH, WEBM)
     * @throws ValidationException if the combination is incompatible
     */
    public void validateCodecFormatCompat(String codec, String format) {
        if (codec == null || format == null) return;
        Set<String> compatible = CODEC_FORMAT_COMPAT.get(codec.toUpperCase());
        if (compatible != null && !compatible.contains(format.toUpperCase())) {
            throw new ValidationException("codec-format-mismatch",
                    "Codec " + codec + " is not compatible with format " + format);
        }
    }

    /**
     * Validates that watermark opacity is between 0 and 1 (inclusive).
     *
     * @param opacity the watermark opacity value
     * @throws ValidationException if opacity is out of range
     */
    public void validateWatermarkOpacity(Double opacity) {
        if (opacity == null) return;
        if (opacity < 0.0 || opacity > 1.0) {
            throw new ValidationException("invalid-watermark-opacity",
                    "Watermark opacity must be between 0 and 1");
        }
    }

    /**
     * Validates that encryption is only enabled for HLS output format.
     *
     * @param encryptionEnabled whether encryption is requested
     * @param format            the output format
     * @throws ValidationException if encryption is enabled for non-HLS format
     */
    public void validateEncryptionFormat(boolean encryptionEnabled, String format) {
        if (encryptionEnabled && !"HLS".equalsIgnoreCase(format)) {
            throw new ValidationException("format-not-supported",
                    "Encryption is only supported for HLS output format");
        }
    }

    /**
     * Validates that the watermark file size does not exceed 5 MB.
     *
     * @param fileSizeBytes the watermark file size in bytes
     * @throws ValidationException if the file exceeds 5 MB
     */
    public void validateWatermarkFileSize(Long fileSizeBytes) {
        if (fileSizeBytes == null) return;
        if (fileSizeBytes > MAX_WATERMARK_SIZE_BYTES) {
            throw new ValidationException("file-size-exceeded",
                    "Watermark image file exceeds maximum size of 5 MB");
        }
    }

    /**
     * Parses a time string to total seconds.
     * Accepts both "HH:MM:SS" format and total seconds as a numeric string.
     *
     * @param time the time string to parse
     * @return total seconds
     */
    public long parseTimeToSeconds(String time) {
        if (time.contains(":")) {
            String[] parts = time.split(":");
            return Long.parseLong(parts[0]) * 3600
                    + Long.parseLong(parts[1]) * 60
                    + Long.parseLong(parts[2]);
        }
        return Long.parseLong(time);
    }
}

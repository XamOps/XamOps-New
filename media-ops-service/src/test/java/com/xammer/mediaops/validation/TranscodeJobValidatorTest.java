package com.xammer.mediaops.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TranscodeJobValidatorTest {

    private TranscodeJobValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TranscodeJobValidator();
    }

    // ========== Shell Injection Validation ==========

    @Test
    void validateCustomParams_nullParams_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCustomParams(null));
    }

    @Test
    void validateCustomParams_cleanParams_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCustomParams("-crf 23 -preset slow"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "param; rm -rf /",
            "param | cat /etc/passwd",
            "param `whoami`",
            "param $HOME",
            "param & background",
            "param (subshell)",
            "param {expansion}",
            "param [bracket]",
            "param <redirect",
            "param >output",
            "param !history"
    })
    void validateCustomParams_shellInjectionChars_throwsException(String params) {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateCustomParams(params));
        assertEquals("parameter-validation-failed", ex.getErrorCode());
    }

    // ========== Subtitle Format Validation ==========

    @Test
    void validateSubtitleFormat_nullFilename_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateSubtitleFormat(null));
    }

    @Test
    void validateSubtitleFormat_srtExtension_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateSubtitleFormat("captions.srt"));
    }

    @Test
    void validateSubtitleFormat_vttExtension_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateSubtitleFormat("captions.vtt"));
    }

    @Test
    void validateSubtitleFormat_uppercaseSrt_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateSubtitleFormat("captions.SRT"));
    }

    @Test
    void validateSubtitleFormat_invalidExtension_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateSubtitleFormat("captions.ass"));
        assertEquals("invalid-subtitle-format", ex.getErrorCode());
    }

    @Test
    void validateSubtitleFormat_noExtension_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateSubtitleFormat("captions"));
        assertEquals("invalid-subtitle-format", ex.getErrorCode());
    }

    // ========== Time Range Validation ==========

    @Test
    void validateTimeRange_bothNull_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateTimeRange(null, null));
    }

    @Test
    void validateTimeRange_onlyStart_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateTimeRange("00:01:00", null));
    }

    @Test
    void validateTimeRange_onlyEnd_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateTimeRange(null, "00:05:00"));
    }

    @Test
    void validateTimeRange_startBeforeEnd_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateTimeRange("00:01:00", "00:05:00"));
    }

    @Test
    void validateTimeRange_startEqualsEnd_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateTimeRange("00:01:00", "00:01:00"));
        assertEquals("invalid-time-range", ex.getErrorCode());
    }

    @Test
    void validateTimeRange_startAfterEnd_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateTimeRange("00:05:00", "00:01:00"));
        assertEquals("invalid-time-range", ex.getErrorCode());
    }

    @Test
    void validateTimeRange_secondsFormat_startBeforeEnd_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateTimeRange("60", "300"));
    }

    @Test
    void validateTimeRange_secondsFormat_startAfterEnd_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateTimeRange("300", "60"));
        assertEquals("invalid-time-range", ex.getErrorCode());
    }

    // ========== Codec-Format Compatibility ==========

    @Test
    void validateCodecFormatCompat_nullCodec_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCodecFormatCompat(null, "MP4"));
    }

    @Test
    void validateCodecFormatCompat_nullFormat_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCodecFormatCompat("H264", null));
    }

    @Test
    void validateCodecFormatCompat_h264WithMp4_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCodecFormatCompat("H264", "MP4"));
    }

    @Test
    void validateCodecFormatCompat_h264WithMkv_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCodecFormatCompat("H264", "MKV"));
    }

    @Test
    void validateCodecFormatCompat_h264WithHls_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCodecFormatCompat("H264", "HLS"));
    }

    @Test
    void validateCodecFormatCompat_h264WithWebm_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateCodecFormatCompat("H264", "WEBM"));
        assertEquals("codec-format-mismatch", ex.getErrorCode());
    }

    @Test
    void validateCodecFormatCompat_av1WithWebm_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCodecFormatCompat("AV1", "WEBM"));
    }

    @Test
    void validateCodecFormatCompat_av1WithHls_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateCodecFormatCompat("AV1", "HLS"));
        assertEquals("codec-format-mismatch", ex.getErrorCode());
    }

    @Test
    void validateCodecFormatCompat_vp9WithWebm_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCodecFormatCompat("VP9", "WEBM"));
    }

    @Test
    void validateCodecFormatCompat_vp9WithMp4_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateCodecFormatCompat("VP9", "MP4"));
        assertEquals("codec-format-mismatch", ex.getErrorCode());
    }

    @Test
    void validateCodecFormatCompat_caseInsensitive_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateCodecFormatCompat("h264", "mp4"));
    }

    // ========== Watermark Opacity Validation ==========

    @Test
    void validateWatermarkOpacity_null_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateWatermarkOpacity(null));
    }

    @Test
    void validateWatermarkOpacity_zero_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateWatermarkOpacity(0.0));
    }

    @Test
    void validateWatermarkOpacity_one_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateWatermarkOpacity(1.0));
    }

    @Test
    void validateWatermarkOpacity_midRange_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateWatermarkOpacity(0.5));
    }

    @Test
    void validateWatermarkOpacity_belowZero_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateWatermarkOpacity(-0.1));
        assertEquals("invalid-watermark-opacity", ex.getErrorCode());
    }

    @Test
    void validateWatermarkOpacity_aboveOne_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateWatermarkOpacity(1.1));
        assertEquals("invalid-watermark-opacity", ex.getErrorCode());
    }

    // ========== Encryption Format Validation ==========

    @Test
    void validateEncryptionFormat_disabledWithAnyFormat_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateEncryptionFormat(false, "MP4"));
    }

    @Test
    void validateEncryptionFormat_enabledWithHls_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateEncryptionFormat(true, "HLS"));
    }

    @Test
    void validateEncryptionFormat_enabledWithHlsCaseInsensitive_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateEncryptionFormat(true, "hls"));
    }

    @Test
    void validateEncryptionFormat_enabledWithMp4_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateEncryptionFormat(true, "MP4"));
        assertEquals("format-not-supported", ex.getErrorCode());
    }

    @Test
    void validateEncryptionFormat_enabledWithDash_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateEncryptionFormat(true, "DASH"));
        assertEquals("format-not-supported", ex.getErrorCode());
    }

    // ========== Watermark File Size Validation ==========

    @Test
    void validateWatermarkFileSize_null_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateWatermarkFileSize(null));
    }

    @Test
    void validateWatermarkFileSize_underLimit_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateWatermarkFileSize(1024L * 1024)); // 1 MB
    }

    @Test
    void validateWatermarkFileSize_exactLimit_doesNotThrow() {
        assertDoesNotThrow(() -> validator.validateWatermarkFileSize(5L * 1024 * 1024)); // 5 MB exactly
    }

    @Test
    void validateWatermarkFileSize_overLimit_throwsException() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateWatermarkFileSize(5L * 1024 * 1024 + 1)); // 5 MB + 1 byte
        assertEquals("file-size-exceeded", ex.getErrorCode());
    }

    // ========== parseTimeToSeconds ==========

    @Test
    void parseTimeToSeconds_hhmmssFormat() {
        assertEquals(3661, validator.parseTimeToSeconds("01:01:01"));
    }

    @Test
    void parseTimeToSeconds_secondsFormat() {
        assertEquals(120, validator.parseTimeToSeconds("120"));
    }

    @Test
    void parseTimeToSeconds_zeroTime() {
        assertEquals(0, validator.parseTimeToSeconds("00:00:00"));
    }

    @Test
    void parseTimeToSeconds_largeTime() {
        assertEquals(7200, validator.parseTimeToSeconds("02:00:00"));
    }
}
